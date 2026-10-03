package io.github.yuubiz.feeddeck.model

/** Persisted as `FeedFetchState.lastErrorCode` (enum name). Names are stable storage values. */
enum class FetchError {
    /** No connectivity / DNS failure / connection refused. */
    NETWORK,
    TIMEOUT,
    /** http:// URL while the user has not opted in to cleartext feeds/images. Nothing was sent. */
    HTTP_BLOCKED,
    /** Unsafe URL, private/loopback address, userinfo, or rejected XML construct (DOCTYPE etc.). */
    SECURITY,
    /** Certificate / hostname verification failure. Verification is never relaxed. */
    TLS,
    /** 401 / 403. */
    ACCESS_DENIED,
    /** 404. */
    NOT_FOUND,
    /** 410. */
    GONE,
    /** 429. Retry-After honored. */
    RATE_LIMITED,
    /** 5xx. */
    SERVER,
    /** Other unexpected HTTP status. */
    HTTP_STATUS,
    REDIRECT_LOOP,
    TOO_MANY_REDIRECTS,
    /** Syntactically broken XML. */
    FORMAT,
    /** HTML page instead of a feed. */
    NOT_A_FEED,
    /** Well-formed XML but not RSS 2.0 / Atom 1.0 / RSS 1.0. */
    UNSUPPORTED_FORMAT,
    /** Response size / entry count / XML depth above the fixed limits. Nothing stored. */
    LIMIT_EXCEEDED,
    /** 304 twice on a first (unconditional) fetch. */
    UNEXPECTED_NOT_MODIFIED,
    /** Database write failed. */
    STORAGE,
    ;

    companion object {
        fun fromStored(value: String?): FetchError? = value?.let { v -> entries.firstOrNull { it.name == v } }
    }
}
