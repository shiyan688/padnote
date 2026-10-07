package com.padnote.android;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Typed r1 archive manifest. This validates archive metadata and references, not NoteStore payload semantics. */
public final class LibraryBackupManifest {
    public static final String FORMAT = "com.padnote.library-archive";
    public static final int FORMAT_VERSION = 1;
    public static final int MAX_MANIFEST_BYTES = 16 * 1024 * 1024;
    public static final int MAX_RESOURCES = 10_000;
    public static final long MAX_ARCHIVE_BYTES = 1_181_116_006L;
    public static final long MAX_TOTAL_RESOURCE_BYTES = 1L << 30;
    public static final long MAX_NOTE_BYTES = 50L * 1024 * 1024;
    public static final long MAX_PDF_BYTES = 100L * 1024 * 1024;
    public static final long MAX_PNG_BYTES = 8L * 1024 * 1024;
    public static final long MAX_VAULT_BYTES = 16L * 1024 * 1024;
    public static final long MAX_VIDEO_BYTES = 100L * 1024 * 1024;

    private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern ITEM_ID = Pattern.compile("[ivac]-[0-9a-f]{32}");
    private static final Pattern RESOURCE_ID = Pattern.compile("r-[0-9a-f]{32}");

    public final long createdAtMs;
    public final Producer producer;
    public final Scope scope;
    public final List<Note> notes;
    public final List<VaultEntry> vaultEntries;
    public final List<VideoAttachment> videoAttachments;
    public final List<CoverPreset> coverPresets;
    public final List<Resource> resources;

    public LibraryBackupManifest(long createdAtMs, Producer producer, Scope scope,
            List<Note> notes, List<VaultEntry> vaultEntries, List<VideoAttachment> videoAttachments,
            List<CoverPreset> coverPresets, List<Resource> resources) throws IOException {
        this.createdAtMs = createdAtMs;
        this.producer = producer;
        this.scope = scope;
        this.notes = immutable(notes);
        this.vaultEntries = immutable(vaultEntries);
        this.videoAttachments = immutable(videoAttachments);
        this.coverPresets = immutable(coverPresets);
        this.resources = immutable(resources);
        validate();
    }

    private static <T> List<T> immutable(List<T> values) {
        return Collections.unmodifiableList(new ArrayList<>(values == null ? Collections.<T>emptyList() : values));
    }

    public static LibraryBackupManifest parse(byte[] bytes) throws IOException {
        Map<String, Object> top = LibraryBackupJson.object(
                LibraryBackupJson.parse(bytes, MAX_MANIFEST_BYTES), "MANIFEST_OBJECT");
        LibraryBackupJson.exactKeys(top, "MANIFEST_FIELDS", "format", "format_version", "created_at_ms",
                "producer", "scope", "notes", "vault_entries", "video_attachments", "cover_presets", "resources");
        if (!FORMAT.equals(LibraryBackupJson.string(top.get("format"), "FORMAT"))) throw new IOException("FORMAT_UNSUPPORTED");
        if (LibraryBackupJson.integer(top.get("format_version"), 1, 1, "FORMAT_VERSION") != FORMAT_VERSION)
            throw new IOException("FORMAT_UNSUPPORTED");
        long createdAtMs = LibraryBackupJson.integer(top.get("created_at_ms"), 0, Long.MAX_VALUE, "TIME_INVALID");

        Map<String, Object> p = LibraryBackupJson.object(top.get("producer"), "PRODUCER_FIELDS");
        LibraryBackupJson.exactKeys(p, "PRODUCER_FIELDS", "platform", "app_version");
        Producer producer = new Producer(text(p.get("platform"), 16, "PRODUCER_PLATFORM"),
                text(p.get("app_version"), 128, "PRODUCER_APP_VERSION"));

        Map<String, Object> s = LibraryBackupJson.object(top.get("scope"), "SCOPE_FIELDS");
        LibraryBackupJson.exactKeys(s, "SCOPE_FIELDS", "notes", "attached_pdfs", "assigned_covers",
                "vault_entries", "user_cover_presets", "video_attachments", "credentials", "connections",
                "task_history", "in_flight_work");
        Scope scope = new Scope(text(s.get("notes"), 32, "SCOPE_NOTES"), bool(s, "attached_pdfs"),
                bool(s, "assigned_covers"), bool(s, "vault_entries"), bool(s, "user_cover_presets"),
                bool(s, "video_attachments"), bool(s, "credentials"), bool(s, "connections"),
                bool(s, "task_history"), bool(s, "in_flight_work"));

        List<Note> notes = new ArrayList<>();
        for (Object value : array(top.get("notes"), "NOTES_ARRAY")) {
            Map<String, Object> row = LibraryBackupJson.object(value, "NOTE_FIELDS");
            LibraryBackupJson.exactKeys(row, "NOTE_FIELDS", "item_id", "source_note_id", "source_revision_ms",
                    "note_schema_version", "note_resource_id", "pdf_resource_id", "cover_resource_id",
                    "vault_entry_ids", "video_attachment_ids");
            notes.add(new Note(text(row.get("item_id"), 64, "ITEM_ID_INVALID"),
                    text(row.get("source_note_id"), 4096, "SOURCE_NOTE_ID"),
                    integer(row, "source_revision_ms", 0, Long.MAX_VALUE),
                    (int) integer(row, "note_schema_version", 1, 8),
                    text(row.get("note_resource_id"), 64, "RESOURCE_ID_INVALID"),
                    nullableText(row.get("pdf_resource_id"), 64, "RESOURCE_ID_INVALID"),
                    nullableText(row.get("cover_resource_id"), 64, "RESOURCE_ID_INVALID"),
                    textList(row.get("vault_entry_ids"), 10_000, "NOTE_REFERENCE_LIST"),
                    textList(row.get("video_attachment_ids"), 10_000, "NOTE_REFERENCE_LIST")));
        }

        List<VaultEntry> vaults = new ArrayList<>();
        for (Object value : array(top.get("vault_entries"), "VAULT_ARRAY")) {
            Map<String, Object> row = LibraryBackupJson.object(value, "VAULT_FIELDS");
            LibraryBackupJson.exactKeys(row, "VAULT_FIELDS", "item_id", "note_item_id", "source_state",
                    "source_note_id", "source_revision_ms", "created_at_ms", "resource_id");
            vaults.add(new VaultEntry(text(row.get("item_id"), 64, "ITEM_ID_INVALID"),
                    nullableText(row.get("note_item_id"), 64, "ITEM_ID_INVALID"),
                    text(row.get("source_state"), 32, "SOURCE_STATE"),
                    text(row.get("source_note_id"), 4096, "SOURCE_NOTE_ID"),
                    integer(row, "source_revision_ms", 0, Long.MAX_VALUE),
                    integer(row, "created_at_ms", 0, Long.MAX_VALUE),
                    text(row.get("resource_id"), 64, "RESOURCE_ID_INVALID")));
        }

        List<VideoAttachment> videos = new ArrayList<>();
        for (Object value : array(top.get("video_attachments"), "VIDEO_ARRAY")) {
            Map<String, Object> row = LibraryBackupJson.object(value, "VIDEO_FIELDS");
            LibraryBackupJson.exactKeys(row, "VIDEO_FIELDS", "item_id", "note_item_id", "source_state",
                    "origin_kind", "source_note_id", "source_revision_ms", "source_revision_precision_ms",
                    "source_bundle_sha256", "task_payload_sha256", "digest_kind", "offline_state", "task_id",
                    "remote_task_id", "connection_provenance", "artifact_id", "display_name", "media_type",
                    "byte_length", "sha256", "created_at_ms", "resource_id");
            Map<String, Object> cp = LibraryBackupJson.object(row.get("connection_provenance"), "CONNECTION_PROVENANCE_FIELDS");
            LibraryBackupJson.exactKeys(cp, "CONNECTION_PROVENANCE_FIELDS", "connection_id", "connection_revision",
                    "kind", "transport", "bridge_id", "instance_id", "certificate_sha256");
            ConnectionProvenance provenance = new ConnectionProvenance(
                    nullableText(cp.get("connection_id"), 512, "CONNECTION_VALUE"),
                    nullableInteger(cp.get("connection_revision"), "CONNECTION_REVISION"),
                    nullableText(cp.get("kind"), 512, "CONNECTION_VALUE"),
                    nullableText(cp.get("transport"), 512, "CONNECTION_VALUE"),
                    nullableText(cp.get("bridge_id"), 512, "CONNECTION_VALUE"),
                    nullableText(cp.get("instance_id"), 512, "CONNECTION_VALUE"),
                    nullableText(cp.get("certificate_sha256"), 64, "CERTIFICATE_SHA"));
            videos.add(new VideoAttachment(text(row.get("item_id"), 64, "ITEM_ID_INVALID"),
                    nullableText(row.get("note_item_id"), 64, "ITEM_ID_INVALID"),
                    text(row.get("source_state"), 32, "SOURCE_STATE"),
                    text(row.get("origin_kind"), 32, "VIDEO_ORIGIN"),
                    text(row.get("source_note_id"), 4096, "SOURCE_NOTE_ID"),
                    integer(row, "source_revision_ms", 0, Long.MAX_VALUE),
                    (int) integer(row, "source_revision_precision_ms", 1, 1000),
                    text(row.get("source_bundle_sha256"), 64, "VIDEO_DIGEST"),
                    nullableText(row.get("task_payload_sha256"), 64, "VIDEO_DIGEST"),
                    text(row.get("digest_kind"), 64, "VIDEO_DIGEST_KIND"),
                    text(row.get("offline_state"), 64, "VIDEO_OFFLINE_STATE"),
                    nullableText(row.get("task_id"), 512, "VIDEO_TASK_ID"),
                    nullableText(row.get("remote_task_id"), 512, "VIDEO_TASK_ID"), provenance,
                    nullableText(row.get("artifact_id"), 512, "VIDEO_ARTIFACT_ID"),
                    text(row.get("display_name"), 1024, "VIDEO_DISPLAY_NAME"),
                    text(row.get("media_type"), 64, "VIDEO_MEDIA_TYPE"),
                    integer(row, "byte_length", 0, MAX_VIDEO_BYTES),
                    text(row.get("sha256"), 64, "VIDEO_SHA"),
                    integer(row, "created_at_ms", 0, Long.MAX_VALUE),
                    text(row.get("resource_id"), 64, "RESOURCE_ID_INVALID")));
        }

        List<CoverPreset> presets = new ArrayList<>();
        for (Object value : array(top.get("cover_presets"), "COVER_ARRAY")) {
            Map<String, Object> row = LibraryBackupJson.object(value, "COVER_PRESET_FIELDS");
            LibraryBackupJson.exactKeys(row, "COVER_PRESET_FIELDS", "item_id", "display_name", "resource_id");
            presets.add(new CoverPreset(text(row.get("item_id"), 64, "ITEM_ID_INVALID"),
                    text(row.get("display_name"), 1024, "COVER_DISPLAY_NAME"),
                    text(row.get("resource_id"), 64, "RESOURCE_ID_INVALID")));
        }

        List<Resource> resources = new ArrayList<>();
        for (Object value : array(top.get("resources"), "RESOURCE_ARRAY")) {
            Map<String, Object> row = LibraryBackupJson.object(value, "RESOURCE_FIELDS");
            LibraryBackupJson.exactKeys(row, "RESOURCE_FIELDS", "resource_id", "role", "media_type", "byte_length", "sha256", "member");
            resources.add(new Resource(text(row.get("resource_id"), 64, "RESOURCE_ID_INVALID"),
                    text(row.get("role"), 64, "RESOURCE_ROLE"), text(row.get("media_type"), 128, "RESOURCE_MEDIA"),
                    integer(row, "byte_length", 0, Long.MAX_VALUE), text(row.get("sha256"), 64, "RESOURCE_SHA"),
                    text(row.get("member"), 128, "RESOURCE_MEMBER")));
        }
        return new LibraryBackupManifest(createdAtMs, producer, scope, notes, vaults, videos, presets, resources);
    }

    public byte[] toJsonBytes() throws IOException {
        return LibraryBackupJson.encode(asJson());
    }

    public void validate() throws IOException {
        if (createdAtMs < 0 || producer == null || scope == null) throw new IOException("MANIFEST_FIELDS");
        if (!("android".equals(producer.platform) || "ios".equals(producer.platform)) || empty(producer.appVersion))
            throw new IOException("PRODUCER_FIELDS");
        if (!"all-selected".equals(scope.notes) || !scope.attachedPdfs || !scope.assignedCovers || !scope.vaultEntries ||
                !scope.userCoverPresets || !scope.videoAttachments || scope.credentials || scope.connections || scope.taskHistory || scope.inFlightWork)
            throw new IOException("FORBIDDEN_SCOPE");
        if (notes.size() + vaultEntries.size() + videoAttachments.size() + coverPresets.size() > MAX_RESOURCES || resources.size() > MAX_RESOURCES)
            throw new IOException("RESOURCE_COUNT_LIMIT");

        Map<String, Note> notesById = new HashMap<>();
        Set<String> allItemIds = new HashSet<>();
        for (Note note : notes) {
            requireId(note.itemId, 'i', "ITEM_ID_INVALID");
            requireId(note.noteResourceId, "RESOURCE_ID_INVALID");
            if (note.pdfResourceId != null) requireId(note.pdfResourceId, "RESOURCE_ID_INVALID");
            if (note.coverResourceId != null) requireId(note.coverResourceId, "RESOURCE_ID_INVALID");
            if (!allItemIds.add(note.itemId)) throw new IOException("ITEM_ID_DUPLICATE");
            requireText(note.sourceNoteId, 4096, "SOURCE_NOTE_ID");
            if (note.sourceRevisionMs < 0 || note.noteSchemaVersion < 1 || note.noteSchemaVersion > 8) throw new IOException("NOTE_FIELDS");
            uniqueIds(note.vaultEntryIds, 'v', "NOTE_REFERENCE_LIST");
            uniqueIds(note.videoAttachmentIds, 'a', "NOTE_REFERENCE_LIST");
            notesById.put(note.itemId, note);
        }

        Map<String, Resource> byResource = new LinkedHashMap<>();
        for (Resource resource : resources) {
            requireId(resource.resourceId, "RESOURCE_ID_INVALID");
            if (byResource.put(resource.resourceId, resource) != null) throw new IOException("RESOURCE_ID_DUPLICATE");
            String roleMedia = resource.role == null ? null : roleMedia(resource.role);
            if (roleMedia == null || !roleMedia.equals(resource.mediaType)) throw new IOException("RESOURCE_MEDIA");
            long roleLimit = roleLimit(resource.role);
            if (resource.byteLength < 0 || resource.byteLength > roleLimit) throw new IOException("RESOURCE_LENGTH");
            if (!isHash(resource.sha256)) throw new IOException("RESOURCE_SHA");
            if (!("payload/" + resource.resourceId + ".bin").equals(resource.member)) throw new IOException("RESOURCE_MEMBER");
        }

        Map<String, Integer> referenced = new HashMap<>();
        for (Note note : notes) {
            use(note.noteResourceId, "note_document", byResource, referenced);
            if (note.pdfResourceId != null) use(note.pdfResourceId, "pdf_original", byResource, referenced);
            if (note.coverResourceId != null) use(note.coverResourceId, "assigned_cover_png", byResource, referenced);
        }
        Map<String, VaultEntry> vaultsById = new HashMap<>();
        for (VaultEntry entry : vaultEntries) {
            requireId(entry.itemId, 'v', "ITEM_ID_INVALID");
            if (!allItemIds.add(entry.itemId)) throw new IOException("ITEM_ID_DUPLICATE");
            validateSource(entry.sourceState, entry.noteItemId, entry.sourceNoteId, notesById, false);
            if (entry.sourceRevisionMs < 0 || entry.createdAtMs < 0) throw new IOException("TIME_INVALID");
            use(entry.resourceId, "vault_entry_json", byResource, referenced);
            vaultsById.put(entry.itemId, entry);
        }
        Map<String, VideoAttachment> videosById = new HashMap<>();
        for (VideoAttachment video : videoAttachments) {
            requireId(video.itemId, 'a', "ITEM_ID_INVALID");
            if (!allItemIds.add(video.itemId)) throw new IOException("ITEM_ID_DUPLICATE");
            validateSource(video.sourceState, video.noteItemId, video.sourceNoteId, notesById,
                    "computer_task".equals(video.originKind));
            if (!("computer_task".equals(video.originKind) || "restored_archive".equals(video.originKind))) throw new IOException("VIDEO_ORIGIN");
            if (video.sourceRevisionMs < 0 || video.createdAtMs < 0) throw new IOException("TIME_INVALID");
            if (!(video.sourceRevisionPrecisionMs == 1 || video.sourceRevisionPrecisionMs == 1000) ||
                    (video.sourceRevisionPrecisionMs == 1000 && video.sourceRevisionMs % 1000 != 0)) throw new IOException("VIDEO_PRECISION");
            if (!isHash(video.sourceBundleSha256)) throw new IOException("VIDEO_DIGEST");
            if (video.taskPayloadSha256 != null && !isHash(video.taskPayloadSha256)) throw new IOException("VIDEO_DIGEST");
            if (!("source_snapshot_only".equals(video.digestKind) || "source_and_task_payload".equals(video.digestKind)) ||
                    ("source_snapshot_only".equals(video.digestKind) && video.taskPayloadSha256 != null) ||
                    ("source_and_task_payload".equals(video.digestKind) && video.taskPayloadSha256 == null)) throw new IOException("VIDEO_DIGEST_KIND");
            if (!"verified_local_copy".equals(video.offlineState)) throw new IOException("VIDEO_NOT_LOCAL");
            requireOptionalText(video.taskId, 512, "VIDEO_TASK_ID");
            requireOptionalText(video.remoteTaskId, 512, "VIDEO_TASK_ID");
            requireOptionalText(video.artifactId, 512, "VIDEO_ARTIFACT_ID");
            requireText(video.displayName, 1024, "VIDEO_DISPLAY_NAME");
            if (!"video/mp4".equals(video.mediaType) || video.byteLength < 0 || video.byteLength > MAX_VIDEO_BYTES) throw new IOException("VIDEO_MEDIA");
            if (!isHash(video.sha256)) throw new IOException("VIDEO_SHA");
            if (video.connectionProvenance == null) throw new IOException("CONNECTION_PROVENANCE_FIELDS");
            validateProvenance(video.connectionProvenance);
            use(video.resourceId, "video_attachment_mp4", byResource, referenced);
            Resource resource = byResource.get(video.resourceId);
            if (video.byteLength != resource.byteLength || !video.sha256.equals(resource.sha256) || !video.mediaType.equals(resource.mediaType))
                throw new IOException("VIDEO_RESOURCE_MISMATCH");
            videosById.put(video.itemId, video);
        }
        Set<String> presetIds = new HashSet<>();
        for (CoverPreset preset : coverPresets) {
            requireId(preset.itemId, 'c', "ITEM_ID_INVALID");
            if (!allItemIds.add(preset.itemId) || !presetIds.add(preset.itemId)) throw new IOException("ITEM_ID_DUPLICATE");
            requireText(preset.displayName, 1024, "COVER_DISPLAY_NAME");
            use(preset.resourceId, "user_cover_preset_png", byResource, referenced);
        }
        for (Note note : notes) {
            Set<String> expectedVaults = new HashSet<>();
            for (VaultEntry entry : vaultEntries) if (note.itemId.equals(entry.noteItemId)) expectedVaults.add(entry.itemId);
            if (!expectedVaults.equals(new HashSet<>(note.vaultEntryIds))) throw new IOException("VAULT_REFERENCE_MISMATCH");
            Set<String> expectedVideos = new HashSet<>();
            for (VideoAttachment video : videoAttachments) if (note.itemId.equals(video.noteItemId)) expectedVideos.add(video.itemId);
            if (!expectedVideos.equals(new HashSet<>(note.videoAttachmentIds))) throw new IOException("VIDEO_REFERENCE_MISMATCH");
        }
        if (referenced.size() != byResource.size()) throw new IOException("RESOURCE_UNREFERENCED");
        long total = 0;
        for (Resource resource : resources) {
            if (!referenced.containsKey(resource.resourceId)) throw new IOException("RESOURCE_UNREFERENCED");
            if (resource.byteLength > MAX_TOTAL_RESOURCE_BYTES - total) throw new IOException("TOTAL_RESOURCE_LIMIT");
            total += resource.byteLength;
        }
    }

    public Resource resourceById(String resourceId) {
        for (Resource resource : resources) if (resource.resourceId.equals(resourceId)) return resource;
        return null;
    }

    private static void validateProvenance(ConnectionProvenance value) throws IOException {
        requireOptionalText(value.connectionId, 512, "CONNECTION_VALUE");
        requireOptionalText(value.kind, 512, "CONNECTION_VALUE");
        requireOptionalText(value.transport, 512, "CONNECTION_VALUE");
        requireOptionalText(value.bridgeId, 512, "CONNECTION_VALUE");
        requireOptionalText(value.instanceId, 512, "CONNECTION_VALUE");
        if (value.connectionRevision != null && value.connectionRevision < 0) throw new IOException("CONNECTION_REVISION");
        if (value.certificateSha256 != null && !isHash(value.certificateSha256)) throw new IOException("CERTIFICATE_SHA");
    }

    private static void validateSource(String state, String noteItemId, String sourceNoteId, Map<String, Note> notes,
            boolean matchCurrentNoteSource) throws IOException {
        requireText(sourceNoteId, 4096, "SOURCE_NOTE_ID");
        if ("linked_note".equals(state)) {
            Note source = notes.get(noteItemId);
            if (source == null) throw new IOException("SOURCE_NOTE_REFERENCE");
            if (matchCurrentNoteSource && !source.sourceNoteId.equals(sourceNoteId)) throw new IOException("SOURCE_NOTE_ID_MISMATCH");
        } else if (!("source_deleted".equals(state) || "source_not_selected".equals(state) || "independent".equals(state))) {
            throw new IOException("SOURCE_STATE");
        } else if (noteItemId != null) throw new IOException("INDEPENDENT_ITEM_HAS_NOTE");
    }

    private static void use(String resourceId, String role, Map<String, Resource> resources, Map<String, Integer> used) throws IOException {
        requireId(resourceId, "RESOURCE_ID_INVALID");
        Resource resource = resources.get(resourceId);
        if (resource == null || !role.equals(resource.role)) throw new IOException("RESOURCE_REFERENCE");
        int count = used.containsKey(resourceId) ? used.get(resourceId) + 1 : 1;
        used.put(resourceId, count);
        if (count != 1) throw new IOException("RESOURCE_MULTIPLE_REFERENCE");
    }

    private Map<String, Object> asJson() {
        Map<String, Object> top = map();
        top.put("format", FORMAT); top.put("format_version", FORMAT_VERSION); top.put("created_at_ms", createdAtMs);
        Map<String, Object> p = map(); p.put("platform", producer.platform); p.put("app_version", producer.appVersion); top.put("producer", p);
        Map<String, Object> s = map(); s.put("notes", scope.notes); s.put("attached_pdfs", scope.attachedPdfs);
        s.put("assigned_covers", scope.assignedCovers); s.put("vault_entries", scope.vaultEntries);
        s.put("user_cover_presets", scope.userCoverPresets); s.put("video_attachments", scope.videoAttachments);
        s.put("credentials", scope.credentials); s.put("connections", scope.connections);
        s.put("task_history", scope.taskHistory); s.put("in_flight_work", scope.inFlightWork); top.put("scope", s);
        List<Object> noteRows = new ArrayList<>();
        for (Note n : notes) {
            Map<String, Object> row = map(); row.put("item_id", n.itemId); row.put("source_note_id", n.sourceNoteId);
            row.put("source_revision_ms", n.sourceRevisionMs); row.put("note_schema_version", n.noteSchemaVersion);
            row.put("note_resource_id", n.noteResourceId); row.put("pdf_resource_id", n.pdfResourceId);
            row.put("cover_resource_id", n.coverResourceId); row.put("vault_entry_ids", new ArrayList<>(n.vaultEntryIds));
            row.put("video_attachment_ids", new ArrayList<>(n.videoAttachmentIds)); noteRows.add(row);
        }
        top.put("notes", noteRows);
        List<Object> vaultRows = new ArrayList<>();
        for (VaultEntry v : vaultEntries) {
            Map<String, Object> row = map(); row.put("item_id", v.itemId); row.put("note_item_id", v.noteItemId);
            row.put("source_state", v.sourceState); row.put("source_note_id", v.sourceNoteId);
            row.put("source_revision_ms", v.sourceRevisionMs); row.put("created_at_ms", v.createdAtMs); row.put("resource_id", v.resourceId); vaultRows.add(row);
        }
        top.put("vault_entries", vaultRows);
        List<Object> videoRows = new ArrayList<>();
        for (VideoAttachment v : videoAttachments) {
            Map<String, Object> cp = map(); ConnectionProvenance c = v.connectionProvenance;
            cp.put("connection_id", c.connectionId); cp.put("connection_revision", c.connectionRevision);
            cp.put("kind", c.kind); cp.put("transport", c.transport); cp.put("bridge_id", c.bridgeId);
            cp.put("instance_id", c.instanceId); cp.put("certificate_sha256", c.certificateSha256);
            Map<String, Object> row = map(); row.put("item_id", v.itemId); row.put("note_item_id", v.noteItemId);
            row.put("source_state", v.sourceState); row.put("origin_kind", v.originKind); row.put("source_note_id", v.sourceNoteId);
            row.put("source_revision_ms", v.sourceRevisionMs); row.put("source_revision_precision_ms", v.sourceRevisionPrecisionMs);
            row.put("source_bundle_sha256", v.sourceBundleSha256); row.put("task_payload_sha256", v.taskPayloadSha256);
            row.put("digest_kind", v.digestKind); row.put("offline_state", v.offlineState); row.put("task_id", v.taskId);
            row.put("remote_task_id", v.remoteTaskId); row.put("connection_provenance", cp); row.put("artifact_id", v.artifactId);
            row.put("display_name", v.displayName); row.put("media_type", v.mediaType); row.put("byte_length", v.byteLength);
            row.put("sha256", v.sha256); row.put("created_at_ms", v.createdAtMs); row.put("resource_id", v.resourceId); videoRows.add(row);
        }
        top.put("video_attachments", videoRows);
        List<Object> presetRows = new ArrayList<>();
        for (CoverPreset c : coverPresets) { Map<String, Object> row = map(); row.put("item_id", c.itemId); row.put("display_name", c.displayName); row.put("resource_id", c.resourceId); presetRows.add(row); }
        top.put("cover_presets", presetRows);
        List<Object> resourceRows = new ArrayList<>();
        for (Resource r : resources) { Map<String, Object> row = map(); row.put("resource_id", r.resourceId); row.put("role", r.role); row.put("media_type", r.mediaType); row.put("byte_length", r.byteLength); row.put("sha256", r.sha256); row.put("member", r.member); resourceRows.add(row); }
        top.put("resources", resourceRows);
        return top;
    }

    private static Map<String, Object> map() { return new LinkedHashMap<>(); }
    private static List<Object> array(Object value, String code) throws IOException { return LibraryBackupJson.array(value, code); }
    private static boolean bool(Map<String, Object> map, String key) throws IOException { return LibraryBackupJson.bool(map.get(key), "SCOPE_TYPE"); }
    private static String text(Object value, int limit, String code) throws IOException { String result = LibraryBackupJson.string(value, code); requireText(result, limit, code); return result; }
    private static String nullableText(Object value, int limit, String code) throws IOException { return value == null ? null : text(value, limit, code); }
    private static long integer(Map<String, Object> map, String key, long min, long max) throws IOException { return LibraryBackupJson.integer(map.get(key), min, max, "INTEGER_" + key.toUpperCase()); }
    private static Long nullableInteger(Object value, String code) throws IOException { return value == null ? null : LibraryBackupJson.integer(value, 0, Long.MAX_VALUE, code); }
    private static List<String> textList(Object value, int max, String code) throws IOException {
        List<Object> values = array(value, code); if (values.size() > max) throw new IOException(code);
        List<String> result = new ArrayList<>(); for (Object item : values) result.add(LibraryBackupJson.string(item, code)); return result;
    }
    private static void requireText(String value, int maxBytes, String code) throws IOException {
        if (value == null || value.isEmpty() || value.getBytes(StandardCharsets.UTF_8).length > maxBytes) throw new IOException(code);
    }
    private static void requireOptionalText(String value, int maxBytes, String code) throws IOException { if (value != null) requireText(value, maxBytes, code); }
    private static boolean empty(String value) { return value == null || value.trim().isEmpty(); }
    private static void requireId(String value, String code) throws IOException { if (value == null || !RESOURCE_ID.matcher(value).matches()) throw new IOException(code); }
    private static void requireId(String value, char prefix, String code) throws IOException { if (value == null || !ITEM_ID.matcher(value).matches() || value.charAt(0) != prefix) throw new IOException(code); }
    private static boolean isHash(String value) { return value != null && HASH.matcher(value).matches(); }
    private static void uniqueIds(List<String> values, char prefix, String code) throws IOException {
        Set<String> seen = new HashSet<>();
        for (String value : values) { requireId(value, prefix, code); if (!seen.add(value)) throw new IOException(code); }
    }
    private static String roleMedia(String role) {
        switch (role) {
            case "note_document": case "vault_entry_json": return "application/json";
            case "pdf_original": return "application/pdf";
            case "assigned_cover_png": case "user_cover_preset_png": return "image/png";
            case "video_attachment_mp4": return "video/mp4";
            default: return null;
        }
    }
    private static long roleLimit(String role) {
        switch (role) {
            case "note_document": return MAX_NOTE_BYTES;
            case "pdf_original": return MAX_PDF_BYTES;
            case "assigned_cover_png": case "user_cover_preset_png": return MAX_PNG_BYTES;
            case "vault_entry_json": return MAX_VAULT_BYTES;
            case "video_attachment_mp4": return MAX_VIDEO_BYTES;
            default: return 0;
        }
    }

    public static final class Producer {
        public final String platform, appVersion;
        public Producer(String platform, String appVersion) { this.platform = platform; this.appVersion = appVersion; }
    }
    public static final class Scope {
        public final String notes;
        public final boolean attachedPdfs, assignedCovers, vaultEntries, userCoverPresets, videoAttachments;
        public final boolean credentials, connections, taskHistory, inFlightWork;
        public Scope(String notes, boolean attachedPdfs, boolean assignedCovers, boolean vaultEntries,
                boolean userCoverPresets, boolean videoAttachments, boolean credentials, boolean connections,
                boolean taskHistory, boolean inFlightWork) {
            this.notes = notes; this.attachedPdfs = attachedPdfs; this.assignedCovers = assignedCovers;
            this.vaultEntries = vaultEntries; this.userCoverPresets = userCoverPresets; this.videoAttachments = videoAttachments;
            this.credentials = credentials; this.connections = connections; this.taskHistory = taskHistory; this.inFlightWork = inFlightWork;
        }
        public static Scope allSelected() { return new Scope("all-selected", true, true, true, true, true, false, false, false, false); }
    }
    public static final class Note {
        public final String itemId, sourceNoteId, noteResourceId, pdfResourceId, coverResourceId;
        public final long sourceRevisionMs; public final int noteSchemaVersion;
        public final List<String> vaultEntryIds, videoAttachmentIds;
        public Note(String itemId, String sourceNoteId, long sourceRevisionMs, int noteSchemaVersion,
                String noteResourceId, String pdfResourceId, String coverResourceId,
                List<String> vaultEntryIds, List<String> videoAttachmentIds) {
            this.itemId=itemId;this.sourceNoteId=sourceNoteId;this.sourceRevisionMs=sourceRevisionMs;this.noteSchemaVersion=noteSchemaVersion;
            this.noteResourceId=noteResourceId;this.pdfResourceId=pdfResourceId;this.coverResourceId=coverResourceId;
            this.vaultEntryIds=immutable(vaultEntryIds);this.videoAttachmentIds=immutable(videoAttachmentIds);
        }
    }
    public static final class VaultEntry {
        public final String itemId, noteItemId, sourceState, sourceNoteId, resourceId;
        public final long sourceRevisionMs, createdAtMs;
        public VaultEntry(String itemId, String noteItemId, String sourceState, String sourceNoteId,
                long sourceRevisionMs, long createdAtMs, String resourceId) {
            this.itemId=itemId;this.noteItemId=noteItemId;this.sourceState=sourceState;this.sourceNoteId=sourceNoteId;
            this.sourceRevisionMs=sourceRevisionMs;this.createdAtMs=createdAtMs;this.resourceId=resourceId;
        }
    }
    public static final class ConnectionProvenance {
        public final String connectionId, kind, transport, bridgeId, instanceId, certificateSha256;
        public final Long connectionRevision;
        public ConnectionProvenance(String connectionId, Long connectionRevision, String kind, String transport,
                String bridgeId, String instanceId, String certificateSha256) {
            this.connectionId=connectionId;this.connectionRevision=connectionRevision;this.kind=kind;this.transport=transport;
            this.bridgeId=bridgeId;this.instanceId=instanceId;this.certificateSha256=certificateSha256;
        }
    }
    public static final class VideoAttachment {
        public final String itemId, noteItemId, sourceState, originKind, sourceNoteId;
        public final long sourceRevisionMs, byteLength, createdAtMs;
        public final int sourceRevisionPrecisionMs;
        public final String sourceBundleSha256, taskPayloadSha256, digestKind, offlineState, taskId, remoteTaskId;
        public final ConnectionProvenance connectionProvenance;
        public final String artifactId, displayName, mediaType, sha256, resourceId;
        public VideoAttachment(String itemId, String noteItemId, String sourceState, String originKind,
                String sourceNoteId, long sourceRevisionMs, int sourceRevisionPrecisionMs, String sourceBundleSha256,
                String taskPayloadSha256, String digestKind, String offlineState, String taskId, String remoteTaskId,
                ConnectionProvenance connectionProvenance, String artifactId, String displayName, String mediaType,
                long byteLength, String sha256, long createdAtMs, String resourceId) {
            this.itemId=itemId;this.noteItemId=noteItemId;this.sourceState=sourceState;this.originKind=originKind;
            this.sourceNoteId=sourceNoteId;this.sourceRevisionMs=sourceRevisionMs;this.sourceRevisionPrecisionMs=sourceRevisionPrecisionMs;
            this.sourceBundleSha256=sourceBundleSha256;this.taskPayloadSha256=taskPayloadSha256;this.digestKind=digestKind;
            this.offlineState=offlineState;this.taskId=taskId;this.remoteTaskId=remoteTaskId;this.connectionProvenance=connectionProvenance;
            this.artifactId=artifactId;this.displayName=displayName;this.mediaType=mediaType;this.byteLength=byteLength;
            this.sha256=sha256;this.createdAtMs=createdAtMs;this.resourceId=resourceId;
        }
    }
    public static final class CoverPreset {
        public final String itemId, displayName, resourceId;
        public CoverPreset(String itemId, String displayName, String resourceId) { this.itemId=itemId;this.displayName=displayName;this.resourceId=resourceId; }
    }
    public static final class Resource {
        public final String resourceId, role, mediaType, sha256, member;
        public final long byteLength;
        public Resource(String resourceId, String role, String mediaType, long byteLength, String sha256, String member) {
            this.resourceId=resourceId;this.role=role;this.mediaType=mediaType;this.byteLength=byteLength;this.sha256=sha256;this.member=member;
        }
    }
}
