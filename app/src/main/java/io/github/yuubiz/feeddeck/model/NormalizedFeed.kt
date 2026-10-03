package io.github.yuubiz.feeddeck.model

/** Backend-independent result of parsing one feed response. Holds no DB or Android types. */
data class NormalizedFeed(
    val title: String?,
    val siteUrl: String?,
    val entries: List<NormalizedEntry>,
    /** item/entry elements in the document before duplicate keys were merged (diagnostics). */
    val rawEntryCount: Int = entries.size,
)

data class NormalizedEntry(
    /** `id:` / `url:` / `fallback:` prefixed key, unique per feed (docs/03 section 3). */
    val entryKey: String,
    /** Empty when the feed has no title; the UI shows a placeholder. */
    val title: String,
    /** Validated http(s) URL, or null when missing/unsafe. */
    val linkUrl: String?,
    /** Plain text, at most [Limits.SUMMARY_CODE_POINTS] code points. Never HTML. */
    val summaryText: String,
    val author: String?,
    val publishedAt: Long?,
    val imageUrl: String?,
)

object Limits {
    const val TITLE_CODE_POINTS = 500
    const val AUTHOR_CODE_POINTS = 256
    const val SUMMARY_CODE_POINTS = 2_000
    const val FEED_MAX_BYTES = 5L * 1024 * 1024
    const val FEED_MAX_ENTRIES = 2_000
    const val FEED_MAX_DEPTH = 64
    const val OPML_MAX_BYTES = 5L * 1024 * 1024
    const val OPML_MAX_DEPTH = 32
    const val OPML_MAX_OUTLINES = 10_000
    const val OPML_MAX_FEED_CANDIDATES = 5_000
    const val IMAGE_MAX_BYTES = 8L * 1024 * 1024
}
