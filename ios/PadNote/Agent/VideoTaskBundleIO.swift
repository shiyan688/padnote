import Foundation
import CryptoKit

public enum VideoTaskBundleError: Error, LocalizedError, Equatable { case invalidPath, tooLarge, invalidInput
    public var errorDescription: String? { switch self { case .invalidPath: return "任务包路径不安全"; case .tooLarge: return "任务包内容过大"; case .invalidInput: return "视频任务参数无效" } }
}

public enum VideoTaskBundleIO {
    public static func make(noteId: String, noteRevision: Int, title: String, markdown: String, audience: String, learningGoal: String, durationSeconds: Int, voiceProfile: String = "zh-CN-neutral", speed: Float = 1) throws -> Data {
        guard !noteId.isEmpty, !title.isEmpty, !markdown.isEmpty, !audience.isEmpty, !learningGoal.isEmpty else { throw VideoTaskBundleError.invalidInput }
        let content = Data(markdown.utf8); guard content.count <= 50 * 1024 * 1024 else { throw VideoTaskBundleError.tooLarge }
        let contentHash = SHA256.hash(data: content).hex
        let bundleHash = SHA256.hash(data: Data("input/content.md\0\(content.count)\0\(contentHash)\n".utf8)).hex
        func json(_ value: Any) throws -> Data { guard JSONSerialization.isValidJSONObject(value) else { throw VideoTaskBundleError.invalidInput }; return try JSONSerialization.data(withJSONObject: value, options: [.prettyPrinted, .sortedKeys]) }
        let request: [String: Any] = ["schema_version":"1.0", "task_type":"video.explain.v1", "task_id":"padnote-\(UUID().uuidString.replacingOccurrences(of: "-", with: ""))", "source":["note_id":noteId, "note_revision":max(1,noteRevision), "title":title, "language":"zh-CN", "entrypoint":"input/content.md", "bundle_sha256":bundleHash], "brief":["audience":audience, "learning_goal":learningGoal, "prerequisites":[], "target_duration_sec":max(1,durationSeconds), "aspect_ratio":"9:16", "style_preset":"clean-academic"], "research":["external_research":false], "review":["storyboard_required":true], "voice":["profile":voiceProfile, "speed":max(0.5,min(2,speed))]]
        let manifest: [String: Any] = ["schema_version":"1.0", "files":[["path":"input/content.md", "media_type":"text/markdown", "size_bytes":content.count, "sha256":contentHash]]]
        return try ZipWriter(entries: [("request.json", try json(request)), ("input/manifest.json", try json(manifest)), ("input/content.md", content), ("work/.keep", Data()), ("output/.keep", Data())]).data()
    }
}

private extension SHA256.Digest { var hex: String { map { String(format: "%02x", $0) }.joined() } }

private struct ZipWriter {
    let entries: [(String, Data)]
    func data() throws -> Data { var out = Data(); var central = Data(); var offset: UInt32 = 0
        for (path, bytes) in entries { guard ZipWriter.safe(path) else { throw VideoTaskBundleError.invalidPath }; let name = Data(path.utf8); let crc = CRC32.checksum(bytes); let local = localHeader(name: name, size: bytes.count, crc: crc); out.append(local); out.append(bytes); central.append(centralHeader(name: name, size: bytes.count, crc: crc, offset: offset)); offset += UInt32(local.count + bytes.count) }
        out.append(central); out.append(u32(0x06054b50)); out.append(u16(0)); out.append(u16(0)); out.append(u16(UInt16(entries.count))); out.append(u16(UInt16(entries.count))); out.append(u32(UInt32(central.count))); out.append(u32(offset)); out.append(u16(0)); return out }
    static func safe(_ p: String) -> Bool { !p.isEmpty && !p.hasPrefix("/") && !p.contains("\\") && !p.contains("\0") && !p.split(separator: "/").contains(where: { $0 == "." || $0 == ".." }) }
    func localHeader(name: Data, size: Int, crc: UInt32) -> Data { var d = Data(); d.append(u32(0x04034b50)); d.append(u16(20)); d.append(u16(0)); d.append(u16(0)); d.append(u16(0)); d.append(u16(0)); d.append(u32(crc)); d.append(u32(UInt32(size))); d.append(u32(UInt32(size))); d.append(u16(UInt16(name.count))); d.append(u16(0)); d.append(name); return d }
    func centralHeader(name: Data, size: Int, crc: UInt32, offset: UInt32) -> Data { var d = Data(); d.append(u32(0x02014b50)); d.append(u16(20)); d.append(u16(20)); d.append(u16(0)); d.append(u16(0)); d.append(u16(0)); d.append(u16(0)); d.append(u32(crc)); d.append(u32(UInt32(size))); d.append(u32(UInt32(size))); d.append(u16(UInt16(name.count))); d.append(u16(0)); d.append(u16(0)); d.append(u16(0)); d.append(u16(0)); d.append(u32(0)); d.append(u32(offset)); d.append(name); return d }
}
private func u16(_ v: UInt16) -> Data { var x = v.littleEndian; return Data(bytes: &x, count: 2) }
private func u32(_ v: UInt32) -> Data { var x = v.littleEndian; return Data(bytes: &x, count: 4) }
private enum CRC32 { static func checksum(_ data: Data) -> UInt32 { var crc: UInt32 = 0xffffffff; for byte in data { crc ^= UInt32(byte); for _ in 0..<8 { crc = (crc >> 1) ^ ((crc & 1) != 0 ? 0xedb88320 : 0) } }; return ~crc } }
