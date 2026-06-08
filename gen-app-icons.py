# Generate GT-Alarm app launcher icons from the two root source SVGs.
#
#   phone_icon.svg -> Android phone-app launcher icon.
#                     Both Android launcher slots are rounded-rectangle cut.
#   watch_icon.svg -> HarmonyOS watch-app launcher icon.
#                     Watch launcher icons are circle-cut.
#
# Each source is a square logo. The script rasterizes the SVG at high
# resolution, cuts the required launcher shape, and resizes to every resolution
# the two platforms need. Re-run after swapping either source SVG:
#   python gen-app-icons.py
#
# Requires Pillow + CairoSVG. On Windows run with UTF-8:
#   PYTHONIOENCODING=utf-8 PYTHONUTF8=1 python gen-app-icons.py
from io import BytesIO
import math
import os

import cairosvg
from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.abspath(__file__))
PHONE_SRC = os.path.join(ROOT, "phone_icon.svg")
WATCH_SRC = os.path.join(ROOT, "watch_icon.svg")
PHONE_GENERATED = os.path.join(ROOT, "phone_icon.generated.png")
WATCH_GENERATED = os.path.join(ROOT, "watch_icon.generated.png")
WATCH_GENERATED_1024 = os.path.join(ROOT, "watch_icon_1024.generated.png")

SS = 4              # Supersample factor for clean mask edges.
RADIUS_FRAC = 0.22  # Corner radius as a fraction of the icon side.
SOURCE_RENDER_SIZE = 1024
ROOT_GENERATED_SIZE = 512

# Android legacy launcher icon: one PNG per density bucket, base 192 (xxxhdpi).
ANDROID_DENSITIES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
# Android adaptive icon foreground layer: 108dp canvas per density.
ANDROID_ADAPTIVE_DENSITIES = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}
# HarmonyOS Lite Wearable: sizes fixed by the DevEco project template.
WATCH_ICON = 104
WATCH_ICON_SMALL = 92


def load_svg_square(path):
    png = cairosvg.svg2png(
        url=path,
        output_width=SOURCE_RENDER_SIZE,
        output_height=SOURCE_RENDER_SIZE,
    )
    im = Image.open(BytesIO(png)).convert("RGBA")
    w, h = im.size
    if w != h:
        s = min(w, h)
        im = im.crop(((w - s) // 2, (h - s) // 2, (w - s) // 2 + s, (h - s) // 2 + s))
    return im


def _masked(im, size, draw_mask):
    big = size * SS
    scaled = im.resize((big, big), Image.Resampling.LANCZOS)
    mask = Image.new("L", (big, big), 0)
    draw_mask(ImageDraw.Draw(mask), big)
    out = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    out.paste(scaled, (0, 0), mask)
    return out.resize((size, size), Image.Resampling.LANCZOS)


def rounded(im, size):
    return _masked(
        im,
        size,
        lambda d, big: d.rounded_rectangle(
            [0, 0, big - 1, big - 1],
            radius=int(big * RADIUS_FRAC),
            fill=255,
        ),
    )


def circle(im, size):
    return _masked(
        im,
        size,
        lambda d, big: d.ellipse([0, 0, big - 1, big - 1], fill=255),
    )


def adaptive_foreground(im, size):
    # Fit the rounded rectangle inside Android's circular launcher mask. This
    # prevents Pixel Launcher from showing a generated circular backing plate.
    max_side = size * 0.5 / (math.sqrt(2) * (0.5 - RADIUS_FRAC) + RADIUS_FRAC)
    artwork_size = int(max_side) - 2
    artwork = rounded(im, artwork_size)
    out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    offset = (size - artwork_size) // 2
    out.paste(artwork, (offset, offset), artwork)
    return out


def write_adaptive_icon_xmls(android_res):
    d = os.path.join(android_res, "mipmap-anydpi-v26")
    os.makedirs(d, exist_ok=True)
    content = """<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@android:color/transparent" />
    <foreground android:drawable="@mipmap/ic_launcher_foreground" />
</adaptive-icon>
"""
    for name in ("ic_launcher.xml", "ic_launcher_round.xml"):
        with open(os.path.join(d, name), "w", encoding="utf-8", newline="\n") as f:
            f.write(content)


def main():
    phone = load_svg_square(PHONE_SRC)
    phone.resize((ROOT_GENERATED_SIZE, ROOT_GENERATED_SIZE), Image.Resampling.LANCZOS).save(PHONE_GENERATED)
    print("root: phone_icon.generated.png %dx%d square" % (ROOT_GENERATED_SIZE, ROOT_GENERATED_SIZE))

    android_res = os.path.join(ROOT, "android-app", "app", "src", "main", "res")
    for dens, px in ANDROID_DENSITIES.items():
        d = os.path.join(android_res, "mipmap-" + dens)
        os.makedirs(d, exist_ok=True)
        rounded(phone, px).save(os.path.join(d, "ic_launcher.png"))
        rounded(phone, px).save(os.path.join(d, "ic_launcher_round.png"))
        print("android mipmap-%s: rounded ic_launcher %dx%d + ic_launcher_round" % (dens, px, px))

    for dens, px in ANDROID_ADAPTIVE_DENSITIES.items():
        d = os.path.join(android_res, "mipmap-" + dens)
        adaptive_foreground(phone, px).save(os.path.join(d, "ic_launcher_foreground.png"))
        print("android mipmap-%s: adaptive foreground %dx%d" % (dens, px, px))
    write_adaptive_icon_xmls(android_res)

    watch = load_svg_square(WATCH_SRC)
    watch.resize((ROOT_GENERATED_SIZE, ROOT_GENERATED_SIZE), Image.Resampling.LANCZOS).save(WATCH_GENERATED)
    print("root: watch_icon.generated.png %dx%d square" % (ROOT_GENERATED_SIZE, ROOT_GENERATED_SIZE))
    watch.resize((SOURCE_RENDER_SIZE, SOURCE_RENDER_SIZE), Image.Resampling.LANCZOS).save(WATCH_GENERATED_1024)
    print("root: watch_icon_1024.generated.png %dx%d square" % (SOURCE_RENDER_SIZE, SOURCE_RENDER_SIZE))

    watch_media = os.path.join(ROOT, "watch-app", "entry", "src", "main", "resources", "base", "media")
    circle(watch, WATCH_ICON).save(os.path.join(watch_media, "icon.png"))
    circle(watch, WATCH_ICON_SMALL).save(os.path.join(watch_media, "icon_small.png"))
    print(
        "watch: icon.png %dx%d + icon_small.png %dx%d"
        % (WATCH_ICON, WATCH_ICON, WATCH_ICON_SMALL, WATCH_ICON_SMALL),
    )
    print("done")


if __name__ == "__main__":
    main()
