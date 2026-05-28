# Generate GT-Alarm app launcher icons from the two root source PNGs.
#
#   phone_icon_src.png  -> Android phone-app launcher icon (shows a watch:
#                          the cross-device promise "rings on your watch")
#   watch_icon_src.png  -> HarmonyOS watch-app launcher icon (shows a phone:
#                          "snoozes your phone")
#
# Each source is a square logo. The script cuts rounded corners and resizes
# to every resolution the two platforms need. Re-run after swapping either
# source PNG:  python gen-app-icons.py
#
# Requires Pillow.  On Windows run with UTF-8:
#   PYTHONIOENCODING=utf-8 PYTHONUTF8=1 python gen-app-icons.py
import os
from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.abspath(__file__))
PHONE_SRC = os.path.join(ROOT, "phone_icon_src.png")
WATCH_SRC = os.path.join(ROOT, "watch_icon_src.png")

SS = 4              # supersample factor for clean mask edges
RADIUS_FRAC = 0.22  # corner radius as a fraction of the icon side

# Android legacy launcher icon: one PNG per density bucket, base 192 (xxxhdpi).
ANDROID_DENSITIES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
# HarmonyOS Lite Wearable: sizes fixed by the DevEco project template.
WATCH_ICON = 104
WATCH_ICON_SMALL = 92


def load_square(path):
    im = Image.open(path).convert("RGBA")
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
    return _masked(im, size, lambda d, big: d.rounded_rectangle(
        [0, 0, big - 1, big - 1], radius=int(big * RADIUS_FRAC), fill=255))


def circle(im, size):
    return _masked(im, size, lambda d, big: d.ellipse([0, 0, big - 1, big - 1], fill=255))


def main():
    phone = load_square(PHONE_SRC)
    android_res = os.path.join(ROOT, "android-app", "app", "src", "main", "res")
    for dens, px in ANDROID_DENSITIES.items():
        d = os.path.join(android_res, "mipmap-" + dens)
        os.makedirs(d, exist_ok=True)
        rounded(phone, px).save(os.path.join(d, "ic_launcher.png"))
        circle(phone, px).save(os.path.join(d, "ic_launcher_round.png"))
        print("android mipmap-%s: ic_launcher %dx%d + ic_launcher_round" % (dens, px, px))

    # Watch launcher icons are circle-cut — HarmonyOS watch faces present
    # app icons as circles, so a rounded square would look wrong.
    watch = load_square(WATCH_SRC)
    watch_media = os.path.join(ROOT, "watch-app", "entry", "src", "main", "resources", "base", "media")
    circle(watch, WATCH_ICON).save(os.path.join(watch_media, "icon.png"))
    circle(watch, WATCH_ICON_SMALL).save(os.path.join(watch_media, "icon_small.png"))
    print("watch: icon.png %dx%d + icon_small.png %dx%d"
          % (WATCH_ICON, WATCH_ICON, WATCH_ICON_SMALL, WATCH_ICON_SMALL))
    print("done")


if __name__ == "__main__":
    main()
