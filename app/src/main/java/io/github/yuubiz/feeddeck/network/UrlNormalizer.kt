package io.github.yuubiz.feeddeck.network

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * URL identity rules (docs/data-and-import.md section 2).
 *
 * Parsing is delegated to OkHttp's [HttpUrl]: scheme/host lower-cased, IDN per library rules,
 * default port omitted, empty path becomes "/". The fragment is removed. Path case, trailing slash,
 * query order/duplicates and http vs https are preserved. userinfo and non-HTTP(S) schemes are rejected.
 */
object UrlNormalizer {
    const val MAX_URL_LENGTH = 8 * 1024

    sealed interface Result {
        data class Valid(val url: String, val isHttps: Boolean) : Result
        data class Invalid(val reason: Reason) : Result
    }

    enum class Reason { EMPTY, TOO_LONG, UNSUPPORTED_SCHEME, MALFORMED, USERINFO }

    fun normalize(raw: String?): Result {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return Result.Invalid(Reason.EMPTY)
        if (trimmed.length > MAX_URL_LENGTH) return Result.Invalid(Reason.TOO_LONG)
        val scheme = trimmed.substringBefore(':', missingDelimiterValue = "").lowercase()
        if (scheme != "http" && scheme != "https") return Result.Invalid(Reason.UNSUPPORTED_SCHEME)
        val parsed = trimmed.toHttpUrlOrNull() ?: return Result.Invalid(Reason.MALFORMED)
        return fromHttpUrl(parsed)
    }

    /** Resolves [reference] against [base] (both already validated or raw) and normalizes the result. */
    fun resolve(base: String?, reference: String?): Result {
        val ref = reference?.trim().orEmpty()
        if (ref.isEmpty()) return Result.Invalid(Reason.EMPTY)
        if (ref.length > MAX_URL_LENGTH) return Result.Invalid(Reason.TOO_LONG)
        val absoluteScheme = Regex("^([A-Za-z][A-Za-z0-9+.-]*):").find(ref)?.groupValues?.get(1)?.lowercase()
        if (absoluteScheme != null) return normalize(ref)
        val baseUrl = base?.toHttpUrlOrNull() ?: return Result.Invalid(Reason.MALFORMED)
        if (baseUrl.username.isNotEmpty() || baseUrl.password.isNotEmpty()) return Result.Invalid(Reason.USERINFO)
        val resolved = baseUrl.resolve(ref) ?: return Result.Invalid(Reason.MALFORMED)
        return fromHttpUrl(resolved)
    }

    fun normalizedOrNull(raw: String?): String? = (normalize(raw) as? Result.Valid)?.url

    private fun fromHttpUrl(url: HttpUrl): Result {
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) return Result.Invalid(Reason.USERINFO)
        val normalized = url.newBuilder().fragment(null).build().toString()
        if (normalized.length > MAX_URL_LENGTH) return Result.Invalid(Reason.TOO_LONG)
        return Result.Valid(normalized, url.isHttps)
    }
}
