package io.github.yuubiz.folirel.network

import io.github.yuubiz.folirel.model.FetchError
import okhttp3.CookieJar
import okhttp3.Dispatcher
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/** A request refused by the app-level network policy before any HTTP bytes were sent. */
class BlockedRequestException(val error: FetchError, message: String) : IOException(message)

/**
 * The single network policy for feeds and images (docs/fetch-and-security.md section 6):
 * - only http/https, no userinfo; http only after the user's opt-in
 * - every resolved address (and every IP literal) must pass [addressPolicy]; OkHttp connects to exactly the
 *   addresses returned by [dns], so the validated answer is the one used for the connection
 * - checked again on every redirect hop by [SafeRedirectInterceptor]
 */
class NetworkGuard(
    private val addressPolicy: AddressPolicy,
    private val allowHttp: () -> Boolean,
    private val resolver: Dns = Dns.SYSTEM,
) {
    val dns: Dns = Dns { hostname ->
        val addresses = resolver.lookup(hostname)
        if (addresses.isEmpty()) throw java.net.UnknownHostException(hostname)
        if (addresses.any { !addressPolicy.isAllowed(it) }) {
            throw BlockedRequestException(FetchError.SECURITY, "Host resolves to a non-public address")
        }
        addresses
    }

    fun check(url: HttpUrl) {
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) {
            throw BlockedRequestException(FetchError.SECURITY, "userinfo in URL")
        }
        if (!url.isHttps) {
            if (url.scheme != "http") throw BlockedRequestException(FetchError.SECURITY, "Unsupported scheme")
            if (!allowHttp()) throw BlockedRequestException(FetchError.HTTP_BLOCKED, "Cleartext HTTP is disabled")
        }
        // OkHttp connects to numeric hosts without consulting Dns; validate them here with the same parsing.
        val host = url.host
        if (IP_LITERAL.matches(host)) {
            val address = try {
                InetAddress.getByName(host)
            } catch (_: IOException) {
                throw BlockedRequestException(FetchError.SECURITY, "Invalid address literal")
            }
            if (!addressPolicy.isAllowed(address)) {
                throw BlockedRequestException(FetchError.SECURITY, "Non-public address literal")
            }
        }
    }

    private companion object {
        // Same shape OkHttp uses to decide that a host is an IP address.
        val IP_LITERAL = Regex("([0-9a-fA-F]*:[0-9a-fA-F:.]*)|([\\d.]+)")
    }
}

/** Optional request tag: validators are attached only to the hop whose URL issued them. */
data class ConditionalValidators(val url: String, val etag: String?, val lastModified: String?)

data class RedirectHop(val status: Int, val target: String)

/** Optional request tag collecting redirect hops for the caller. */
class RedirectTrace {
    val hops = mutableListOf<RedirectHop>()
}

/** Follows redirects manually so every hop is validated (scheme, http opt-in, address) before contacting it. */
class SafeRedirectInterceptor(
    private val guard: NetworkGuard,
    private val maxRedirects: Int = MAX_REDIRECTS,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        var request = chain.request()
        val trace = request.tag(RedirectTrace::class.java)
        val validators = request.tag(ConditionalValidators::class.java)
        val visited = HashSet<String>()
        var redirects = 0
        while (true) {
            guard.check(request.url)
            if (!visited.add(request.url.toString())) throw BlockedRequestException(FetchError.REDIRECT_LOOP, "Redirect loop")
            request = withValidators(request, validators)
            val response = chain.proceed(request)
            if (response.code !in REDIRECT_CODES) return response
            val target = response.header("Location")?.let { response.request.url.resolve(it) } ?: return response
            response.close()
            if (++redirects > maxRedirects) throw BlockedRequestException(FetchError.TOO_MANY_REDIRECTS, "Too many redirects")
            trace?.hops?.add(RedirectHop(response.code, target.toString()))
            request = request.newBuilder().url(target).get().build()
        }
    }

    private fun withValidators(request: Request, validators: ConditionalValidators?): Request {
        val builder = request.newBuilder().removeHeader("If-None-Match").removeHeader("If-Modified-Since")
        if (validators != null && validators.url == request.url.toString()) {
            validators.etag?.let { builder.header("If-None-Match", it) }
            validators.lastModified?.let { builder.header("If-Modified-Since", it) }
        }
        return builder.build()
    }

    companion object {
        const val MAX_REDIRECTS = 5
        private val REDIRECT_CODES = setOf(300, 301, 302, 303, 307, 308)
    }
}

private class UserAgentInterceptor(private val userAgent: String) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response =
        chain.proceed(chain.request().newBuilder().header("User-Agent", userAgent).build())
}

/** Fails image responses above [maxBytes] (declared or actually read). */
private class ResponseSizeLimitInterceptor(private val maxBytes: Long) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        val body = response.body
        if (body.contentLength() > maxBytes) {
            response.close()
            throw BlockedRequestException(FetchError.LIMIT_EXCEEDED, "Image larger than limit")
        }
        val limited = object : ForwardingSource(body.source()) {
            private var total = 0L
            override fun read(sink: Buffer, byteCount: Long): Long {
                val read = super.read(sink, byteCount)
                if (read > 0) {
                    total += read
                    if (total > maxBytes) throw BlockedRequestException(FetchError.LIMIT_EXCEEDED, "Image larger than limit")
                }
                return read
            }
        }.buffer()
        return response.newBuilder().body(limited.asResponseBody(body.contentType(), body.contentLength())).build()
    }
}

object HttpClients {
    const val CONNECT_TIMEOUT_SECONDS = 10L
    const val IO_TIMEOUT_SECONDS = 10L
    const val CALL_TIMEOUT_SECONDS = 25L

    /** Base client for feeds: no cookies, no HTTP cache, no proxy, no automatic redirects. */
    fun feedClient(guard: NetworkGuard, userAgent: String): OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .proxy(Proxy.NO_PROXY)
        .dns(guard.dns)
        .cookieJar(CookieJar.NO_COOKIES)
        .cache(null)
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        // Inactivity timeouts shorter than the call budget: a stalled TLS handshake fails after 10 s and OkHttp
        // can still retry (e.g. with its compatible TLS spec; certificates are verified either way) within 25 s.
        .readTimeout(IO_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(IO_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .addInterceptor(UserAgentInterceptor(userAgent))
        .addInterceptor(SafeRedirectInterceptor(guard))
        .build()

    /**
     * Image client: same policy; own bounded dispatcher; per-response size limit. The limit is an application interceptor (inside the
     * redirect one, so it sees every hop): it then counts the body after OkHttp has removed gzip, the bytes the decoder would get. As a
     * network interceptor it counted the compressed bytes, and a small gzip body could expand far past the limit.
     */
    fun imageClient(feedClient: OkHttpClient, maxBytes: Long): OkHttpClient = feedClient.newBuilder()
        .dispatcher(Dispatcher().apply { maxRequests = 4; maxRequestsPerHost = 2 })
        .addInterceptor(ResponseSizeLimitInterceptor(maxBytes))
        .build()
}
