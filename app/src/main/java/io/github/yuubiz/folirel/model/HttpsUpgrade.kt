package io.github.yuubiz.folirel.model

/** Outcome of checking one http:// feed over HTTPS (user-consented migration). */
enum class HttpsVerdict {
    /** Verified over HTTPS and the fetch URL was changed. */
    CHANGED,
    /** Checked: HTTPS is not usable for this feed (or reaches something else). The HTTP URL stays. */
    UNCHANGED,
    /** Could not be decided (timeout, network, busy server, cancelled). The HTTP URL stays; check again later. */
    UNCHECKED,
}

enum class HttpsReason {
    OK,
    /** An HTML page instead of a feed. */
    HTML_PAGE,
    /** XML that is not RSS/Atom, or broken XML. */
    NOT_A_FEED,
    /** Certificate / TLS failure (verification is never relaxed). */
    TLS_ERROR,
    /** HTTPS redirected back to http://. */
    DOWNGRADE_TO_HTTP,
    /** HTTPS answered with a feed, but apparently not the same feed. */
    DIFFERENT_FEED,
    /**
     * HTTPS redirected to another address and nothing confirms it is the same feed. With HTTP allowed, the HTTP
     * document is compared too; without it the URL stays and the feed counts as not checked.
     */
    REDIRECT_UNCONFIRMED,
    /** 4xx / unexpected status / redirect problems. */
    HTTP_STATUS,
    /** Address or content refused by the security policy. */
    BLOCKED,
    TOO_LARGE,
    TIMEOUT,
    NETWORK,
    /** 429 / 5xx: try later. */
    SERVER_BUSY,
    CANCELLED,
    /** The feed changed (unsubscribed, URL edited) while it was being checked. */
    SKIPPED,
}

data class HttpsCheck(
    val feedId: String,
    val name: String,
    val httpUrl: String,
    val httpsUrl: String?,
    val verdict: HttpsVerdict,
    val reason: HttpsReason,
    val httpStatus: Int? = null,
)
