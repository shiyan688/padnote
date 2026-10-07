#!/usr/bin/env python3
"""Build a deterministic app-decodable backup fixture into a new output directory."""
import argparse
import hashlib
import importlib.util
import json
import struct
import zlib
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]
BASE_GENERATOR = ROOT / "docs/fixtures/library-backup-r1/generate-fixtures.py"
spec = importlib.util.spec_from_file_location("backup_fixture_generator", BASE_GENERATOR)
base = importlib.util.module_from_spec(spec)
spec.loader.exec_module(base)


def make_application():
    manifest, resources = base.make()
    assets = HERE / "source-assets"
    now = manifest["created_at_ms"]
    note_rows = manifest["notes"]
    source_ids = ["fixture-note-ordinary", "fixture-note-pdf"]
    for note, filename, source_id in zip(note_rows, ("note-schema8.json", "note-schema7-pdf.json"), source_ids):
        body = json.loads((assets / filename).read_text(encoding="utf-8"))
        body["id"] = source_id
        body["title"] = "可恢复合成笔记"
        body["updatedAt"] = now
        body["pdfPageCount"] = 1 if filename == "note-schema7-pdf.json" else 0
        note["source_note_id"] = source_id
        note["source_revision_ms"] = now
        note["note_schema_version"] = body["schemaVersion"]
        resource_id = note["note_resource_id"]
        role, media, _ = resources[resource_id]
        resources[resource_id] = (role, media, base.jbytes(body))
    # The PDF-backed sample has a real one-page PDF and matching NoteDocument metadata.
    note_rows[1]["pdf_resource_id"] = next(
        row["resource_id"] for row in manifest["resources"] if row["role"] == "pdf_original")
    pdf_id = note_rows[1]["pdf_resource_id"]
    role, media, _ = resources[pdf_id]
    resources[pdf_id] = (role, media, (assets / "one-page.pdf").read_bytes())

    # Keep the linked Vault payload and descriptor tied to the updated note source ID.
    linked_vault = next(row for row in manifest["vault_entries"] if row["source_state"] == "linked_note")
    linked_vault["source_note_id"] = source_ids[0]
    vault_id = linked_vault["resource_id"]
    role, media, payload = resources[vault_id]
    vault_body = json.loads(payload)
    vault_body["source_note_id"] = source_ids[0]
    resources[vault_id] = (role, media, base.jbytes(vault_body))

    mp4 = (assets / "synthetic-r25-video.mp4").read_bytes()
    for row in manifest["resources"]:
        if row["role"] == "video_attachment_mp4":
            role, media, _ = resources[row["resource_id"]]
            resources[row["resource_id"]] = (role, media, mp4)
    for row in manifest["video_attachments"]:
        row["byte_length"] = len(mp4)
        row["sha256"] = hashlib.sha256(mp4).hexdigest()
        # The linked video points to the PDF sample; preserve the independent deleted-source row.
        if row["source_state"] == "linked_note":
            row["source_note_id"] = source_ids[1]

    for row in manifest["resources"]:
        data = resources[row["resource_id"]][2]
        row["byte_length"] = len(data)
        row["sha256"] = hashlib.sha256(data).hexdigest()
    return manifest, resources


def ios_stored_descriptor_zip(entries):
    """Mirror the iOS writer's UTF-8 + stored + signed data-descriptor format."""
    local, central, offset = [], [], 0
    for name, data in entries:
        encoded = name.encode("utf-8")
        crc = zlib.crc32(data) & 0xFFFFFFFF
        header = struct.pack("<IHHHHHIIIHH", 0x04034B50, 20, 0x808, 0, 0, 0, 0, 0, 0,
                             len(encoded), 0)
        local.append(header + encoded + data + struct.pack("<IIII", 0x08074B50, crc, len(data), len(data)))
        central.append(struct.pack("<IHHHHHHIIIHHHHHII", 0x02014B50, 20, 20, 0x808, 0, 0, 0,
                                   crc, len(data), len(data), len(encoded), 0, 0, 0, 0, 0, offset) + encoded)
        offset += len(local[-1])
    central_data = b"".join(central)
    end = struct.pack("<IHHHHIIH", 0x06054B50, 0, 0, len(entries), len(entries), len(central_data), offset, 0)
    return b"".join(local) + central_data + end


def write(output):
    if output.exists():
        raise SystemExit("output-must-be-new")
    output.mkdir(parents=True)
    manifest, resources = make_application()
    raw_manifest = base.jbytes(manifest)
    (output / "valid-manifest.json").write_bytes(raw_manifest)
    entries = [("manifest.json", raw_manifest)]
    entries.extend((row["member"], resources[row["resource_id"]][2]) for row in manifest["resources"])
    (output / "valid-library.zip").write_bytes(ios_stored_descriptor_zip(entries))
    pins = {p.name: hashlib.sha256(p.read_bytes()).hexdigest()
            for p in (output / "valid-manifest.json", output / "valid-library.zip")}
    (output / "fixture-pins.json").write_text(json.dumps({"schema": 1, "files": pins}, sort_keys=True, indent=2) + "\n", encoding="utf-8")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", required=True, type=Path)
    write(parser.parse_args().output)


if __name__ == "__main__":
    main()
