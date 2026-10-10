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
    public let legacyVaultSourceFiles: [String: URL]
    public let videoSourceIdentities: [String: LibraryBackupVideoSourceIdentity]
}

public struct LibraryBackupVideoSourceIdentity {
    public let sourceKind: String
    public let sourceAttachmentID: String
    public let sourceNoteID: String
    public let metadataURL: URL?
}

/// Captures a stable set of already-local resources into a private, generated-name staging directory.
public enum LibraryBackupSnapshot {
    private struct VaultPlan { let value: VaultNote; let sourceURL: URL?; let sourceNoteID: String; let createdAtMS: Int64; let revisionMS: Int64; let linkedItemID: String?; let currentLinkedNoteID: String?; let sourceState: String; let journalRoot: URL }
    private struct VideoPlan { let value: NoteVideoAttachment; let sourceURL: URL; let metadataURL: URL?; let itemID: String; let linkedItemID: String?; let sourceState: String }
    private struct RestoredVideoPlan { let value: RestoredVideoAttachment; let sourceURL: URL; let metadataURL: URL?; let itemID: String; let linkedItemID: String?; let sourceState: String }
    private struct PresetPlan { let value: UserCoverPreset; let sourceURL: URL; let itemID: String }

    private struct NotePlan { let value: NoteDocument; let sourceURL: URL; let pdfURL: URL?; let coverURL: URL?; let itemID: String; let noteResource: String; let pdfResource: String?; let coverResource: String? }
    private struct Plan { let notes: [NotePlan]; let vault: [VaultPlan]; let videos: [VideoPlan]; let restoredVideos: [RestoredVideoPlan]; let presets: [PresetPlan]; let unavailableVideos: Int }

    @MainActor static func capture(library: NoteLibrary, vault: VaultLibrary, videoStore: NoteVideoAttachmentStore,
                                           presetStore: UserCoverPresetStore, restoredVideoStore: RestoredVideoAttachmentStore,
                                           coverStore: NoteCoverStore = NoteCoverStore(),
                                           into stagingDirectory: URL, cancellation: LibraryBackupCancellationToken? = nil,
                                           includeLegacySourceURLs: Bool = false) async throws -> LibraryBackupCapturedSnapshot {
        try await NoteGroupCatalogFence.withCapture {
            try await captureUnderExistingLease(library: library, vault: vault, videoStore: videoStore,
                presetStore: presetStore, restoredVideoStore: restoredVideoStore, coverStore: coverStore,
                into: stagingDirectory, cancellation: cancellation, includeLegacySourceURLs: includeLegacySourceURLs)
        }
    }

    /// Internal adapter entry point for a caller that holds `NoteGroupCatalogFence.withCapture`
    /// across its additional source-metadata staging and witness construction.
    @MainActor static func captureUnderExistingLease(library: NoteLibrary, vault: VaultLibrary, videoStore: NoteVideoAttachmentStore,
                                           presetStore: UserCoverPresetStore, restoredVideoStore: RestoredVideoAttachmentStore,
                                           coverStore: NoteCoverStore = NoteCoverStore(),
                                           into stagingDirectory: URL, cancellation: LibraryBackupCancellationToken? = nil,
                                           includeLegacySourceURLs: Bool = false) async throws -> LibraryBackupCapturedSnapshot {
        guard library.pendingDrafts.isEmpty else { throw LibraryBackupError.transaction("有未保存的笔记草稿。请先保存，或单独导出恢复副本后再备份。") }
        let identityKey: (String) -> String = { value in UUID(uuidString: value)?.uuidString.lowercased() ?? value }
        var notesByID = [String: NoteDocument](), noteItemBySource = [String: String]()
        var groupedVideoNoteKeys = Set<String>()
        var groupedVaultNoteKeys = Set<String>()
        var groupedVideoPlans = [VideoPlan]()
        var groupedRestoredVideoPlans = [RestoredVideoPlan]()
        var groupedVaultPlans = [VaultPlan]()
        for note in library.notes {
            let key = identityKey(note.id)
            guard notesByID[key] == nil else { throw LibraryBackupError.invalidManifest("笔记 UUID 大小写归一后重复") }
            notesByID[key] = note
        }
        var notePlans = [NotePlan]()
        for note in library.notes {
            try cancellation?.check()
            let groupSession = try library.currentGroupSession(noteID: note.id)
            let noteURL: URL
            if let groupSession, groupSession.groupToken != nil {
                guard let body = groupSession.members.first(where: { $0.record.role == .body }) else {
                    throw LibraryBackupError.sourceChanged
                }
                noteURL = body.url
            } else {
                noteURL = try library.backupSourceURL(for: note)
            }
            let itemID = generatedID("i"), noteResource = generatedID("r")
            noteItemBySource[identityKey(note.id)] = itemID
            var pdf: URL?, cover: URL?
            if note.pdfPageCount > 0 {
                let url: URL?
                if let groupSession, groupSession.groupToken != nil {
                    url = groupSession.members.first(where: { $0.record.role == .pdf })?.url
                } else {
                    url = library.pdfURL(for: note)
                }
                guard let url else { throw LibraryBackupError.transaction("笔记 \(note.title) 的 PDF 原文缺失") }
                pdf = url
            }
            if let groupSession, groupSession.groupToken != nil {
                cover = groupSession.members.first(where: { $0.record.role == .cover })?.url
                groupedVideoNoteKeys.insert(identityKey(note.id))
                let attachments = try library.groupedVideoAttachments(basedOn: groupSession)
                let restoredAttachments = try library.groupedRestoredVideoMembers(basedOn: groupSession)
                let videoMembers = groupSession.members.filter { $0.record.role == .video }
                let metadataMembers = groupSession.members.filter { $0.record.role == .videoMetadata }
                guard videoMembers.count == attachments.count + restoredAttachments.count,
                      metadataMembers.count == videoMembers.count else {
                    throw LibraryBackupError.sourceChanged
                }
                func stableMemberID(_ key: String) -> String {
                    var bytes = Array(SHA256.hash(data: Data("padnote-group-member-v1|\(identityKey(note.id))|\(key)".utf8)).prefix(16))
                    bytes[6] = (bytes[6] & 0x0f) | 0x50
                    bytes[8] = (bytes[8] & 0x3f) | 0x80
                    let value = UUID(uuid: (bytes[0], bytes[1], bytes[2], bytes[3], bytes[4], bytes[5], bytes[6], bytes[7],
                                            bytes[8], bytes[9], bytes[10], bytes[11], bytes[12], bytes[13], bytes[14], bytes[15]))
                    return value.uuidString.lowercased()
                }
                for attachment in attachments {
                    let videoID = attachment.id.uuidString.lowercased()
                    guard let video = videoMembers.first(where: { $0.record.id == videoID }),
                          let metadata = metadataMembers.first(where: { $0.record.id == stableMemberID("video-metadata:\(videoID)") }) else {
                        throw LibraryBackupError.sourceChanged
                    }
                    let decoded = try JSONDecoder().decode(NoteVideoAttachment.self,
                        from: LibraryBackupArchive.readSmallFile(metadata.url, maximumBytes: 64 * 1024))
                    guard decoded == attachment else { throw LibraryBackupError.sourceChanged }
                    groupedVideoPlans.append(VideoPlan(value: attachment, sourceURL: video.url,
                        metadataURL: includeLegacySourceURLs ? metadata.url : nil, itemID: generatedID("a"),
                        linkedItemID: noteItemBySource[identityKey(note.id)], sourceState: "linked_note"))
                }
                for restored in restoredAttachments {
                    groupedRestoredVideoPlans.append(RestoredVideoPlan(value: restored.attachment,
                        sourceURL: restored.videoURL, metadataURL: includeLegacySourceURLs ? restored.metadataURL : nil,
                        itemID: generatedID("a"), linkedItemID: noteItemBySource[identityKey(note.id)],
                        sourceState: "linked_note"))
                }
                let vaultMembers = try library.groupedVaultMembers(basedOn: groupSession,
                                                                   journalRoot: vault.backupJournalRoot())
                groupedVaultNoteKeys.insert(identityKey(note.id))
                for member in vaultMembers {
                    let value = member.value
                    let sourceNoteID = value.archiveOrigin == "restored_archive"
                        ? (value.archiveSourceNoteID ?? value.id) : value.id
                    let linkedSource = value.archiveOrigin == nil ? value.id : value.archiveLinkedNoteID
                    let linked = linkedSource.flatMap { noteItemBySource[identityKey($0)] }
                    let state: String
                    if linked != nil { state = "linked_note" }
                    else if value.archiveOrigin == "restored_archive", let priorOwner = value.archiveLinkedNoteID {
                        state = notesByID[identityKey(priorOwner)] == nil ? "source_deleted" : "source_not_selected"
                    } else if value.archiveOrigin == "restored_archive" {
                        state = value.archiveSourceState ?? "independent"
                    } else { state = notesByID[identityKey(sourceNoteID)] == nil ? "source_deleted" : "source_not_selected" }
                    groupedVaultPlans.append(VaultPlan(value: value,
                        sourceURL: includeLegacySourceURLs ? member.metadataURL : nil, sourceNoteID: sourceNoteID,
                        createdAtMS: try floorMS(value.createdAt.timeIntervalSince1970 * 1000),
                        revisionMS: try floorMS(value.sourceUpdatedAt), linkedItemID: linked,
                        currentLinkedNoteID: linked == nil ? nil : linkedSource, sourceState: state,
                        journalRoot: vault.backupJournalRoot()))
                }
            } else {
                cover = try coverStore.backupPNGURL(noteID: note.id)
            }
            notePlans.append(NotePlan(value: note, sourceURL: noteURL, pdfURL: pdf, coverURL: cover,
                                      itemID: itemID, noteResource: noteResource,
                                      pdfResource: pdf == nil ? nil : generatedID("r"),
                                      coverResource: cover == nil ? nil : generatedID("r")))
        }
        let vaultValues = vault.notes
        var vaultPlans = groupedVaultPlans
        for listedValue in vaultValues {
            try cancellation?.check()
            let ownerID = listedValue.archiveOrigin == "restored_archive"
                ? (listedValue.archiveLinkedNoteID ?? listedValue.archiveSourceNoteID ?? listedValue.id) : listedValue.id
            if groupedVaultNoteKeys.contains(identityKey(ownerID)) { continue }
            let value = try vault.backupColdRead(for: listedValue)
            let sourceURL = includeLegacySourceURLs ? try vault.backupSourceURL(for: value) : nil
            let originalSource = value.archiveOrigin == "restored_archive" ? (value.archiveSourceNoteID ?? value.id) : value.id
            let linkedSource = value.archiveOrigin == nil ? value.id : value.archiveLinkedNoteID
            let linked = linkedSource.flatMap { noteItemBySource[identityKey($0)] }
            let state: String
            if linked != nil { state = "linked_note" }
            else if value.archiveOrigin == "restored_archive", let priorOwner = value.archiveLinkedNoteID {
                state = notesByID[identityKey(priorOwner)] == nil ? "source_deleted" : "source_not_selected"
            } else if value.archiveOrigin == "restored_archive" {
                state = value.archiveSourceState ?? "independent"
            } else { state = notesByID[identityKey(originalSource)] == nil ? "source_deleted" : "source_not_selected" }
            let revision = try floorMS(value.sourceUpdatedAt)
            let created = try floorMS(value.createdAt.timeIntervalSince1970 * 1000)
            vaultPlans.append(VaultPlan(value: value, sourceURL: sourceURL, sourceNoteID: originalSource, createdAtMS: created,
                                        revisionMS: revision, linkedItemID: linked,
                                        currentLinkedNoteID: linked == nil ? nil : linkedSource, sourceState: state,
                                        journalRoot: vault.backupJournalRoot()))
        }
        let groupedKeysForListing = groupedVideoNoteKeys
        let videosListing = try await Task.detached(priority: .userInitiated) {
            try videoStore.archiveRecords(excludingNoteIDs: groupedKeysForListing)
        }.value
        var videoPlans = groupedVideoPlans
        for record in videosListing.records {
            try cancellation?.check()
            let value = record.attachment
            if groupedVideoNoteKeys.contains(identityKey(value.noteID)) { continue }
            let linked = noteItemBySource[identityKey(value.noteID)]
            videoPlans.append(VideoPlan(value: value, sourceURL: record.fileURL, metadataURL: includeLegacySourceURLs ? record.metadataURL : nil, itemID: generatedID("a"),
                                        linkedItemID: linked, sourceState: linked == nil ? "source_deleted" : "linked_note"))
        }
        let restoredSources = try await Task.detached(priority: .userInitiated) {
            try restoredVideoStore.listing(excludingNoteIDs: groupedKeysForListing).map { value in
                (value, try restoredVideoStore.fileURL(for: value), includeLegacySourceURLs ? try restoredVideoStore.metadataURL(for: value) : nil)
            }
        }.value
        var restoredPlans = groupedRestoredVideoPlans
        for (value, url, metadataURL) in restoredSources {
            try cancellation?.check()
            if let noteID = value.noteID, groupedVideoNoteKeys.contains(identityKey(noteID)) { continue }
            let linked = value.noteID.flatMap { noteItemBySource[identityKey($0)] }
            restoredPlans.append(RestoredVideoPlan(value: value, sourceURL: url, metadataURL: includeLegacySourceURLs ? metadataURL : nil, itemID: generatedID("a"),
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

    static func vaultStorageText(title: String, sourceNoteID: String, metadata: VaultDigitizationMetadata,
                                 markdown: String) -> String {
        let operationLine = metadata.operationID.map { "digitization-operation-id: \($0)\n" } ?? ""
        return "---\ntitle: \(title)\nnote-id: \(sourceNoteID)\npages: \(metadata.pages)\ndigitized: \(metadata.digitized)\ndigitized-epoch: \(metadata.digitizedEpoch)\nsource-modified: \(metadata.sourceModified)\n\(operationLine)---\n\(markdown)"
    }

    private static func materialize(_ plan: Plan, into directory: URL, cancellation: LibraryBackupCancellationToken?) throws -> LibraryBackupCapturedSnapshot {
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: false)
        var resources = [LibraryBackupManifest.Resource](), resourceFiles = [String: URL](), notes = [LibraryBackupManifest.Note]()
        var vaultEntries = [LibraryBackupManifest.Vault](), videos = [LibraryBackupManifest.Video](), coverPresets = [LibraryBackupManifest.CoverPreset]()
        var restoredVaultDescriptors = [String: Data]()
        var legacyVaultSourceFiles = [String: URL]()
        var videoSourceIdentities = [String: LibraryBackupVideoSourceIdentity]()
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
            if let sourceURL = item.sourceURL {
                guard legacyVaultSourceFiles[itemID] == nil else { throw LibraryBackupError.invalidManifest("知识库归档身份重复") }
                legacyVaultSourceFiles[itemID] = sourceURL
            }
            _ = try addData(data, resourceID: id, role: "vault_entry_json", mime: "application/json", limit: LibraryBackupArchive.maxVaultBytes)
            var storageResourceID: String?
            if let metadata = item.value.archiveDigitizationMetadata {
                guard metadata.isValid(sourceRevisionMS: item.revisionMS, createdAtMS: item.createdAtMS),
                      !item.value.title.contains("\n"), !item.value.title.contains("\r") else {
                    throw LibraryBackupError.sourceChanged
                }
                let storage = vaultStorageText(title: item.value.title, sourceNoteID: item.sourceNoteID,
                                               metadata: metadata, markdown: item.value.markdown)
                let storageID = generatedID("r")
                _ = try addData(Data(storage.utf8), resourceID: storageID, role: "vault_storage_markdown",
                                mime: "text/markdown", limit: LibraryBackupArchive.maxVaultBytes)
                storageResourceID = storageID
            }
            vaultEntries.append(LibraryBackupManifest.Vault(itemID: itemID, noteItemID: item.linkedItemID, sourceState: item.sourceState,
                sourceNoteID: item.sourceNoteID, sourceRevisionMS: item.revisionMS, createdAtMS: item.createdAtMS,
                resourceID: id, sourceStorageResourceID: storageResourceID))
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
            guard videoSourceIdentities[item.itemID] == nil else { throw LibraryBackupError.invalidManifest("视频来源身份重复") }
            videoSourceIdentities[item.itemID] = LibraryBackupVideoSourceIdentity(sourceKind: "note_video_attachment",
                sourceAttachmentID: value.id.uuidString, sourceNoteID: value.noteID, metadataURL: item.metadataURL)
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
            guard videoSourceIdentities[item.itemID] == nil else { throw LibraryBackupError.invalidManifest("视频来源身份重复") }
            videoSourceIdentities[item.itemID] = LibraryBackupVideoSourceIdentity(sourceKind: "restored_video_attachment",
                sourceAttachmentID: item.value.id.uuidString, sourceNoteID: item.value.sourceNoteID, metadataURL: item.metadataURL)
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
        var manifest=LibraryBackupManifest(createdAtMS:try floorMS(Date().timeIntervalSince1970*1000),producer:["platform":"ios","app_version":"1"],notes:notes,vaultEntries:vaultEntries,videoAttachments:videos,coverPresets:coverPresets,resources:resources)
        if manifest.vaultEntries.contains(where: { $0.sourceStorageResourceID != nil }) {
            manifest.formatVersion = 2
            manifest.updateProfiles = try manifest.notes.map { note in
                guard let bodyURL = resourceFiles[note.noteResourceID] else { throw LibraryBackupError.sourceChanged }
                let bodyData = try LibraryBackupArchive.readSmallFile(bodyURL, maximumBytes: LibraryBackupArchive.maxNoteBytes)
                return try LibraryBackupArchive.makeCopiedUpdateProfile(note: note, manifest: manifest, bodyData: bodyData)
            }
        }
        try LibraryBackupArchive.validate(manifest:manifest)
        return LibraryBackupCapturedSnapshot(manifest:manifest,resourceFiles:resourceFiles,
            restoredVaultDescriptors: restoredVaultDescriptors, directory:directory,
            unavailableVideoCount:plan.unavailableVideos, legacyVaultSourceFiles: legacyVaultSourceFiles,
            videoSourceIdentities: videoSourceIdentities)
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
