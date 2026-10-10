import Foundation
import CryptoKit

/// Exact, read-only inventory of the legacy files that would become one note generation.
/// The caller must recapture this inventory immediately before explicit confirmation.
public struct LegacyNoteGroupSnapshot {
    public struct Source {
        public let id: String
        public let role: NoteGroupMemberRole
        public let url: URL
        public init(id: String, role: NoteGroupMemberRole, url: URL) {
            self.id = id; self.role = role; self.url = url
        }
    }

    public let localNoteID: String
    public let lineageID: String
    public let sources: [Source]

    public init(localNoteID: String, lineageID: String, sources: [Source]) {
        self.localNoteID = localNoteID; self.lineageID = lineageID; self.sources = sources
    }
}

public struct NoteGroupPreview: Equatable {
    public struct Witness: Equatable {
        public let id: String
        public let role: NoteGroupMemberRole
        public let byteLength: Int64
        public let sha256: String
    }
    public let localNoteID: String
    public let lineageID: String
    public let witnesses: [Witness]
}

public enum NoteGroupFacadeError: Error, Equatable {
    case invalidUUID
    case invalidSnapshot
    case sourceChanged
    case groupAlreadyExists
    case legacyCaptureUnavailable
}

/// Opt-in bridge around the durable group store. It deliberately does not alter legacy files
/// or connect to app UI; callers must ask the user to review a preview before calling adopt.
public final class NoteGroupFacade {
    private let store: NoteGroupStore
    private let recapture: (String) throws -> LegacyNoteGroupSnapshot

    public init(store: NoteGroupStore,
                recaptureLegacy: @escaping (String) throws -> LegacyNoteGroupSnapshot) {
        self.store = store
        self.recapture = recaptureLegacy
    }

    /// A foundation-owned identity is a canonical lowercase UUID. Legacy source IDs remain
    /// untouched in witness/source metadata; only the new group's UUID keys are normalized.
    public static func foundationUUID(_ raw: String) throws -> String {
        guard let value = UUID(uuidString: raw) else { throw NoteGroupFacadeError.invalidUUID }
        return value.uuidString.lowercased()
    }

    public func preview(noteID: String) throws -> NoteGroupPreview {
        let localID = try Self.foundationUUID(noteID)
        let snapshot = try recapture(localID)
        return try preview(snapshot: snapshot, expectedLocalID: localID)
    }

    /// Builds a preview from a concrete storage capture, allowing async capture coordinators to
    /// retain and clean their private snapshot directory around this synchronous witness step.
    public func preview(snapshot: LegacyNoteGroupSnapshot) throws -> NoteGroupPreview {
        try preview(snapshot: snapshot, expectedLocalID: Self.foundationUUID(snapshot.localNoteID))
    }

    /// Explicit confirmation boundary: reread every source witness, reject any change, then
    /// publish exactly one complete immutable generation with empty-parent CAS.
    @discardableResult
    public func adopt(_ preview: NoteGroupPreview) throws -> NoteGroupVersionToken {
        let current = try recapture(preview.localNoteID)
        return try adopt(preview, currentSnapshot: current)
    }

    /// Commits a freshly captured source set only if its complete witness exactly matches preview.
    public func adopt(_ preview: NoteGroupPreview, currentSnapshot: LegacyNoteGroupSnapshot) throws -> NoteGroupVersionToken {
        let confirmed = try makePreview(currentSnapshot, expectedLocalID: preview.localNoteID)
        guard confirmed == preview else { throw NoteGroupFacadeError.sourceChanged }
        if let _ = try store.readCurrentIfPresent(localNoteID: preview.localNoteID, lineageID: preview.lineageID) {
            throw NoteGroupFacadeError.groupAlreadyExists
        }
        let inputs = try currentSnapshot.sources.map {
            let canonicalID = try Self.foundationUUID($0.id)
            return NoteGroupMemberInput(id: canonicalID, role: $0.role, mediaType: Self.mediaType(for: $0.role), sourceURL: $0.url)
        }
        let expected = confirmed.witnesses.map {
            NoteGroupExpectedMemberWitness(id: $0.id, role: $0.role, byteLength: $0.byteLength, sha256: $0.sha256)
        }
        do {
            return try store.commit(localNoteID: preview.localNoteID, lineageID: preview.lineageID,
                                    expected: nil, members: inputs, expectedSourceWitnesses: expected)
        } catch NoteGroupStoreError.sourceChanged {
            throw NoteGroupFacadeError.sourceChanged
        }
    }

    /// Current group is authoritative, including its tombstone. Legacy fallback happens only
    /// when no group marker exists; members are returned from one verified revision token.
    public func read(noteID: String, lineageID: String) throws -> NoteGroupReadResult {
        let localID = try Self.foundationUUID(noteID)
        let lineage = try Self.foundationUUID(lineageID)
        if let current = try store.readCurrentIfPresent(localNoteID: localID, lineageID: lineage) {
            if current.manifest.tombstone { return .retired(current.token) }
            let members = try current.manifest.members.map { record in
                NoteGroupReadMember(record: record, url: try store.memberURL(for: record, in: current.token))
            }
            guard members.filter({ $0.record.role == .body }).count == 1 else {
                throw NoteGroupStoreError.corruptRevision
            }
            return .current(current.token, members)
        }
        return .legacy(try recapture(localID))
    }

    private func makePreview(_ snapshot: LegacyNoteGroupSnapshot,
                             expectedLocalID: String) throws -> NoteGroupPreview {
        let localID = try Self.foundationUUID(snapshot.localNoteID)
        let lineage = try Self.foundationUUID(snapshot.lineageID)
        guard localID == expectedLocalID, !snapshot.sources.isEmpty,
              snapshot.sources.filter({ $0.role == .body }).count == 1,
              snapshot.sources.filter({ $0.role == .pdf }).count <= 1,
              snapshot.sources.filter({ $0.role == .cover }).count <= 1,
              snapshot.sources.filter({ $0.role == .association }).count <= 1,
              snapshot.sources.count <= 256 else { throw NoteGroupFacadeError.invalidSnapshot }
        var seen = Set<String>()
        let witnesses = try snapshot.sources.map { source -> NoteGroupPreview.Witness in
            let id = try Self.foundationUUID(source.id)
            guard seen.insert(id).inserted else { throw NoteGroupFacadeError.invalidSnapshot }
            guard source.url.isFileURL else { throw NoteGroupFacadeError.invalidSnapshot }
            if source.role == .association {
                let bytes = try LibraryBackupArchive.readSmallFile(source.url, maximumBytes: 1_048_576)
                guard (try? JSONSerialization.jsonObject(with: bytes, options: [.fragmentsAllowed])) != nil else {
                    throw NoteGroupFacadeError.invalidSnapshot
                }
            }
            let file = try LibraryBackupArchive.hashFile(source.url)
            return .init(id: id, role: source.role, byteLength: file.size, sha256: file.sha256)
        }.sorted { $0.id < $1.id }
        return NoteGroupPreview(localNoteID: localID, lineageID: lineage, witnesses: witnesses)
    }

    private func preview(snapshot: LegacyNoteGroupSnapshot, expectedLocalID: String) throws -> NoteGroupPreview {
        try makePreview(snapshot, expectedLocalID: expectedLocalID)
    }

    private static func mediaType(for role: NoteGroupMemberRole) -> String {
        switch role {
        case .body, .association: return "application/json"
        case .pdf: return "application/pdf"
        case .cover: return "image/png"
        case .video: return "video/mp4"
        case .videoMetadata: return "application/json"
        case .vault: return "text/markdown"
        case .vaultMetadata: return "application/json"
        }
    }
}

public struct NoteGroupReadMember {
    public let record: NoteGroupMemberRecord
    public let url: URL
}

public enum NoteGroupReadResult {
    case legacy(LegacyNoteGroupSnapshot)
    case current(NoteGroupVersionToken, [NoteGroupReadMember])
    case retired(NoteGroupVersionToken)
}
