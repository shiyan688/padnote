import Foundation
import CryptoKit

/// Cold, journal-bound projection for one restored VaultLibrary record.
enum RestoredVaultMaterialProjection {
    static func descriptor(_ value: VaultNote, sourceNoteID: String, sourceRevisionMS: Int64,
                           createdAtMS: Int64, currentSourceState: String,
                           currentNoteItemID: String?, currentLinkedNoteID: String?,
                           journalRoot: URL) throws -> Data {
        guard value.archiveOrigin == "restored_archive",
              let transactionID = value.restoreTransactionID,
              let groupID = value.restoreGroupID,
              UUID(uuidString: value.id)?.uuidString.lowercased() == value.id,
              UUID(uuidString: transactionID)?.uuidString.lowercased() == transactionID else {
            throw LibraryBackupError.transaction("恢复知识库条目缺少稳定本地身份或事务")
        }
        guard let journal = try LibraryBackupTransactionGate.readJournal(transactionID, in: journalRoot) else {
            throw LibraryBackupError.transaction("恢复知识库提交日志缺失")
        }
        try LibraryBackupRestoreCoordinator.validateJournalInternalConsistency(journal)
        guard journal.archiveSHA256.range(of: "^[0-9a-f]{64}$", options: .regularExpression) != nil,
              journal.status == "committed" || LibraryBackupRestoreCoordinator.isGroupCompleted(groupID, in: journal),
              LibraryBackupTransactionGate.isCommitted(transactionID, groupID: groupID, kind: "vault", localID: value.id,
                                                       in: journalRoot) else {
            throw LibraryBackupError.transaction("恢复知识库提交绑定无效")
        }
        let itemIDs = journal.vaultIDs.filter { $0.value == value.id }.map { $0.key }
        guard itemIDs.count == 1, let itemID = itemIDs.first,
              journal.vaultGroupIDsByItemID[itemID] == groupID else {
            throw LibraryBackupError.transaction("恢复知识库清单项映射有歧义")
        }
        let noteItemID = groupID == itemID ? nil : groupID
        let ownerLineageID: String?
        if let importedLocalNoteID = value.archiveLinkedNoteID {
            guard let noteItemID, journal.noteIDs[noteItemID] == importedLocalNoteID else {
                throw LibraryBackupError.transaction("恢复知识库原笔记映射无效")
            }
            ownerLineageID = importedLocalNoteID
            if currentSourceState == "linked_note" {
                // The snapshot item ID is newly generated for this archive. Validate the
                // current local note identity against the journal's restored note ID.
                guard currentNoteItemID != nil, currentLinkedNoteID == importedLocalNoteID else {
                    throw LibraryBackupError.transaction("恢复知识库当前笔记关联无效")
                }
            } else {
                guard currentNoteItemID == nil, currentLinkedNoteID == nil,
                      ["source_deleted", "source_not_selected"].contains(currentSourceState) else {
                    throw LibraryBackupError.transaction("恢复知识库脱离状态无效")
                }
            }
        } else if currentSourceState == "independent" {
            guard noteItemID == nil, currentNoteItemID == nil, currentLinkedNoteID == nil,
                  journal.independentGroupIDs.contains(groupID) else {
                throw LibraryBackupError.transaction("独立恢复知识库关联无效")
            }
            ownerLineageID = nil
        } else {
            guard noteItemID == nil, currentNoteItemID == nil, currentLinkedNoteID == nil,
                  ["source_deleted", "source_not_selected"].contains(currentSourceState),
                  journal.independentGroupIDs.contains(groupID) else {
                throw LibraryBackupError.transaction("历史恢复知识库关联无效")
            }
            let seed = "PadNote/ImporterVaultHistoryAlias/v1\0\(journal.archiveSHA256)\0\(transactionID)\0\(groupID)\0\(itemID)\0-\0\(value.id)"
            var digest = Data(SHA256.hash(data: Data(seed.utf8)))
            digest[6] = (digest[6] & 0x0f) | 0x50
            digest[8] = (digest[8] & 0x3f) | 0x80
            let hex = digest.prefix(16).map { String(format: "%02x", $0) }.joined()
            ownerLineageID = "\(hex.prefix(8))-\(hex.dropFirst(8).prefix(4))-\(hex.dropFirst(12).prefix(4))-\(hex.dropFirst(16).prefix(4))-\(hex.dropFirst(20).prefix(12))"
        }
        let model = MaterialCodec.Vault(materialID: value.id, sourceState: currentSourceState, sourceNoteID: sourceNoteID,
            title: value.title, ownerLineageID: ownerLineageID,
            sourceLineageID: currentSourceState == "linked_note" ? ownerLineageID : nil,
            sourceRevision: .integer("unix_ms_i64", sourceRevisionMS),
            createdAt: .integer("unix_ms_i64", createdAtMS), pageCount: nil,
            markdownUTF8: Data(value.markdown.utf8))
        let encoded = try MaterialCodec.vaultBytes(model)
        _ = try MaterialCodec.digest(encoded)
        return encoded
    }
}
