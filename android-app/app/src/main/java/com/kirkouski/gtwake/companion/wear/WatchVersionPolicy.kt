package com.kirkouski.gtwake.companion.wear

/** A watch that reports no code is older than 1.0.13. */
const val WATCH_CODE_OLDER = 0

/**
 * The phone can ask users to update the watch app, and can gate breaking
 * changes. Raise [REQUIRED_WATCH_CODE] only after AppGallery serves that build:
 * before that, the prompt has nothing to offer. Breaking-change rule:
 * docs/sync-architecture.md.
 */
object WatchVersionPolicy {
    const val REQUIRED_WATCH_CODE = 0
    private const val CODE_BASE = 1_000_000
    private const val VERSION_PREFIX = "1.0."

    /** An unknown version never prompts. */
    fun needsUpdate(stored: Int?, required: Int = REQUIRED_WATCH_CODE): Boolean =
        stored != null && stored < required

    /** 1000013 gives `1.0.13`. Null for a code that is not an exact version. */
    fun versionName(code: Int?): String? =
        code?.takeIf { it > CODE_BASE }?.let { VERSION_PREFIX + (it - CODE_BASE) }
}
