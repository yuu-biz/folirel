package io.github.yuubiz.feeddeck.model

/** How a candidate feed address was found. */
enum class CandidateVia {
    /** `link rel=alternate` of the site page. */
    SITE_PAGE,

    /** The address the user typed or pasted, and it is a feed itself. */
    ENTERED_URL,
}

/**
 * One feed address found by "find where this feed moved" (SUB-DISCOVERY). Facts only: what was found, whether it
 * can be fetched and parsed as a feed, and what it has in common with the subscribed feed. Nothing here says
 * the candidate is the same operator or the right one: the user decides.
 */
data class DiscoveryCandidate(
    val url: String,
    val via: CandidateVia,
    /** True only when the address returned a body that parsed as RSS/Atom/RDF. HTML is never a candidate feed. */
    val fetchable: Boolean,
    /** Why it cannot be used (when [fetchable] is false). */
    val failure: FetchError? = null,
    val feedTitle: String? = null,
    val siteUrl: String? = null,
    val entryCount: Int = 0,
    val oldestAt: Long? = null,
    val newestAt: Long? = null,
    /** Entries (by URL-like key, http/https alike) that the subscribed feed already has: strong evidence. */
    val sharedEntries: Int = 0,
    /** Same feed title as the subscribed feed (weak evidence on its own; many sites share titles). */
    val sameTitle: Boolean = false,
    /** The title the site page gave this link, if any. */
    val titleOnPage: String? = null,
)

/** Result of one discovery run. */
data class DiscoveryResult(
    /** The page that was read for feed links (after redirects), or null when nothing could be read. */
    val pageUrl: String?,
    val page: PageStatus,
    val candidates: List<DiscoveryCandidate>,
    /** The fetch URL of the subscription the candidates were found for; adopting one is valid only while it is still the feed's URL. */
    val sourceFetchUrl: String? = null,
) {
    enum class PageStatus {
        /** A page was read. */
        READ,

        /** The entered address was itself a feed. */
        FEED_ENTERED,

        /** No address to look at (no site known). */
        NO_SITE,

        /** HTTP address while cleartext HTTP is not allowed. */
        HTTP_NOT_ALLOWED,

        /** The page could not be read (error, not found, unsafe address, invalid address). */
        UNREADABLE,
    }
}
