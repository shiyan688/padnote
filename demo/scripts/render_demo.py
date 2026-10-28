#!/usr/bin/env python3
"""Render real screen-recording clips into subtitled PadNote demo videos."""
from __future__ import annotations
import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import textwrap

from PIL import Image, ImageDraw, ImageFont

ROOT = Path.cwd()
LOG_TARGET: Path | None = None
FONT_CANDIDATES = [
    "/System/Library/Fonts/PingFang.ttc",
    "/System/Library/Fonts/STHeiti Medium.ttc",
    "/System/Library/Fonts/Hiragino Sans GB.ttc",
    "C:/Windows/Fonts/msyh.ttc",
    "C:/Windows/Fonts/simhei.ttf",
    "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
    "/usr/share/fonts/truetype/noto/NotoSansCJK-Regular.ttc",
    "/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc",
]


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for block in iter(lambda: f.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()


def inside_root(path: Path) -> bool:
    try:
        path.resolve().relative_to(ROOT.resolve())
        return True
    except ValueError:
        return False


def require_text(value, label: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise ValueError(f"{label} must be a non-empty string")
    return value.strip()


def load_spec(spec_path: Path):
    spec_path = spec_path.resolve()
    spec = json.loads(spec_path.read_text(encoding="utf-8"))
    title = require_text(spec.get("title"), "title")
    tagline = require_text(spec.get("tagline"), "tagline")
    closing_text = require_text(spec.get("closing_text"), "closing_text")
    output = (ROOT / spec.get("output", "")).resolve()
    if not inside_root(output) or output.suffix.lower() != ".mp4":
        raise ValueError("output must be an .mp4 inside this review directory")
    width, height, fps = int(spec.get("width", 1920)), int(spec.get("height", 1080)), int(spec.get("fps", 30))
    if width < 640 or height < 360 or width % 2 or height % 2 or not (24 <= fps <= 60):
        raise ValueError("output dimensions must be even and at least 640x360; fps must be 24..60")
    title_seconds = float(spec.get("title_seconds", 3.0))
    closing_seconds = float(spec.get("closing_seconds", 4.0))
    if not (1 <= title_seconds <= 8) or not (2 <= closing_seconds <= 8):
        raise ValueError("title_seconds must be 1..8 and closing_seconds 2..8")
    scenes = spec.get("scenes")
    if not isinstance(scenes, list) or not scenes:
        raise ValueError("scenes must be a non-empty list")
    normalized = []
    for index, scene in enumerate(scenes, 1):
        rel = Path(require_text(scene.get("clip"), f"scene {index} clip"))
        clip = (ROOT / rel).resolve()
        if not inside_root(clip) or not clip.is_file():
            raise ValueError(f"scene {index}: source clip missing or outside review directory: {rel}")
        start, end = float(scene.get("in", 0)), float(scene.get("out", 0))
        if not math.isfinite(start) or not math.isfinite(end) or start < 0 or end <= start:
            raise ValueError(f"scene {index}: invalid in/out timestamps")
        normalized.append({"clip": clip, "in": start, "out": end,
                           "caption": require_text(scene.get("caption"), f"scene {index} caption"),
                           "label": str(scene.get("label", f"片段 {index}"))[:30]})
    total = title_seconds + closing_seconds + sum(s["out"]-s["in"] for s in normalized)
    if not 30 <= total <= 60:
        raise ValueError(f"final video must be 30..60 seconds; current scene total is {total:.2f}s")
    return {"title": title, "tagline": tagline, "closing_text": closing_text,
            "output": output, "width": width, "height": height, "fps": fps,
            "title_seconds": title_seconds, "closing_seconds": closing_seconds, "scenes": normalized}


def choose_font(requested: str | None):
    choices = [requested] if requested else []
    choices += FONT_CANDIDATES
    for candidate in choices:
        if candidate and Path(candidate).is_file():
            return str(Path(candidate).resolve())
    raise FileNotFoundError("No Chinese-capable font found; pass --font with PingFang/Noto CJK/Source Han font")


def font(path: str, size: int):
    return ImageFont.truetype(path, size=size, index=0)


def wrap_by_width(text: str, face, max_width: int):
    lines = []
    for paragraph in text.splitlines() or [text]:
        current = ""
        for char in paragraph:
            proposal = current + char
            if current and face.getlength(proposal) > max_width:
                lines.append(current)
                current = char
            else:
                current = proposal
        if current:
            lines.append(current)
    return lines


def draw_centered(draw, lines, face, y, width, fill, spacing=12):
    for line in lines:
        box = draw.textbbox((0, 0), line, font=face, stroke_width=0)
        x = (width - (box[2] - box[0])) / 2
        draw.text((x, y), line, font=face, fill=fill)
        y += box[3] - box[1] + spacing
    return y


def make_title(path: Path, title: str, tagline: str, font_path: str, width: int, height: int,
               brand="PADNOTE  /  ANDROID"):
    paper = (244, 241, 232, 255)
    ink = (31, 41, 51, 255)
    blue = (40, 94, 168, 255)
    image = Image.new("RGBA", (width, height), paper)
    draw = ImageDraw.Draw(image)
    margin = int(width * .08)
    # Quiet editorial title card: paper background, ink typography, one blue rule.
    draw.rectangle((margin, int(height*.19), margin+96, int(height*.19)+8), fill=blue)
    draw.text((margin, int(height*.19)+28), brand, font=font(font_path, max(22, height//36)), fill=blue)
    title_size = max(46, height//14)
    while True:
        face = font(font_path, title_size)
        lines = wrap_by_width(title, face, int(width*.84))
        if len(lines) <= 1 or len(lines[-1]) > 1 or title_size <= 40:
            break
        title_size -= 3
    if len(lines) > 3:
        raise ValueError("title is too long for the title card; shorten it")
    y = int(height*.40)
    for line in lines:
        draw.text((margin, y), line, font=face, fill=ink)
        y += draw.textbbox((0, 0), line, font=face)[3] + 16
    subface = font(font_path, max(22, height//37))
    lines = wrap_by_width(tagline, subface, int(width*.84))
    for line in lines:
        draw.text((margin, min(y+24, int(height*.78))), line, font=subface, fill=blue)
        y += draw.textbbox((0, 0), line, font=subface)[3] + 8
    path.parent.mkdir(parents=True, exist_ok=True)
    image.save(path)


def make_overlay(path: Path, scene: dict, font_path: str, width: int, height: int):
    image = Image.new("RGBA", (width, height), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)
    # Footer lives below the fitted, uncropped source frame; it never covers controls.
    panel_h = max(112, round(height * .17))
    panel_y = height - panel_h
    draw.rectangle((0, panel_y, width, height), fill=(244, 241, 232, 255))
    draw.rectangle((0, panel_y, width, max(panel_y+4, panel_y+5)), fill=(40, 94, 168, 255))
    margin = int(width * .055)
    label_face = font(font_path, max(20, height//40))
    draw.text((margin, panel_y+14), scene["label"], font=label_face, fill=(40, 94, 168, 255))
    face = font(font_path, max(28, height//25))
    lines = wrap_by_width(scene["caption"], face, width - 2*margin)
    if len(lines) > 2:
        face = font(font_path, max(26, height//29))
        lines = wrap_by_width(scene["caption"], face, width - 2*margin)
    if len(lines) > 2:
        raise ValueError(f"caption does not fit in the footer: {scene['caption']}")
    y = panel_y + 45
    for line in lines:
        draw.text((margin, y), line, font=face, fill=(31, 41, 51, 255))
        y += draw.textbbox((0, 0), line, font=face)[3] + 4
    path.parent.mkdir(parents=True, exist_ok=True)
    image.save(path)


def input_resolution(ffmpeg: Path, clip: Path) -> str:
    result = subprocess.run([str(ffmpeg), "-hide_banner", "-i", str(clip)], stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, text=True, errors="replace", check=False)
    match = re.search(r"Video:.*?,\s*(\d{3,5})x(\d{3,5})(?:\s|,)", result.stdout, re.IGNORECASE)
    if not match:
        raise RuntimeError(f"could not read real screenrecord dimensions: {clip.name}")
    dimensions = f"{match.group(1)}x{match.group(2)}"
    if dimensions not in {"2800x1840", "1920x1264"}:
        raise RuntimeError(f"unexpected screenrecord dimensions {dimensions}; expected 2800x1840 or 1920x1264")
    return dimensions


def run_capture(args, log_parts, allow_failure=False):
    global LOG_TARGET
    completed = subprocess.run(args, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
                               errors="replace", check=False)
    log_parts.append("$ " + " ".join(map(str, args)) + "\n" + completed.stdout)
    if LOG_TARGET is not None:
        LOG_TARGET.parent.mkdir(parents=True, exist_ok=True)
        LOG_TARGET.write_text("\n".join(log_parts), encoding="utf-8")
    if completed.returncode and not allow_failure:
        raise RuntimeError(f"Command failed ({completed.returncode}): {args[0]} (see encode log)")
    return completed.stdout


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--scenes", required=True, type=Path, help="scene JSON, paths relative to review directory")
    parser.add_argument("--ffmpeg", required=True, type=Path, help="explicit FFmpeg executable; no PATH fallback")
    parser.add_argument("--font", help="Chinese font file; auto-detects installed CJK fonts otherwise")
    parser.add_argument("--workspace", type=Path, default=Path.cwd(), help="private working directory holding recordings/raw; rendered artifacts stay here")
    parser.add_argument("--ffprobe", type=Path, help="optional ffprobe executable for a JSON stream/format summary")
    parser.add_argument("--validate-only", action="store_true", help="check scene/source files without rendering")
    args = parser.parse_args()
    global ROOT
    ROOT = args.workspace.resolve()
    if not ROOT.is_dir():
        raise FileNotFoundError(f"workspace directory does not exist: {ROOT}")
    spec = load_spec(args.scenes)
    font_path = choose_font(args.font)
    ffmpeg = args.ffmpeg.resolve()
    if not ffmpeg.is_file():
        raise FileNotFoundError(f"FFmpeg executable does not exist: {ffmpeg}")
    if args.validate_only:
        total = spec["title_seconds"] + spec["closing_seconds"] + sum(s["out"]-s["in"] for s in spec["scenes"])
        print(f"valid: {len(spec['scenes'])} real footage scenes; {total:.1f}s total; font={font_path}")
        for scene in spec["scenes"]:
            dimensions = input_resolution(ffmpeg, scene["clip"])
            print(f"  {scene['clip'].relative_to(ROOT)} {dimensions} {scene['in']:.3f}-{scene['out']:.3f}s sha256={sha256(scene['clip'])}")
        return 0

    for scene in spec["scenes"]:
        input_resolution(ffmpeg, scene["clip"])
    output = spec["output"]
    output.parent.mkdir(parents=True, exist_ok=True)
    qa_dir = ROOT / "qa" / output.stem
    overlay_dir = qa_dir / "overlays"
    frames_dir = qa_dir / "frames"
    overlay_dir.mkdir(parents=True, exist_ok=True)
    frames_dir.mkdir(parents=True, exist_ok=True)
    title_png = overlay_dir / "00-title-card.png"
    make_title(title_png, spec["title"], spec["tagline"], font_path, spec["width"], spec["height"])
    closing_png = overlay_dir / "99-closing-disclosure.png"
    make_title(closing_png, "演示说明", spec["closing_text"], font_path, spec["width"], spec["height"],
               brand="DEMO NOTE  /  录屏说明")
    overlays = []
    for i, scene in enumerate(spec["scenes"], 1):
        png = overlay_dir / f"scene-{i:02d}.png"
        make_overlay(png, scene, font_path, spec["width"], spec["height"])
        overlays.append(png)

    fps = spec["fps"]
    command = [str(ffmpeg), "-hide_banner", "-y", "-loop", "1", "-framerate", str(fps),
               "-t", str(spec["title_seconds"]), "-i", str(title_png)]
    panel_h = max(112, round(spec["height"] * .17))
    content_h = spec["height"] - panel_h
    paper = "0xF4F1E8"
    filters = [f"[0:v]fps={fps},scale={spec['width']}:{spec['height']},setsar=1,format=yuv420p[v0]"]
    previous_total = spec["title_seconds"]
    for i, (scene, png) in enumerate(zip(spec["scenes"], overlays), 1):
        vid_idx, ov_idx = 2*i-1, 2*i
        duration = scene["out"] - scene["in"]
        command += ["-ss", f"{scene['in']:.6f}", "-i", str(scene["clip"]),
                    "-loop", "1", "-framerate", str(fps), "-t", f"{duration:.6f}", "-i", str(png)]
        filters.append(
            f"[{vid_idx}:v]trim=duration={duration:.6f},setpts=PTS-STARTPTS,fps={fps},"
            f"scale={spec['width']}:{content_h}:force_original_aspect_ratio=decrease,"
            f"pad={spec['width']}:{content_h}:(ow-iw)/2:(oh-ih)/2:color={paper},"
            f"pad={spec['width']}:{spec['height']}:0:0:color={paper},setsar=1[base{i}]"
        )
        filters.append(f"[{ov_idx}:v]format=rgba[ov{i}]")
        filters.append(f"[base{i}][ov{i}]overlay=0:0:shortest=1,format=yuv420p[v{i}]")
        previous_total += duration
    closing_idx = 1 + 2*len(spec["scenes"])
    command += ["-loop", "1", "-framerate", str(fps), "-t", str(spec["closing_seconds"]), "-i", str(closing_png)]
    filters.append(f"[{closing_idx}:v]fps={fps},scale={spec['width']}:{spec['height']},setsar=1,format=yuv420p[vclose]")
    previous_total += spec["closing_seconds"]
    count = len(spec["scenes"]) + 2
    concat_inputs = "".join(f"[v{i}]" for i in range(count-1)) + "[vclose]"
    filters.append(f"{concat_inputs}concat=n={count}:v=1:a=0[outv]")
    command += ["-filter_complex_threads", "2", "-filter_complex", ";".join(filters), "-map", "[outv]", "-an",
                "-c:v", "libx264", "-preset", "medium", "-crf", "19", "-pix_fmt", "yuv420p", "-threads", "2",
                "-r", str(fps), "-movflags", "+faststart", str(output)]

    log_parts = []
    global LOG_TARGET
    LOG_TARGET = ROOT / "metadata" / f"{output.stem}.encode.log"
    version = run_capture([str(ffmpeg), "-version"], log_parts).splitlines()[0]
    run_capture(command, log_parts)
    # Probe through FFmpeg itself; the pinned imageio-ffmpeg runtime need not ship ffprobe.
    probe = run_capture([str(ffmpeg), "-hide_banner", "-i", str(output)], log_parts, allow_failure=True)
    if not re.search(r"Video: h264", probe, re.IGNORECASE) or not re.search(r"yuv420p", probe):
        raise RuntimeError("output codec/pixel format check failed")
    duration_match = re.search(r"Duration: (\d+):(\d+):(\d+(?:\.\d+)?)", probe)
    if not duration_match:
        raise RuntimeError("could not read output duration from FFmpeg probe")
    duration_s = int(duration_match[1])*3600 + int(duration_match[2])*60 + float(duration_match[3])
    expected = previous_total
    if abs(duration_s-expected) > max(0.75, 2/fps):
        raise RuntimeError(f"duration mismatch: expected {expected:.2f}s, got {duration_s:.2f}s")

    scene_midpoints, cursor = [(spec["title_seconds"]/2, "title")], spec["title_seconds"]
    for i, scene in enumerate(spec["scenes"], 1):
        duration = scene["out"] - scene["in"]
        scene_midpoints.append((cursor + duration/2, f"scene-{i:02d}"))
        cursor += duration
    scene_midpoints.append((cursor + spec["closing_seconds"]/2, "closing"))
    log_parts.append("QA frame extraction\n")
    for timestamp, name in scene_midpoints:
        frame = frames_dir / f"{name}.png"
        run_capture([str(ffmpeg), "-hide_banner", "-y", "-ss", f"{timestamp:.4f}", "-i", str(output),
                     "-frames:v", "1", "-update", "1", str(frame)], log_parts)
    # Contact sheet keeps every scene midpoint for quick visual verification.
    thumbs = []
    for frame in sorted(frames_dir.glob("*.png")):
        with Image.open(frame) as im:
            thumbs.append((frame.name, im.convert("RGB").copy()))
    thumb_w, thumb_h = 480, 270
    cols = 2
    rows = (len(thumbs)+cols-1)//cols
    sheet = Image.new("RGB", (cols*thumb_w, rows*(thumb_h+38)), (23, 31, 45))
    draw = ImageDraw.Draw(sheet)
    small = font(font_path, 22)
    for n, (name, im) in enumerate(thumbs):
        im.thumbnail((thumb_w, thumb_h))
        x, y = (n%cols)*thumb_w, (n//cols)*(thumb_h+38)
        sheet.paste(im, (x, y))
        draw.text((x+8, y+thumb_h+4), name, font=small, fill=(240,245,252))
    contact = qa_dir / "contact-sheet.jpg"
    sheet.save(contact, quality=90)
    log_path = LOG_TARGET
    log_path.parent.mkdir(parents=True, exist_ok=True)
    log_path.write_text("\n".join(log_parts), encoding="utf-8")
    # Preserve an editable subtitle timing script alongside the burned-in captions.
    def srt_time(seconds: float) -> str:
        ms = round(seconds * 1000)
        hh, ms = divmod(ms, 3_600_000)
        mm, ms = divmod(ms, 60_000)
        ss, ms = divmod(ms, 1000)
        return f"{hh:02}:{mm:02}:{ss:02},{ms:03}"
    srt_path = ROOT / "metadata" / f"{output.stem}.zh-Hans.srt"
    srt_path.parent.mkdir(parents=True, exist_ok=True)
    cursor = spec["title_seconds"]
    srt_rows = []
    for index, scene in enumerate(spec["scenes"], 1):
        duration = scene["out"] - scene["in"]
        srt_rows += [str(index), f"{srt_time(cursor)} --> {srt_time(cursor+duration)}", scene["caption"], ""]
        cursor += duration
    srt_path.write_text("\n".join(srt_rows), encoding="utf-8")

    ffprobe_path = args.ffprobe.resolve() if args.ffprobe else (Path(shutil.which("ffprobe")) if shutil.which("ffprobe") else None)
    probe_summary = {"tool": "ffprobe", "available": False, "note": "ffprobe executable unavailable; FFmpeg -i probe is retained in encode log"}
    if ffprobe_path and ffprobe_path.is_file():
        probe_json = run_capture([str(ffprobe_path), "-v", "error", "-show_entries", "format=filename,format_name,duration,size,bit_rate:stream=index,codec_type,codec_name,width,height,pix_fmt,avg_frame_rate,sample_rate,channels", "-of", "json", str(output)], log_parts)
        probe_data = json.loads(probe_json)
        probe_summary = {"tool": "ffprobe", "available": True, "ffprobe": str(ffprobe_path), "result": probe_data}
    probe_path = ROOT / "metadata" / f"{output.stem}.ffprobe-summary.json"
    probe_path.write_text(json.dumps(probe_summary, ensure_ascii=False, indent=2)+"\n", encoding="utf-8")

    manifest = {
        "output": output.relative_to(ROOT).as_posix(), "output_sha256": sha256(output),
        "output_bytes": output.stat().st_size, "duration_seconds": duration_s,
        "codec": "h264", "pixel_format": "yuv420p", "frame_rate": fps,
        "resolution": f"{spec['width']}x{spec['height']}", "audio": "none",
        "ffmpeg": str(ffmpeg), "ffmpeg_version": version, "font": font_path,
        "title": spec["title"], "tagline": spec["tagline"],
        "title_card_seconds": spec["title_seconds"],
        "closing_disclosure": spec["closing_text"], "closing_card_seconds": spec["closing_seconds"],
        "scenes": [{"clip": s["clip"].relative_to(ROOT).as_posix(), "sha256": sha256(s["clip"]),
                    "in": s["in"], "out": s["out"], "caption": s["caption"], "label": s["label"]}
                   for s in spec["scenes"]],
        "encode_log": log_path.relative_to(ROOT).as_posix(),
        "subtitle_timing": srt_path.relative_to(ROOT).as_posix(),
        "subtitle_timing_sha256": sha256(srt_path),
        "probe_summary": probe_path.relative_to(ROOT).as_posix(),
        "probe_summary_sha256": sha256(probe_path),
        "contact_sheet": contact.relative_to(ROOT).as_posix(),
    }
    manifest_path = ROOT / "metadata" / f"{output.stem}.render.json"
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2)+"\n", encoding="utf-8")
    print(f"rendered {output} ({duration_s:.2f}s); QA: {contact}")
    return 0

if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(f"render error: {exc}", file=sys.stderr)
        raise SystemExit(2)
