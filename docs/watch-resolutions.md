# Watch screen resolutions — supported device matrix

Single source of truth for **which Lite Wearable screens GT Wake must lay out for**,
and the rule the phone-side background cropper follows. Keyed off by:

- `watch-references/watch-ui-responsive.html` — the `PROFILES` array (browser mockup).
- `watch-app/.../common/screen.js` + `pages/index/index.js applyScreen()` — the on-watch
  responsive layout (adapts to **any** reported `getInfo()` size; the table is for testing).
- Part C phone cropper (`WatchBackgroundPicker` / `WatchBackgroundEncoder`) — the overlay
  is drawn **only** for a *known* profile; an unknown runtime resolution gets **no overlay**.

> The on-watch layout is resolution-agnostic — it reads `@system.device.getInfo()`
> (`windowWidth`/`windowHeight`/`screenShape`) and computes every dimension in px.
> This table exists to (a) enumerate what we test against and (b) gate the phone cropper.

## Authoritative device list

Huawei's own "WATCH GT series — AppGallery-supported watch apps" page lists the watches
that can install a third-party Lite Wearable app: **WATCH GT 3 / GT 4 / GT 5 / GT 6 series,
GT Runner / GT Runner 2, GT Cyber, GT 2022 Collector's Edition.** The reviewer additionally
tested us on a **Watch FIT 5 Pro**, so the AGConnect `liteWearable` distribution reaches the
**FIT** family too. (Source: consumer.huawei.com/cn/support/content/zh-cn15878302/.)

## Resolution matrix

| Profile | Devices | W×H (px) | Shape | Status |
|---|---|---|---|---|
| **Round 466** | GT 3 / GT 3 Pro, GT 4 (41 & 46 mm), GT 5 / GT 5 Pro, **GT 6 / GT 6 Pro**, GT Runner / Runner 2, GT Cyber, GT 2022 Collector | **466 × 466** | circle | ✅ primary (our dev device) |
| Round 454 (legacy) | GT 2 / GT 2 Pro (46 mm) | 454 × 454 | circle | 🟡 not on current store list; layout still adapts |
| Round 390 (legacy) | GT / GT 2 (42 mm) | 390 × 390 | circle | 🟡 not on current store list; layout still adapts |
| **Rect FIT (tall)** | **Watch FIT 3 / FIT 4 / FIT 5 / FIT 5 Pro** | **408 × 480** (portrait) | rect | 🟠 reviewer device (FIT 5 Pro); no hardware on hand |
| Rect FIT 2 | Watch FIT 2 | 336 × 480 (portrait) | rect | 🟠 narrower 1.74″; layout adapts, time box tight |

**Key facts**

- Every *currently AppGallery-supported* **round** watch is **466 × 466** — one round size to support.
- The **FIT family is portrait** (taller than wide): **W < H**. Marketing "480 × 408" is the
  panel's *tall × wide*; `getInfo()` reports `windowWidth = 408, windowHeight = 480`.
- FIT 5 (non-Pro) display size was reported inconsistently in one source as 480×480 — **unverified**;
  treat the FIT family as 408×480 until a real `getInfo()` log says otherwise. The layout adapts
  regardless; only the cropper's known-profile list would need the extra entry.

Sources: HUAWEI official specs (consumer.huawei.com — watch-gt4/gt5/gt6, watch-fit2/fit3/fit4/fit5),
GSMArena (huawei_watch_gt_4, huawei_watch_fit_3), corroborated against the CN AppGallery-support page.

## Responsive layout math (`applyScreen`, mirrored in the mockup)

```
listH   = H − 146                       # header block; list scrolls
arcCx   = round(W/2)                     # decorative ring arc, centred
arcCy   = round(H/2)
arcR    = round(min(W,H) · 0.472)        # radius tracks the short side
padTop  = round ? 24 : 8                 # round-face chord over-scroll headroom
padBot  = round ? 56 : 8
titleW  = min(420, W − 24)
rowW    = min(390, W − 48)               # GT6 → 390 (unchanged); narrower screens shrink
rowMargin = round((W − rowW) / 2)        # GT6 → 38
timeW   = rowW − 186                     # 186 = dot(24)+padding(36)+dayGrid(126); time box absorbs the shrink
```

On the GT6 (W=H=466) every value reproduces the previous hard-coded constants exactly
(**zero round-screen regression**). On FIT (408) and FIT 2 (336) the row + time box shrink
so content never clips the right edge; the 126 px day-grid stays fixed for legibility.

## Phone cropper rule (Part C) — unknown resolution ⇒ no overlay

The watch reports `{type:"watch_screen", width, height, shape}` on connect/foreground.
The cropper looks the reported `(W, H, shape)` up in **this matrix**:

- **Known profile** → draw the crop overlay: `CircleShape` for `circle`, a `W:H` rounded-rect
  for `rect`; output the cropped bitmap at the watch's exact `W×H` (feeds `WatchBackgroundEncoder`).
- **Unknown / unsupported resolution** → **draw no crop overlay.** We can't promise an accurate
  shaped preview for a screen we haven't validated, so the cropper falls back to a plain
  (un-masked, un-shaped) selection and a best-effort full-bleed encode at the reported size.
  (Per user requirement 2026-06-11.)

## In-app layout test without the hardware (`dev_force_screen`)

We own a GT6 (466 round) only. To eyeball the FIT/FIT 2 layouts on it, `screen.js` reads an
optional `@system.storage` key **`dev_force_screen`** = `"WxH:shape"` (e.g. `"408x480:rect"`).
When set, the watch renders **as if** it had that screen, so `applyScreen` reflow can be verified
on the one device. The key is **never written in production** (the phone doesn't set it), so
release behaviour is unchanged. Clear it by setting it to `""`. The browser mockup
(`watch-ui-responsive.html`) is the no-device check for layout *intent*; `dev_force_screen` is the
on-device check that the ACELite renderer actually honours the computed px on real firmware.
