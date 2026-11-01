package io.github.yuubiz.folirel.backend.local

import io.github.yuubiz.folirel.backend.local.parser.FeedDates
import io.github.yuubiz.folirel.model.FetchError
import io.github.yuubiz.folirel.model.Limits
import io.github.yuubiz.folirel.network.BlockedRequestException
import io.github.yuubiz.folirel.network.ConditionalValidators
import io.github.yuubiz.folirel.network.RedirectHop
import io.github.yuubiz.folirel.network.RedirectTrace
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.time.Clock
import javax.net.ssl.SSLException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

sealed interface FetchOutcome {
    class Success(
        val body: ByteArray,
        val charset: String?,
        val contentType: String?,
        val finalUrl: String,
        val etag: String?,
        val lastModified: String?,
        /** Target after the leading run of 301/308 hops, if any. */
        val permanentRedirectUrl: String?,
        val httpStatus: Int = 200,
        /** Whether stored validators were sent on the request that produced this response. */
        val validatorsSent: Boolean = false,
    ) : FetchOutcome

    data object NotModified : FetchOutcome

    data class Failure(
        val error: FetchError,
        val retryAfterMillis: Long? = null,
        val httpStatus: Int? = null,
        /** Which limit/problem, for diagnostics (e.g. BODY_SIZE). */
        val detail: String? = null,
    ) : FetchOutcome
}

/** GET only. Conditional requests, status classification and body limits (docs/fetch-and-security.md section 4). */
class FeedFetcher(
    private val client: OkHttpClient,
    private val clock: Clock,
    private val maxBytes: Long = Limits.FEED_MAX_BYTES,
) {
    /**
     * [revalidate]: explicit "revalidate and refetch" (SYNC-03). The stored validators are not sent and
     * `Cache-Control: no-cache` asks intermediaries to revalidate. This client has no local HTTP cache, so the
     * header is only a request to caches on the way; it cannot force a server or CDN to return fresh content.
     */
    suspend fun fetch(url: String, validators: ConditionalValidators?, initialized: Boolean, revalidate: Boolean = false): FetchOutcome {
        val first = execute(url, if (initialized && !revalidate) validators else null, revalidate)
        if (first !is Raw.NotModified) return first.toOutcome()
        if (initialized && first.validatorsSent) return FetchOutcome.NotModified
        // 304 on a first fetch (or without validators): retry once unconditionally. Never treated as success.
        val second = execute(url, null, revalidate)
        if (second is Raw.NotModified) return FetchOutcome.Failure(FetchError.UNEXPECTED_NOT_MODIFIED, httpStatus = 304)
        return second.toOutcome()
    }

    private sealed interface Raw {
        fun toOutcome(): FetchOutcome
        class Done(val outcome: FetchOutcome) : Raw {
            override fun toOutcome() = outcome
        }
        class NotModified(val validatorsSent: Boolean) : Raw {
            override fun toOutcome() = FetchOutcome.NotModified
        }
    }

    private suspend fun execute(url: String, validators: ConditionalValidators?, revalidate: Boolean): Raw {
        val httpUrl = url.toHttpUrlOrNull() ?: return Raw.Done(FetchOutcome.Failure(FetchError.SECURITY))
        val trace = RedirectTrace()
        val request = Request.Builder()
            .url(httpUrl)
            .get()
            .header("Accept", "application/rss+xml, application/atom+xml, application/rdf+xml, application/feed+json, application/xml;q=0.9, text/xml;q=0.9, application/json;q=0.9, */*;q=0.5")
            .tag(RedirectTrace::class.java, trace)
            .apply { if (revalidate) header("Cache-Control", "no-cache") }
            .apply { if (validators != null) tag(ConditionalValidators::class.java, validators) }
            .build()
        return try {
            client.newCall(request).await { response -> handle(httpUrl.toString(), response, trace, validators) }
        } catch (e: BlockedRequestException) {
            Raw.Done(FetchOutcome.Failure(e.error))
        } catch (_: SSLException) {
            Raw.Done(FetchOutcome.Failure(FetchError.TLS))
        } catch (_: SocketTimeoutException) {
            Raw.Done(FetchOutcome.Failure(FetchError.TIMEOUT))
        } catch (e: InterruptedIOException) {
            Raw.Done(FetchOutcome.Failure(if (e.message == "timeout") FetchError.TIMEOUT else FetchError.NETWORK))
        } catch (_: IOException) {
            Raw.Done(FetchOutcome.Failure(FetchError.NETWORK))
        }
    }

    private fun handle(requestedUrl: String, response: Response, trace: RedirectTrace, validators: ConditionalValidators?): Raw {
        val code = response.code
        val failure = when {
            code == 304 -> return Raw.NotModified(validatorsSent = validators != null && validators.url == response.request.url.toString())
            code in 200..299 -> null
            code == 401 || code == 403 -> FetchError.ACCESS_DENIED
            code == 404 -> FetchError.NOT_FOUND
            code == 410 -> FetchError.GONE
            code == 429 -> FetchError.RATE_LIMITED
            code in 500..599 -> FetchError.SERVER
            else -> FetchError.HTTP_STATUS
        }
        if (failure != null) {
            val retryAfter = if (failure == FetchError.RATE_LIMITED || failure == FetchError.SERVER) retryAfterMillis(response.header("Retry-After")) else null
            return Raw.Done(FetchOutcome.Failure(failure, retryAfter, code))
        }
        val body = response.body
        if (body.contentLength() > maxBytes) return Raw.Done(FetchOutcome.Failure(FetchError.LIMIT_EXCEEDED, httpStatus = code, detail = "BODY_SIZE"))
        val buffer = Buffer()
        val source = body.source()
        while (true) {
            val read = source.read(buffer, 64 * 1024)
            if (read == -1L) break
            // Counted after transparent gzip decompression.
            if (buffer.size > maxBytes) return Raw.Done(FetchOutcome.Failure(FetchError.LIMIT_EXCEEDED, httpStatus = code, detail = "BODY_SIZE"))
        }
        val permanent = permanentTarget(requestedUrl, trace.hops)
        return Raw.Done(
            FetchOutcome.Success(
                body = buffer.readByteArray(),
                charset = body.contentType()?.charset()?.name(),
                contentType = body.contentType()?.toString(),
                finalUrl = response.request.url.toString(),
                etag = response.header("ETag"),
                lastModified = response.header("Last-Modified"),
                permanentRedirectUrl = permanent,
                httpStatus = code,
                validatorsSent = validators != null,
            ),
        )
    }

    /** Retry-After as delta-seconds or HTTP-date. Invalid → null (caller applies the default). */
    fun retryAfterMillis(value: String?): Long? {
        val v = value?.trim().orEmpty()
        if (v.isEmpty()) return null
        v.toLongOrNull()?.let { seconds -> return if (seconds < 0) null else seconds.coerceAtMost(MAX_RETRY_AFTER_SECONDS) * 1000 }
        val date = FeedDates.parse(v) ?: return null
        return date - clock.millis()
    }

    internal companion object {
        /**
         * The address a feed moved to for good: the target of the leading run of 301/308 hops. The run ends before a hop from https:// to
         * http://: with HTTP allowed such a redirect is followed for this fetch, but it never becomes the feed's address (the app never
         * falls back from HTTPS to HTTP on its own; docs/fetch-and-security.md section 6).
         */
        fun permanentTarget(requestedUrl: String, hops: List<RedirectHop>): String? {
            var from = requestedUrl
            var permanent: String? = null
            for (hop in hops) {
                if (hop.status != 301 && hop.status != 308) break
                if (from.startsWith("https://", ignoreCase = true) && hop.target.startsWith("http://", ignoreCase = true)) break
                permanent = hop.target
                from = hop.target
            }
            return permanent
        }

        // Only an overflow guard; the store applies the real upper bound (RetryPolicy.MAX_SERVER_WAIT_MS).
        private const val MAX_RETRY_AFTER_SECONDS = 10L * 365 * 24 * 60 * 60
    }
}

/** Enqueues the call and runs [block] on the response; cancelling the coroutine cancels the call. */
internal suspend fun <T> Call.await(block: (Response) -> T): T = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            val result = runCatching { response.use(block) }
            result.fold(onSuccess = { cont.resume(it) }, onFailure = { cont.resumeWithException(it) })
        }
    })
}
