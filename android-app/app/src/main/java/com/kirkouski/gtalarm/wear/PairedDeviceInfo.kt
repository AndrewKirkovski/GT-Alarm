package com.kirkouski.gtalarm.wear

/**
 * Snapshot of the currently bonded Huawei wearable as reported by Wear Engine's
 * `DeviceClient.getBondedDevices()`. Surfaced via [WearBridgeService.pairedDeviceInfo]
 * so the alarm-list "Watch sync" card can show a model name next to the icon.
 *
 * `null` means "no bonded device yet resolved" (cold start, Wear Engine SDK
 * absent, or Huawei Health permission not granted).
 *
 * [displayName] is the pre-normalized single-line label — see [normalizeDeviceLabel].
 */
data class PairedDeviceInfo(
    val displayName: String,
    val connected: Boolean,
)

/**
 * Collapse the raw Wear Engine `name` / `model` pair into a clean display
 * label like `"Huawei GT 6 Pro"`. The raw `name` is the BT broadcast name
 * (e.g. `"HUAWEI WATCH GT 6 Pro-AB1"` or the user's renamed string), and
 * `model` is the SKU code (e.g. `"FRD-BX9"`). We:
 *
 *  - prefer the `name` (human-readable) but fall through to `model` when it's
 *    blank,
 *  - strip the BT-broadcast serial suffix (`-AB1`, `-X1Z`, etc.) repeatedly
 *    so chained suffixes like `-AB1-XX` are all peeled off,
 *  - normalize the all-caps `"HUAWEI"` to `"Huawei"`,
 *  - drop the redundant `"WATCH"` word (the icon already says "watch"),
 *  - collapse repeated whitespace.
 *
 * Returns `""` when both inputs are blank — the card hides the label entirely
 * in that case rather than render a stub.
 */
fun normalizeDeviceLabel(rawName: String?, rawModel: String?): String {
    val source = rawName.orEmpty().trim().ifBlank { rawModel.orEmpty().trim() }
    if (source.isBlank()) return ""

    // Repeatedly peel trailing serial fragments: " -AB1", "_XX9", " ABC1".
    // Stops once the candidate no longer ends with a suffix-looking token.
    val suffixRegex = Regex("""[-_\s]+[A-Z0-9]{2,8}$""")
    var trimmed = source
    while (true) {
        val next = trimmed.replace(suffixRegex, "").trim()
        if (next == trimmed || next.isBlank()) break
        trimmed = next
    }

    // Normalize brand keywords. Done after suffix-strip so the brand-case fix
    // applies to the cleaned string. The `WATCH`/`Watch` removal is
    // case-insensitive and word-bounded so "GT" or "Bracelet" never get hit.
    trimmed = trimmed
        .replace(Regex("""\bHUAWEI\b""", RegexOption.IGNORE_CASE), "Huawei")
        .replace(Regex("""\bWATCH\b""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("""\s+"""), " ")
        .trim()

    return trimmed
}
