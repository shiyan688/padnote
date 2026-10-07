import Foundation
import PDFKit
import CryptoKit
import Darwin

public struct LibraryBackupVaultPayload: Codable, Equatable {
    public let schemaVersion: Int
    public let title: String
    public let markdown: String
    public let sourceNoteID: String
    public let sourceRevisionMS: Int64
    public let createdAtMS: Int64
    enum CodingKeys: String, CodingKey { case schemaVersion = "schema_version", title, markdown, sourceNoteID = "source_note_id", sourceRevisionMS = "source_revision_ms", createdAtMS = "created_at_ms" }
}

public struct LibraryBackupCapturedSnapshot {
    public let manifest: LibraryBackupManifest
    public let resourceFiles: [String: URL]
    public let restoredVaultDescriptors: [String: Data]
    public let directory: URL
    public let unavailableVideoCount: Int
}

/// Captures a stable set of already-local resources into a private, generated-name staging directory.
public enum LibraryBackupSnapshot {
    private struct VaultPlan { let value: VaultNote; let sourceNoteID: String; let createdAtMS: Int64; let revisionMS: Int64; let linkedItemID: String?; let currentLinkedNoteID: String?; let sourceState: String; let journalRoot: URL }
    private struct VideoPlan { let value: NoteVideoAttachment; let sourceURL: URL; let itemID: String; let linkedItemID: String?; let sourceState: String }
    private struct RestoredVideoPlan { let value: RestoredVideoAttachment; let sourceURL: URL; let itemID: String; let linkedItemID: String?; let sourceState: String }
    private struct PresetPlan { let value: UserCoverPreset; let sourceURL: URL; let itemID: String }

    private struct NotePlan { let value: NoteDocument; let sourceURL: URL; let pdfURL: URL?; let coverURL: URL?; let itemID: String; let noteResource: String; let pdfResource: String?; let coverResource: String? }
    private struct Plan { let notes: [NotePlan]; let vault: [VaultPlan]; let videos: [VideoPlan]; let restoredVideos: [RestoredVideoPlan]; let presets: [PresetPlan]; let unavailableVideos: Int }

    @MainActor static func capture(library: NoteLibrary, vault: VaultLibrary, videoStore: NoteVideoAttachmentStore,
                                           presetStore: UserCoverPresetStore, restoredVideoStore: RestoredVideoAttachmentStore,
                                           coverStore: NoteCoverStore = NoteCoverStore(),
                                           into stagingDirectory: URL, cancellation: LibraryBackupCancellationToken? = nil) async throws -> LibraryBackupCapturedSnapshot {
        guard library.pendingDrafts.isEmpty else { throw LibraryBackupError.transaction("有未保存的笔记草稿。请先保存，或单独导出恢复副本后再备份。") }
        let notesByID = Dictionary(uniqueKeysWithValues: library.notes.map { ($0.id, $0) })
        var notePlans = [NotePlan](), noteItemBySource = [String: String]()
        for note in library.notes {
            try cancellation?.check()
            let noteURL = try library.backupSourceURL(for: note)
            let itemID = generatedID("i"), noteResource = generatedID("r")
            noteItemBySource[note.id] = itemID
            var pdf: URL?, cover: URL?
            if note.pdfPageCount > 0 {
                guard let url = library.pdfURL(for: note) else { throw LibraryBackupError.transaction("笔记 \(note.title) 的 PDF 原文缺失") }
                pdf = url
            }
            cover = try coverStore.backupPNGURL(noteID: note.id)
            notePlans.append(NotePlan(value: note, sourceURL: noteURL, pdfURL: pdf, coverURL: cover,
                                      itemID: itemID, noteResource: noteResource,
                                      pdfResource: pdf == nil ? nil : generatedID("r"),
                                      coverResource: cover == nil ? nil : generatedID("r")))
        }
        let vaultValues = vault.notes
        var vaultPlans = [VaultPlan]()
        for listedValue in vaultValues {
            try cancellation?.check()
            let value = try vault.backupColdRead(for: listedValue)
            let originalSource = value.archiveOrigin == "restored_archive" ? (value.archiveSourceNoteID ?? value.id) : value.id
            let linkedSource = value.archiveOrigin == nil ? value.id : value.archiveLinkedNoteID
            let linked = linkedSource.flatMap { noteItemBySource[$0] }
            let state: String
            if linked != nil { state = "linked_note" }
            else if value.archiveOrigin == "restored_archive", let priorOwner = value.archiveLinkedNoteID {
                state = notesByID[priorOwner] == nil ? "source_deleted" : "source_not_selected"
            } else if value.archiveOrigin == "restored_archive" {
                state = value.archiveSourceState ?? "independent"
            } else { state = notesByID[originalSource] == nil ? "source_deleted" : "source_not_selected" }
            let revision = try floorMS(value.sourceUpdatedAt)
            let created = try floorMS(value.createdAt.timeIntervalSince1970 * 1000)
            vaultPlans.append(VaultPlan(value: value, sourceNoteID: originalSource, createdAtMS: created,
                                        revisionMS: revision, linkedItemID: linked,
                                        currentLinkedNoteID: linked == nil ? nil : linkedSource, sourceState: state,
                                        journalRoot: vault.backupJournalRoot()))
        }
        let videosListing = try await Task.detached(priority: .userInitiated) { try videoStore.archiveRecords() }.value
        var videoPlans = [VideoPlan]()
        for record in videosListing.records {
            try cancellation?.check()
            let value = record.attachment
            let linked = noteItemBySource[value.noteID]
            videoPlans.append(VideoPlan(value: value, sourceURL: record.fileURL, itemID: generatedID("a"),
                                        linkedItemID: linked, sourceState: linked == nil ? "source_deleted" : "linked_note"))
        }
        let restoredSources = try await Task.detached(priority: .userInitiated) {
            try restoredVideoStore.listing().map { ($0, try restoredVideoStore.fileURL(for: $0)) }
        }.value
        var restoredPlans = [RestoredVideoPlan]()
        for (value, url) in restoredSources {
            try cancellation?.check()
            let linked = value.noteID.flatMap { noteItemBySource[$0] }
            restoredPlans.append(RestoredVideoPlan(value: value, sourceURL: url, itemID: generatedID("a"),
                linkedItemID: linked, sourceState: linked == nil ? "source_deleted" : "linked_note"))
        }
        var presetPlans = [PresetPlan]()
        let presetSources = try await Task.detached(priority: .userInitiated) {
            try presetStore.listing().map { ($0, try presetStore.pngURL(for: $0)) }
        }.value
        for (value, source) in presetSources { try cancellation?.check(); presetPlans.append(PresetPlan(value: value, sourceURL: source, itemID: generatedID("c"))) }
        try await Task.detached(priority: .userInitiated) {
            for note in notePlans {
                if let pdf = note.pdfURL { try validatePDF(pdf, expectedPageCount: note.value.pdfPageCount) }
                if let cover = note.coverURL { _ = try NoteCoverStore.validatePNGFile(cover) }
            }
        }.value
        let plan = Plan(notes: notePlans, vault: vaultPlans, videos: videoPlans, restoredVideos: restoredPlans,
                        presets: presetPlans, unavailableVideos: videosListing.unavailableCount)
        return try await Task.detached(priority: .userInitiated) { try materialize(plan, into: stagingDirectory, cancellation: cancellation) }.value
    }

    private static func materialize(_ plan: Plan, into directory: URL, cancellation: LibraryBackupCancellationToken?) throws -> LibraryBackupCapturedSnapshot {
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: false)
        var resources = [LibraryBackupManifest.Resource](), resourceFiles = [String: URL](), notes = [LibraryBackupManifest.Note]()
        var vaultEntries = [LibraryBackupManifest.Vault](), videos = [LibraryBackupManifest.Video](), coverPresets = [LibraryBackupManifest.CoverPreset]()
        var restoredVaultDescriptors = [String: Data]()
        var totalPixels: Int64 = 0
        func addFile(_ source: URL, resourceID: String, role: String, mime: String, limit: Int64) throws -> URL {
            let target = directory.appendingPathComponent(resourceID + ".bin")
            let copied = try LibraryBackupArchive.copyVerified(source, to: target, maximumBytes: limit, cancellation: cancellation)
            let descriptor = LibraryBackupManifest.Resource(resourceID: resourceID, role: role, mediaType: mime,
                byteLength: copied.size, sha256: copied.sha256, member: "payload/\(resourceID).bin")
            resources.append(descriptor); resourceFiles[resourceID] = target
            return target
        }
        func addData(_ data: Data, resourceID: String, role: String, mime: String, limit: Int64) throws -> URL {
            guard Int64(data.count) <= limit else { throw LibraryBackupError.sizeLimit("单项资料超过限制") }
            let target = directory.appendingPathComponent(resourceID + ".bin")
            let fd = open(target.path, O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW | O_CLOEXEC, S_IRUSR | S_IWUSR)
            guard fd >= 0 else { throw LibraryBackupError.unsafeFile }
            let handle = FileHandle(fileDescriptor: fd, closeOnDealloc: true)
            try handle.write(contentsOf: data); try handle.synchronize(); try handle.close()
            let hash = try LibraryBackupArchive.hashFile(target)
            let descriptor = LibraryBackupManifest.Resource(resourceID: resourceID, role: role, mediaType: mime,
                byteLength: hash.size, sha256: hash.sha256, member: "payload/\(resourceID).bin")
            resources.append(descriptor); resourceFiles[resourceID] = target
            return target
        }
        for note in plan.notes {
            try cancellation?.check()
            let noteFile = try addFile(note.sourceURL, resourceID: note.noteResource, role: "note_document", mime: "application/json", limit: LibraryBackupArchive.maxNoteBytes)
            let persisted = try NoteDocument.decode(Data(contentsOf: noteFile))
            guard persisted == note.value else { throw LibraryBackupError.sourceChanged }
            var pdfID: String?, coverID: String?
            if let pdf = note.pdfURL, let id = note.pdfResource {
                let copiedPDF = try addFile(pdf, resourceID: id, role: "pdf_original", mime: "application/pdf", limit: LibraryBackupArchive.maxPDFBytes)
                try validatePDF(copiedPDF, expectedPageCount: note.value.pdfPageCount)
                pdfID = id
            }
            if let cover = note.coverURL, let id = note.coverResource {
                let copiedCover = try addFile(cover, resourceID: id, role: "assigned_cover_png", mime: "image/png", limit: LibraryBackupArchive.maxPNGBytes)
                let dimensions = try NoteCoverStore.validatePNGFile(copiedCover)
                totalPixels += Int64(dimensions.width) * Int64(dimensions.height)
                guard totalPixels <= 120_000_000 else { throw LibraryBackupError.sizeLimit("图片像素总量超过限制") }
                coverID = id
            }
            notes.append(LibraryBackupManifest.Note(itemID: note.itemID, sourceNoteID: note.value.id,
                sourceRevisionMS: try floorMS(note.value.updatedAt), schemaVersion: persisted.schemaVersion,
                noteResourceID: note.noteResource, pdfResourceID: pdfID, coverResourceID: coverID, vaultEntryIDs: [], videoAttachmentIDs: []))
        }
        var noteIndex = Dictionary(uniqueKeysWithValues: notes.enumerated().map { ($0.element.itemID, $0.offset) })
        for item in plan.vault {
            try cancellation?.check()
            let payload = LibraryBackupVaultPayload(schemaVersion: 1, title: item.value.title, markdown: item.value.markdown,
                sourceNoteID: item.sourceNoteID, sourceRevisionMS: item.revisionMS, createdAtMS: item.createdAtMS)
            let data = try JSONEncoder.libraryBackup.encode(payload)
            guard data.count <= LibraryBackupArchive.maxVaultBytes else { throw LibraryBackupError.sizeLimit("知识库条目超过 16 MiB") }
            let id = generatedID("r"), itemID = generatedID("v")
            _ = try addData(data, resourceID: id, role: "vault_entry_json", mime: "application/json", limit: LibraryBackupArchive.maxVaultBytes)
            vaultEntries.append(LibraryBackupManifest.Vault(itemID: itemID, noteItemID: item.linkedItemID, sourceState: item.sourceState,
                sourceNoteID: item.sourceNoteID, sourceRevisionMS: item.revisionMS, createdAtMS: item.createdAtMS, resourceID: id))
            if item.value.archiveOrigin == "restored_archive" {
                let descriptor = try RestoredVaultMaterialProjection.descriptor(item.value,
                    sourceNoteID: item.sourceNoteID, sourceRevisionMS: item.revisionMS,
                    createdAtMS: item.createdAtMS, currentSourceState: item.sourceState,
                    currentNoteItemID: item.linkedItemID, currentLinkedNoteID: item.currentLinkedNoteID,
                    journalRoot: item.journalRoot)
                guard restoredVaultDescriptors[item.value.id] == nil else {
                    throw LibraryBackupError.invalidManifest("恢复知识库本地身份重复")
                }
                let summary = try MaterialCodec.inspectVaultDescriptor(descriptor)
                let markdown = Data(item.value.markdown.utf8)
                let bodySHA256 = SHA256.hash(data: markdown).map { String(format: "%02x", $0) }.joined()
                guard summary.materialID == item.value.id, summary.sourceState == item.sourceState,
                      summary.sourceNoteID == item.sourceNoteID, summary.title == item.value.title,
                      summary.bodyByteLength == Int64(markdown.count), summary.bodySHA256 == bodySHA256 else {
                    throw LibraryBackupError.sourceChanged
                }
                _ = try MaterialCodec.digest(descriptor)
                restoredVaultDescriptors[item.value.id] = descriptor
            }
            if let linked = item.linkedItemID, let index = noteIndex[linked] { notes[index].vaultEntryIDs.append(itemID) }
        }
        for item in plan.videos {
            try cancellation?.check()
            let value = item.value, id = generatedID("r")
            let copied = try addFile(item.sourceURL, resourceID: id, role: "video_attachment_mp4", mime: "video/mp4", limit: LibraryBackupArchive.maxVideoBytes)
            let size = resources.last!.byteLength, sha = resources.last!.sha256
            guard size == Int64(value.sizeBytes), sha == value.sha256 else { throw LibraryBackupError.sourceChanged }
            let itemID = item.itemID
            let connection = LibraryBackupManifest.ConnectionProvenance(connectionID: value.connectionID.uuidString.lowercased(), revision: value.connectionRevision,
                kind: value.connectionKind.rawValue, transport: nil, bridgeID: value.bridgeID, instanceID: value.instanceID, certificateSHA256: value.certSHA256)
            let sourceRevision = Int64(value.sourceRevision).multipliedReportingOverflow(by: 1000)
            guard !sourceRevision.overflow else { throw LibraryBackupError.invalidManifest("视频来源修订超出时间范围") }
            let descriptor = LibraryBackupManifest.Video(itemID: itemID, noteItemID: item.linkedItemID, sourceState: item.sourceState,
                originKind: "computer_task", sourceNoteID: value.noteID, sourceRevisionMS: sourceRevision.partialValue, sourceRevisionPrecisionMS: 1000,
                sourceBundleSHA256: value.sourceSnapshotSHA256, taskPayloadSHA256: nil, digestKind: "source_snapshot_only", offlineState: "verified_local_copy",
                taskID: value.taskID.uuidString.lowercased(), remoteTaskID: value.remoteTaskID, connection: connection, artifactID: value.artifactID,
                displayName: value.displayName, mediaType: "video/mp4", byteLength: size, sha256: sha,
                createdAtMS: try floorMS(value.createdAt.timeIntervalSince1970 * 1000), resourceID: id)
            videos.append(descriptor)
            if let linked=item.linkedItemID,let index=noteIndex[linked]{notes[index].videoAttachmentIDs.append(itemID)}
            _ = copied
        }
        for item in plan.restoredVideos {
            try cancellation?.check()
            let value=item.value,id=generatedID("r");_ = try addFile(item.sourceURL,resourceID:id,role:"video_attachment_mp4",mime:"video/mp4",limit:LibraryBackupArchive.maxVideoBytes)
            let itemID=item.itemID
            let desc=LibraryBackupManifest.Video(itemID:itemID,noteItemID:item.linkedItemID,sourceState:item.sourceState,originKind:"restored_archive",
                sourceNoteID:value.sourceNoteID,sourceRevisionMS:value.sourceRevisionMS,sourceRevisionPrecisionMS:value.sourceRevisionPrecisionMS,sourceBundleSHA256:value.sourceBundleSHA256,
                taskPayloadSHA256:value.taskPayloadSHA256,digestKind:value.taskPayloadSHA256 == nil ? "source_snapshot_only":"source_and_task_payload",
                offlineState:"verified_local_copy",taskID:value.taskID,remoteTaskID:value.remoteTaskID,connection:value.connection,artifactID:value.artifactID,
                displayName:value.displayName,mediaType:"video/mp4",byteLength:value.byteLength,sha256:value.sha256,createdAtMS:value.createdAtMS,resourceID:id)
            videos.append(desc);if let linked=item.linkedItemID,let index=noteIndex[linked]{notes[index].videoAttachmentIDs.append(itemID)}
        }
        for item in plan.presets {
            try cancellation?.check()
            let id=generatedID("r")
            let copiedPreset=try addFile(item.sourceURL,resourceID:id,role:"user_cover_preset_png",mime:"image/png",limit:LibraryBackupArchive.maxPNGBytes)
            let dimensions=try NoteCoverStore.validatePNGFile(copiedPreset);totalPixels+=Int64(dimensions.width)*Int64(dimensions.height);guard totalPixels<=120_000_000 else{throw LibraryBackupError.sizeLimit("图片像素总量超过限制")}
            coverPresets.append(LibraryBackupManifest.CoverPreset(itemID:item.itemID,displayName:item.value.name,resourceID:id))
        }
        let manifest=LibraryBackupManifest(createdAtMS:try floorMS(Date().timeIntervalSince1970*1000),producer:["platform":"ios","app_version":"1"],notes:notes,vaultEntries:vaultEntries,videoAttachments:videos,coverPresets:coverPresets,resources:resources)
        try LibraryBackupArchive.validate(manifest:manifest)
        return LibraryBackupCapturedSnapshot(manifest:manifest,resourceFiles:resourceFiles,
            restoredVaultDescriptors: restoredVaultDescriptors, directory:directory,
            unavailableVideoCount:plan.unavailableVideos)
    }

    private static func validatePDF(_ url:URL,expectedPageCount:Int)throws {
        _=try LibraryBackupArchive.validateRegularSource(url,maximumBytes:LibraryBackupArchive.maxPDFBytes)
        guard let document=PDFDocument(url:url),!document.isEncrypted,document.pageCount>0,!document.isLocked,document.pageCount<=500,document.pageCount==expectedPageCount else{throw LibraryBackupError.invalidManifest("PDF 无法读取或页数不一致")}
        for index in 0..<document.pageCount { guard let page=document.page(at:index) else{throw LibraryBackupError.invalidManifest("PDF页面缺失")};let r=page.bounds(for:.mediaBox);guard r.width.isFinite,r.height.isFinite,r.width>0,r.height>0,r.width<=100_000,r.height<=100_000 else{throw LibraryBackupError.invalidManifest("PDF页面尺寸无效")} }
    }
    private static func floorMS(_ raw:Double)throws->Int64 { guard raw.isFinite,raw>=0,raw<Double(Int64.max) else{throw LibraryBackupError.invalidManifest("时间值无效")};return Int64(raw.rounded(.down)) }
    private static func generatedID(_ prefix:String)->String { prefix+"-"+UUID().uuidString.replacingOccurrences(of:"-",with:"").lowercased() }
}

private extension JSONEncoder { static var libraryBackup:JSONEncoder {let e=JSONEncoder();e.outputFormatting=[.sortedKeys,.withoutEscapingSlashes];return e} }
