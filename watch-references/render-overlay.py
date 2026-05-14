# Render watch-references/ring-overlay.svg → res/drawable-nodpi/watch_overlay.png.
#
# Source-of-truth pipeline (single canonical asset → multiple consumers):
#   watch-references/ring-overlay.svg     ← edit me (hand-tuned)
#     ├─→ this script (cairosvg) → res/drawable-nodpi/watch_overlay.png
#     │   Picker loads via painterResource(R.drawable.watch_overlay).
#     └─→ watch-references/watch-ui-preview.html
#         (Playwright-verified web mockup; CSS values copied from SVG)
#
# Re-run this script after editing the SVG. Idempotent.
#
# Why drawable-nodpi: the picker scales the bitmap into a circular
# viewport whose pixel size depends on phone screen + dialog padding.
# Letting Android density-bucket the resource would force us to maintain
# 6 resolution variants — pointless when we resize on render anyway.
# nodpi tells Android "this asset's pixels are absolute".
#
# Output dimension is 4× watch native (466 × 4 = 1864) so phones at
# high dialog viewports (~600–800 dp ≈ 1200–1600 px on xxhdpi) stay
# crisp without aliasing. SVG → PNG at this size is ~150 KB.

import os
import sys

import cairosvg

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.abspath(os.path.join(SCRIPT_DIR, os.pardir))
SVG_PATH = os.path.join(SCRIPT_DIR, "ring-overlay.svg")
OUT_DIR = os.path.join(
    REPO_ROOT,
    "android-app",
    "app",
    "src",
    "main",
    "res",
    "drawable-nodpi",
)
OUT_PATH = os.path.join(OUT_DIR, "watch_overlay.png")

NATIVE_PX = 466
OUTPUT_SCALE = 4


def main() -> int:
    if not os.path.exists(SVG_PATH):
        print(f"ERROR: SVG not found at {SVG_PATH}", file=sys.stderr)
        return 1
    os.makedirs(OUT_DIR, exist_ok=True)
    cairosvg.svg2png(
        url=SVG_PATH,
        write_to=OUT_PATH,
        output_width=NATIVE_PX * OUTPUT_SCALE,
        output_height=NATIVE_PX * OUTPUT_SCALE,
    )
    size = os.path.getsize(OUT_PATH)
    print(f"wrote {OUT_PATH} ({size} bytes, {NATIVE_PX * OUTPUT_SCALE}² px)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
