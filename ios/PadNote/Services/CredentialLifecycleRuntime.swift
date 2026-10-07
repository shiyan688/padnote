import CryptoKit
import Darwin
import Foundation

public enum CredentialLifecycleSide: String, Codable, Hashable, CaseIterable {
    case ai
    case agent
}

public struct CredentialLifecycleReference: Codable, Hashable {
    public let side: CredentialLifecycleSide
    public let account: String

    public init(side: CredentialLifecycleSide, account: String) {
        self.side = side
        self.account = account
    }
}

public enum CredentialLifecycleState: Equatable {
    case notReady
    case ready
    case blocked(String)
    case disabledAwaitingNextProcess
    case cleanupRetryRequired
    case completeKnownReferences
}

final class CredentialLifecycleLockLease {
    private var isReleased = false
    private let releaseBody: () -> Void
    init(_ releaseBody: @escaping () -> Void) { self.releaseBody = releaseBody }
    func release() { guard !isReleased else { return }; isReleased = true; releaseBody() }
    deinit { release() }
}

public extension CredentialLifecycleState {
    var blockingMessage: String? {
        if case .blocked(let message) = self { return message }
        return nil
    }
}

public struct CredentialClearResult: Equatable {
    public enum Phase: Equatable {
        case failedBeforeDisable
        case partiallyDisabled
        case disabledAwaitingNextProcess
        case cleanupRetryRequired
        case completeKnownReferences
    }

    public let phase: Phase
    public let aiMetadataVerified: Bool
    public let agentMetadataVerified: Bool
    public let unresolvedReferenceCount: Int
    public let message: String
}

public enum CredentialLifecycleError: Error, LocalizedError {
    case corruptJournal
    case corruptMetadata(CredentialLifecycleSide)
    case persistence
    case blocked(String)
    case tooManyReferences
    case cleanupVerificationFailed(CredentialLifecycleReference)

    public var errorDescription: String? {
        switch self {
        case .corruptJournal: return "本机凭据清理记录损坏；凭据读取和迁移已阻止。"
        case .corruptMetadata(let side): return "\(side.rawValue) 凭据档案无法安全读取；原记录已保留。"
        case .persistence: return "无法可靠保存本机凭据清理进度。"
        case .blocked(let reason): return reason
        case .tooManyReferences: return "本机已知凭据数量超过安全上限；没有开始清理。"
        case .cleanupVerificationFailed: return "部分已知凭据仍待清理，将保留阻止状态并重试。"
        }
    }
}

/// Native adapters own decoding/writing of their production payloads. These methods are
/// invoked under the runtime mutation barrier and may use only the matching service store.
protocol CredentialLifecycleParticipant: AnyObject {
    var side: CredentialLifecycleSide { get }
    func snapshotKnownReferences() throws -> Set<String>
    func disableKnownReferences(_ references: Set<String>, operationID: String) throws
    func verifyDisabled(_ references: Set<String>, operationID: String) throws -> Bool
    func deleteRaw(reference: String) throws
    func readRaw(reference: String) throws -> String?
}

final class ClosureCredentialLifecycleParticipant: CredentialLifecycleParticipant {
    let side: CredentialLifecycleSide
    private let snapshotBody: () throws -> Set<String>
    private let disableBody: (Set<String>, String) throws -> Void
    private let verifyBody: (Set<String>, String) throws -> Bool
    private let deleteBody: (String) throws -> Void
    private let readBody: (String) throws -> String?

    init(side: CredentialLifecycleSide,
         snapshot: @escaping () throws -> Set<String>,
         disable: @escaping (Set<String>, String) throws -> Void,
         verify: @escaping (Set<String>, String) throws -> Bool,
         deleteRaw: @escaping (String) throws -> Void,
         readRaw: @escaping (String) throws -> String?) {
        self.side = side; snapshotBody = snapshot; disableBody = disable
        verifyBody = verify; deleteBody = deleteRaw; readBody = readRaw
    }

    func snapshotKnownReferences() throws -> Set<String> { try snapshotBody() }
    func disableKnownReferences(_ references: Set<String>, operationID: String) throws {
        try disableBody(references, operationID)
    }
    func verifyDisabled(_ references: Set<String>, operationID: String) throws -> Bool {
        try verifyBody(references, operationID)
    }
    func deleteRaw(reference: String) throws { try deleteBody(reference) }
    func readRaw(reference: String) throws -> String? { try readBody(reference) }
}

private final class EmptyCredentialLifecycleParticipant: CredentialLifecycleParticipant {
    let side: CredentialLifecycleSide
    init(_ side: CredentialLifecycleSide) { self.side = side }
    func snapshotKnownReferences() throws -> Set<String> { [] }
    func disableKnownReferences(_ references: Set<String>, operationID: String) throws {}
    func verifyDisabled(_ references: Set<String>, operationID: String) throws -> Bool { true }
    func deleteRaw(reference: String) throws {}
    func readRaw(reference: String) throws -> String? { nil }
}

private struct CredentialLifecycleIntent: Codable {
    var schemaVersion: Int
    var operationID: String
    var processGeneration: String
    var references: Set<CredentialLifecycleReference>
    var disabledReferences: Set<CredentialLifecycleReference>
    var committedSides: Set<CredentialLifecycleSide>
    var cleanupStarted: Bool
    var initialPendingDeletion: Set<CredentialLifecycleReference>
    var pendingDeletion: Set<CredentialLifecycleReference>
    var verifiedAbsent: Set<CredentialLifecycleReference>
    var failures: Set<CredentialLifecycleReference>

    func validate() throws {
        let maximum = CredentialLifecycleRuntime.maximumKnownReferences
        guard schemaVersion == 1,
              !operationID.isEmpty, !processGeneration.isEmpty,
              references.count <= maximum, disabledReferences.count <= maximum,
              references.allSatisfy({ Self.valid($0.account) }),
              disabledReferences.allSatisfy({ Self.valid($0.account) }),
              disabledReferences.isSuperset(of: references),
              initialPendingDeletion.isSubset(of: disabledReferences),
              references.isSubset(of: initialPendingDeletion),
              pendingDeletion.isSubset(of: initialPendingDeletion),
              verifiedAbsent.isSubset(of: initialPendingDeletion),
              pendingDeletion.isDisjoint(with: verifiedAbsent),
              pendingDeletion.union(verifiedAbsent) == initialPendingDeletion,
              failures.isSubset(of: pendingDeletion),
              !cleanupStarted || committedSides == Set(CredentialLifecycleSide.allCases),
              cleanupStarted || (pendingDeletion == initialPendingDeletion && verifiedAbsent.isEmpty) else {
            throw CredentialLifecycleError.corruptJournal
        }
    }

    private static func valid(_ value: String) -> Bool {
        !value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            && !value.hasPrefix("padnote-disabled://")
    }
}

private final class CredentialLifecycleFileJournal {
    static let maximumBytes = 1_048_576
    private let url: URL

    init(url: URL) { self.url = url }

    func read() throws -> Data? {
        let fd = url.path.withCString { open($0, O_RDONLY | O_NOFOLLOW | O_NONBLOCK) }
        if fd < 0 {
            if errno == ENOENT {
                let parent = url.deletingLastPathComponent()
                let dir = parent.path.withCString { open($0, O_RDONLY | O_DIRECTORY | O_NOFOLLOW) }
                guard dir >= 0 else { throw CredentialLifecycleError.corruptJournal }
                var parentInfo = stat()
                guard fstat(dir, &parentInfo) == 0, Self.safeDirectory(parentInfo) else {
                    _ = close(dir)
                    throw CredentialLifecycleError.corruptJournal
                }
                _ = close(dir)
                return nil
            }
            throw CredentialLifecycleError.corruptJournal
        }
        defer { _ = close(fd) }
        var before = stat()
        guard fstat(fd, &before) == 0, Self.safe(before) else { throw CredentialLifecycleError.corruptJournal }
        guard before.st_size >= 0, before.st_size <= off_t(Self.maximumBytes) else {
            throw CredentialLifecycleError.corruptJournal
        }
        var pathInfo = stat()
        guard url.path.withCString({ lstat($0, &pathInfo) }) == 0,
              Self.same(before, pathInfo) else { throw CredentialLifecycleError.corruptJournal }
        var data = Data()
        data.reserveCapacity(Int(before.st_size))
        var buffer = [UInt8](repeating: 0, count: 16 * 1024)
        while true {
            let count = buffer.withUnsafeMutableBytes { Darwin.read(fd, $0.baseAddress, $0.count) }
            if count == 0 { break }
            if count < 0 {
                if errno == EINTR { continue }
                throw CredentialLifecycleError.corruptJournal
            }
            guard data.count <= Self.maximumBytes - count else { throw CredentialLifecycleError.corruptJournal }
            data.append(contentsOf: buffer[0..<count])
        }
        var after = stat()
        var afterPath = stat()
        guard fstat(fd, &after) == 0,
              url.path.withCString({ lstat($0, &afterPath) }) == 0,
              Self.same(before, after), Self.same(before, afterPath),
              data.count == Int(before.st_size) else { throw CredentialLifecycleError.corruptJournal }
        return data
    }

    func write(_ data: Data) throws {
        guard data.count <= Self.maximumBytes else { throw CredentialLifecycleError.corruptJournal }
        let parent = url.deletingLastPathComponent()
        let directory = parent.path.withCString { open($0, O_RDONLY | O_DIRECTORY | O_NOFOLLOW) }
        guard directory >= 0 else { throw CredentialLifecycleError.persistence }
        defer { _ = close(directory) }
        var directoryInfo = stat()
        guard fstat(directory, &directoryInfo) == 0, Self.safeDirectory(directoryInfo) else {
            throw CredentialLifecycleError.persistence
        }
        let temporary = parent.appendingPathComponent(".credential-clear-\(UUID().uuidString).tmp")
        let fd = temporary.path.withCString { open($0, O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW, mode_t(0o600)) }
        guard fd >= 0 else { throw CredentialLifecycleError.persistence }
        var temporaryInfo = stat()
        guard fstat(fd, &temporaryInfo) == 0, Self.safe(temporaryInfo) else {
            _ = close(fd)
            _ = temporary.path.withCString { unlink($0) }
            throw CredentialLifecycleError.persistence
        }
        var openFD = true
        var exists = true
        defer {
            if openFD { _ = close(fd) }
            if exists { _ = temporary.path.withCString { unlink($0) } }
        }
        try data.withUnsafeBytes { buffer in
            guard let base = buffer.baseAddress else { return }
            var offset = 0
            while offset < buffer.count {
                let count = systemWrite(fd, base.advanced(by: offset), buffer.count - offset)
                if count < 0 {
                    if errno == EINTR { continue }
                    throw CredentialLifecycleError.persistence
                }
                guard count > 0 else { throw CredentialLifecycleError.persistence }
                offset += count
            }
        }
        guard fsync(fd) == 0 else { throw CredentialLifecycleError.persistence }
        let closeResult = close(fd)
        openFD = false
        guard closeResult == 0 else { throw CredentialLifecycleError.persistence }
        let result = temporary.path.withCString { source in url.path.withCString { destination in rename(source, destination) } }
        guard result == 0 else { throw CredentialLifecycleError.persistence }
        exists = false
        guard fsync(directory) == 0, try read() == data else { throw CredentialLifecycleError.persistence }
    }

    private static func safe(_ value: stat) -> Bool {
        (value.st_mode & S_IFMT) == S_IFREG
            && (value.st_mode & 0o077) == 0
            && value.st_uid == geteuid()
            && value.st_nlink == 1
    }

    private static func safeDirectory(_ value: stat) -> Bool {
        (value.st_mode & S_IFMT) == S_IFDIR
            && (value.st_mode & 0o077) == 0
            && value.st_uid == geteuid()
    }

    private static func same(_ lhs: stat, _ rhs: stat) -> Bool {
        safe(rhs) && lhs.st_dev == rhs.st_dev && lhs.st_ino == rhs.st_ino
            && lhs.st_uid == rhs.st_uid && lhs.st_nlink == rhs.st_nlink
            && lhs.st_mode == rhs.st_mode && lhs.st_size == rhs.st_size
            && lhs.st_mtimespec.tv_sec == rhs.st_mtimespec.tv_sec
            && lhs.st_mtimespec.tv_nsec == rhs.st_mtimespec.tv_nsec
            && lhs.st_ctimespec.tv_sec == rhs.st_ctimespec.tv_sec
            && lhs.st_ctimespec.tv_nsec == rhs.st_ctimespec.tv_nsec
    }
}

private func systemWrite(_ fd: Int32, _ bytes: UnsafeRawPointer, _ count: Int) -> Int {
#if canImport(Darwin)
    return Darwin.write(fd, bytes, count)
#else
    return Glibc.write(fd, bytes, count)
#endif
}

/// One process-wide serialization barrier. Callers must acquire this before Agent's
/// existing mutationLock, never in the reverse order, and must not hold it across await.
public final class CredentialLifecycleRuntime {
    public static let maximumKnownReferences = 4_096
    public static let stateDidChange = Notification.Name("PadNote.CredentialLifecycleStateDidChange")
    private static let mutationBarrier = NSRecursiveLock()
    private static var mutationEpoch: UInt64 = 0
    private static let generation = UUID().uuidString.lowercased()
    private static let sharedRuntime: CredentialLifecycleRuntime = {
        let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("PadNote", isDirectory: true)
            .appendingPathComponent("credential-clear", isDirectory: true)
        try? FileManager.default.createDirectory(at: support, withIntermediateDirectories: true,
            attributes: [.posixPermissions: 0o700])
        let runtime = CredentialLifecycleRuntime(
            journalURL: support.appendingPathComponent("credential-clear-v1.json"),
            processGeneration: generation)
        runtime.installParticipants([
            AISettingsStore.credentialLifecycleParticipant(defaults: .standard, secrets: KeychainSecretStore()),
            AgentConnectionStore.credentialLifecycleParticipant(defaults: .standard, keychain: KeychainTokenStore())
        ])
        return runtime
    }()

    public static var shared: CredentialLifecycleRuntime { sharedRuntime }

    static func isolatedForInjectedAI(defaults: UserDefaults, secrets: SecretStore) -> CredentialLifecycleRuntime {
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent("padnote-credential-runtime-\(UUID().uuidString)", isDirectory: true)
        try? FileManager.default.createDirectory(at: root, withIntermediateDirectories: true,
            attributes: [.posixPermissions: 0o700])
        return CredentialLifecycleRuntime(journalURL: root.appendingPathComponent("journal.json"),
            participants: [AISettingsStore.credentialLifecycleParticipant(defaults: defaults, secrets: secrets),
                           EmptyCredentialLifecycleParticipant(.agent)], ownedTemporaryDirectory: root)
    }

    static func isolatedForInjectedAgent(defaults: UserDefaults, keychain: AgentTokenStore) -> CredentialLifecycleRuntime {
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent("padnote-credential-runtime-\(UUID().uuidString)", isDirectory: true)
        try? FileManager.default.createDirectory(at: root, withIntermediateDirectories: true,
            attributes: [.posixPermissions: 0o700])
        return CredentialLifecycleRuntime(journalURL: root.appendingPathComponent("journal.json"),
            participants: [EmptyCredentialLifecycleParticipant(.ai),
                           AgentConnectionStore.credentialLifecycleParticipant(defaults: defaults, keychain: keychain)],
            ownedTemporaryDirectory: root)
    }

    static func disabledReference(operationID: String, side: CredentialLifecycleSide, account: String) -> String {
        let digest = SHA256.hash(data: Data((side.rawValue + "\u{0}" + account).utf8))
            .map { String(format: "%02x", $0) }.joined()
        return "padnote-disabled://\(operationID)/\(digest)"
    }

    private let journal: CredentialLifecycleFileJournal
    private let processGeneration: String
    private let ownedTemporaryDirectory: URL?
    private var participants: [CredentialLifecycleSide: CredentialLifecycleParticipant] = [:]
    private var bootstrapped = false
    private var state: CredentialLifecycleState = .notReady

    private func transition(to next: CredentialLifecycleState) {
        guard state != next else { return }
        state = next
        NotificationCenter.default.post(name: Self.stateDidChange, object: self)
    }

    /// Test-only construction seam. Tests must supply fresh defaults suites, fake stores,
    /// and a private temporary journal directory; this initializer never discovers Keychain.
    init(journalURL: URL, processGeneration: String = UUID().uuidString.lowercased(),
         participants: [CredentialLifecycleParticipant] = [], ownedTemporaryDirectory: URL? = nil) {
        journal = CredentialLifecycleFileJournal(url: journalURL)
        self.processGeneration = processGeneration
        self.ownedTemporaryDirectory = ownedTemporaryDirectory
        installParticipants(participants)
    }

    deinit {
        if let ownedTemporaryDirectory { try? FileManager.default.removeItem(at: ownedTemporaryDirectory) }
    }

    func installParticipants(_ values: [CredentialLifecycleParticipant]) {
        for value in values { participants[value.side] = value }
    }

    @discardableResult
    public func bootstrapBeforeStores() -> CredentialLifecycleState {
        Self.mutationBarrier.lock()
        defer { Self.mutationBarrier.unlock() }
        do {
            try bootstrapLocked()
            return state
        } catch {
            transition(to: .blocked(error.localizedDescription))
            bootstrapped = true
            return state
        }
    }

    /// Holds the outer barrier across the store's complete initialization so a clear cannot
    /// race its legacy migration or first metadata save.
    func lockForStoreInitialization() -> CredentialLifecycleLockLease {
        Self.mutationBarrier.lock()
        do { try bootstrapLocked() }
        catch { transition(to: .blocked(error.localizedDescription)); bootstrapped = true }
        return CredentialLifecycleLockLease { Self.mutationBarrier.unlock() }
    }

    public var currentState: CredentialLifecycleState {
        Self.mutationBarrier.lock(); defer { Self.mutationBarrier.unlock() }
        return state
    }

    func withMutation<T>(_ body: () throws -> T) rethrows -> T {
        Self.mutationBarrier.lock(); defer { Self.mutationBarrier.unlock() }
        return try body()
    }

    static func withAgentStoreLock<T>(_ body: () throws -> T) rethrows -> T {
        mutationBarrier.lock(); defer { mutationBarrier.unlock() }
        return try body()
    }

    public var epochSnapshot: UInt64 {
        Self.mutationBarrier.lock(); defer { Self.mutationBarrier.unlock() }
        return Self.mutationEpoch
    }

    var allowsMutation: Bool {
        Self.mutationBarrier.lock()
        defer { Self.mutationBarrier.unlock() }
        do {
            try bootstrapLocked()
            _ = try readIntentLocked() // Revalidate the durable gate for every metadata admission.
            switch state {
            case .ready, .disabledAwaitingNextProcess, .cleanupRetryRequired, .completeKnownReferences: return true
            case .notReady, .blocked: return false
            }
        } catch {
            transition(to: .blocked(error.localizedDescription))
            bootstrapped = true
            return false
        }
    }

    func readCredential(side: CredentialLifecycleSide, reference: String,
                        rawRead: () throws -> String?) throws -> String? {
        Self.mutationBarrier.lock(); defer { Self.mutationBarrier.unlock() }
        try bootstrapLocked()
        guard case .blocked(let reason) = state else {
            let intent: CredentialLifecycleIntent?
            do { intent = try readIntentLocked() }
            catch {
                transition(to: .blocked(error.localizedDescription))
                bootstrapped = true
                    throw error
            }
            if reference.hasPrefix("padnote-disabled://")
                || intent?.disabledReferences.contains(.init(side: side, account: reference)) == true {
                throw CredentialLifecycleError.blocked("此凭据已在本机停用；请重新填写连接凭据。")
            }
            return try rawRead()
        }
        throw CredentialLifecycleError.blocked(reason)
    }

    func isDisabled(side: CredentialLifecycleSide, reference: String) throws -> Bool {
        Self.mutationBarrier.lock(); defer { Self.mutationBarrier.unlock() }
        try bootstrapLocked()
        guard case .blocked(let reason) = state else {
            do {
                return try reference.hasPrefix("padnote-disabled://")
                    || readIntentLocked()?.disabledReferences.contains(.init(side: side, account: reference)) == true
            }
            catch {
                transition(to: .blocked(error.localizedDescription))
                bootstrapped = true
                    throw error
            }
        }
        throw CredentialLifecycleError.blocked(reason)
    }

    public func clearKnownCredentials() -> CredentialClearResult {
        Self.mutationBarrier.lock(); defer { Self.mutationBarrier.unlock() }
        do {
            if case .blocked = state, bootstrapped, try readIntentLocked() == nil {
                // A pre-intent write failure is safe to retry after revalidating both payloads.
                bootstrapped = false
                transition(to: .notReady)
            }
            try bootstrapLocked()
            if case .blocked(let reason) = state { throw CredentialLifecycleError.blocked(reason) }
            var snapshots: [CredentialLifecycleReference] = []
            for side in CredentialLifecycleSide.allCases {
                guard let participant = participants[side] else { throw CredentialLifecycleError.corruptMetadata(side) }
                let known = try participant.snapshotKnownReferences()
                snapshots.append(contentsOf: known.map { CredentialLifecycleReference(side: side, account: $0) })
            }
            guard snapshots.count <= Self.maximumKnownReferences else { throw CredentialLifecycleError.tooManyReferences }
            let oldIntent = try readIntentLocked()
            let refs = Set(snapshots)
            let disabled = (oldIntent?.disabledReferences ?? []).union(refs)
            guard disabled.count <= Self.maximumKnownReferences else { throw CredentialLifecycleError.tooManyReferences }
            var intent = CredentialLifecycleIntent(schemaVersion: 1, operationID: UUID().uuidString.lowercased(),
                processGeneration: processGeneration, references: refs, disabledReferences: disabled,
                committedSides: [], cleanupStarted: false,
                initialPendingDeletion: (oldIntent?.pendingDeletion ?? []).union(refs),
                pendingDeletion: (oldIntent?.pendingDeletion ?? []).union(refs), verifiedAbsent: [], failures: [])
            do { try persist(intent) } catch { throw error }
            Self.mutationEpoch &+= 1
            transition(to: .disabledAwaitingNextProcess)
            for side in CredentialLifecycleSide.allCases {
                guard let participant = participants[side] else { throw CredentialLifecycleError.corruptMetadata(side) }
                try participant.disableKnownReferences(Set(refs.filter { $0.side == side }.map(\.account)), operationID: intent.operationID)
                guard try participant.verifyDisabled(Set(refs.filter { $0.side == side }.map(\.account)), operationID: intent.operationID) else {
                    transition(to: .blocked("本机凭据停用尚未全部写入；读取保持阻止，请重试。"))
                    return result(.partiallyDisabled, intent, "一部分凭据已停用；其余仍被读取门禁阻止，请重试。")
                }
                intent.committedSides.insert(side)
                try persist(intent)
            }
            // The clear action never deletes credentials in the same process generation.
            transition(to: .disabledAwaitingNextProcess)
            return result(.disabledAwaitingNextProcess, intent, "本机已知连接凭据已停用；部分凭据将在下次打开应用时继续清理。")
        } catch {
            let intent = (try? readIntentLocked()) ?? emptyIntent()
            if intent.disabledReferences.isEmpty {
                transition(to: .blocked(error.localizedDescription))
                    return result(.failedBeforeDisable, intent, error.localizedDescription)
            }
            transition(to: .blocked(error.localizedDescription))
            return result(.partiallyDisabled, intent, error.localizedDescription)
        }
    }

    private func bootstrapLocked() throws {
        if bootstrapped {
            if case .blocked(let reason) = state {
                guard let intent = try readIntentLocked() else { throw CredentialLifecycleError.blocked(reason) }
                try recover(intent)
                if case .blocked(let recoveryReason) = state {
                    throw CredentialLifecycleError.blocked(recoveryReason)
                }
            } else if let intent = try readIntentLocked(), case .ready = state {
                // A newly observed durable intent must gate stores even if this runtime
                // instance bootstrapped before the intent appeared.
                try recover(intent)
            } else {
                // Re-check each native payload before a new store can migrate or drain GC.
                for side in CredentialLifecycleSide.allCases {
                    guard let participant = participants[side] else { throw CredentialLifecycleError.corruptMetadata(side) }
                    _ = try participant.snapshotKnownReferences()
                }
            }
            return
        }
        guard participants.count == CredentialLifecycleSide.allCases.count else {
            throw CredentialLifecycleError.blocked("凭据恢复组件尚未安装；启动迁移已阻止。")
        }
        if let intent = try readIntentLocked() {
            try recover(intent)
        } else {
            for side in CredentialLifecycleSide.allCases {
                guard let participant = participants[side] else { throw CredentialLifecycleError.corruptMetadata(side) }
                _ = try participant.snapshotKnownReferences() // Validate actual payloads before migration.
            }
            transition(to: .ready)
        }
        bootstrapped = true
    }

    private func recover(_ initial: CredentialLifecycleIntent) throws {
        var intent = initial
        Self.mutationEpoch &+= 1
        for side in CredentialLifecycleSide.allCases {
            guard let participant = participants[side] else { throw CredentialLifecycleError.corruptMetadata(side) }
            let refs = Set(intent.references.filter { $0.side == side }.map(\.account))
            try participant.disableKnownReferences(refs, operationID: intent.operationID)
            guard try participant.verifyDisabled(refs, operationID: intent.operationID) else {
                transition(to: .blocked("本机凭据恢复未完成；读取和迁移仍被阻止。"))
                return
            }
            if !intent.committedSides.contains(side) {
                intent.committedSides.insert(side); try persist(intent)
            }
        }
        guard intent.committedSides.count == CredentialLifecycleSide.allCases.count else {
            transition(to: .blocked("本机凭据恢复未完成；读取和迁移仍被阻止."))
            return
        }
        if intent.processGeneration == processGeneration {
            transition(to: .disabledAwaitingNextProcess)
            return
        }
        intent.failures.removeAll()
        intent.cleanupStarted = true
        try persist(intent)
        for ref in intent.pendingDeletion {
            guard let participant = participants[ref.side] else { throw CredentialLifecycleError.corruptMetadata(ref.side) }
            do {
                try participant.deleteRaw(reference: ref.account)
                guard try participant.readRaw(reference: ref.account) == nil else {
                    intent.failures.insert(ref)
                    try persist(intent)
                    continue
                }
                intent.pendingDeletion.remove(ref)
                intent.verifiedAbsent.insert(ref)
                try persist(intent)
            } catch {
                intent.failures.insert(ref)
                try persist(intent)
            }
        }
        transition(to: intent.pendingDeletion.isEmpty ? .completeKnownReferences : .cleanupRetryRequired)
    }

    private func readIntentLocked() throws -> CredentialLifecycleIntent? {
        guard let data = try journal.read() else { return nil }
        guard data.count <= CredentialLifecycleFileJournal.maximumBytes,
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw CredentialLifecycleError.corruptJournal
        }
        let expectedKeys: Set<String> = ["schemaVersion", "operationID", "processGeneration", "references",
            "disabledReferences", "committedSides", "cleanupStarted", "initialPendingDeletion",
            "pendingDeletion", "verifiedAbsent", "failures"]
        guard Set(object.keys) == expectedKeys,
              Self.hasUniqueStrings(object["committedSides"]),
              Self.hasUniqueReferences(object["references"]),
              Self.hasUniqueReferences(object["disabledReferences"]),
              Self.hasUniqueReferences(object["initialPendingDeletion"]),
              Self.hasUniqueReferences(object["pendingDeletion"]),
              Self.hasUniqueReferences(object["verifiedAbsent"]),
              Self.hasUniqueReferences(object["failures"]),
              let intent = try? JSONDecoder().decode(CredentialLifecycleIntent.self, from: data) else {
            throw CredentialLifecycleError.corruptJournal
        }
        try intent.validate()
        return intent
    }

    private static func hasUniqueStrings(_ raw: Any?) -> Bool {
        guard let values = raw as? [String] else { return false }
        return Set(values).count == values.count
    }

    private static func hasUniqueReferences(_ raw: Any?) -> Bool {
        guard let values = raw as? [[String: Any]] else { return false }
        var keys = Set<String>()
        for value in values {
            guard Set(value.keys) == Set(["side", "account"]),
                  let side = value["side"] as? String, CredentialLifecycleSide(rawValue: side) != nil,
                  let account = value["account"] as? String else { return false }
            guard keys.insert(side + "\u{0}" + account).inserted else { return false }
        }
        return true
    }

    private func persist(_ intent: CredentialLifecycleIntent) throws {
        try intent.validate()
        let encoder = JSONEncoder(); encoder.outputFormatting = [.sortedKeys]
        let data = try encoder.encode(intent)
        guard data.count <= CredentialLifecycleFileJournal.maximumBytes else { throw CredentialLifecycleError.tooManyReferences }
        try journal.write(data)
        guard try journal.read() == data else { throw CredentialLifecycleError.persistence }
    }

    private func emptyIntent() -> CredentialLifecycleIntent {
        CredentialLifecycleIntent(schemaVersion: 1, operationID: "none", processGeneration: processGeneration,
            references: [], disabledReferences: [], committedSides: [], cleanupStarted: false,
            initialPendingDeletion: [], pendingDeletion: [], verifiedAbsent: [], failures: [])
    }

    private func result(_ phase: CredentialClearResult.Phase, _ intent: CredentialLifecycleIntent,
                        _ message: String) -> CredentialClearResult {
        CredentialClearResult(phase: phase,
            aiMetadataVerified: intent.committedSides.contains(.ai),
            agentMetadataVerified: intent.committedSides.contains(.agent),
            unresolvedReferenceCount: intent.pendingDeletion.count, message: message)
    }
}
