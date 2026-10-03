package io.github.yuubiz.feeddeck.backend.local

import io.github.yuubiz.feeddeck.backend.local.parser.FeedParseException
import io.github.yuubiz.feeddeck.backend.local.parser.FeedParser
import io.github.yuubiz.feeddeck.backend.local.parser.TextNormalizer
import io.github.yuubiz.feeddeck.backend.local.parser.XmlCharset
import io.github.yuubiz.feeddeck.model.FetchError
import io.github.yuubiz.feeddeck.model.HttpsReason
import io.github.yuubiz.feeddeck.model.HttpsVerdict
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Checks whether an http:// feed can be fetched over HTTPS. Used only after the user agreed to the migration.
 *
 * Success requires all of: a real GET to the https:// candidate with normal certificate verification, every
 * redirect hop staying on HTTPS ([fetcher] must use a client whose network guard never allows cleartext HTTP),
 * a 2xx body that parses as RSS/Atom, and evidence that it is the same feed. A 200, a HEAD or an HTML page alone
 * never counts. Timeouts and network errors are retried once and then reported as "not checked", never as
 * "HTTPS not supported".
 */
class HttpsUpgradeChecker(
    private val fetcher: FeedFetcher,
    private val parseDispatcher: CoroutineDispatcher = Dispatchers.Default,
    /** http → https candidate. Default: same host/path/query, default port 80 becomes 443. */
    private val candidateFor: (HttpUrl) -> HttpUrl = { it.newBuilder().scheme("https").build() },
) {
    /** What is already known about the feed, to tell "the same feed over HTTPS" from "something else". */
    class Known(val feedTitle: String?, val entryKeys: Set<String>)

    data class Result(val verdict: HttpsVerdict, val reason: HttpsReason, val httpsUrl: String?, val httpStatus: Int? = null)

    suspend fun check(httpUrl: String, known: Known): Result {
        val original = httpUrl.toHttpUrlOrNull()
        if (original == null || original.isHttps) return Result(HttpsVerdict.UNCHANGED, HttpsReason.SKIPPED, null)
        val candidate = candidateFor(original).toString()
        var outcome = fetcher.fetch(candidate, null, initialized = false)
        if (outcome is FetchOutcome.Failure && (outcome.error == FetchError.TIMEOUT || outcome.error == FetchError.NETWORK)) {
            outcome = fetcher.fetch(candidate, null, initialized = false) // one more try before "not checked"
        }
        return when (outcome) {
            is FetchOutcome.Failure -> failure(outcome, candidate)
            FetchOutcome.NotModified -> Result(HttpsVerdict.UNCHECKED, HttpsReason.HTTP_STATUS, candidate, 304)
            is FetchOutcome.Success -> verify(outcome, original, candidate, known)
        }
    }

    private suspend fun verify(outcome: FetchOutcome.Success, original: HttpUrl, candidate: String, known: Known): Result {
        val final = outcome.finalUrl.toHttpUrlOrNull()
        if (final == null || !final.isHttps) return Result(HttpsVerdict.UNCHANGED, HttpsReason.DOWNGRADE_TO_HTTP, candidate, outcome.httpStatus)
        val parsed = try {
            withContext(parseDispatcher) { FeedParser.parse(XmlCharset.decode(outcome.body, outcome.charset).text, outcome.finalUrl) }
        } catch (e: FeedParseException) {
            val reason = when (e.error) {
                FetchError.NOT_A_FEED -> HttpsReason.HTML_PAGE
                FetchError.LIMIT_EXCEEDED -> HttpsReason.TOO_LARGE
                FetchError.SECURITY -> HttpsReason.BLOCKED
                else -> HttpsReason.NOT_A_FEED
            }
            return Result(HttpsVerdict.UNCHANGED, reason, candidate, outcome.httpStatus)
        }
        val samePlace = sameHost(original, final) && samePath(original, final)
        val titleMatches = known.feedTitle != null && parsed.title != null &&
            TextNormalizer.normalize(known.feedTitle).equals(TextNormalizer.normalize(parsed.title), ignoreCase = true)
        val knownKeys = known.entryKeys.mapTo(HashSet(), ::comparableKey)
        val overlap = parsed.entries.any { comparableKey(it.entryKey) in knownKeys }
        val hasHistory = known.entryKeys.isNotEmpty()
        val same = if (samePlace) {
            // Same address: accept unless what we already have clearly disagrees with what HTTPS returned.
            !(hasHistory && !overlap && known.feedTitle != null && !titleMatches)
        } else {
            // Redirected somewhere else: only with positive evidence that it is the same feed.
            overlap || titleMatches
        }
        return if (same) Result(HttpsVerdict.CHANGED, HttpsReason.OK, candidate, outcome.httpStatus)
        else Result(HttpsVerdict.UNCHANGED, HttpsReason.DIFFERENT_FEED, candidate, outcome.httpStatus)
    }

    private fun failure(outcome: FetchOutcome.Failure, candidate: String): Result {
        val (verdict, reason) = when (outcome.error) {
            FetchError.TLS -> HttpsVerdict.UNCHANGED to HttpsReason.TLS_ERROR
            FetchError.HTTP_BLOCKED -> HttpsVerdict.UNCHANGED to HttpsReason.DOWNGRADE_TO_HTTP
            FetchError.SECURITY -> HttpsVerdict.UNCHANGED to HttpsReason.BLOCKED
            FetchError.LIMIT_EXCEEDED -> HttpsVerdict.UNCHANGED to HttpsReason.TOO_LARGE
            FetchError.ACCESS_DENIED, FetchError.NOT_FOUND, FetchError.GONE, FetchError.HTTP_STATUS,
            FetchError.REDIRECT_LOOP, FetchError.TOO_MANY_REDIRECTS -> HttpsVerdict.UNCHANGED to HttpsReason.HTTP_STATUS
            FetchError.RATE_LIMITED, FetchError.SERVER -> HttpsVerdict.UNCHECKED to HttpsReason.SERVER_BUSY
            FetchError.TIMEOUT -> HttpsVerdict.UNCHECKED to HttpsReason.TIMEOUT
            else -> HttpsVerdict.UNCHECKED to HttpsReason.NETWORK
        }
        return Result(verdict, reason, candidate, outcome.httpStatus)
    }

    private fun sameHost(a: HttpUrl, b: HttpUrl) = a.host.removePrefix("www.").equals(b.host.removePrefix("www."), ignoreCase = true)

    private fun samePath(a: HttpUrl, b: HttpUrl) =
        a.encodedPath.trimEnd('/') == b.encodedPath.trimEnd('/') && a.encodedQuery == b.encodedQuery

    companion object {
        /** Entry keys compared without the http/https difference of URL-like ids and links. */
        fun comparableKey(key: String): String = key.replace("://www.", "://").replaceFirst("https://", "http://")
    }
}
