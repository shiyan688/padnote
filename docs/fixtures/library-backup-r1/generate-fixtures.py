#!/usr/bin/env python3
"""Generate synthetic canonical r1 backup fixtures into a new directory only."""
import argparse
import hashlib
import json
import stat
import struct
import zipfile
import zlib
from pathlib import Path

def png_chunk(kind, data):
    return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)


PNG_1X1 = (b"\x89PNG\r\n\x1a\n" +
           png_chunk(b"IHDR", struct.pack(">IIBBBBB", 1, 1, 8, 6, 0, 0, 0)) +
           png_chunk(b"IDAT", zlib.compress(b"\x00\x00\x00\x00\xff")) +
           png_chunk(b"IEND", b""))
PDF = (b"%PDF-1.4\n1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n"
       b"2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n"
       b"3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 600 800] /Contents 4 0 R >>\nendobj\n"
       b"4 0 obj\n<< /Length 35 >>\nstream\n0.95 0.92 0.80 rg 0 0 600 800 re f\nendstream\nendobj\n"
       b"xref\n0 5\n0000000000 65535 f \n0000000009 00000 n \n0000000058 00000 n \n"
       b"0000000115 00000 n \n0000000202 00000 n \ntrailer\n<< /Size 5 /Root 1 0 R >>\n"
       b"startxref\n286\n%%EOF\n")
MP4 = b"\x00\x00\x00\x18ftypisom\x00\x00\x02\x00isomiso2"
NOW = 1790000000000


def jbytes(value):
    return (json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")) + "\n").encode()


def make():
    resources_data = {}
    def resource(rid, role, media, data):
        resources_data[rid] = (role, media, data)
        return {"resource_id": rid, "role": role, "media_type": media, "byte_length": len(data),
                "sha256": hashlib.sha256(data).hexdigest(), "member": f"payload/{rid}.bin"}

    note1, note2 = "i-" + "1" * 32, "i-" + "2" * 32
    vault1, vault2 = "v-" + "1" * 32, "v-" + "2" * 32
    video1, video2 = "a-" + "1" * 32, "a-" + "2" * 32
    note1doc, note2doc = "r-" + "1" * 32, "r-" + "2" * 32
    pdfid, coverid, vaultres1, vaultres2 = ("r-" + str(n) * 32 for n in (3, 4, 5, 6))
    videores1, videores2, presetres = ("r-" + str(n) * 32 for n in (7, 8, 9))
    source_note_1, source_note_2 = "source-note-ordinary", "source-note-pdf"
    doc1 = jbytes({"schemaVersion": 8, "id": source_note_1, "title": "示例笔记", "updatedAt": NOW + 0.75,
        "pageWidth": 768, "pageHeight": 1086, "pageGap": 24, "pageCount": 1, "pdfPageCount": 0,
        "pageTopologyRevision": 0, "strokes": [], "textFlows": [], "images": [],
        "pageStyle": {"paper": "ruled", "ratio": "screen", "landscape": False},
        "viewportZoom": 1, "viewportCenterX": 384, "viewportCenterY": 543})
    doc2 = jbytes({"schemaVersion": 8, "id": source_note_2, "title": "PDF 示例", "updatedAt": NOW,
        "pageWidth": 600, "pageHeight": 800, "pageGap": 24, "pageCount": 2, "pdfPageCount": 1,
        "pageTopologyRevision": 0, "strokes": [],
        "textFlows": [{"id": "flow-fixture", "format": "markdown", "source": "PDF fixture page 2",
            "fontSizeSp": 16, "lineHeight": 1.35, "width": 460, "anchorPageIndex": 1,
            "anchorXInPage": 50, "anchorYInPage": 90}], "images": [],
        "pageStyle": {"paper": "blank", "ratio": "screen", "landscape": False},
        "viewportZoom": 1, "viewportCenterX": 300, "viewportCenterY": 400})
    vault_payload1 = jbytes({"schema_version": 1, "title": "旧修订知识库", "markdown": "fixture only", "source_note_id": source_note_1, "source_revision_ms": NOW - 2000, "created_at_ms": NOW - 1000})
    vault_payload2 = jbytes({"schema_version": 1, "title": "已删除来源知识库", "markdown": "independent fixture", "source_note_id": "deleted-source", "source_revision_ms": NOW - 3000, "created_at_ms": NOW - 1000})
    resource_rows = [
        resource(note1doc, "note_document", "application/json", doc1),
        resource(note2doc, "note_document", "application/json", doc2),
        resource(pdfid, "pdf_original", "application/pdf", PDF),
        resource(coverid, "assigned_cover_png", "image/png", PNG_1X1),
        resource(vaultres1, "vault_entry_json", "application/json", vault_payload1),
        resource(vaultres2, "vault_entry_json", "application/json", vault_payload2),
        resource(videores1, "video_attachment_mp4", "video/mp4", MP4),
        resource(videores2, "video_attachment_mp4", "video/mp4", MP4 + b"source-deleted"),
        resource(presetres, "user_cover_preset_png", "image/png", PNG_1X1),
    ]
    video_common = {
        "origin_kind": "computer_task", "source_bundle_sha256": "a" * 64,
        "task_payload_sha256": None, "digest_kind": "source_snapshot_only",
        "offline_state": "verified_local_copy", "task_id": "readonly-task-1", "remote_task_id": "remote-task-1",
        "connection_provenance": {"connection_id": "conn-history-only", "connection_revision": 4,
            "kind": "BUILTIN_VIDEO", "transport": "BRIDGE", "bridge_id": None,
            "instance_id": None, "certificate_sha256": None},
        "artifact_id": "artifact-history-only", "display_name": "synthetic-video.mp4",
        "media_type": "video/mp4", "created_at_ms": NOW - 500,
    }
    video_linked = {"item_id": video1, "note_item_id": note2, "source_state": "linked_note",
        "source_note_id": source_note_2, "source_revision_ms": NOW - 1000,
        "source_revision_precision_ms": 1, "byte_length": len(MP4), "sha256": hashlib.sha256(MP4).hexdigest(),
        "resource_id": videores1, **video_common}
    video_deleted = {"item_id": video2, "note_item_id": None, "source_state": "source_deleted",
        "source_note_id": "deleted-source", "source_revision_ms": (NOW // 1000 - 10) * 1000,
        "source_revision_precision_ms": 1000, "byte_length": len(MP4 + b"source-deleted"),
        "sha256": hashlib.sha256(MP4 + b"source-deleted").hexdigest(), "resource_id": videores2,
        **{**video_common, "task_id": None, "remote_task_id": None, "artifact_id": None}}
    manifest = {
        "format": "com.padnote.library-archive", "format_version": 1, "created_at_ms": NOW,
        "producer": {"platform": "ios", "app_version": "fixture-1"},
        "scope": {"notes": "all-selected", "attached_pdfs": True, "assigned_covers": True,
            "vault_entries": True, "user_cover_presets": True, "video_attachments": True,
            "credentials": False, "connections": False, "task_history": False, "in_flight_work": False},
        "notes": [
            {"item_id": note1, "source_note_id": source_note_1, "source_revision_ms": NOW,
             "note_schema_version": 8, "note_resource_id": note1doc, "pdf_resource_id": None,
             "cover_resource_id": coverid, "vault_entry_ids": [vault1], "video_attachment_ids": []},
            {"item_id": note2, "source_note_id": source_note_2, "source_revision_ms": NOW,
             "note_schema_version": 8, "note_resource_id": note2doc, "pdf_resource_id": pdfid,
             "cover_resource_id": None, "vault_entry_ids": [], "video_attachment_ids": [video1]},
        ],
        "vault_entries": [
            {"item_id": vault1, "note_item_id": note1, "source_state": "linked_note", "source_note_id": source_note_1,
             "source_revision_ms": NOW - 2000, "created_at_ms": NOW - 1000, "resource_id": vaultres1},
            {"item_id": vault2, "note_item_id": None, "source_state": "source_deleted", "source_note_id": "deleted-source",
             "source_revision_ms": NOW - 3000, "created_at_ms": NOW - 1000, "resource_id": vaultres2},
        ],
        "video_attachments": [video_linked, video_deleted],
        "cover_presets": [{"item_id": "c-" + "1" * 32, "display_name": "合成预设", "resource_id": presetres}],
        "resources": resource_rows,
    }
    return manifest, resources_data


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--output", required=True, type=Path)
    args = ap.parse_args()
    out = args.output
    if out.exists(): raise SystemExit("output-must-be-new")
    out.mkdir(parents=True)
    manifest, payloads = make()
    raw = jbytes(manifest)
    (out / "valid-manifest.json").write_bytes(raw)
    with zipfile.ZipFile(out / "valid-library.zip", "x", compression=zipfile.ZIP_DEFLATED, compresslevel=6) as zf:
        entries = [("manifest.json", raw)] + [(row["member"], payloads[row["resource_id"]][2]) for row in manifest["resources"]]
        for name, data in entries:
            info = zipfile.ZipInfo(name, date_time=(2020, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.create_system = 3
            info.external_attr = (stat.S_IFREG | 0o644) << 16
            zf.writestr(info, data, compress_type=zipfile.ZIP_DEFLATED, compresslevel=6)
    hashes = {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in out.iterdir()}
    (out / "fixture-pins.json").write_text(json.dumps({"schema": 1, "files": hashes}, sort_keys=True, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__": main()
