#!/usr/bin/env python3
"""Read-only reference validator for PadNote Library Backup r1 ZIP files."""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import re
import stat
import struct
import sys
import unicodedata
import zipfile
import zlib
from pathlib import Path

# Contract limit: floor(1.1 GiB) in bytes (1 GiB = 2^30 bytes).
MAX_ARCHIVE = 1_181_116_006
MAX_MANIFEST = 16 * 1024 * 1024
MAX_RESOURCES = 10_000
MAX_TOTAL = 1024 * 1024 * 1024
MAX_BY_ROLE = {
    "note_document": 50 * 1024 * 1024,
    "pdf_original": 100 * 1024 * 1024,
    "assigned_cover_png": 8 * 1024 * 1024,
    "user_cover_preset_png": 8 * 1024 * 1024,
    "vault_entry_json": 16 * 1024 * 1024,
    "video_attachment_mp4": 100 * 1024 * 1024,
}
MEDIA_BY_ROLE = {
    "note_document": "application/json", "pdf_original": "application/pdf",
    "assigned_cover_png": "image/png", "user_cover_preset_png": "image/png",
    "vault_entry_json": "application/json", "video_attachment_mp4": "video/mp4",
}
ROLES = set(MAX_BY_ROLE)
ITEM_RE = {prefix: re.compile(r"^" + prefix + r"-[0-9a-f]{32}$") for prefix in "ivac"}
RESOURCE_RE = re.compile(r"^r-[0-9a-f]{32}$")
HEX_RE = re.compile(r"^[0-9a-f]{64}$")
ALLOWED_ZIP_FLAGS = 0x800 | 0x8 | 0x2 | 0x4


class ArchiveError(ValueError):
    """A stable validation failure; messages are fixed and contain no file data."""


def fail(code: str):
    raise ArchiveError(code)


def exact(obj, keys, code="MANIFEST_FIELDS"):
    if not isinstance(obj, dict) or set(obj) != set(keys):
        fail(code)


def object_pairs(pairs):
    out = {}
    for key, value in pairs:
        if key in out:
            fail("JSON_DUPLICATE_KEY")
        out[key] = value
    return out


def json_load(data: bytes):
    try:
        return json.loads(data.decode("utf-8"), object_pairs_hook=object_pairs,
                          parse_constant=lambda _: fail("JSON_CONSTANT"))
    except ArchiveError:
        raise
    except (UnicodeError, ValueError, RecursionError):
        fail("JSON_INVALID")


def integer(value, minimum=0, maximum=(1 << 63) - 1):
    return type(value) is int and minimum <= value <= maximum


def safe_member(path):
    if not isinstance(path, str) or not path or "\\" in path or "\x00" in path or ":" in path:
        return False
    if path.startswith("/") or path.endswith("/"):
        return False
    parts = path.split("/")
    if any(p in ("", ".", "..") for p in parts):
        return False
    if any(p.endswith((".", " ")) or any(unicodedata.category(c).startswith("C") for c in p) for p in parts):
        return False
    if any(re.match(r"(?i)^(con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\.|$)", p) for p in parts):
        return False
    return True


def checked_id(value, prefix):
    if not isinstance(value, str) or not ITEM_RE[prefix].fullmatch(value):
        fail("ITEM_ID_INVALID")
    return value


def checked_resource(value):
    if not isinstance(value, str) or not RESOURCE_RE.fullmatch(value):
        fail("RESOURCE_ID_INVALID")
    return value


def checked_text(value, limit=4096):
    if not isinstance(value, str) or not value or len(value.encode("utf-8")) > limit:
        fail("TEXT_INVALID")
    return value


def validate_scope(scope):
    keys = ("notes", "attached_pdfs", "assigned_covers", "vault_entries", "user_cover_presets",
            "video_attachments", "credentials", "connections", "task_history", "in_flight_work")
    exact(scope, keys, "SCOPE_FIELDS")
    if scope["notes"] != "all-selected": fail("SCOPE_NOT_SUPPORTED")
    for key in keys[1:]:
        if type(scope[key]) is not bool: fail("SCOPE_TYPE")
    if any(scope[k] for k in ("credentials", "connections", "task_history", "in_flight_work")):
        fail("FORBIDDEN_SCOPE")


def validate_manifest(m):
    exact(m, ("format", "format_version", "created_at_ms", "producer", "scope", "notes",
              "vault_entries", "video_attachments", "cover_presets", "resources"))
    if m["format"] != "com.padnote.library-archive" or type(m["format_version"]) is not int or m["format_version"] != 1:
        fail("FORMAT_UNSUPPORTED")
    if not integer(m["created_at_ms"]): fail("TIME_INVALID")
    exact(m["producer"], ("platform", "app_version"), "PRODUCER_FIELDS")
    if m["producer"]["platform"] not in ("android", "ios"): fail("PRODUCER_PLATFORM")
    checked_text(m["producer"]["app_version"], 128)
    validate_scope(m["scope"])
    arrays = ("notes", "vault_entries", "video_attachments", "cover_presets", "resources")
    if any(not isinstance(m[k], list) for k in arrays): fail("ARRAY_INVALID")
    resources = m["resources"]
    if len(resources) > MAX_RESOURCES: fail("RESOURCE_COUNT_LIMIT")
    resource_map, member_map = {}, {}
    for item in resources:
        exact(item, ("resource_id", "role", "media_type", "byte_length", "sha256", "member"), "RESOURCE_FIELDS")
        rid = checked_resource(item["resource_id"])
        if rid in resource_map: fail("RESOURCE_ID_DUPLICATE")
        role = item["role"]
        if role not in ROLES: fail("RESOURCE_ROLE")
        if item["media_type"] != MEDIA_BY_ROLE[role]: fail("RESOURCE_MEDIA_TYPE")
        if not integer(item["byte_length"], 0, MAX_BY_ROLE[role]): fail("RESOURCE_LENGTH")
        if not isinstance(item["sha256"], str) or not HEX_RE.fullmatch(item["sha256"]): fail("RESOURCE_SHA")
        member = item["member"]
        if not safe_member(member) or member != f"payload/{rid}.bin": fail("RESOURCE_MEMBER")
        key = member.casefold()
        if key in member_map: fail("MEMBER_DUPLICATE")
        member_map[key] = rid
        resource_map[rid] = item

    used = {}
    items = {}
    def use(rid, role):
        rid = checked_resource(rid)
        r = resource_map.get(rid)
        if r is None or r["role"] != role: fail("RESOURCE_REFERENCE")
        used[rid] = used.get(rid, 0) + 1
        if used[rid] != 1: fail("RESOURCE_MULTIPLE_REFERENCE")

    for note in m["notes"]:
        exact(note, ("item_id", "source_note_id", "source_revision_ms", "note_schema_version",
                     "note_resource_id", "pdf_resource_id", "cover_resource_id", "vault_entry_ids",
                     "video_attachment_ids"), "NOTE_FIELDS")
        iid = checked_id(note["item_id"], "i")
        if iid in items: fail("ITEM_ID_DUPLICATE")
        items[iid] = note
        checked_text(note["source_note_id"])
        if not integer(note["source_revision_ms"]): fail("TIME_INVALID")
        if type(note["note_schema_version"]) is not int or not 1 <= note["note_schema_version"] <= 8: fail("NOTE_SCHEMA")
        use(note["note_resource_id"], "note_document")
        if note["pdf_resource_id"] is not None: use(note["pdf_resource_id"], "pdf_original")
        if note["cover_resource_id"] is not None: use(note["cover_resource_id"], "assigned_cover_png")
        for key, prefix in (("vault_entry_ids", "v"), ("video_attachment_ids", "a")):
            if not isinstance(note[key], list) or len(set(note[key])) != len(note[key]): fail("NOTE_REFERENCE_LIST")
            for item_id in note[key]: checked_id(item_id, prefix)

    vault_ids, video_ids = set(), set()
    vault_by_resource = {}
    for item in m["vault_entries"]:
        exact(item, ("item_id", "note_item_id", "source_state", "source_note_id", "source_revision_ms",
                     "created_at_ms", "resource_id"), "VAULT_FIELDS")
        iid = checked_id(item["item_id"], "v")
        if iid in items or iid in vault_ids: fail("ITEM_ID_DUPLICATE")
        vault_ids.add(iid)
        # Vault source identifiers describe the historical origin of the entry.
        # Its current note_item_id is only the restored association and may point
        # at a copied note with a different source identifier.
        validate_item_source(item, items, require_source_match=False)
        checked_text(item["source_note_id"])
        if not integer(item["source_revision_ms"]) or not integer(item["created_at_ms"]): fail("TIME_INVALID")
        use(item["resource_id"], "vault_entry_json")
        vault_by_resource[item["resource_id"]] = item
    for item in m["video_attachments"]:
        exact(item, ("item_id", "note_item_id", "source_state", "origin_kind", "source_note_id",
                     "source_revision_ms", "source_revision_precision_ms", "source_bundle_sha256",
                     "task_payload_sha256", "digest_kind", "offline_state", "task_id", "remote_task_id",
                     "connection_provenance", "artifact_id", "display_name", "media_type", "byte_length",
                     "sha256", "created_at_ms", "resource_id"), "VIDEO_FIELDS")
        iid = checked_id(item["item_id"], "a")
        if iid in items or iid in vault_ids or iid in video_ids: fail("ITEM_ID_DUPLICATE")
        video_ids.add(iid)
        if item["origin_kind"] not in ("computer_task", "restored_archive"): fail("VIDEO_ORIGIN")
        # A computer-task video was produced from the currently linked note;
        # an archived video keeps its historical source identity after restore.
        validate_item_source(item, items, require_source_match=item["origin_kind"] == "computer_task")
        if not integer(item["source_revision_ms"]): fail("TIME_INVALID")
        precision = item["source_revision_precision_ms"]
        if type(precision) is not int or precision not in (1, 1000): fail("VIDEO_PRECISION")
        if precision == 1000 and item["source_revision_ms"] % 1000: fail("VIDEO_PRECISION_ALIGNMENT")
        for key in ("source_bundle_sha256",):
            if not isinstance(item[key], str) or not HEX_RE.fullmatch(item[key]): fail("VIDEO_DIGEST")
        if item["task_payload_sha256"] is not None and (not isinstance(item["task_payload_sha256"], str) or not HEX_RE.fullmatch(item["task_payload_sha256"])): fail("VIDEO_DIGEST")
        if item["digest_kind"] not in ("source_snapshot_only", "source_and_task_payload"): fail("VIDEO_DIGEST_KIND")
        if item["digest_kind"] == "source_snapshot_only" and item["task_payload_sha256"] is not None: fail("VIDEO_DIGEST_KIND")
        if item["digest_kind"] == "source_and_task_payload" and item["task_payload_sha256"] is None: fail("VIDEO_DIGEST_KIND")
        if item["offline_state"] != "verified_local_copy": fail("VIDEO_NOT_LOCAL")
        for key in ("task_id", "remote_task_id", "artifact_id"):
            if item[key] is not None: checked_text(item[key], 512)
        checked_text(item["display_name"], 1024)
        if item["media_type"] != "video/mp4" or not integer(item["byte_length"], 0, MAX_BY_ROLE["video_attachment_mp4"]): fail("VIDEO_MEDIA")
        if not isinstance(item["sha256"], str) or not HEX_RE.fullmatch(item["sha256"]): fail("VIDEO_SHA")
        if not integer(item["created_at_ms"]): fail("TIME_INVALID")
        cp = item["connection_provenance"]
        exact(cp, ("connection_id", "connection_revision", "kind", "transport", "bridge_id", "instance_id", "certificate_sha256"), "CONNECTION_PROVENANCE_FIELDS")
        for key in ("connection_id", "kind", "transport", "bridge_id", "instance_id"):
            if cp[key] is not None: checked_text(cp[key], 512)
        if cp["connection_revision"] is not None and not integer(cp["connection_revision"], 0, (1 << 63)-1): fail("CONNECTION_REVISION")
        if cp["certificate_sha256"] is not None and (not isinstance(cp["certificate_sha256"], str) or not HEX_RE.fullmatch(cp["certificate_sha256"])): fail("CERTIFICATE_SHA")
        use(item["resource_id"], "video_attachment_mp4")
        r = resource_map[item["resource_id"]]
        if (item["byte_length"], item["sha256"], item["media_type"]) != (r["byte_length"], r["sha256"], r["media_type"]): fail("VIDEO_RESOURCE_MISMATCH")

    preset_ids = set()
    for item in m["cover_presets"]:
        exact(item, ("item_id", "display_name", "resource_id"), "PRESET_FIELDS")
        iid = checked_id(item["item_id"], "c")
        if iid in items or iid in vault_ids or iid in video_ids or iid in preset_ids: fail("ITEM_ID_DUPLICATE")
        preset_ids.add(iid)
        checked_text(item["display_name"], 1024)
        use(item["resource_id"], "user_cover_preset_png")

    for iid, note in items.items():
        if set(note["vault_entry_ids"]) != {v["item_id"] for v in m["vault_entries"] if v["note_item_id"] == iid}:
            fail("VAULT_REFERENCE_MISMATCH")
        if set(note["video_attachment_ids"]) != {v["item_id"] for v in m["video_attachments"] if v["note_item_id"] == iid}:
            fail("VIDEO_REFERENCE_MISMATCH")
    if set(used) != set(resource_map): fail("RESOURCE_UNREFERENCED")
    return resource_map


def validate_item_source(item, notes, *, require_source_match=False):
    state, note_id = item["source_state"], item["note_item_id"]
    if state not in ("linked_note", "source_deleted", "source_not_selected", "independent"): fail("SOURCE_STATE")
    if state == "linked_note":
        if not isinstance(note_id, str) or note_id not in notes: fail("SOURCE_NOTE_REFERENCE")
        if require_source_match and item.get("source_note_id") != notes[note_id].get("source_note_id"):
            fail("SOURCE_NOTE_ID_MISMATCH")
    elif note_id is not None:
        fail("INDEPENDENT_ITEM_HAS_NOTE")


def check_zip_structure(path: Path, zf: zipfile.ZipFile):
    if path.stat().st_size > MAX_ARCHIVE: fail("ARCHIVE_SIZE_LIMIT")
    infos = zf.infolist()
    if not infos or len(infos) > MAX_RESOURCES + 1 or infos[0].header_offset != 0: fail("ZIP_PREFIX_OR_EMPTY")
    raw_names = [x.orig_filename for x in infos]
    if len(raw_names) != len(set(raw_names)): fail("ZIP_DUPLICATE_MEMBER")
    folded = [n.casefold() for n in raw_names]
    if len(folded) != len(set(folded)): fail("ZIP_CASEFOLD_DUPLICATE")
    spans = []
    with path.open("rb") as stream:
      for info in infos:
        if info.orig_filename != info.filename or not safe_member(info.orig_filename): fail("ZIP_MEMBER_PATH")
        if info.is_dir(): fail("ZIP_DIRECTORY_MEMBER")
        if info.flag_bits & 1 or info.flag_bits & ~ALLOWED_ZIP_FLAGS: fail("ZIP_FLAGS")
        if info.compress_type not in (zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED): fail("ZIP_COMPRESSION")
        if info.compress_type == zipfile.ZIP_STORED and info.flag_bits & 0x6: fail("ZIP_FLAGS")
        if not (info.flag_bits & 0x800) and any(ord(ch) > 127 for ch in info.orig_filename): fail("ZIP_MEMBER_ENCODING")
        if info.extract_version >= 45 or info.file_size == 0xFFFFFFFF or info.compress_size == 0xFFFFFFFF: fail("ZIP64_REJECTED")
        if info.volume != 0: fail("ZIP_MULTIDISK")
        mode = (info.external_attr >> 16) & 0xFFFF
        kind = stat.S_IFMT(mode)
        if kind not in (0, stat.S_IFREG): fail("ZIP_SPECIAL_FILE")
        extra = info.extra
        offset = 0
        while offset + 4 <= len(extra):
            tag, length = struct.unpack_from("<HH", extra, offset)
            offset += 4
            if offset + length > len(extra): fail("ZIP_EXTRA_INVALID")
            if tag == 0x0001: fail("ZIP64_REJECTED")
            offset += length
        if offset != len(extra): fail("ZIP_EXTRA_INVALID")
        stream.seek(info.header_offset)
        local = stream.read(30)
        if len(local) != 30: fail("ZIP_LOCAL_HEADER")
        sig, _ver, local_flags, local_method, _time, _date, local_crc, local_comp, local_size, nlen, elen = struct.unpack("<IHHHHHIIIHH", local)
        if sig != 0x04034B50 or local_flags != info.flag_bits or local_method != info.compress_type:
            fail("ZIP_LOCAL_HEADER")
        local_name, local_extra = stream.read(nlen), stream.read(elen)
        try: decoded_name = local_name.decode("utf-8" if info.flag_bits & 0x800 else "cp437")
        except UnicodeError: fail("ZIP_MEMBER_ENCODING")
        if decoded_name != info.orig_filename: fail("ZIP_LOCAL_NAME")
        x = 0
        while x + 4 <= len(local_extra):
            tag, length = struct.unpack_from("<HH", local_extra, x); x += 4
            if x + length > len(local_extra): fail("ZIP_EXTRA_INVALID")
            if tag == 1: fail("ZIP64_REJECTED")
            x += length
        if x != len(local_extra): fail("ZIP_EXTRA_INVALID")
        data_start = info.header_offset + 30 + nlen + elen
        data_end = data_start + info.compress_size
        if not (info.flag_bits & 8) and (local_crc, local_comp, local_size) != (info.CRC, info.compress_size, info.file_size):
            fail("ZIP_LOCAL_SIZE_MISMATCH")
        span_end = data_end
        if info.flag_bits & 8:
            stream.seek(data_end); descriptor = stream.read(16)
            if len(descriptor) < 12: fail("ZIP_DESCRIPTOR_TRUNCATED")
            signed = len(descriptor) >= 16 and descriptor[:4] == b"PK\x07\x08" and struct.unpack_from("<III", descriptor, 4) == (info.CRC, info.compress_size, info.file_size)
            if signed:
                dcrc, dcomp, dsize = struct.unpack_from("<III", descriptor, 4); span_end += 16
            else:
                dcrc, dcomp, dsize = struct.unpack_from("<III", descriptor, 0); span_end += 12
            if (dcrc, dcomp, dsize) != (info.CRC, info.compress_size, info.file_size): fail("ZIP_DESCRIPTOR_MISMATCH")
        spans.append((info.header_offset, span_end))
    spans.sort()
    cursor = 0
    for start, end in spans:
        if start != cursor or end < start: fail("ZIP_GAP_OR_OVERLAP")
        cursor = end
    if cursor != zf.start_dir: fail("ZIP_GAP_OR_OVERLAP")
    # Locate EOCD and require it, including its comment, to end at the file boundary.
    with path.open("rb") as stream:
        stream.seek(max(0, path.stat().st_size - (65535 + 22)))
        tail = stream.read()
    positions, signatures, search = [], 0, 0
    while True:
        pos = tail.find(b"PK\x05\x06", search)
        if pos < 0: break
        signatures += 1
        if pos + 22 <= len(tail):
            comment_len = struct.unpack_from("<H", tail, pos + 20)[0]
            if pos + 22 + comment_len == len(tail): positions.append(pos)
        search = pos + 1
    if not positions: fail("ZIP_TRAILING_BYTES" if signatures else "ZIP_EOCD_MISSING")
    pos = positions[-1]
    if b"PK\x06\x06" in tail or b"PK\x06\x07" in tail: fail("ZIP64_REJECTED")
    disk, cd_disk, entries_disk, entries_total = struct.unpack_from("<HHHH", tail, pos + 4)
    if disk or cd_disk or entries_disk != entries_total or entries_total != len(infos): fail("ZIP_MULTIDISK")
    cd_size, cd_offset = struct.unpack_from("<II", tail, pos + 12)
    absolute_eocd = path.stat().st_size - len(tail) + pos
    if cd_offset != zf.start_dir or cd_offset + cd_size != absolute_eocd: fail("ZIP_CENTRAL_DIRECTORY")


def raw_member_blocks(path: Path, info: zipfile.ZipInfo, expanded_limit: int):
    """Read exactly the declared member data and reject incomplete/trailing deflate streams."""
    with path.open("rb") as source:
        source.seek(info.header_offset)
        local = source.read(30)
        if len(local) != 30: fail("ZIP_LOCAL_HEADER")
        _sig, _version, _flags, _method, _time, _date, _crc, _csize, _usize, name_len, extra_len = struct.unpack(
            "<IHHHHHIIIHH", local)
        source.seek(info.header_offset + 30 + name_len + extra_len)
        remaining = info.compress_size
        expanded = 0
        if info.compress_type == zipfile.ZIP_STORED:
            if info.compress_size != info.file_size: fail("STORED_SIZE_MISMATCH")
            while remaining:
                block = source.read(min(1024 * 1024, remaining))
                if not block: fail("ZIP_MEMBER_TRUNCATED")
                remaining -= len(block)
                expanded += len(block)
                if expanded > expanded_limit: fail("RESOURCE_EXPANSION_LIMIT")
                yield block
            return

        decoder = zlib.decompressobj(-15)
        while remaining:
            compressed = source.read(min(64 * 1024, remaining))
            if not compressed: fail("ZIP_MEMBER_TRUNCATED")
            remaining -= len(compressed)
            try:
                block = decoder.decompress(compressed, expanded_limit + 1 - expanded)
            except zlib.error:
                fail("DEFLATE_INVALID")
            expanded += len(block)
            if expanded > expanded_limit: fail("RESOURCE_EXPANSION_LIMIT")
            if decoder.unused_data: fail("DEFLATE_TRAILING_DATA")
            if decoder.unconsumed_tail: fail("RESOURCE_EXPANSION_LIMIT")
            if block: yield block
        if not decoder.eof: fail("DEFLATE_TRUNCATED")
        if decoder.unused_data or decoder.unconsumed_tail: fail("DEFLATE_TRAILING_DATA")
        try:
            tail = decoder.flush(expanded_limit + 1 - expanded)
        except zlib.error:
            fail("DEFLATE_INVALID")
        expanded += len(tail)
        if expanded > expanded_limit: fail("RESOURCE_EXPANSION_LIMIT")
        if tail: yield tail


def read_verified_member(path: Path, info: zipfile.ZipInfo, limit: int) -> bytes:
    chunks, size, crc = [], 0, 0
    for block in raw_member_blocks(path, info, limit):
        size += len(block)
        if size > limit: fail("RESOURCE_EXPANSION_LIMIT")
        crc = zlib.crc32(block, crc)
        chunks.append(block)
    if size != info.file_size: fail("RESOURCE_LENGTH_MISMATCH")
    if (crc & 0xFFFFFFFF) != info.CRC: fail("ZIP_CRC_MISMATCH")
    return b"".join(chunks)


def validate_note_payload(note, payload):
    if not isinstance(payload, dict): fail("NOTE_DOCUMENT_INVALID")
    version = payload.get("schemaVersion")
    if type(version) is not int or version != note["note_schema_version"]: fail("NOTE_SCHEMA_MISMATCH")
    if payload.get("id") != note["source_note_id"]: fail("NOTE_SOURCE_ID_MISMATCH")
    updated = payload.get("updatedAt")
    if isinstance(updated, bool) or not isinstance(updated, (int, float)) or not math.isfinite(updated) or updated < 0:
        fail("NOTE_UPDATED_AT_INVALID")
    if math.floor(updated) != note["source_revision_ms"]: fail("NOTE_REVISION_MISMATCH")
    if not isinstance(payload.get("strokes"), list): fail("NOTE_STROKES_MISSING")
    if version >= 5 and not isinstance(payload.get("textFlows"), list): fail("NOTE_TEXT_FLOWS_MISSING")
    if 3 <= version <= 4 and not isinstance(payload.get("textBoxes"), list): fail("NOTE_TEXT_BOXES_MISSING")
    pdf_pages = payload.get("pdfPageCount", 0)
    page_count = payload.get("pageCount", 1)
    if type(page_count) is not int or not 1 <= page_count <= 500: fail("NOTE_PAGE_COUNT_INVALID")
    if type(pdf_pages) is not int or not 0 <= pdf_pages <= page_count: fail("NOTE_PDF_PAGE_COUNT_INVALID")
    if note["pdf_resource_id"] is None and pdf_pages != 0: fail("NOTE_PDF_REFERENCE_MISMATCH")
    if note["pdf_resource_id"] is not None and pdf_pages < 1: fail("NOTE_PDF_REFERENCE_MISMATCH")


def validate_archive(path: Path):
    path = Path(path)
    try:
        leaf = path.lstat()
    except OSError:
        fail("ARCHIVE_UNAVAILABLE")
    if stat.S_ISLNK(leaf.st_mode) or getattr(leaf, "st_file_attributes", 0) & 0x400:
        fail("ARCHIVE_LINK_PATH")
    try:
        absolute = path.resolve(strict=True)
    except OSError:
        fail("ARCHIVE_UNAVAILABLE")
    cur = Path(absolute.anchor)
    for part in absolute.parts[1:]:
        cur /= part
        info = cur.lstat()
        if stat.S_ISLNK(info.st_mode) or getattr(info, "st_file_attributes", 0) & 0x400:
            fail("ARCHIVE_LINK_PATH")
        if cur != absolute and not stat.S_ISDIR(info.st_mode): fail("ARCHIVE_PARENT_NOT_DIRECTORY")
    info = absolute.lstat()
    if not stat.S_ISREG(info.st_mode) or info.st_nlink != 1: fail("ARCHIVE_NOT_SINGLE_LINK_FILE")
    if info.st_size > MAX_ARCHIVE: fail("ARCHIVE_SIZE_LIMIT")
    try:
        with zipfile.ZipFile(path, "r") as zf:
            check_zip_structure(path, zf)
            by_name = {info.filename: info for info in zf.infolist()}
            if "manifest.json" not in by_name: fail("MANIFEST_MISSING")
            mi = by_name["manifest.json"]
            if mi.file_size > MAX_MANIFEST: fail("MANIFEST_SIZE_LIMIT")
            manifest = json_load(read_verified_member(path, mi, MAX_MANIFEST))
            resources = validate_manifest(manifest)
            vault_by_resource = {entry["resource_id"]: entry for entry in manifest["vault_entries"]}
            expected = {"manifest.json"} | {r["member"] for r in resources.values()}
            if set(by_name) != expected: fail("ZIP_MEMBER_SET")
            total = 0
            note_payloads = {}
            for rid, resource in resources.items():
                info = by_name[resource["member"]]
                if info.file_size != resource["byte_length"]: fail("RESOURCE_LENGTH_MISMATCH")
                total += info.file_size
                if total > MAX_TOTAL: fail("TOTAL_RESOURCE_LIMIT")
                h = hashlib.sha256()
                actual = 0
                crc = 0
                json_chunks = [] if resource["role"] in ("note_document", "vault_entry_json") else None
                for block in raw_member_blocks(path, info, resource["byte_length"]):
                    actual += len(block)
                    if actual > resource["byte_length"] or total - info.file_size + actual > MAX_TOTAL: fail("RESOURCE_EXPANSION_LIMIT")
                    h.update(block)
                    crc = zlib.crc32(block, crc)
                    if json_chunks is not None: json_chunks.append(block)
                if actual != resource["byte_length"]: fail("RESOURCE_LENGTH_MISMATCH")
                if (crc & 0xFFFFFFFF) != info.CRC: fail("ZIP_CRC_MISMATCH")
                if h.hexdigest() != resource["sha256"]: fail("RESOURCE_HASH_MISMATCH")
                if info.filename != f"payload/{rid}.bin": fail("RESOURCE_MEMBER")
                if json_chunks is not None:
                    parsed = json_load(b"".join(json_chunks))
                    if not isinstance(parsed, dict): fail("JSON_RESOURCE_NOT_OBJECT")
                    if resource["role"] == "note_document": note_payloads[rid] = parsed
                else:
                    payload = None
                if resource["role"] in ("pdf_original", "assigned_cover_png", "user_cover_preset_png", "video_attachment_mp4"):
                    with zf.open(info, "r") as media:
                        head = media.read(12)
                    if resource["role"] == "pdf_original" and not head.startswith(b"%PDF-"): fail("PDF_SIGNATURE")
                    if resource["role"] in ("assigned_cover_png", "user_cover_preset_png") and not head.startswith(b"\x89PNG\r\n\x1a\n"): fail("PNG_SIGNATURE")
                    if resource["role"] == "video_attachment_mp4" and (len(head) < 12 or head[4:8] != b"ftyp"): fail("MP4_SIGNATURE")
                if resource["role"] == "vault_entry_json":
                    vault = parsed
                    exact(vault, ("schema_version", "title", "markdown", "source_note_id", "source_revision_ms", "created_at_ms"), "VAULT_PAYLOAD_FIELDS")
                    if type(vault["schema_version"]) is not int or vault["schema_version"] != 1: fail("VAULT_PAYLOAD_SCHEMA")
                    checked_text(vault["title"], 4096)
                    checked_text(vault["markdown"], MAX_BY_ROLE["vault_entry_json"])
                    checked_text(vault["source_note_id"])
                    if not integer(vault["source_revision_ms"]) or not integer(vault["created_at_ms"]): fail("VAULT_PAYLOAD_TIME")
                    descriptor = vault_by_resource.get(rid)
                    if descriptor is None or (vault["source_note_id"], vault["source_revision_ms"], vault["created_at_ms"]) != (descriptor["source_note_id"], descriptor["source_revision_ms"], descriptor["created_at_ms"]):
                        fail("VAULT_DESCRIPTOR_MISMATCH")
            for note in manifest["notes"]:
                validate_note_payload(note, note_payloads.get(note["note_resource_id"]))
            bad = zf.testzip()
            if bad is not None: fail("ZIP_CRC_MISMATCH")
            return {"format": manifest["format"], "format_version": 1,
                    "notes": len(manifest["notes"]), "vault_entries": len(manifest["vault_entries"]),
                    "video_attachments": len(manifest["video_attachments"]),
                    "cover_presets": len(manifest["cover_presets"]), "resources": len(resources),
                    "resource_bytes": total}
    except ArchiveError:
        raise
    except (OSError, zipfile.BadZipFile, RuntimeError, EOFError, zlib.error, struct.error,
            TypeError, KeyError, OverflowError, ValueError, UnicodeError, RecursionError):
        fail("ZIP_INVALID_OR_TRUNCATED")


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("archive", type=Path)
    args = ap.parse_args(argv)
    try:
        summary = validate_archive(args.archive)
    except ArchiveError as exc:
        print(json.dumps({"status": "rejected", "code": str(exc)}, sort_keys=True))
        return 1
    print(json.dumps({"status": "valid", **summary}, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
