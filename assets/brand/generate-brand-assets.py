#!/usr/bin/env python3
"""Mechanically export PadNote brand assets from the generated raster masters.
Requires Pillow. No vector tracing or path/JSON input is used.
"""
from pathlib import Path
from PIL import Image
import math

ROOT = Path(__file__).resolve().parents[2]
BRAND = ROOT / "assets/brand"
MARK = Image.open(BRAND / "imagegen-v2/padnote-mark-clean.png").convert("RGBA")
PAPER = (254, 250, 238)
MARK_ALPHA_LAYOUT = MARK.getchannel("A").point(lambda value: 255 if value >= 2 else 0)
layout_bounds = MARK_ALPHA_LAYOUT.getbbox()
if layout_bounds is None:
    raise ValueError("clean mark master has no visible alpha")
layout_width = layout_bounds[2] - layout_bounds[0]
# Build every opaque platform icon from the same transparent master. Alpha>=2
# sets the visual centering/size; the complete source alpha remains in the layer.
ICON_SIZE = 1024
visible_width = round(ICON_SIZE * 0.62)
mark_scale = visible_width / layout_width
scaled_w = round(MARK.width * mark_scale)
scaled_h = round(MARK.height * mark_scale)
scaled_mark = MARK.resize((scaled_w, scaled_h), Image.Resampling.LANCZOS)
layout_center_x = (layout_bounds[0] + layout_bounds[2]) / 2 * mark_scale
layout_center_y = (layout_bounds[1] + layout_bounds[3]) / 2 * mark_scale
offset = (round(ICON_SIZE / 2 - layout_center_x),
          round(ICON_SIZE / 2 - layout_center_y))
icon_layer = Image.new("RGBA", (ICON_SIZE, ICON_SIZE), (0, 0, 0, 0))
icon_layer.alpha_composite(scaled_mark, offset)
ICON = Image.new("RGB", (ICON_SIZE, ICON_SIZE), PAPER)
ICON.paste(icon_layer, mask=icon_layer.getchannel("A"))
SIZES = (16, 24, 32, 48, 64, 128, 256, 512, 1024)
exports = BRAND
for size in SIZES:
    ICON.resize((size, size), Image.Resampling.LANCZOS).save(exports / f"PadNote-{size}.png", optimize=True)
ICON.resize((1024, 1024), Image.Resampling.LANCZOS).save(ROOT / "ios/PadNote/Resources/Assets.xcassets/AppIcon.appiconset/AppIcon.png", optimize=True)
ICON.resize((256, 256), Image.Resampling.LANCZOS).save(BRAND / "PadNote.ico", format="ICO", sizes=[(n, n) for n in (16, 24, 32, 48, 64, 128, 256)])

# Render adaptive layers directly from the high-resolution transparent master.
# Radius is measured in the canonical 108px viewport so every Android density
# keeps the mark inside the 66dp circular safe area.
bounds = MARK.getchannel("A").getbbox()
crop = MARK.crop(bounds)
layout_center_x = (layout_bounds[0] + layout_bounds[2]) / 2 - bounds[0]
layout_center_y = (layout_bounds[1] + layout_bounds[3]) / 2 - bounds[1]
RES = ROOT / "android/app/src/main/res"
(RES / "drawable-nodpi").mkdir(exist_ok=True)


def render_master(size, scale):
    target_w = max(1, round(crop.width * scale * size / 108))
    target_h = max(1, round(crop.height * scale * size / 108))
    layer = crop.resize((target_w, target_h), Image.Resampling.LANCZOS)
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    left = round(size / 2 - layout_center_x * scale * size / 108)
    top = round(size / 2 - layout_center_y * scale * size / 108)
    canvas.alpha_composite(layer, (left, top))
    alpha = canvas.getchannel("A")
    # Normalize all pixel centers into the Android 108x108 coordinate space.
    radius = max((math.hypot((x + .5) * 108 / size - 54,
                             (y + .5) * 108 / size - 54)
                  for y in range(size) for x in range(size)
                  if alpha.getpixel((x, y))), default=0)
    return canvas, radius

low, high = 0.0, 0.2
for _ in range(40):
    mid = (low + high) / 2
    _, radius = render_master(864, mid)
    if radius <= 32.7:
        low = mid
    else:
        high = mid
foreground, radius = render_master(864, low)
mono = Image.new("RGBA", foreground.size, (255, 255, 255, 0))
mono.putalpha(foreground.getchannel("A"))
foreground.save(RES / "drawable-nodpi/ic_launcher_foreground.png", optimize=True)
mono.save(RES / "drawable-nodpi/ic_launcher_monochrome.png", optimize=True)
MARK.resize((512, 512), Image.Resampling.LANCZOS).save(
    RES / "drawable-nodpi/padnote_mark.png", optimize=True)

# Save a real-size preview with circle and squircle masks over the exported layer.
preview = Image.new("RGB", (432, 224), (238, 231, 213))
from PIL import ImageDraw
preview_draw = ImageDraw.Draw(preview)
for idx, shape in enumerate(("circle", "squircle")):
    left = 18 + idx * 210
    layer_108 = foreground.resize((108,108), Image.Resampling.LANCZOS)
    # Model launcher output at 72px: show the centered 72/108 viewport without
    # enlarging it, then apply the circular or squircle system mask.
    tile = Image.new("RGBA", (72,72), (254,250,238,255))
    visible = layer_108.crop((18,18,90,90))
    tile.alpha_composite(visible)
    mask = Image.new("L", (72,72), 0)
    md = ImageDraw.Draw(mask)
    if shape == "circle": md.ellipse((0,0,71,71), fill=255)
    else: md.rounded_rectangle((0,0,71,71), radius=16, fill=255)
    preview.paste(tile.convert("RGB"), (left, 18), mask)
    preview_draw.text((left, 108), f"{shape} · 72/108", fill=(34,48,65))
preview.save(BRAND / "adaptive-icon-preview.png", optimize=True)
for density, size in (("mdpi",48),("hdpi",72),("xhdpi",96),("xxhdpi",144),("xxxhdpi",192)):
    dest = RES / f"mipmap-{density}"
    dest.mkdir(exist_ok=True)
    raster = ICON.resize((size, size), Image.Resampling.LANCZOS)
    raster.save(dest / "ic_launcher.png", optimize=True)
    raster.save(dest / "ic_launcher_round.png", optimize=True)
print(f"adaptive master export: {foreground.size[0]}x{foreground.size[1]} px; normalized safe-circle radius: {radius:.3f}/33 px; alpha bounds: {foreground.getchannel('A').getbbox()}; mark export: 512x512 px")
