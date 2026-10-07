"""Adversarial, synthetic tests for the read-only library archive validator."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import stat
import struct
import tempfile
import unittest
import warnings
import zipfile

ROOT = Path(__file__).resolve().parents[2]
VALIDATOR_PATH = ROOT / "tools/validate-library-backup.py"
GENERATOR_PATH = ROOT / "docs/fixtures/library-backup-r1/generate-fixtures.py"


def load_module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


validator = load_module("library_backup_validator", VALIDATOR_PATH)
generator = load_module("library_backup_fixture_generator", GENERATOR_PATH)


def zip_bytes(entries, *, descriptors=None, flags=None, duplicate=False, zip64=False, symlink=None,
              methods=None, deflate_trailing=None):
    """Minimal ZIP writer used to produce descriptor/flag edge cases reproducibly."""
    descriptors = descriptors or {}
    flags = flags or {}
    methods = methods or {}
    local_parts, central = [], []
    offset = 0
    rows = list(entries)
    if duplicate and rows:
        rows.append(rows[-1])
    for name, data in rows:
        encoded = name.encode("utf-8")
        method = methods.get(name, 0)
        fbits = flags.get(name, 0x800)
        descriptor = descriptors.get(name)
        local_extra = central_extra = b""
        if zip64 and name == "manifest.json":
            local_extra = central_extra = struct.pack("<HHQ", 1, 8, 0)
        crc = __import__("zlib").crc32(data) & 0xFFFFFFFF
        stored_data = data
        if method == zipfile.ZIP_DEFLATED:
            compressor = __import__("zlib").compressobj(wbits=-15)
            stored_data = compressor.compress(data) + compressor.flush()
            if deflate_trailing == name:
                stored_data += b"trailing-compressed-data"
        compressed_size = len(stored_data)
        if descriptor is None:
            local_crc, csize, usize = crc, compressed_size, len(data)
        else:
            fbits |= 8
            local_crc = csize = usize = 0
        header = struct.pack("<IHHHHHIIIHH", 0x04034B50, 20, fbits, method, 0, 33,
                             local_crc, csize, usize, len(encoded), len(local_extra))
        local = header + encoded + local_extra + stored_data
        local_offset = offset
        local_parts.append(local)
        offset += len(local)
        if descriptor is not None:
            dd = (struct.pack("<I", 0x08074B50) if descriptor else b"") + struct.pack("<III", crc, compressed_size, len(data))
            local_parts.append(dd)
            offset += len(dd)
        mode = (stat.S_IFLNK | 0o777) if symlink == name else (stat.S_IFREG | 0o644)
        central.append(struct.pack("<IHHHHHHIIIHHHHHII", 0x02014B50, (3 << 8) | 20, 20,
                                   fbits, method, 0, 33, crc, compressed_size, len(data), len(encoded),
                                   len(central_extra), 0, 0, 0, mode << 16, local_offset)
                       + encoded + central_extra)
    central_bytes = b"".join(central)
    eocd = struct.pack("<IHHHHIIH", 0x06054B50, 0, 0, len(central), len(central),
                       len(central_bytes), offset, 0)
    return b"".join(local_parts) + central_bytes + eocd


class BackupValidatorTests(unittest.TestCase):
    def test_archive_quota_matches_contract_bytes(self):
        self.assertEqual(validator.MAX_ARCHIVE, 1_181_116_006)

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.manifest, self.payload_rows = generator.make()
        self.entries = [("manifest.json", generator.jbytes(self.manifest))]
        self.entries.extend((row["member"], self.payload_rows[row["resource_id"]][2])
                            for row in self.manifest["resources"])

    def tearDown(self):
        self.tmp.cleanup()

    def put(self, name, raw):
        path = self.root / name
        path.write_bytes(raw)
        return path

    def rejected(self, path, expected=None):
        with self.assertRaises(validator.ArchiveError) as caught:
            validator.validate_archive(path)
        if expected:
            self.assertEqual(str(caught.exception), expected)

    def test_canonical_fixture_validates_and_carries_cross_platform_cases(self):
        raw = zip_bytes(self.entries)
        summary = validator.validate_archive(self.put("canonical.zip", raw))
        self.assertEqual(summary["resources"], 9)
        self.assertEqual(summary["notes"], 2)
        self.assertEqual(summary["vault_entries"], 2)
        self.assertEqual(summary["video_attachments"], 2)
        videos = self.manifest["video_attachments"]
        self.assertEqual({x["source_state"] for x in videos}, {"linked_note", "source_deleted"})
        self.assertEqual({x["source_revision_precision_ms"] for x in videos}, {1, 1000})
        self.assertTrue(any(n["pdf_resource_id"] for n in self.manifest["notes"]))
        self.assertTrue(self.manifest["cover_presets"])

    def test_checked_in_zipfile_fixture_uses_standard_flags_zero_stored_deflate(self):
        fixture = ROOT / "docs/fixtures/library-backup-r1/canonical/valid-library.zip"
        manifest_path = ROOT / "docs/fixtures/library-backup-r1/canonical/valid-manifest.json"
        self.assertEqual(hashlib.sha256(fixture.read_bytes()).hexdigest(), "8f319616681acfa50abe840300c3b9040262296e69a7f0b3e693f547a5984cc5")
        self.assertEqual(hashlib.sha256(manifest_path.read_bytes()).hexdigest(), "2d66d913ff7b5d75d5ff5101fe9a27b52b0b5018b5c595b1da8a5064dc674164")
        self.assertEqual(zipfile.ZipFile(fixture).read("manifest.json"), manifest_path.read_bytes())
        summary = validator.validate_archive(fixture)
        self.assertEqual(summary["resources"], 9)
        with zipfile.ZipFile(fixture) as zf:
            self.assertTrue(all(info.flag_bits & 8 == 0 for info in zf.infolist()))
            self.assertIn(zipfile.ZIP_DEFLATED, {info.compress_type for info in zf.infolist()})

    def test_data_descriptor_with_and_without_signature_is_supported(self):
        for signature in (True, False):
            raw = zip_bytes(self.entries, descriptors={self.entries[1][0]: signature})
            with self.subTest(signature=signature):
                result = validator.validate_archive(self.put(f"descriptor-{signature}.zip", raw))
                self.assertEqual(result["resources"], 9)

    def test_manifest_and_payload_data_descriptors_with_both_signature_forms(self):
        manifest_name, payload_name = "manifest.json", self.entries[1][0]
        for manifest_signature in (True, False):
            for payload_signature in (True, False):
                raw = zip_bytes(self.entries, descriptors={
                    manifest_name: manifest_signature,
                    payload_name: payload_signature,
                })
                with self.subTest(manifest_signature=manifest_signature, payload_signature=payload_signature):
                    result = validator.validate_archive(self.put(
                        f"manifest-payload-descriptor-{manifest_signature}-{payload_signature}.zip", raw))
                    self.assertEqual(result["resources"], 9)

    def test_deflate_stream_must_end_at_member_boundary(self):
        payload_name = self.entries[1][0]
        for name, rows in (("manifest.json", self.entries), (payload_name, self.entries)):
            raw = zip_bytes(rows, methods={name: zipfile.ZIP_DEFLATED}, deflate_trailing=name)
            self.rejected(self.put(f"deflate-trailing-{name.replace('/', '-')}.zip", raw), "DEFLATE_TRAILING_DATA")

    def test_note_payload_identity_schema_and_revision_match_manifest(self):
        cases = (
            ("id", "tampered-source", "NOTE_SOURCE_ID_MISMATCH"),
            ("schemaVersion", 7, "NOTE_SCHEMA_MISMATCH"),
            ("updatedAt", 1_700_000_000_000, "NOTE_REVISION_MISMATCH"),
        )
        for field, value, code in cases:
            manifest, payload_rows = generator.make()
            note = manifest["notes"][0]
            rid = note["note_resource_id"]
            role, media, raw = payload_rows[rid]
            body = json.loads(raw)
            body[field] = value
            raw = generator.jbytes(body)
            payload_rows[rid] = (role, media, raw)
            resource = next(item for item in manifest["resources"] if item["resource_id"] == rid)
            resource["byte_length"] = len(raw)
            resource["sha256"] = hashlib.sha256(raw).hexdigest()
            entries = [("manifest.json", generator.jbytes(manifest))]
            entries.extend((item["member"], payload_rows[item["resource_id"]][2]) for item in manifest["resources"])
            self.rejected(self.put(f"note-{field}.zip", zip_bytes(entries)), code)

    def _archive_from_manifest_and_payloads(self, name, manifest, payload_rows):
        entries = [("manifest.json", generator.jbytes(manifest))]
        entries.extend((item["member"], payload_rows[item["resource_id"]][2])
                       for item in manifest["resources"])
        return self.put(name, zip_bytes(entries))

    def test_restored_linked_history_keeps_historical_source_ids(self):
        manifest, payload_rows = generator.make()
        vault_item = manifest["vault_entries"][0]
        vault_item["source_note_id"] = "historical-vault-source"
        role, media, raw = payload_rows[vault_item["resource_id"]]
        payload = json.loads(raw)
        payload["source_note_id"] = "historical-vault-source"
        payload_rows[vault_item["resource_id"]] = (role, media, generator.jbytes(payload))
        resource = next(row for row in manifest["resources"]
                        if row["resource_id"] == vault_item["resource_id"])
        raw = payload_rows[vault_item["resource_id"]][2]
        resource["byte_length"] = len(raw)
        resource["sha256"] = hashlib.sha256(raw).hexdigest()

        video = manifest["video_attachments"][0]
        video["origin_kind"] = "restored_archive"
        video["source_note_id"] = "historical-video-source"
        # Both items remain associated with copied notes through note_item_id
        # and the note's reverse reference arrays.
        result = validator.validate_archive(
            self._archive_from_manifest_and_payloads("restored-history.zip", manifest, payload_rows))
        self.assertEqual((result["notes"], result["vault_entries"], result["video_attachments"]), (2, 2, 2))

    def test_computer_task_video_must_match_current_linked_note_source(self):
        manifest, payload_rows = generator.make()
        manifest["video_attachments"][0]["source_note_id"] = "different-historical-source"
        path = self._archive_from_manifest_and_payloads("computer-task-mismatch.zip", manifest, payload_rows)
        self.rejected(path, "SOURCE_NOTE_ID_MISMATCH")

    def test_vault_descriptor_must_still_match_historical_payload(self):
        manifest, payload_rows = generator.make()
        manifest["vault_entries"][0]["source_note_id"] = "historical-vault-source"
        path = self._archive_from_manifest_and_payloads("vault-payload-mismatch.zip", manifest, payload_rows)
        self.rejected(path, "VAULT_DESCRIPTOR_MISMATCH")

    def test_application_valid_fixture_contains_real_document_pdf_and_playable_video(self):
        fixture = ROOT / "docs/fixtures/library-backup-r1/application-valid/archive/valid-library.zip"
        manifest = ROOT / "docs/fixtures/library-backup-r1/application-valid/archive/valid-manifest.json"
        assets = ROOT / "docs/fixtures/library-backup-r1/application-valid/source-assets"
        self.assertEqual(zipfile.ZipFile(fixture).read("manifest.json"), manifest.read_bytes())
        with zipfile.ZipFile(fixture) as zf:
            self.assertTrue(all(info.flag_bits == 0x808 for info in zf.infolist()))
            doc_by_id = {json.loads(zf.read(row["member"]))["id"]: json.loads(zf.read(row["member"]))
                         for row in json.loads(manifest.read_bytes())["resources"] if row["role"] == "note_document"}
            self.assertEqual({x["schemaVersion"] for x in doc_by_id.values()}, {7, 8})
            self.assertEqual(next(x["pdfPageCount"] for x in doc_by_id.values() if x["pdfPageCount"]), 1)
            pdf_row = next(row for row in json.loads(manifest.read_bytes())["resources"] if row["role"] == "pdf_original")
            self.assertEqual(zf.read(pdf_row["member"]), (assets / "one-page.pdf").read_bytes())
            video_row = next(row for row in json.loads(manifest.read_bytes())["resources"] if row["role"] == "video_attachment_mp4")
            video = zf.read(video_row["member"])
            self.assertEqual(hashlib.sha256(video).hexdigest(), "801cd47ebc1a1eb31c4c398374ec60fb1dc5f8dfe23665d67445a340732e6218")
            self.assertEqual(video, (assets / "synthetic-r25-video.mp4").read_bytes())
        manifest_rows = json.loads(manifest.read_bytes())
        self.assertTrue(any(row["source_state"] == "source_deleted" and row["note_item_id"] is None
                            for row in manifest_rows["video_attachments"]))
        result = validator.validate_archive(fixture)
        self.assertEqual((result["notes"], result["video_attachments"], result["resources"]), (2, 2, 9))

    def test_zip_path_duplicate_casefold_and_extra_members_are_rejected(self):
        self.rejected(self.put("traversal.zip", zip_bytes(self.entries + [("payload/../bad.bin", b"x")])), "ZIP_MEMBER_PATH")
        self.rejected(self.put("duplicate.zip", zip_bytes(self.entries, duplicate=True)), "ZIP_DUPLICATE_MEMBER")
        folded = list(self.entries) + [(self.entries[1][0].upper(), self.entries[1][1])]
        self.rejected(self.put("casefold.zip", zip_bytes(folded)), "ZIP_CASEFOLD_DUPLICATE")
        self.rejected(self.put("extra.zip", zip_bytes(self.entries + [("other.bin", b"x")])), "ZIP_MEMBER_SET")
        for name in ("payload//bad.bin", "payload/./bad.bin", "payload/C:stream.bin", "payload/CON.bin", "payload/bad. "):
            with self.subTest(name=name):
                self.rejected(self.put("badpath-" + str(len(name)) + ".zip", zip_bytes(self.entries + [(name, b"x")])), "ZIP_MEMBER_PATH")

    def test_zip_prefix_trailer_truncation_zip64_encryption_and_link_are_rejected(self):
        valid = zip_bytes(self.entries)
        self.rejected(self.put("prefix.zip", b"MZ" + valid), "ZIP_PREFIX_OR_EMPTY")
        self.rejected(self.put("trailer.zip", valid + b"junk"), "ZIP_TRAILING_BYTES")
        self.rejected(self.put("truncated.zip", valid[:-8]))
        self.rejected(self.put("zip64.zip", zip_bytes(self.entries, zip64=True)), "ZIP64_REJECTED")
        encrypted_flags = {"manifest.json": 0x801}
        self.rejected(self.put("encrypted.zip", zip_bytes(self.entries, flags=encrypted_flags)), "ZIP_FLAGS")
        self.rejected(self.put("symlink.zip", zip_bytes(self.entries, symlink=self.entries[1][0])), "ZIP_SPECIAL_FILE")

    def test_payload_crc_and_manifest_hash_are_checked(self):
        raw = bytearray(zip_bytes(self.entries))
        target = self.entries[1][1]
        offset = raw.find(target)
        self.assertGreaterEqual(offset, 0)
        raw[offset] ^= 1
        self.rejected(self.put("bad-crc.zip", bytes(raw)))

        bad_manifest = json.loads(generator.jbytes(self.manifest))
        bad_manifest["resources"][0]["sha256"] = "0" * 64
        entries = [("manifest.json", generator.jbytes(bad_manifest))] + self.entries[1:]
        self.rejected(self.put("bad-sha.zip", zip_bytes(entries)), "RESOURCE_HASH_MISMATCH")

    def test_dangling_refs_precision_and_duplicate_json_keys_rejected(self):
        bad = json.loads(generator.jbytes(self.manifest))
        bad["notes"][0]["vault_entry_ids"] = ["v-" + "f" * 32]
        self.rejected(self.put("dangling.zip", zip_bytes([("manifest.json", generator.jbytes(bad))] + self.entries[1:])), "VAULT_REFERENCE_MISMATCH")
        bad = json.loads(generator.jbytes(self.manifest))
        bad["video_attachments"][1]["source_revision_precision_ms"] = 1000
        bad["video_attachments"][1]["source_revision_ms"] += 1
        self.rejected(self.put("precision.zip", zip_bytes([("manifest.json", generator.jbytes(bad))] + self.entries[1:])), "VIDEO_PRECISION_ALIGNMENT")
        duplicate_json = b'{"format":"com.padnote.library-archive","format":"again"}'
        self.rejected(self.put("duplicate-json.zip", zip_bytes([("manifest.json", duplicate_json)])), "JSON_DUPLICATE_KEY")

    def test_version_scope_missing_nullable_and_strict_integer_edges(self):
        mutations = (
            (lambda m: m.__setitem__("format_version", 99), "FORMAT_UNSUPPORTED"),
            (lambda m: m["notes"][0].__setitem__("note_schema_version", 9), "NOTE_SCHEMA"),
            (lambda m: m["scope"].__setitem__("credentials", True), "FORBIDDEN_SCOPE"),
            (lambda m: m["vault_entries"][1].pop("note_item_id"), "VAULT_FIELDS"),
            (lambda m: m.__setitem__("created_at_ms", 1.0), "TIME_INVALID"),
            (lambda m: m.__setitem__("created_at_ms", 1e3), "TIME_INVALID"),
            (lambda m: m.__setitem__("created_at_ms", True), "TIME_INVALID"),
            (lambda m: m.__setitem__("created_at_ms", (1 << 63)), "TIME_INVALID"),
            (lambda m: m.__setitem__("created_at_ms", -1), "TIME_INVALID"),
            (lambda m: m["notes"][0].__setitem__("source_revision_ms", 1.0), "TIME_INVALID"),
            (lambda m: m["video_attachments"][0].__setitem__("source_revision_precision_ms", 1.0), "VIDEO_PRECISION"),
        )
        for index, (mutate, code) in enumerate(mutations):
            bad = json.loads(generator.jbytes(self.manifest))
            mutate(bad)
            with self.subTest(index=index):
                self.rejected(self.put(f"strict-{index}.zip", zip_bytes([("manifest.json", generator.jbytes(bad))] + self.entries[1:])), code)

    def test_duplicate_manifest_ids_and_resource_quotas_are_rejected(self):
        duplicate = json.loads(generator.jbytes(self.manifest))
        duplicate["resources"][1]["resource_id"] = duplicate["resources"][0]["resource_id"]
        with self.assertRaises(validator.ArchiveError) as caught:
            validator.validate_manifest(duplicate)
        self.assertEqual(str(caught.exception), "RESOURCE_ID_DUPLICATE")

        duplicate_item = json.loads(generator.jbytes(self.manifest))
        duplicate_item["notes"][1]["item_id"] = duplicate_item["notes"][0]["item_id"]
        with self.assertRaises(validator.ArchiveError) as caught:
            validator.validate_manifest(duplicate_item)
        self.assertEqual(str(caught.exception), "ITEM_ID_DUPLICATE")

        too_many = json.loads(generator.jbytes(self.manifest))
        too_many["resources"] = [{}] * (validator.MAX_RESOURCES + 1)
        with self.assertRaises(validator.ArchiveError) as caught:
            validator.validate_manifest(too_many)
        self.assertEqual(str(caught.exception), "RESOURCE_COUNT_LIMIT")

        too_large = json.loads(generator.jbytes(self.manifest))
        too_large["resources"][0]["byte_length"] = validator.MAX_BY_ROLE["note_document"] + 1
        with self.assertRaises(validator.ArchiveError) as caught:
            validator.validate_manifest(too_large)
        self.assertEqual(str(caught.exception), "RESOURCE_LENGTH")

        old_total, old_archive, old_manifest = validator.MAX_TOTAL, validator.MAX_ARCHIVE, validator.MAX_MANIFEST
        try:
            validator.MAX_TOTAL = 10
            self.rejected(self.put("quota-total.zip", zip_bytes(self.entries)), "TOTAL_RESOURCE_LIMIT")
            validator.MAX_ARCHIVE = 4
            self.rejected(self.put("quota-archive.zip", zip_bytes(self.entries)), "ARCHIVE_SIZE_LIMIT")
            validator.MAX_ARCHIVE = old_archive
            validator.MAX_MANIFEST = 1
            self.rejected(self.put("quota-manifest.zip", zip_bytes(self.entries)), "MANIFEST_SIZE_LIMIT")
        finally:
            validator.MAX_TOTAL, validator.MAX_ARCHIVE, validator.MAX_MANIFEST = old_total, old_archive, old_manifest

    def test_unknown_compression_method_is_rejected_before_decompression(self):
        raw = zip_bytes(self.entries, methods={"manifest.json": 99})
        self.rejected(self.put("unknown-compression.zip", raw), "ZIP_COMPRESSION")

    def test_archive_symlink_and_hardlink_are_rejected_before_read(self):
        source = self.put("source.zip", zip_bytes(self.entries))
        link = self.root / "symlink.zip"
        try:
            link.symlink_to(source)
            self.rejected(link, "ARCHIVE_LINK_PATH")
        except (OSError, NotImplementedError):
            self.skipTest("symlink creation unavailable")
        hard = self.root / "hardlink.zip"
        try:
            os.link(source, hard)
            self.rejected(hard, "ARCHIVE_NOT_SINGLE_LINK_FILE")
        except OSError:
            self.skipTest("hardlink creation unavailable")


if __name__ == "__main__":
    unittest.main()
