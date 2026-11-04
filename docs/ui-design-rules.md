# GT Alarm — UI Design Rules

**This document is the single source of truth for all UI layout and visual design decisions.**
Read it end-to-end before touching any screen layout, card, pill, gradient, or fade.

---

## Acceptance criteria (non-negotiable)

1. **You can scroll content so the card's bottom edge is above the pill.** The bottom spacer inside the card enables this.
2. **When scrolling content under the pill, fade-out starts at the mid-point of the pill.**
3. **Prefer scrolling the whole page over scrolling the inside of a card.** Cards have no internal vertical scroll.
4. **If there is a fixed header (e.g. time picker) or fixed footer (e.g. action pill), content scrolls UNDER it.** The opacity fade-out is the visibility safeguard that keeps content readable near the fixed element.

---

## Card layout

- All content lives inside large white cards with **20dp rounded corners** on all four sides.
- Cards have **16dp horizontal margin** from screen edges.
- Child sections inside a card are separated by **thin HorizontalDivider lines** (0.5dp, ~10% opacity).

## Background

- Every screen background is a **brand gradient**: cyan → pink → blue tints.
- Cards sit on top of this gradient; the gradient shows through the margins.

## Scroll model — the whole screen scrolls as one unit

**Cards have NO internal scroll.** The whole screen (time picker + toggle + card) scrolls together as one column.

- The screen content is a vertically scrollable `Column` (`fillMaxWidth` + `verticalScroll`).
- The card is a regular composable inside that column — it wraps its content height, no `weight(1f)`, no internal `verticalScroll`.
- When the user scrolls down, the card slides UP. The card's bottom edge eventually clears the pill.
- When the user scrolls up, the card's bottom edge slides back under the pill.

## Pills hover OVER content

- Bottom action pills (CancelSaveBar) and bottom nav pills float **on top of** the scrollable content.
- The pill is always visible; the content scrolls behind and under it.
- There is no gap reserved above the pill — the scrollable column's height is the full viewport.

## Fade

- The fade is applied to the **outer viewport Box** (not inside the card), using `BlendMode.DstIn` + `CompositingStrategy.Offscreen` on a `Box(fillMaxSize)` that wraps the scrollable column.
- Because the Box is viewport-sized, `size.height` in `drawWithContent` = screen height, so the fade position is screen-relative regardless of scroll position.
- **Fade starts at the vertical midpoint of the bottom pill:**
  - `navBarPaddingDp = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()`
  - Bottom nav: `startY = size.height - navBarPaddingDp.toPx() - 38.dp.toPx()`. 38dp = 8dp (pill outer vertical padding) + 30dp (half of the 60dp pill height).
  - CancelSaveBar: 40dp = 12dp (pill outer vertical padding) + 28dp (half of the 56dp pill height).
  - `endY = size.height`

## Bottom scroll spacer

- A `Spacer(Modifier.height(navBarPaddingDp + 80.dp))` placed **outside the card** (inside the outer scrollable Column, after the card) gives extra scroll space so the card's rounded bottom corner can scroll above the pill.
- This is the key spacer for the "card bottom clears pill" AC. Without it, the card bottom is always pinned at the screen bottom at max scroll.
- At max scroll with this spacer: `card_bottom = screen_height - (navBarPaddingDp + 80dp)` which is ~12dp above the pill top — the card's rounded corner is fully visible against the gradient.
- This spacer is outside the card. The card itself wraps its content tightly (no inner bottom spacer needed).

## Pill design (CancelSaveBar / bottom nav)

- CancelSaveBar: `Surface` with `RoundedCornerShape(28.dp)`, height 56dp, shadow elevation 8dp, in a `Box` with `navigationBarsPadding()` + `padding(horizontal = 24.dp, vertical = 12.dp)`. Buttons inside: `TextButton`, `weight(1f)`, full height, separated by `VerticalDivider`.
- Bottom nav: height 60dp, `padding(horizontal = 48.dp, vertical = 8.dp)`. 4 items, icons only (66dp per item at 360dp, 56dp at 320dp). The selected item is a pill that fills its cell. The whole cell is the tap target, at least 48dp. Long press shows the name in a tooltip, and TalkBack reads the name and the selected state.

## Choosing and removing

**Words.** One word for each thing, in all 6 locales: Sound, Picture, Remove, "Choose…", Change. Do not use Audio, Ringtone, Background image, Background, Clear, Reset, Set, Not set, System default or App default in these rows.

**Shared row.** Every sound and picture row, in the alarm editor and in Settings, uses `ui/components/MediaChoiceRow.kt`:

```
[icon]  Sound                        [play, sound rows only]
        What will really be used
        [Choose… or Change]  [Remove]
        [preview, picture rows only]
```

- The value line always names what will really be used. Preview plays that same sound, and it stops when Remove is pressed.
- The main button uses `GtAccentButton`. Remove uses the neutral `GtFloatingButton`, because it removes a choice, not a file. Red stays for deleting alarms.
- The buttons keep their own width and sit on their own line, so nothing jumps when Remove appears or goes away.
- Tapping a picture preview does the same as the main button.

**How things are removed.**

| Action | Behaviour | Why |
|---|---|---|
| Remove a sound or picture from an alarm | Instant, with Undo | Reversible |
| Remove a default sound or phone picture | Instant, with Undo | Reversible |
| Stopwatch Reset | Instant, with Undo, paused state only | Reversible while the screen is open |
| Remove the default watch picture | Dialog, `Remove picture` and `Keep picture` | It visibly wakes the watch and cannot be undone |
| Cancel a running timer | Dialog, `Cancel timer` and `Keep timer` | It stops a live countdown |
| Delete an alarm | Existing dialog | It destroys a saved alarm |

A dialog names the effect and uses verb buttons, never OK or Yes. New dialog text states the fallback.

**Feedback.** `GtSnackbarHost` sits above the bottom pill and outside the fading viewport, so the fade cannot hide it. Text is short, for example `Sound removed`, with the action `Undo`. The row changes too, so the result is still visible after the snackbar goes.

**Accessibility.**
- Tap targets are at least 48dp. `GtAccentButton`, `GtFloatingButton` and `OptionChip` keep their look and gain a 48dp target where they are reused.
- The value line uses `onSurfaceVariant`. Primary cyan on white is about 3:1, which is too low for small text.
- The value line is a polite live region, so TalkBack announces the new state.
- Time displays use tabular digits, so numbers do not shake.

## Summary — what to check before any layout change

1. Is content in a single outer vertically-scrollable column? If not, fix it.
2. Does the card have NO internal scroll and NO `weight(1f)`? If not, fix it.
3. Is the fade applied to the outer viewport Box (not inside the card)? If not, fix it.
4. Does the fade start at mid-pill (`size.height - navBarPadding - 40dp`)? If not, fix it.
5. Is there a bottom spacer inside the card's content? If not, add it.
