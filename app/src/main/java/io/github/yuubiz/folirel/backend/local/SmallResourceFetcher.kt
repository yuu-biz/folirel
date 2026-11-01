package io.github.yuubiz.folirel.backend.local

import io.github.yuubiz.folirel.network.BlockedRequestException
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import java.io.IOException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException

/** Outcome of a small, bounded GET (site page, icon). Never a feed fetch: its failures are not feed failures. */
sealed interface ResourceResult {
    class Ok(
        val bytes: ByteArray,
        val finalUrl: String,
        val contentType: String?,
        /** The body was longer than the limit and was cut (only when truncation was requested). */
        val truncated: Boolean,
    ) : ResourceResult

    data class Failed(val kind: Kind, val httpStatus: Int? = null) : ResourceResult

    enum class Kind {
        /** http:// while cleartext HTTP is not allowed: nothing was sent. */
        HTTP_BLOCKED,

        /** Unsafe address or URL (private/loopback target, userinfo, loop, too many redirects). */
        BLOCKED,
        TLS,
        STATUS,
        TOO_LARGE,
        NETWORK,
    }

    /** Worth trying again later (a change of settings or a temporary problem), as opposed to "there is nothing". */
    val retryable: Boolean
        get() = when (this) {
            is Ok -> false
            is Failed -> when (kind) {
                Kind.HTTP_BLOCKED, Kind.NETWORK -> true
                Kind.STATUS -> httpStatus == 429 || (httpStatus != null && httpStatus >= 500)
                Kind.BLOCKED, Kind.TLS, Kind.TOO_LARGE -> false
            }
        }
}


/**
 * GET with a hard byte limit, through the app's network client (same address policy, HTTP opt-in and TLS
 * verification as feeds; every redirect hop is checked). No cookies, no JavaScript, nothing is stored here.
 */
class SmallResourceFetcher(private val client: OkHttpClient) {
    /** [truncate]: a body above [maxBytes] is cut at the limit instead of failing (for the head of an HTML page). */
    suspend fun get(url: String, maxBytes: Long, accept: String, truncate: Boolean = false): ResourceResult =
        read(url, accept, pageMaxBytes = maxBytes, truncate = truncate, feedMaxBytes = null)

    /**
     * One read of an address that may be a page or a feed. The limit depends on what the document turns out to be, decided from its
     * root element (or a leading `{`) once the first [SNIFF_BYTES] are in: an RSS/Atom/RDF or JSON document may be [feedMaxBytes] long (above that it fails, like
     * the feed fetcher), anything else is cut at [pageMaxBytes] like the head of a page. The caller can use the bytes of a feed as
     * they are, so a feed address is not fetched a second time to be checked.
     */
    suspend fun getPageOrFeed(url: String, pageMaxBytes: Long, feedMaxBytes: Long, accept: String): ResourceResult =
        read(url, accept, pageMaxBytes = pageMaxBytes, truncate = true, feedMaxBytes = feedMaxBytes)

    private suspend fun read(url: String, accept: String, pageMaxBytes: Long, truncate: Boolean, feedMaxBytes: Long?): ResourceResult {
        val httpUrl = url.toHttpUrlOrNull() ?: return ResourceResult.Failed(ResourceResult.Kind.BLOCKED)
        val request = Request.Builder().url(httpUrl).get().header("Accept", accept).build()
        return try {
            client.newCall(request).await { response ->
                if (response.code !in 200..299) return@await ResourceResult.Failed(ResourceResult.Kind.STATUS, response.code)
                val body = response.body
                val hardLimit = feedMaxBytes ?: pageMaxBytes
                if ((!truncate || feedMaxBytes != null) && body.contentLength() > hardLimit) return@await ResourceResult.Failed(ResourceResult.Kind.TOO_LARGE)
                val buffer = Buffer()
                val source = body.source()
                var truncated = false
                // Until the kind of document is known the larger limit applies.
                var limit = hardLimit
                var kindKnown = feedMaxBytes == null
                var isFeed = false
                while (true) {
                    val read = source.read(buffer, 16 * 1024)
                    if (read == -1L) break
                    if (!kindKnown && buffer.size >= SNIFF_BYTES) {
                        isFeed = HtmlLinks.isFeedDocument(buffer.snapshot(SNIFF_BYTES.toInt()).toByteArray())
                        limit = if (isFeed) feedMaxBytes!! else pageMaxBytes
                        kindKnown = true
                    }
                    if (buffer.size > limit) {
                        if (!truncate || isFeed) return@await ResourceResult.Failed(ResourceResult.Kind.TOO_LARGE)
                        truncated = true
                        break
                    }
                }
                val bytes = buffer.readByteArray(minOf(buffer.size, limit))
                ResourceResult.Ok(bytes, response.request.url.toString(), body.contentType()?.toString(), truncated)
            }
        } catch (e: BlockedRequestException) {
            ResourceResult.Failed(if (e.error == io.github.yuubiz.folirel.model.FetchError.HTTP_BLOCKED) ResourceResult.Kind.HTTP_BLOCKED else ResourceResult.Kind.BLOCKED)
        } catch (_: SSLException) {
            ResourceResult.Failed(ResourceResult.Kind.TLS)
        } catch (_: SocketTimeoutException) {
            ResourceResult.Failed(ResourceResult.Kind.NETWORK)
        } catch (_: IOException) {
            ResourceResult.Failed(ResourceResult.Kind.NETWORK)
        }
    }

    private companion object {
        /** How much of a document is looked at to tell a feed from a page; both limits are larger than this. */
        const val SNIFF_BYTES = 64L * 1024
    }
}
