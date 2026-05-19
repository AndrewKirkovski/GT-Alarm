# icongen

Generates the GT-Alarm icon set from [Tabler Icons](https://tabler.io/icons)
(MIT, no attribution required). Replaces the retired `.local/build_icon_drawables.py`
and `.local/gen_icons.py`.

## Run

```
python tools/icongen/icongen.py            # generate every icon
python tools/icongen/icongen.py --preview  # + contact sheet to .local/
python tools/icongen/icongen.py --only ic_alarm,snooze
```

Requires `cairosvg`, `Pillow`, `numpy` (already used elsewhere in the repo).

Outputs land in:
- `android-app/app/src/main/res/drawable-nodpi/ic_*.png`
- `watch-app/entry/src/main/js/MainAbility/common/*.png`

## How it works

Each icon is one row in `manifest.json`. The pipeline loads the vendored
Tabler SVG, mutates its paint via `ElementTree`, rasterizes with `cairosvg`
at 4x, then downsamples with Pillow.

### Modes

| Mode | Result | `paint` resolves to |
|---|---|---|
| `raw` | glyph in a solid color | `palette.colors` key |
| `grad` | glyph in a 135deg linear gradient (stroke for outline icons, fill for filled) | `palette.gradients` key |
| `circle` | white glyph composited on a gradient-filled circle | `palette.gradients` key |

### Palette

`manifest.json` → `palette` holds the GT-Alarm brand colors and gradients.
Gradients are `[start, end]` hex pairs swept top-left → bottom-right.

## Adding an icon

1. Download the glyph SVG into `tabler/outline/` or `tabler/filled/` from
   `https://raw.githubusercontent.com/tabler/tabler-icons/main/icons/<variant>/<name>.svg`.
   Note: files in `tabler/filled/` drop the `-filled` suffix (e.g. the web
   name `bell-filled` is the file `filled/bell.svg`).
2. Add a row to `manifest.json` → `icons`.
3. Re-run the generator.

`tabler/LICENSE` is the upstream MIT license — keep it alongside the SVGs.
