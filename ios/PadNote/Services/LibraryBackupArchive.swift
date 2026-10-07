import Foundation
import CryptoKit
import zlib
import Darwin
import CoreFoundation

public enum LibraryBackupError: Error, LocalizedError, Equatable {
    case invalidArchive(String), invalidManifest(String), sizeLimit(String), unsafeFile, sourceChanged, insufficientSpace, cancelled, transaction(String)
    public var errorDescription: String? { switch self { case .invalidArchive(let v), .invalidManifest(let v), .sizeLimit(let v), .transaction(let v): return v; case .unsafeFile: return "备份包含不安全的文件"; case .sourceChanged: return "备份期间资料发生变化，请重新生成备份"; case .insufficientSpace: return "设备可用空间不足，未修改资料库"; case .cancelled: return "已取消备份操作，原资料未修改" } }
}

public final class LibraryBackupCancellationToken: @unchecked Sendable {
    private let lock = NSLock()
    private var cancelled = false
    public init() {}
    public func cancel() { lock.lock(); cancelled = true; lock.unlock() }
    public func check() throws {
        lock.lock(); let value = cancelled; lock.unlock()
        if value { throw LibraryBackupError.cancelled }
    }
}

public struct LibraryBackupManifest: Codable, Equatable {
    public struct Scope: Codable, Equatable {
        public var notes = "all-selected"; public var attachedPDFs = true; public var assignedCovers = true; public var vaultEntries = true; public var userCoverPresets = true; public var videoAttachments = true
        public var credentials = false; public var connections = false; public var taskHistory = false; public var inFlightWork = false
        enum CodingKeys: String, CodingKey { case notes, attachedPDFs="attached_pdfs", assignedCovers="assigned_covers", vaultEntries="vault_entries", userCoverPresets="user_cover_presets", videoAttachments="video_attachments", credentials, connections, taskHistory="task_history", inFlightWork="in_flight_work" }
    }
    public struct Note: Codable, Equatable {
        public let itemID: String; public let sourceNoteID: String; public let sourceRevisionMS: Int64; public let schemaVersion: Int; public let noteResourceID: String; public let pdfResourceID: String?; public let coverResourceID: String?
        public var vaultEntryIDs: [String] = []; public var videoAttachmentIDs: [String] = []
        enum CodingKeys: String, CodingKey { case itemID="item_id", sourceNoteID="source_note_id", sourceRevisionMS="source_revision_ms", schemaVersion="note_schema_version", noteResourceID="note_resource_id", pdfResourceID="pdf_resource_id", coverResourceID="cover_resource_id", vaultEntryIDs="vault_entry_ids", videoAttachmentIDs="video_attachment_ids" }
        public func encode(to encoder: Encoder) throws {
            var c = encoder.container(keyedBy: CodingKeys.self)
            try c.encode(itemID, forKey: .itemID); try c.encode(sourceNoteID, forKey: .sourceNoteID)
            try c.encode(sourceRevisionMS, forKey: .sourceRevisionMS); try c.encode(schemaVersion, forKey: .schemaVersion)
            try c.encode(noteResourceID, forKey: .noteResourceID)
            if let pdfResourceID { try c.encode(pdfResourceID, forKey: .pdfResourceID) } else { try c.encodeNil(forKey: .pdfResourceID) }
            if let coverResourceID { try c.encode(coverResourceID, forKey: .coverResourceID) } else { try c.encodeNil(forKey: .coverResourceID) }
            try c.encode(vaultEntryIDs, forKey: .vaultEntryIDs); try c.encode(videoAttachmentIDs, forKey: .videoAttachmentIDs)
        }
    }
    public struct Vault: Codable, Equatable {
        public let itemID: String; public let noteItemID: String?; public let sourceState: String; public let sourceNoteID: String; public let sourceRevisionMS: Int64; public let createdAtMS: Int64; public let resourceID: String
        enum CodingKeys: String, CodingKey { case itemID="item_id", noteItemID="note_item_id", sourceState="source_state", sourceNoteID="source_note_id", sourceRevisionMS="source_revision_ms", createdAtMS="created_at_ms", resourceID="resource_id" }
        public func encode(to encoder: Encoder) throws {
            var c = encoder.container(keyedBy: CodingKeys.self)
            try c.encode(itemID, forKey: .itemID)
            if let noteItemID { try c.encode(noteItemID, forKey: .noteItemID) } else { try c.encodeNil(forKey: .noteItemID) }
            try c.encode(sourceState, forKey: .sourceState); try c.encode(sourceNoteID, forKey: .sourceNoteID)
            try c.encode(sourceRevisionMS, forKey: .sourceRevisionMS); try c.encode(createdAtMS, forKey: .createdAtMS)
            try c.encode(resourceID, forKey: .resourceID)
        }
    }
    public struct ConnectionProvenance: Codable, Equatable {
        public let connectionID: String?; public let revision: Int?; public let kind: String?; public let transport: String?; public let bridgeID: String?; public let instanceID: String?; public let certificateSHA256: String?
        enum CodingKeys: String, CodingKey { case connectionID="connection_id", revision="connection_revision", kind, transport, bridgeID="bridge_id", instanceID="instance_id", certificateSHA256="certificate_sha256" }
        public func encode(to encoder: Encoder) throws {
            var c = encoder.container(keyedBy: CodingKeys.self)
            try Self.encode(connectionID, to: &c, forKey: .connectionID); try Self.encode(revision, to: &c, forKey: .revision)
            try Self.encode(kind, to: &c, forKey: .kind); try Self.encode(transport, to: &c, forKey: .transport)
            try Self.encode(bridgeID, to: &c, forKey: .bridgeID); try Self.encode(instanceID, to: &c, forKey: .instanceID)
            try Self.encode(certificateSHA256, to: &c, forKey: .certificateSHA256)
        }
        private static func encode<T: Encodable>(_ value: T?, to c: inout KeyedEncodingContainer<CodingKeys>, forKey key: CodingKeys) throws {
            if let value { try c.encode(value, forKey: key) } else { try c.encodeNil(forKey: key) }
        }
    }
    public struct Video: Codable, Equatable {
        public let itemID: String; public let noteItemID: String?; public let sourceState: String; public let originKind: String; public let sourceNoteID: String; public let sourceRevisionMS: Int64; public let sourceRevisionPrecisionMS: Int64; public let sourceBundleSHA256: String; public let taskPayloadSHA256: String?; public let digestKind: String; public let offlineState: String; public let taskID: String?; public let remoteTaskID: String?; public let connection: ConnectionProvenance; public let artifactID: String?; public let displayName: String; public let mediaType: String; public let byteLength: Int64; public let sha256: String; public let createdAtMS: Int64; public let resourceID: String
        enum CodingKeys: String, CodingKey { case itemID="item_id", noteItemID="note_item_id", sourceState="source_state", originKind="origin_kind", sourceNoteID="source_note_id", sourceRevisionMS="source_revision_ms", sourceRevisionPrecisionMS="source_revision_precision_ms", sourceBundleSHA256="source_bundle_sha256", taskPayloadSHA256="task_payload_sha256", digestKind="digest_kind", offlineState="offline_state", taskID="task_id", remoteTaskID="remote_task_id", connection="connection_provenance", artifactID="artifact_id", displayName="display_name", mediaType="media_type", byteLength="byte_length", sha256, createdAtMS="created_at_ms", resourceID="resource_id" }
        public func encode(to encoder: Encoder) throws {
            var c = encoder.container(keyedBy: CodingKeys.self)
            try c.encode(itemID, forKey: .itemID)
            try Self.encode(noteItemID, to: &c, forKey: .noteItemID); try c.encode(sourceState, forKey: .sourceState)
            try c.encode(originKind, forKey: .originKind); try c.encode(sourceNoteID, forKey: .sourceNoteID)
            try c.encode(sourceRevisionMS, forKey: .sourceRevisionMS); try c.encode(sourceRevisionPrecisionMS, forKey: .sourceRevisionPrecisionMS)
            try c.encode(sourceBundleSHA256, forKey: .sourceBundleSHA256); try Self.encode(taskPayloadSHA256, to: &c, forKey: .taskPayloadSHA256)
            try c.encode(digestKind, forKey: .digestKind); try c.encode(offlineState, forKey: .offlineState)
            try Self.encode(taskID, to: &c, forKey: .taskID); try Self.encode(remoteTaskID, to: &c, forKey: .remoteTaskID)
            try c.encode(connection, forKey: .connection); try Self.encode(artifactID, to: &c, forKey: .artifactID)
            try c.encode(displayName, forKey: .displayName); try c.encode(mediaType, forKey: .mediaType)
            try c.encode(byteLength, forKey: .byteLength); try c.encode(sha256, forKey: .sha256)
            try c.encode(createdAtMS, forKey: .createdAtMS); try c.encode(resourceID, forKey: .resourceID)
        }
        private static func encode<T: Encodable>(_ value: T?, to c: inout KeyedEncodingContainer<CodingKeys>, forKey key: CodingKeys) throws {
            if let value { try c.encode(value, forKey: key) } else { try c.encodeNil(forKey: key) }
        }
    }
    public struct CoverPreset: Codable, Equatable { public let itemID: String; public let displayName: String; public let resourceID: String; enum CodingKeys: String, CodingKey { case itemID="item_id", displayName="display_name", resourceID="resource_id" } }
    public struct Resource: Codable, Equatable { public let resourceID: String; public let role: String; public let mediaType: String; public let byteLength: Int64; public let sha256: String; public let member: String; enum CodingKeys: String, CodingKey { case resourceID="resource_id", role, mediaType="media_type", byteLength="byte_length", sha256, member } }
    public let format = "com.padnote.library-archive"; public let formatVersion = 1; public let createdAtMS: Int64; public let producer: [String:String]; public let scope = Scope(); public var notes:[Note]; public var vaultEntries:[Vault]; public var videoAttachments:[Video]; public var coverPresets:[CoverPreset]; public var resources:[Resource]
    enum CodingKeys:String,CodingKey { case format, formatVersion="format_version", createdAtMS="created_at_ms", producer, scope, notes, vaultEntries="vault_entries", videoAttachments="video_attachments", coverPresets="cover_presets", resources }
    public init(createdAtMS:Int64,producer:[String:String],notes:[Note],vaultEntries:[Vault],videoAttachments:[Video],coverPresets:[CoverPreset],resources:[Resource]) { self.createdAtMS=createdAtMS;self.producer=producer;self.notes=notes;self.vaultEntries=vaultEntries;self.videoAttachments=videoAttachments;self.coverPresets=coverPresets;self.resources=resources }
}

/// Independent r1 ZIP implementation. It uses fixed generated member names and streams payloads to/from files.
public enum LibraryBackupArchive {
    public static let maxArchiveBytes:Int64=1_181_116_006; public static let maxManifestBytes=16*1024*1024; public static let maxResources=10_000; public static let maxExpandedBytes:Int64=1024*1024*1024; public static let maxNoteBytes:Int64=50*1024*1024; public static let maxPDFBytes:Int64=100*1024*1024; public static let maxPNGBytes:Int64=8*1024*1024; public static let maxVideoBytes:Int64=100*1024*1024; public static let maxVaultBytes:Int64=16*1024*1024; private static let chunk=64*1024
    public struct StagedArchive { public let manifest:LibraryBackupManifest; public let directory:URL; public let archiveSHA256:String }
    private struct Entry { let name:String;let flags:UInt16;let method:UInt16;let crc:UInt32;let compressed:UInt32;let expanded:UInt32;let offset:UInt32;let external:UInt32 }
    private struct Identity:Equatable { let dev:UInt64;let ino:UInt64;let size:Int64;let mtime:Int64;let ctime:Int64;let links:UInt16 }

    public static func write(manifest:LibraryBackupManifest,resourceFiles:[String:URL],to destination:URL,
                             cancellation: LibraryBackupCancellationToken? = nil)throws {
        try validate(manifest:manifest);guard Set(resourceFiles.keys)==Set(manifest.resources.map(\.resourceID)),resourceFiles.count==manifest.resources.count else {throw LibraryBackupError.invalidManifest("资源文件不完整")}
        let json=try JSONEncoder.libraryBackup.encode(manifest);guard json.count<=maxManifestBytes else {throw LibraryBackupError.sizeLimit("manifest 超过 16 MiB")}
        let parent=destination.deletingLastPathComponent();try FileManager.default.createDirectory(at:parent,withIntermediateDirectories:true);let tmp=parent.appendingPathComponent(".\(UUID().uuidString).library-backup.partial")
        let fd=Darwin.open(tmp.path,O_WRONLY|O_CREAT|O_EXCL|O_NOFOLLOW|O_CLOEXEC,S_IRUSR|S_IWUSR);guard fd>=0 else{throw LibraryBackupError.unsafeFile};let out=FileHandle(fileDescriptor:fd,closeOnDealloc:true);var central=[Entry](),cursor:UInt64=0
        do {
            let all=[("manifest.json",json,nil as URL?)]+manifest.resources.map{($0.member,Data(),resourceFiles[$0.resourceID])}
            for(name,data,url) in all { try cancellation?.check(); let nb=Data(name.utf8);guard safeMember(name),cursor<=UInt32.max else{throw LibraryBackupError.invalidManifest("成员路径无效")};let offset=UInt32(cursor);let size=url == nil ? Int64(data.count):try checkedIdentity(url!).size;guard size<=UInt32.max else{throw LibraryBackupError.sizeLimit("成员超出 ZIP r1 限制")};try zipWrite(&cursor,out,localHeader(nb))
                var crc:uLong=crc32(0,nil,0);var sha=SHA256();var count:UInt64=0
                if let url {let(inFD,before)=try openRead(url);defer{_ = Darwin.close(inFD)};let input=FileHandle(fileDescriptor:inFD,closeOnDealloc:false);while true{try cancellation?.check();let p=try input.read(upToCount:chunk) ?? Data();if p.isEmpty{break};count+=UInt64(p.count);guard count<=UInt64(size),count<=UInt64(maxExpandedBytes) else{throw LibraryBackupError.sizeLimit("资源总量超过 1 GiB")};crc=p.withUnsafeBytes{crc32(crc,$0.bindMemory(to:Bytef.self).baseAddress,uInt(p.count))};sha.update(data:p);try zipWrite(&cursor,out,p)};guard count==UInt64(size),try identity(inFD)==before,try checkedIdentity(url)==before else{throw LibraryBackupError.sourceChanged};guard let r=manifest.resources.first(where:{$0.member==name}),r.byteLength==Int64(count),r.sha256==sha.finalize().hexString else{throw LibraryBackupError.sourceChanged}
                } else {count=UInt64(data.count);crc=data.withUnsafeBytes{crc32(crc,$0.bindMemory(to:Bytef.self).baseAddress,uInt(data.count))};try zipWrite(&cursor,out,data)}
                let cv=UInt32(truncatingIfNeeded:crc);try zipWrite(&cursor,out,descriptor(cv,UInt32(count)));central.append(Entry(name:name,flags:0x808,method:0,crc:cv,compressed:UInt32(count),expanded:UInt32(count),offset:offset,external:0))
            }
            try cancellation?.check();let start=cursor;for e in central{try zipWrite(&cursor,out,centralHeader(e))};let size=cursor-start;guard cursor<=UInt32.max,start<=UInt32.max,size<=UInt32.max else{throw LibraryBackupError.sizeLimit("归档超 ZIP r1 限制")};try zipWrite(&cursor,out,endRecord(UInt16(central.count),UInt32(size),UInt32(start)));try out.synchronize();try out.close();try cancellation?.check();guard cursor<=UInt64(maxArchiveBytes),!FileManager.default.fileExists(atPath:destination.path) else{throw LibraryBackupError.sizeLimit("归档超限或目标已存在")};try FileManager.default.moveItem(at:tmp,to:destination)
        }catch{try? out.close();try? FileManager.default.removeItem(at:tmp);throw error}
    }

    public static func stage(from archive:URL,into directory:URL, cancellation: LibraryBackupCancellationToken? = nil)throws->StagedArchive {
        let archiveID=try checkedIdentity(archive);guard archiveID.size<=maxArchiveBytes else{throw LibraryBackupError.sizeLimit("归档超过 1.1 GiB")};try FileManager.default.createDirectory(at:directory,withIntermediateDirectories:false)
        do{let(fd,before)=try openRead(archive);defer{_ = Darwin.close(fd)};let input=FileHandle(fileDescriptor:fd,closeOnDealloc:false);let entries=try readCentral(input,archiveSize:archiveID.size);guard entries.count>0,entries.count<=maxResources+1,let me=entries.first(where:{$0.name=="manifest.json"}),me.expanded<=UInt32(maxManifestBytes) else{throw LibraryBackupError.invalidArchive("manifest缺失或条目超限")};let md=try readMember(input,me,output:nil,limit:Int64(maxManifestBytes),archiveSize:archiveID.size);try validateManifestJSON(md);let manifest=try JSONDecoder().decode(LibraryBackupManifest.self,from:md);try validate(manifest:manifest);let expected=Set(["manifest.json"]+manifest.resources.map { $0.member });guard Set(entries.map(\.name))==expected else{throw LibraryBackupError.invalidArchive("ZIP成员与manifest不匹配")};var total:Int64=0
            for r in manifest.resources{try cancellation?.check();guard let e=entries.first(where:{$0.name==r.member}),Int64(e.expanded)==r.byteLength else{throw LibraryBackupError.invalidArchive("资源长度声明不匹配")};total+=Int64(e.expanded);guard total<=maxExpandedBytes else{throw LibraryBackupError.sizeLimit("解压总量超过1GiB")};let target=directory.appendingPathComponent(r.resourceID+".bin");let(outFD,_)=try openCreate(target);let out=FileHandle(fileDescriptor:outFD,closeOnDealloc:false);_ = try readMember(input,e,output:out,limit:roleLimit(r.role),archiveSize:archiveID.size,cancellation:cancellation);try out.synchronize();try out.close();let actual=try hashFile(target);guard actual.size==r.byteLength,actual.sha256==r.sha256 else{throw LibraryBackupError.invalidArchive("资源摘要不匹配")}}
            guard try identity(fd)==before,try checkedIdentity(archive)==before else{throw LibraryBackupError.sourceChanged};return StagedArchive(manifest:manifest,directory:directory,archiveSHA256:try hashFile(archive).sha256)
        }catch{try? FileManager.default.removeItem(at:directory);throw error}
    }

    public static func writeStagedManifest(_ manifest: LibraryBackupManifest, to directory: URL) throws {
        try validate(manifest: manifest)
        let data = try JSONEncoder.libraryBackup.encode(manifest)
        guard data.count <= maxManifestBytes else { throw LibraryBackupError.sizeLimit("manifest 超过 16 MiB") }
        let target = directory.appendingPathComponent(".restore-manifest.json")
        let fd = Darwin.open(target.path, O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, S_IRUSR | S_IWUSR)
        guard fd >= 0 else { throw LibraryBackupError.unsafeFile }
        let output = FileHandle(fileDescriptor: fd, closeOnDealloc: true)
        do { try output.write(contentsOf: data); try output.synchronize(); try output.close() }
        catch { try? output.close(); try? FileManager.default.removeItem(at: target); throw error }
    }

    public static func readStagedManifest(from directory: URL) throws -> LibraryBackupManifest {
        let data = try readSmallFile(directory.appendingPathComponent(".restore-manifest.json"), maximumBytes: Int64(maxManifestBytes))
        try validateManifestJSON(data)
        let manifest = try JSONDecoder().decode(LibraryBackupManifest.self, from: data)
        try validate(manifest: manifest)
        return manifest
    }

    static func rejectDuplicateJSONKeys(_ data: Data) throws {
        let bytes = [UInt8](data)
        var index = 0
        func whitespace() { while index < bytes.count && [UInt8(0x20), 0x09, 0x0a, 0x0d].contains(bytes[index]) { index += 1 } }
        func string() throws -> String {
            whitespace()
            guard index < bytes.count, bytes[index] == 0x22 else { throw LibraryBackupError.invalidManifest("JSON字符串无效") }
            let start = index; index += 1; var escaped = false
            while index < bytes.count {
                let byte = bytes[index]; index += 1
                if escaped { escaped = false; continue }
                if byte == 0x5c { escaped = true; continue }
                if byte == 0x22 {
                    return try JSONDecoder().decode(String.self, from: Data(bytes[start..<index]))
                }
            }
            throw LibraryBackupError.invalidManifest("JSON字符串未结束")
        }
        func value(_ depth: Int) throws {
            whitespace()
            guard depth <= 128, index < bytes.count else { throw LibraryBackupError.invalidManifest("JSON结构无效") }
            if bytes[index] == 0x7b {
                index += 1; whitespace()
                if index < bytes.count && bytes[index] == 0x7d { index += 1; return }
                var keys = Set<String>()
                while true {
                    let key = try string()
                    guard keys.insert(key).inserted else { throw LibraryBackupError.invalidManifest("JSON对象含重复字段") }
                    whitespace(); guard index < bytes.count, bytes[index] == 0x3a else { throw LibraryBackupError.invalidManifest("JSON对象无效") }
                    index += 1; try value(depth + 1); whitespace()
                    guard index < bytes.count else { throw LibraryBackupError.invalidManifest("JSON对象未结束") }
                    if bytes[index] == 0x7d { index += 1; return }
                    guard bytes[index] == 0x2c else { throw LibraryBackupError.invalidManifest("JSON对象无效") }
                    index += 1
                }
            }
            if bytes[index] == 0x5b {
                index += 1; whitespace()
                if index < bytes.count && bytes[index] == 0x5d { index += 1; return }
                while true {
                    try value(depth + 1); whitespace()
                    guard index < bytes.count else { throw LibraryBackupError.invalidManifest("JSON数组未结束") }
                    if bytes[index] == 0x5d { index += 1; return }
                    guard bytes[index] == 0x2c else { throw LibraryBackupError.invalidManifest("JSON数组无效") }
                    index += 1
                }
            }
            if bytes[index] == 0x22 { _ = try string(); return }
            while index < bytes.count && ![UInt8(0x2c), 0x5d, 0x7d, 0x20, 0x09, 0x0a, 0x0d].contains(bytes[index]) { index += 1 }
        }
        try value(0); whitespace()
        guard index == bytes.count else { throw LibraryBackupError.invalidManifest("JSON尾部数据无效") }
    }

    static func validateIntegerJSONTokens(_ data: Data) throws {
        try rejectDuplicateJSONKeys(data)
        // JSONDecoder's integer strategy accepts 1.0 and 1e3; archive integer fields require
        // exact integer JSON tokens. This is intentionally not applied to NoteDocument payloads.
        let bytes = [UInt8](data)
        var i = 0
        while i < bytes.count {
            if bytes[i] == 0x22 {
                i += 1
                var escaped = false
                while i < bytes.count {
                    let b = bytes[i]; i += 1
                    if escaped { escaped = false; continue }
                    if b == 0x5c { escaped = true; continue }
                    if b == 0x22 { break }
                }
            } else if bytes[i] == 0x2d || (bytes[i] >= 0x30 && bytes[i] <= 0x39) {
                let start = i; i += 1
                while i < bytes.count && bytes[i] != 0x2c && bytes[i] != 0x5d && bytes[i] != 0x7d &&
                        bytes[i] != 0x20 && bytes[i] != 0x0a && bytes[i] != 0x0d && bytes[i] != 0x09 { i += 1 }
                let token = String(decoding: bytes[start..<i], as: UTF8.self)
                guard !token.contains("."), !token.contains("e"), !token.contains("E"), Int64(token) != nil else {
                    throw LibraryBackupError.invalidManifest("整数必须使用精确的 JSON 整数格式")
                }
            } else { i += 1 }
        }
    }

    static func validateManifestJSON(_ data: Data) throws {
        try validateIntegerJSONTokens(data)
        let root = try JSONSerialization.jsonObject(with: data)
        func object(_ value: Any?, _ label: String) throws -> [String: Any] {
            guard let value = value as? [String: Any] else { throw LibraryBackupError.invalidManifest("\(label)结构无效") }
            return value
        }
        func exact(_ value: [String: Any], _ keys: Set<String>, _ label: String) throws {
            guard Set(value.keys) == keys else { throw LibraryBackupError.invalidManifest("\(label)字段缺失或未知") }
        }
        func integer(_ value: [String: Any], _ key: String) throws {
            guard let number = value[key] as? NSNumber, CFGetTypeID(number) != CFBooleanGetTypeID(),
                  ["c", "s", "i", "q", "l", "C", "S", "I", "Q", "L"].contains(String(cString: number.objCType)) else {
                throw LibraryBackupError.invalidManifest("\(key)必须为 JSON 整数")
            }
        }
        func boolean(_ value: [String: Any], _ key: String) throws {
            guard let number = value[key] as? NSNumber, CFGetTypeID(number) == CFBooleanGetTypeID() else {
                throw LibraryBackupError.invalidManifest("\(key)必须为 JSON 布尔值")
            }
        }
        func records(_ value: Any?, _ keys: Set<String>, _ label: String) throws -> [[String: Any]] {
            guard let rows = value as? [Any] else { throw LibraryBackupError.invalidManifest("\(label)列表无效") }
            return try rows.map { row in let item = try object(row, label); try exact(item, keys, label); return item }
        }
        let topKeys: Set<String> = ["format", "format_version", "created_at_ms", "producer", "scope", "notes", "vault_entries", "video_attachments", "cover_presets", "resources"]
        let scopeKeys: Set<String> = ["notes", "attached_pdfs", "assigned_covers", "vault_entries", "user_cover_presets", "video_attachments", "credentials", "connections", "task_history", "in_flight_work"]
        let noteKeys: Set<String> = ["item_id", "source_note_id", "source_revision_ms", "note_schema_version", "note_resource_id", "pdf_resource_id", "cover_resource_id", "vault_entry_ids", "video_attachment_ids"]
        let vaultKeys: Set<String> = ["item_id", "note_item_id", "source_state", "source_note_id", "source_revision_ms", "created_at_ms", "resource_id"]
        let connectionKeys: Set<String> = ["connection_id", "connection_revision", "kind", "transport", "bridge_id", "instance_id", "certificate_sha256"]
        let videoKeys: Set<String> = ["item_id", "note_item_id", "source_state", "origin_kind", "source_note_id", "source_revision_ms", "source_revision_precision_ms", "source_bundle_sha256", "task_payload_sha256", "digest_kind", "offline_state", "task_id", "remote_task_id", "connection_provenance", "artifact_id", "display_name", "media_type", "byte_length", "sha256", "created_at_ms", "resource_id"]
        let coverKeys: Set<String> = ["item_id", "display_name", "resource_id"]
        let resourceKeys: Set<String> = ["resource_id", "role", "media_type", "byte_length", "sha256", "member"]
        let doc = try object(root, "manifest"); try exact(doc, topKeys, "manifest")
        guard (doc["format"] as? String) == "com.padnote.library-archive" else { throw LibraryBackupError.invalidManifest("格式无效") }
        let producer = try object(doc["producer"], "producer")
        try exact(producer, Set(["platform", "app_version"]), "producer")
        guard let platform = producer["platform"] as? String, ["ios", "android"].contains(platform),
              let appVersion = producer["app_version"] as? String, !appVersion.isEmpty, appVersion.utf8.count <= 128 else {
            throw LibraryBackupError.invalidManifest("producer 无效")
        }
        try integer(doc, "format_version"); try integer(doc, "created_at_ms")
        guard (doc["format_version"] as? NSNumber)?.intValue == 1 else { throw LibraryBackupError.invalidManifest("格式版本无效") }
        let scope = try object(doc["scope"], "scope"); try exact(scope, scopeKeys, "scope")
        guard scope["notes"] as? String == "all-selected" else { throw LibraryBackupError.invalidManifest("笔记范围无效") }
        let expectedScope: [String: Bool] = ["attached_pdfs": true, "assigned_covers": true, "credentials": false,
            "connections": false, "task_history": false, "in_flight_work": false, "user_cover_presets": true, "video_attachments": true]
        for (key, expected) in expectedScope {
            try boolean(scope, key)
            guard let value = scope[key] as? NSNumber, value.boolValue == expected else { throw LibraryBackupError.invalidManifest("备份范围无效") }
        }
        let notes = try records(doc["notes"], noteKeys, "note")
        for row in notes { try integer(row, "source_revision_ms"); try integer(row, "note_schema_version") }
        let vault = try records(doc["vault_entries"], vaultKeys, "vault")
        for row in vault { try integer(row, "source_revision_ms"); try integer(row, "created_at_ms") }
        let videos = try records(doc["video_attachments"], videoKeys, "video")
        for row in videos {
            try integer(row, "source_revision_ms"); try integer(row, "source_revision_precision_ms")
            try integer(row, "byte_length"); try integer(row, "created_at_ms")
            let connection = try object(row["connection_provenance"], "connection_provenance")
            try exact(connection, connectionKeys, "connection_provenance")
            if connection["connection_revision"] is NSNull { } else { try integer(connection, "connection_revision") }
        }
        _ = try records(doc["cover_presets"], coverKeys, "cover preset")
        let resources = try records(doc["resources"], resourceKeys, "resource")
        for row in resources { try integer(row, "byte_length") }
    }

    public static func validate(manifest: LibraryBackupManifest) throws {
        guard manifest.format == "com.padnote.library-archive", manifest.formatVersion == 1,
              manifest.createdAtMS >= 0, Set(manifest.producer.keys) == Set(["platform", "app_version"]),
              ["ios", "android"].contains(manifest.producer["platform"] ?? ""),
              let appVersion = manifest.producer["app_version"], !appVersion.isEmpty, appVersion.utf8.count <= 128,
              !manifest.scope.credentials, !manifest.scope.connections,
              !manifest.scope.taskHistory, !manifest.scope.inFlightWork else {
            throw LibraryBackupError.invalidManifest("格式版本或备份范围无效")
        }
        guard manifest.resources.count <= maxResources,
              Set(manifest.resources.map(\.resourceID)).count == manifest.resources.count else {
            throw LibraryBackupError.invalidManifest("资源数量或ID无效")
        }
        let expectedMedia: [String: String] = [
            "note_document": "application/json", "pdf_original": "application/pdf",
            "assigned_cover_png": "image/png", "vault_entry_json": "application/json",
            "user_cover_preset_png": "image/png", "video_attachment_mp4": "video/mp4"
        ]
        var sum: Int64 = 0
        var resourceIDs = Set<String>()
        for resource in manifest.resources {
            guard validID(resource.resourceID, "r"), resourceIDs.insert(resource.resourceID).inserted,
                  let mediaType = expectedMedia[resource.role], resource.mediaType == mediaType,
                  resource.byteLength >= 0, resource.sha256.isSHA,
                  resource.member == "payload/\(resource.resourceID).bin", safeMember(resource.member),
                  resource.byteLength <= roleLimit(resource.role) else {
                throw LibraryBackupError.invalidManifest("资源描述无效")
            }
            let (next, overflow) = sum.addingReportingOverflow(resource.byteLength)
            guard !overflow, next <= maxExpandedBytes else { throw LibraryBackupError.sizeLimit("资源总量超过1GiB") }
            sum = next
        }

        var itemIDs = Set<String>()
        var notesByID = [String: LibraryBackupManifest.Note]()
        for note in manifest.notes {
            guard notesByID[note.itemID] == nil else { throw LibraryBackupError.invalidManifest("笔记item_id重复") }
            notesByID[note.itemID] = note
        }
        for note in manifest.notes {
            guard validID(note.itemID, "i"), itemIDs.insert(note.itemID).inserted,
                  validOpaqueID(note.sourceNoteID), note.sourceRevisionMS >= 0,
                  has(manifest, note.noteResourceID, "note_document"),
                  (note.pdfResourceID == nil || has(manifest, note.pdfResourceID!, "pdf_original")),
                  (note.coverResourceID == nil || has(manifest, note.coverResourceID!, "assigned_cover_png")),
                  Set(note.vaultEntryIDs).count == note.vaultEntryIDs.count,
                  Set(note.videoAttachmentIDs).count == note.videoAttachmentIDs.count else {
                throw LibraryBackupError.invalidManifest("笔记引用无效")
            }
        }
        for vault in manifest.vaultEntries {
            guard validID(vault.itemID, "v"), itemIDs.insert(vault.itemID).inserted,
                  validOpaqueID(vault.sourceNoteID), vault.sourceRevisionMS >= 0, vault.createdAtMS >= 0,
                  ["linked_note", "source_deleted", "source_not_selected", "independent"].contains(vault.sourceState),
                  has(manifest, vault.resourceID, "vault_entry_json"),
                  (vault.sourceState == "linked_note" ? vault.noteItemID != nil : vault.noteItemID == nil),
                  vault.noteItemID.map({ notesByID[$0] != nil }) ?? (vault.sourceState != "linked_note") else {
                throw LibraryBackupError.invalidManifest("Vault引用无效")
            }
            if let noteID = vault.noteItemID,
               !notesByID[noteID]!.vaultEntryIDs.contains(vault.itemID) {
                throw LibraryBackupError.invalidManifest("Vault双向关联不一致")
            }
        }
        for video in manifest.videoAttachments {
            guard validID(video.itemID, "a"), itemIDs.insert(video.itemID).inserted,
                  validOpaqueID(video.sourceNoteID), ["computer_task", "restored_archive"].contains(video.originKind),
                  ["linked_note", "source_deleted", "source_not_selected", "independent"].contains(video.sourceState),
                  video.sourceRevisionMS >= 0, [1, 1000].contains(video.sourceRevisionPrecisionMS),
                  (video.sourceRevisionPrecisionMS != 1000 || video.sourceRevisionMS % 1000 == 0),
                  video.sourceBundleSHA256.isSHA, (video.taskPayloadSHA256?.isSHA ?? true),
                  (video.digestKind == "source_snapshot_only" ? video.taskPayloadSHA256 == nil :
                    (video.digestKind == "source_and_task_payload" && video.taskPayloadSHA256 != nil)),
                  video.offlineState == "verified_local_copy", video.mediaType == "video/mp4",
                  video.byteLength > 0, video.sha256.isSHA,
                  has(manifest, video.resourceID, "video_attachment_mp4"),
                  manifest.resources.first(where: { $0.resourceID == video.resourceID })?.byteLength == video.byteLength,
                  (video.sourceState == "linked_note" ? video.noteItemID != nil : video.noteItemID == nil),
                  video.noteItemID.map({ notesByID[$0] != nil }) ?? (video.sourceState != "linked_note") else {
                throw LibraryBackupError.invalidManifest("video attachment引用无效")
            }
            if let noteID = video.noteItemID {
                guard let note = notesByID[noteID], note.videoAttachmentIDs.contains(video.itemID),
                      (video.originKind == "restored_archive" || note.sourceNoteID == video.sourceNoteID) else {
                    throw LibraryBackupError.invalidManifest("视频双向关联或来源ID不一致")
                }
            }
        }
        for note in manifest.notes {
            for vaultID in note.vaultEntryIDs {
                guard manifest.vaultEntries.contains(where: { $0.itemID == vaultID && $0.noteItemID == note.itemID && $0.sourceState == "linked_note" }) else {
                    throw LibraryBackupError.invalidManifest("笔记Vault关联不一致")
                }
            }
            for videoID in note.videoAttachmentIDs {
                guard manifest.videoAttachments.contains(where: { $0.itemID == videoID && $0.noteItemID == note.itemID && $0.sourceState == "linked_note" }) else {
                    throw LibraryBackupError.invalidManifest("笔记视频关联不一致")
                }
            }
        }
        for preset in manifest.coverPresets {
            guard validID(preset.itemID, "c"), itemIDs.insert(preset.itemID).inserted,
                  !preset.displayName.isEmpty, preset.displayName.utf8.count <= 512,
                  has(manifest, preset.resourceID, "user_cover_preset_png") else {
                throw LibraryBackupError.invalidManifest("封面预设引用无效")
            }
        }
        let references = manifest.notes.flatMap { [$0.noteResourceID] + [$0.pdfResourceID, $0.coverResourceID].compactMap { $0 } }
            + manifest.vaultEntries.map(\.resourceID) + manifest.videoAttachments.map(\.resourceID)
            + manifest.coverPresets.map(\.resourceID)
        guard references.count == resourceIDs.count, Set(references).count == references.count,
              Set(references) == resourceIDs else {
            throw LibraryBackupError.invalidManifest("未引用或重复引用资源")
        }
    }
    private static func validOpaqueID(_ value: String) -> Bool {
        !value.isEmpty && value.utf8.count <= 512 && value.unicodeScalars.allSatisfy { !CharacterSet.controlCharacters.contains($0) }
    }
    private static func has(_ m:LibraryBackupManifest,_ id:String,_ role:String)->Bool{m.resources.contains{$0.resourceID==id && $0.role==role}}
    private static func roleLimit(_ role:String)->Int64{switch role{case"note_document":return maxNoteBytes;case"pdf_original":return maxPDFBytes;case"assigned_cover_png","user_cover_preset_png":return maxPNGBytes;case"vault_entry_json":return maxVaultBytes;case"video_attachment_mp4":return maxVideoBytes;default:return -1}}
    private static func validID(_ v:String,_ p:String)->Bool{v.range(of:"^\(p)-[0-9a-f]{32}$",options:.regularExpression) != nil}
    private static func safeMember(_ v:String)->Bool{v=="manifest.json" || (v.hasPrefix("payload/r-") && v.hasSuffix(".bin") && !v.contains("..") && !v.contains("\\") && !v.contains("\0") && !v.hasPrefix("/"))}

    private static func checkedIdentity(_ url:URL)throws->Identity{let fd=Darwin.open(url.path,O_RDONLY|O_NOFOLLOW|O_CLOEXEC);guard fd>=0 else{throw LibraryBackupError.unsafeFile};defer{_ = Darwin.close(fd)};return try identity(fd)}
    private static func openRead(_ url:URL)throws->(Int32,Identity){let fd=Darwin.open(url.path,O_RDONLY|O_NOFOLLOW|O_CLOEXEC);guard fd>=0 else{throw LibraryBackupError.unsafeFile};do{return(fd,try identity(fd))}catch{_ = Darwin.close(fd);throw error}}
    private static func openCreate(_ url:URL)throws->(Int32,Identity){let fd=Darwin.open(url.path,O_WRONLY|O_CREAT|O_EXCL|O_NOFOLLOW|O_CLOEXEC,S_IRUSR|S_IWUSR);guard fd>=0 else{throw LibraryBackupError.unsafeFile};do{return(fd,try identity(fd))}catch{_ = Darwin.close(fd);throw error}}
    private static func identity(_ fd:Int32)throws->Identity{var s=stat();guard Darwin.fstat(fd,&s)==0,s.st_mode&mode_t(S_IFMT)==mode_t(S_IFREG),s.st_nlink==1,s.st_size>=0 else{throw LibraryBackupError.unsafeFile};return Identity(dev:UInt64(s.st_dev),ino:UInt64(s.st_ino),size:Int64(s.st_size),mtime:Int64(s.st_mtimespec.tv_sec)*1_000_000_000+Int64(s.st_mtimespec.tv_nsec),ctime:Int64(s.st_ctimespec.tv_sec)*1_000_000_000+Int64(s.st_ctimespec.tv_nsec),links:UInt16(s.st_nlink))}
    public static func validateRegularSource(_ url:URL,maximumBytes:Int64)throws->Int64{let id=try checkedIdentity(url);guard id.size<=maximumBytes else{throw LibraryBackupError.sizeLimit("单资源超过限制")};return id.size}
    @discardableResult public static func copyVerified(_ source:URL,to destination:URL,maximumBytes:Int64,cancellation:LibraryBackupCancellationToken?=nil)throws->(size:Int64,sha256:String){let(fd,before)=try openRead(source);defer{_ = Darwin.close(fd)};guard before.size<=maximumBytes else{throw LibraryBackupError.sizeLimit("单资源超过限制")};let outFD=Darwin.open(destination.path,O_WRONLY|O_CREAT|O_EXCL|O_NOFOLLOW|O_CLOEXEC,S_IRUSR|S_IWUSR);guard outFD>=0 else{throw LibraryBackupError.unsafeFile};let input=FileHandle(fileDescriptor:fd,closeOnDealloc:false),output=FileHandle(fileDescriptor:outFD,closeOnDealloc:true);var h=SHA256();var count:Int64=0;do{while true{try cancellation?.check();let d=try input.read(upToCount:chunk) ?? Data();if d.isEmpty{break};count+=Int64(d.count);guard count<=maximumBytes,count<=before.size else{throw LibraryBackupError.sourceChanged};h.update(data:d);try output.write(contentsOf:d)};try output.synchronize();try output.close();guard count==before.size,try identity(fd)==before,try checkedIdentity(source)==before else{throw LibraryBackupError.sourceChanged};return(count,h.finalize().hexString)}catch{try? output.close();try? FileManager.default.removeItem(at:destination);throw error}}
    public static func hashFile(_ url:URL,cancellation:LibraryBackupCancellationToken?=nil)throws->(size:Int64,sha256:String){let(fd,b)=try openRead(url);defer{_ = Darwin.close(fd)};let f=FileHandle(fileDescriptor:fd,closeOnDealloc:false);var h=SHA256();var n:Int64=0;while true{try cancellation?.check();let d=try f.read(upToCount:chunk) ?? Data();if d.isEmpty{break};n+=Int64(d.count);h.update(data:d)};guard try identity(fd)==b,n==b.size else{throw LibraryBackupError.sourceChanged};return(n,h.finalize().hexString)}
    public static func readSmallFile(_ url: URL, maximumBytes: Int64) throws -> Data {
        let (fd, before) = try openRead(url); defer { _ = Darwin.close(fd) }
        guard before.size <= maximumBytes, before.size <= Int64(Int.max) else { throw LibraryBackupError.sizeLimit("文件超过单项读取限制") }
        let input = FileHandle(fileDescriptor: fd, closeOnDealloc: false); var result = Data(); result.reserveCapacity(Int(before.size))
        while true { let part = try input.read(upToCount: chunk) ?? Data(); if part.isEmpty { break }; result.append(part); guard Int64(result.count) <= maximumBytes else { throw LibraryBackupError.sizeLimit("文件超过单项读取限制") } }
        guard Int64(result.count) == before.size, try identity(fd) == before else { throw LibraryBackupError.sourceChanged }
        return result
    }

    private static func readCentral(_ file: FileHandle, archiveSize: Int64) throws -> [Entry] {
        let tailCount = Int(min(archiveSize, 65_557))
        try file.seek(toOffset: UInt64(archiveSize - Int64(tailCount)))
        let tail = try file.read(upToCount: tailCount) ?? Data()
        guard let eocd = (0...max(0, tail.count - 22)).reversed().first(where: { tail.u32($0) == 0x06054b50 }) else {
            throw LibraryBackupError.invalidArchive("ZIP 尾目录缺失")
        }
        let number = tail.u16(eocd + 10)
        guard tail.u16(eocd + 4) == 0, tail.u16(eocd + 6) == 0,
              tail.u16(eocd + 8) == number, number <= UInt16(maxResources + 1),
              eocd + 22 + Int(tail.u16(eocd + 20)) == tail.count else {
            throw LibraryBackupError.invalidArchive("ZIP 尾目录字段无效")
        }
        let count = Int(number), directorySize = Int(tail.u32(eocd + 12)), directoryOffset = UInt64(tail.u32(eocd + 16))
        guard number != UInt16.max, tail.u32(eocd + 12) != UInt32.max, tail.u32(eocd + 16) != UInt32.max else { throw LibraryBackupError.invalidArchive("不支持 ZIP64") }
        guard directorySize <= maxManifestBytes,
              directoryOffset + UInt64(directorySize) == UInt64(archiveSize - Int64(tailCount) + Int64(eocd)) else {
            throw LibraryBackupError.invalidArchive("中央目录范围无效")
        }
        try file.seek(toOffset: directoryOffset)
        let data = try file.read(upToCount: directorySize) ?? Data()
        guard data.count == directorySize else { throw LibraryBackupError.invalidArchive("中央目录截断") }
        var cursor = 0
        var names = Set<String>()
        var result = [Entry]()
        for _ in 0..<count {
            guard cursor + 46 <= data.count, data.u32(cursor) == 0x02014b50 else {
                throw LibraryBackupError.invalidArchive("中央目录格式无效")
            }
            let flags = data.u16(cursor + 8), method = data.u16(cursor + 10)
            let nameLength = Int(data.u16(cursor + 28)), extraLength = Int(data.u16(cursor + 30))
            let commentLength = Int(data.u16(cursor + 32)), end = cursor + 46 + nameLength + extraLength + commentLength
            guard end <= data.count, flags & 1 == 0, flags & ~UInt16(0x80e) == 0,
                  method == 0 || method == 8,
                  let name = String(data: data[(cursor + 46)..<(cursor + 46 + nameLength)], encoding: .utf8),
                  safeMember(name), names.insert(name.lowercased()).inserted,
                  data.u32(cursor + 20) != UInt32.max, data.u32(cursor + 24) != UInt32.max, data.u32(cursor + 42) != UInt32.max,
                  !hasZIP64Extra(data[(cursor + 46 + nameLength)..<(cursor + 46 + nameLength + extraLength)]) else {
                throw LibraryBackupError.invalidArchive("ZIP 成员属性或路径无效")
            }
            let host = data.u16(cursor + 4) >> 8
            let external = data.u32(cursor + 38)
            let unixMode = UInt16((external >> 16) & 0xffff)
            guard !(host == 3 && unixMode != 0 && unixMode & 0xf000 != 0x8000), external & 0x10 == 0 else {
                throw LibraryBackupError.invalidArchive("ZIP 不能包含目录、符号链接或特殊文件")
            }
            result.append(Entry(name: name, flags: flags, method: method, crc: data.u32(cursor + 16),
                                compressed: data.u32(cursor + 20), expanded: data.u32(cursor + 24),
                                offset: data.u32(cursor + 42), external: data.u32(cursor + 38)))
            cursor = end
        }
        guard cursor == data.count else { throw LibraryBackupError.invalidArchive("中央目录多余数据") }
        let ordered = result.sorted { $0.offset < $1.offset }
        guard let first = ordered.first, first.offset == 0 else { throw LibraryBackupError.invalidArchive("ZIP 前缀不允许") }
        var expectedOffset: UInt64 = 0
        for e in ordered { let end = try localRecordEnd(file, e, archiveSize); guard UInt64(e.offset) == expectedOffset, end > expectedOffset else { throw LibraryBackupError.invalidArchive("ZIP 成员区间重叠或存在间隙") }; expectedOffset = end }
        guard expectedOffset == directoryOffset else { throw LibraryBackupError.invalidArchive("ZIP 数据区存在尾随内容") }
        return result
    }
    private static func hasZIP64Extra(_ bytes: Data) -> Bool {
        var i = 0
        while i + 4 <= bytes.count { let id = bytes.u16(i), size = Int(bytes.u16(i + 2)); if id == 0x0001 { return true }; guard i + 4 + size <= bytes.count else { return true }; i += 4 + size }
        return i != bytes.count
    }
    private static func localRecordEnd(_ file: FileHandle, _ e: Entry, _ archiveSize: Int64) throws -> UInt64 {
        try file.seek(toOffset: UInt64(e.offset)); let h = try file.read(upToCount: 30) ?? Data()
        guard h.count == 30, h.u32(0) == 0x04034b50, h.u16(6) == e.flags, h.u16(8) == e.method else { throw LibraryBackupError.invalidArchive("local header不匹配") }
        let nameLength = Int(h.u16(26)), extraLength = Int(h.u16(28))
        let name = try file.read(upToCount: nameLength) ?? Data(), extra = try file.read(upToCount: extraLength) ?? Data()
        guard name.count == nameLength, extra.count == extraLength, String(data: name, encoding: .utf8) == e.name, !hasZIP64Extra(extra) else { throw LibraryBackupError.invalidArchive("local header路径/额外字段无效") }
        let dataEnd = UInt64(e.offset) + 30 + UInt64(nameLength + extraLength) + UInt64(e.compressed)
        guard dataEnd <= UInt64(archiveSize) else { throw LibraryBackupError.invalidArchive("成员范围无效") }
        if e.flags & 8 == 0 { guard h.u32(14) == e.crc, h.u32(18) == e.compressed, h.u32(22) == e.expanded else { throw LibraryBackupError.invalidArchive("local header长度不匹配") }; return dataEnd }
        try file.seek(toOffset: dataEnd); let first = try file.read(upToCount: 4) ?? Data(); guard first.count == 4 else { throw LibraryBackupError.invalidArchive("descriptor缺失") }
        if first.u32(0) == 0x08074b50 { return dataEnd + 16 }
        guard first.u32(0) == e.crc else { throw LibraryBackupError.invalidArchive("descriptor CRC不匹配") }; return dataEnd + 12
    }
    private static func readMember(_ a:FileHandle,_ e:Entry,output:FileHandle?,limit:Int64,archiveSize:Int64,cancellation:LibraryBackupCancellationToken?=nil)throws->Data{try a.seek(toOffset:UInt64(e.offset));let h=try a.read(upToCount:30) ?? Data();guard h.count==30,h.u32(0)==0x04034b50,h.u16(6)==e.flags,h.u16(8)==e.method,h.u16(6)&1==0 else{throw LibraryBackupError.invalidArchive("local header无效")};if e.flags & 8 == 0 { guard h.u32(14)==e.crc,h.u32(18)==e.compressed,h.u32(22)==e.expanded else { throw LibraryBackupError.invalidArchive("local header长度不匹配") } };let nl=Int(h.u16(26)),el=Int(h.u16(28)),nm=try a.read(upToCount:nl) ?? Data(),extra=try a.read(upToCount:el) ?? Data();guard String(data:nm,encoding:.utf8)==e.name,extra.count==el,!hasZIP64Extra(extra) else{throw LibraryBackupError.invalidArchive("路径或ZIP64额外字段无效")};let start=UInt64(e.offset)+30+UInt64(nl+el),end=start+UInt64(e.compressed);guard end+12<=UInt64(archiveSize) else{throw LibraryBackupError.invalidArchive("成员范围无效")};try a.seek(toOffset:start);var left=Int64(e.compressed),count:Int64=0,crc:uLong=crc32(0,nil,0),buffer=Data();var z=z_stream();var inflateStarted=false;var streamEnded=false;defer{if inflateStarted{inflateEnd(&z)}};if e.method==8{guard inflateInit2_(&z,-15,ZLIB_VERSION,Int32(MemoryLayout<z_stream>.size))==Z_OK else{throw LibraryBackupError.invalidArchive("inflate init")};inflateStarted=true}
        func consume(_ d:Data)throws{count+=Int64(d.count);guard count<=limit else{throw LibraryBackupError.sizeLimit("单资源解压超限")};crc=d.withUnsafeBytes{crc32(crc,$0.bindMemory(to:Bytef.self).baseAddress,uInt(d.count))};if let output{try output.write(contentsOf:d)}else{buffer.append(d)}}
        while left>0{try cancellation?.check();let d=try a.read(upToCount:min(chunk,Int(left))) ?? Data();guard !d.isEmpty else{throw LibraryBackupError.invalidArchive("成员截断")};left-=Int64(d.count);if e.method==0{try consume(d)}else{try d.withUnsafeBytes{src in z.next_in=UnsafeMutablePointer(mutating:src.bindMemory(to:Bytef.self).baseAddress);z.avail_in=uInt(d.count);var out=[UInt8](repeating:0,count:chunk);repeat{let rc=out.withUnsafeMutableBytes{dst->Int32 in z.next_out=dst.bindMemory(to:Bytef.self).baseAddress;z.avail_out=uInt(chunk);return inflate(&z,Z_NO_FLUSH)};let made=chunk-Int(z.avail_out);if made>0{try consume(Data(out[0..<made]))};guard rc==Z_OK||rc==Z_STREAM_END else{throw LibraryBackupError.invalidArchive("Deflate损坏")};if rc==Z_STREAM_END{guard z.avail_in==0,left==0 else{throw LibraryBackupError.invalidArchive("Deflate尾随数据")};streamEnded=true;break}}while z.avail_in>0}}}
        if e.method==8 && !streamEnded { throw LibraryBackupError.invalidArchive("Deflate未结束") }
        guard count==Int64(e.expanded),UInt32(truncatingIfNeeded:crc)==e.crc else{throw LibraryBackupError.invalidArchive("长度或CRC不匹配")};try a.seek(toOffset:end);if e.flags & 8 != 0 { let sigOrCRC=try a.read(upToCount:4) ?? Data();guard sigOrCRC.count==4 else{throw LibraryBackupError.invalidArchive("数据描述符截断")};let crcValue=sigOrCRC.u32(0);if crcValue==0x08074b50 { let desc=try a.read(upToCount:12) ?? Data();guard desc.count==12,desc.u32(0)==e.crc,desc.u32(4)==e.compressed,desc.u32(8)==e.expanded else{throw LibraryBackupError.invalidArchive("数据描述符无效")} } else { let rest=try a.read(upToCount:8) ?? Data();guard rest.count==8,crcValue==e.crc,rest.u32(0)==e.compressed,rest.u32(4)==e.expanded else{throw LibraryBackupError.invalidArchive("数据描述符无效")} } };return buffer}

    private static func localHeader(_ n:Data)->Data{var d=Data();d.appendLE(UInt32(0x04034b50));d.appendLE(UInt16(20));d.appendLE(UInt16(0x808));d.appendLE(UInt16(0));d.appendLE(UInt16(0));d.appendLE(UInt16(0));d.appendLE(UInt32(0));d.appendLE(UInt32(0));d.appendLE(UInt32(0));d.appendLE(UInt16(n.count));d.appendLE(UInt16(0));d.append(n);return d}
    private static func descriptor(_ c:UInt32,_ n:UInt32)->Data{var d=Data();d.appendLE(UInt32(0x08074b50));d.appendLE(c);d.appendLE(n);d.appendLE(n);return d}
    private static func centralHeader(_ e:Entry)->Data{let n=Data(e.name.utf8);var d=Data();d.appendLE(UInt32(0x02014b50));d.appendLE(UInt16(20));d.appendLE(UInt16(20));d.appendLE(e.flags);d.appendLE(e.method);d.appendLE(UInt16(0));d.appendLE(UInt16(0));d.appendLE(e.crc);d.appendLE(e.compressed);d.appendLE(e.expanded);d.appendLE(UInt16(n.count));d.appendLE(UInt16(0));d.appendLE(UInt16(0));d.appendLE(UInt16(0));d.appendLE(UInt16(0));d.appendLE(e.external);d.appendLE(e.offset);d.append(n);return d}
    private static func endRecord(_ c:UInt16,_ s:UInt32,_ o:UInt32)->Data{var d=Data();d.appendLE(UInt32(0x06054b50));d.appendLE(UInt16(0));d.appendLE(UInt16(0));d.appendLE(c);d.appendLE(c);d.appendLE(s);d.appendLE(o);d.appendLE(UInt16(0));return d}
    private static func zipWrite(_ cursor:inout UInt64,_ f:FileHandle,_ d:Data)throws{try f.write(contentsOf:d);cursor+=UInt64(d.count)}
}
private extension JSONEncoder{static var libraryBackup:JSONEncoder{let e=JSONEncoder();e.outputFormatting=[.sortedKeys,.withoutEscapingSlashes];return e}}
private extension String{var isSHA:Bool{count==64 && allSatisfy{$0.isHexDigit && !$0.isUppercase}}}
private extension Digest{var hexString:String{map{String(format:"%02x",$0)}.joined()}}
private extension Data{mutating func appendLE<T:FixedWidthInteger>(_ v:T){var x=v.littleEndian;Swift.withUnsafeBytes(of:&x){append(contentsOf:$0)}};func u16(_ i:Int)->UInt16{UInt16(self[i])|UInt16(self[i+1])<<8};func u32(_ i:Int)->UInt32{UInt32(u16(i))|UInt32(u16(i+2))<<16}}
