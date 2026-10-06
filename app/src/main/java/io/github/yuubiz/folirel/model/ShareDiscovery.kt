package io.github.yuubiz.folirel.model

/** A feed that was really fetched and parsed while looking for what a shared address offers (Share to Subscribe). */
data class ShareCandidate(
    /** The address of the feed after redirects; what is shown. */
    val url: String,
    /** The address to hand to the add-feed path: the one that matched [existing] when there is one, else [url]. */
    val subscribeUrl: String,
    val feedTitle: String?,
    val siteUrl: String?,
    val entryCount: Int,
    val newestAt: Long?,
    /** The title the page gave this link, if any. */
    val titleOnPage: String?,
    /** The feed the user already has for this address (subscribed or not); null when it is new. */
    val existing: ExistingFeed? = null,
    /** Where the feed was found. */
    val provenance: ShareProvenance = ShareProvenance.SHARED_PAGE,
)

/** A feed row that already exists for an address. Unsubscribing is soft, so the row (and its articles) outlives it. */
data class ExistingFeed(val feedId: String, val name: String, val subscribed: Boolean)

/** Why a shared address cannot be turned into a subscription, in terms a person can act on (never an exception text). */
enum class ShareProblem {
    /** The shared text has no http/https address. */
    NO_URL,

    /** The shared text is above the size limit; nothing was read from it. */
    TEXT_TOO_LARGE,
    INVALID_URL,

    /** The page was read but neither it nor its declared links gave a feed. */
    NO_FEED,

    /** The page declared feed links but none could be read as a feed. */
    FEEDS_UNREADABLE,

    /** http:// while cleartext HTTP is not allowed: nothing was sent. */
    HTTP_NOT_ALLOWED,
    TLS,
    NETWORK,

    /** Unsafe address (private/loopback target, redirect loop, too many redirects). */
    BLOCKED,

    /** The server answered with an error status. */
    PAGE_STATUS,
    PAGE_TOO_LARGE,

    /** Something unexpected; the details are not shown. */
    FAILED,
}

/** Outcome of looking at one shared address. Nothing has been stored. */
sealed interface ShareDiscovery {
    /** At least one real feed; [candidates] are in the page's own order, only verified feeds. */
    data class Found(val pageUrl: String?, val candidates: List<ShareCandidate>) : ShareDiscovery

    data class Failed(val problem: ShareProblem) : ShareDiscovery
}

/** Where a shared address's feed was found: the address itself, the page that was shared, or (only when that page gave none) the root of its site. */
enum class ShareProvenance { DIRECT_FEED, SHARED_PAGE, SITE_ROOT }
