# Render watch-references/ring-overlay.svg → res/drawable-nodpi/watch_overlay.png.
#
# Source-of-truth pipeline (single canonical asset → multiple consumers):
#   watch-references/ring-overlay.svg     ← edit me (hand-tuned geometry)
#     ├─→ this script (cairosvg + Pillow) → res/drawable-nodpi/watch_overlay.png
#     │   Picker loads via painterResource(R.drawable.watch_overlay).
#     └─→ watch-references/watch-ui-preview.html
#         (Playwright-verified web mockup; CSS values copied from SVG)
#
# The SVG carries the ring geometry (arc, title, time, button positions).
# The button + bell GLYPHS are composited on top from the watch's own
# icongen PNGs (watch-app/.../common/*.png) so the picker preview stays
# faithful to the real watch UI without re-drawing the art by hand.
#
# Re-run after editing the SVG or regenerating the watch icons. Idempotent.
#
# Output dimension is 4× watch native (466 × 4 = 1864) so phones at high
# dialog viewports stay crisp without aliasing.

import io
import os
import sys

import cairosvg  # type: ignore[reportMissingImports]  # untyped, no stubs published
from PIL import Image

LANCZOS = Image.Resampling.LANCZOS  # Pillow 12 dropped the Image.LANCZOS alias

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.abspath(os.path.join(SCRIPT_DIR, os.pardir))
SVG_PATH = os.path.join(SCRIPT_DIR, "ring-overlay.svg")
OUT_DIR = os.path.join(
    REPO_ROOT, "android-app", "app", "src", "main", "res", "drawable-nodpi",
)
OUT_PATH = os.path.join(OUT_DIR, "watch_overlay.png")
WATCH_COMMON = os.path.join(
    REPO_ROOT, "watch-app", "entry", "src", "main", "js", "MainAbility", "common",
)

NATIVE_PX = 466
OUTPUT_SCALE = 4

# Glyph PNGs composited onto the rendered SVG. Coordinates are in 466-native
# SVG units: (filename, center_x, center_y, diameter) — they mirror the
# <circle> button positions in ring-overlay.svg and the watch ring layout.
GLYPHS = [
    ("snooze.png", 171, 354, 88),
    ("stop.png", 295, 354, 88),
    ("bell.png", 233, 143, 44),
]


def main() -> int:
    if not os.path.exists(SVG_PATH):
        print(f"ERROR: SVG not found at {SVG_PATH}", file=sys.stderr)
        return 1
    os.makedirs(OUT_DIR, exist_ok=True)
    size = NATIVE_PX * OUTPUT_SCALE
    png_bytes = cairosvg.svg2png(
        url=SVG_PATH, output_width=size, output_height=size,
    )
    overlay = Image.open(io.BytesIO(png_bytes)).convert("RGBA")

    composited = 0
    for name, cx, cy, diameter in GLYPHS:
        path = os.path.join(WATCH_COMMON, name)
        if not os.path.exists(path):
            print(f"WARN: glyph missing, skipped: {path}", file=sys.stderr)
            continue
        px = diameter * OUTPUT_SCALE
        glyph = Image.open(path).convert("RGBA").resize((px, px), LANCZOS)
        left = cx * OUTPUT_SCALE - px // 2
        top = cy * OUTPUT_SCALE - px // 2
        overlay.alpha_composite(glyph, (left, top))
        composited += 1

    overlay.save(OUT_PATH)
    print(f"wrote {OUT_PATH} ({os.path.getsize(OUT_PATH)} bytes, "
          f"{size}^2 px, {composited} glyphs composited)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
