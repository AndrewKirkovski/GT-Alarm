# Watch UI reference assets

Source-of-truth assets for the watch ring-screen UI. Anything visible to
the user during alarm-fire on the watch (or in the phone's picker as a
preview) is described here exactly once and consumed by the rendering
layers downstream.

## Layout

```
watch-references/
├── README.md                 ← you are here
├── ring-overlay.svg          ← canonical geometry/colors (hand-edited)
├── render-overlay.py         ← SVG → PNG generator for the phone picker
└── watch-ui-preview.html     ← Playwright-verifiable HTML mockup
```

## Pipeline

```
ring-overlay.svg
   │
   ├─→ render-overlay.py  (cairosvg)
   │   └─→ android-app/.../res/drawable-nodpi/watch_overlay.png
   │       Loaded by WatchBackgroundPicker.kt as the alarm-UI preview.
   │
   ├─→ watch-ui-preview.html  (CSS values mirrored by hand from SVG)
   │   Open in Playwright; pixel-diff vs the SVG render to verify
   │   the watch UI design stays in lock-step.
   │
   └─→ watch-app/.../pages/ring/ring.css  (CSS values mirrored by hand)
       The actual file that ships to the watch.
```

## When to touch what

You're changing the **watch ring-screen design**? Edit `ring-overlay.svg`
first, then propagate:

1. **`watch-ui-preview.html`** — copy the new px values into the
   `.ring-arc`, `.ring-title`, `.ring-time`, `.btn-snooze`, `.btn-dismiss`
   classes. Re-open in Playwright (the HTML mockup is the easiest place
   to eyeball the design before sideloading).
2. **`watch-app/entry/src/main/js/MainAbility/pages/ring/ring.css`** —
   same px values land in the production watch CSS.
3. **`render-overlay.py`** — re-run to regenerate the picker PNG.
4. **Commit all four files together.** Half-updates have caused regressions
   (see `memory/watch_overlay_sync.md`).

## Why an SVG (not Compose Canvas, not vector drawable XML)

Tried both. Compose Canvas drift made the picker preview lie about
what was being saved (pixel↔sp mismatches, non-uniform scaleX/scaleY,
density-dependent text sizing — all real bugs hit on 2026-05-13).
Android vector drawable XML doesn't support `<text>` so you can't put
"Alarm" / "07:30" / "Snooze" / "Dismiss" labels in one without
post-processing.

A hand-written SVG + cairosvg rasterizer:
- WYSIWYG: what `render-overlay.py` outputs is exactly what the picker
  shows; the picker overlays it at alpha 1.0 (the watch UI itself isn't
  transparent on the watch).
- Easy to diff: HTML mockup ↔ SVG render → Playwright pixel comparison
  catches drift before sideloading.
- One source: bumping a font size or button width touches one file, then
  three mechanical copies.

## Dependencies

```bash
python -m pip install cairosvg
```

cairosvg 2.x. The script runs on Windows, macOS, and Linux without
modification.
