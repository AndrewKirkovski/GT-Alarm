# GT-Alarm icon generator.
#
# Turns vendored Tabler SVGs into styled PNGs driven by manifest.json:
#   raw    - glyph painted a solid color
#   grad   - glyph painted a 135deg linear gradient (stroke for outline,
#            fill for filled icons)
#   circle - white glyph composited onto a gradient-filled circle
#
# Pipeline: load Tabler SVG -> mutate paint via ElementTree -> cairosvg
# rasterize at 4x -> Pillow downsample (+ circle compositing) -> PNG.
#
# Usage:
#   python tools/icongen/icongen.py            generate every icon
#   python tools/icongen/icongen.py --preview  also write a contact sheet
#   python tools/icongen/icongen.py --only ic_alarm,snooze
#
# Tabler Icons are MIT licensed (tabler/LICENSE) - no attribution required.

import argparse
import datetime
import io
import json
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

import cairosvg  # type: ignore[reportMissingImports]  # untyped, no stubs published
import numpy as np
from PIL import Image, ImageDraw, ImageFont

LANCZOS = Image.Resampling.LANCZOS  # Pillow 12 dropped the LANCZOS alias

SCRIPT_DIR = Path(__file__).resolve().parent
REPO_ROOT = SCRIPT_DIR.parents[1]
TABLER_DIR = SCRIPT_DIR / "tabler"
MANIFEST = SCRIPT_DIR / "manifest.json"

SVG_NS = "http://www.w3.org/2000/svg"
ET.register_namespace("", SVG_NS)

SUPERSAMPLE = 4
GLYPH_FRACTION = 0.52  # circle-mode glyph diameter vs the circle diameter
RAW_GLYPH_FRACTION = 0.80  # raw/grad glyph vs canvas — leaves an optical margin
                           # so Tabler glyphs don't read oversized vs Material

TARGETS = {
    "android": REPO_ROOT / "android-app/app/src/main/res/drawable-nodpi",
    "watch": REPO_ROOT / "watch-app/entry/src/main/js/MainAbility/common",
}


def hex_rgb(value):
    v = value.lstrip("#")
    return (int(v[0:2], 16), int(v[2:4], 16), int(v[4:6], 16))


def qn(tag):
    return f"{{{SVG_NS}}}{tag}"


def set_paint(root, variant, value):
    # Outline icons paint via stroke; filled icons via fill.
    if variant == "outline":
        root.set("stroke", value)
    else:
        root.set("fill", value)


def inject_gradient(root, grad_id, c0, c1):
    # userSpaceOnUse spanning the 24x24 Tabler viewBox: a 135deg top-left ->
    # bottom-right sweep that EVERY subpath samples — including degenerate
    # dot paths (e.g. the dot under the "?" in help). objectBoundingBox
    # renders those blank because a zero-area path has no bounding box.
    defs = ET.Element(qn("defs"))
    lg = ET.SubElement(
        defs,
        qn("linearGradient"),
        {"id": grad_id, "x1": "0", "y1": "0", "x2": "24", "y2": "24",
         "gradientUnits": "userSpaceOnUse"},
    )
    ET.SubElement(lg, qn("stop"), {"offset": "0", "stop-color": c0})
    ET.SubElement(lg, qn("stop"), {"offset": "1", "stop-color": c1})
    root.insert(0, defs)


def rasterize(root, px):
    data = ET.tostring(root, encoding="utf-8")
    png = cairosvg.svg2png(bytestring=data, output_width=px, output_height=px)
    return Image.open(io.BytesIO(png)).convert("RGBA")


def linear_gradient(size, c0, c1):
    yy, xx = np.mgrid[0:size, 0:size].astype(np.float32)
    t = (xx + yy) / (2.0 * (size - 1))
    a = np.array(hex_rgb(c0), dtype=np.float32)
    b = np.array(hex_rgb(c1), dtype=np.float32)
    return a[None, None, :] * (1.0 - t)[:, :, None] + b[None, None, :] * t[:, :, None]


def circle_layer(size, c0, c1):
    rgba = np.zeros((size, size, 4), dtype=np.float32)
    rgba[:, :, :3] = linear_gradient(size, c0, c1)
    yy, xx = np.mgrid[0:size, 0:size].astype(np.float32)
    center = (size - 1) / 2.0
    dist = np.sqrt((xx - center) ** 2 + (yy - center) ** 2)
    # Antialiased edge: 1px feather at the circle boundary.
    rgba[:, :, 3] = np.clip((size / 2.0) - dist + 0.5, 0.0, 1.0) * 255.0
    return Image.fromarray(rgba.astype(np.uint8), "RGBA")


def render_padded(root, big, size):
    # Rasterize the glyph into a centered RAW_GLYPH_FRACTION box of a
    # transparent canvas, then downsample — gives every raw/grad icon a
    # consistent optical margin.
    glyph_px = int(big * RAW_GLYPH_FRACTION)
    glyph = rasterize(root, glyph_px)
    canvas = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    offset = (big - glyph_px) // 2
    canvas.alpha_composite(glyph, (offset, offset))
    return canvas.resize((size, size), LANCZOS)


def build_icon(spec, palette):
    variant = spec["variant"]
    mode = spec["mode"]
    size = int(spec["size"])
    big = size * SUPERSAMPLE
    svg = TABLER_DIR / variant / f"{spec['glyph']}.svg"
    if not svg.exists():
        raise FileNotFoundError(f"missing Tabler SVG: {svg}")
    root = ET.parse(svg).getroot()

    if mode == "raw":
        set_paint(root, variant, palette["colors"][spec["paint"]])
        return render_padded(root, big, size)

    if mode == "grad":
        c0, c1 = palette["gradients"][spec["paint"]]
        inject_gradient(root, "iconograd", c0, c1)
        set_paint(root, variant, "url(#iconograd)")
        return render_padded(root, big, size)

    if mode == "circle":
        c0, c1 = palette["gradients"][spec["paint"]]
        set_paint(root, variant, "#FFFFFF")
        glyph_px = int(big * GLYPH_FRACTION)
        glyph = rasterize(root, glyph_px)
        canvas = circle_layer(big, c0, c1)
        offset = (big - glyph_px) // 2
        canvas.alpha_composite(glyph, (offset, offset))
        return canvas.resize((size, size), LANCZOS)

    raise ValueError(f"unknown mode: {mode}")


def contact_sheet(images):
    cols = 5
    cell = 150
    pad = 16
    rows = (len(images) + cols - 1) // cols
    sheet = Image.new("RGBA", (cols * cell, rows * cell), (153, 153, 153, 255))
    draw = ImageDraw.Draw(sheet)
    try:
        font = ImageFont.truetype("arial.ttf", 13)
    except OSError:
        font = ImageFont.load_default()
    for idx, (name, img) in enumerate(images):
        cx = (idx % cols) * cell
        cy = (idx // cols) * cell
        thumb = img.copy()
        thumb.thumbnail((cell - 2 * pad, cell - 2 * pad - 18), LANCZOS)
        sheet.alpha_composite(
            thumb, (cx + (cell - thumb.width) // 2, cy + pad))
        draw.text((cx + cell // 2, cy + cell - 16), name, fill=(0, 0, 0, 255),
                  font=font, anchor="mm")
    return sheet


def main():
    parser = argparse.ArgumentParser(description="GT-Alarm icon generator")
    parser.add_argument("--preview", action="store_true",
                        help="also write a contact sheet to .local/")
    parser.add_argument("--only", default="",
                        help="comma-separated icon names to build")
    args = parser.parse_args()

    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    palette = manifest["palette"]
    icons = manifest["icons"]
    only = {n.strip() for n in args.only.split(",") if n.strip()}
    if only:
        icons = [i for i in icons if i["name"] in only]

    for directory in TARGETS.values():
        directory.mkdir(parents=True, exist_ok=True)

    rendered = []
    for spec in icons:
        img = build_icon(spec, palette)
        out_dir = TARGETS[spec["out"]]
        out_path = out_dir / f"{spec['name']}.png"
        img.save(out_path, optimize=True)
        rendered.append((spec["name"], img))
        rel = out_path.relative_to(REPO_ROOT)
        print(f"  {spec['mode']:6s} {spec['name']:20s} -> {rel} "
              f"({spec['size']}px, {out_path.stat().st_size}b)")

    print(f"generated {len(rendered)} icons")

    if args.preview:
        local = REPO_ROOT / ".local"
        local.mkdir(exist_ok=True)
        stamp = datetime.date.today().isoformat()
        preview_path = local / f"{stamp}-icongen-preview.png"
        contact_sheet(rendered).save(preview_path)
        print(f"preview -> {preview_path}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
