package io.github.yuubiz.feeddeck.backend.local.parser

import java.security.MessageDigest

/**
 * Entry identity (docs/data-and-import.md section 3):
 * 1. `id:` + trimmed opaque guid / atom:id / rdf:about (never URL-normalized)
 * 2. `url:` + normalized link
 * 3. `fallback:` + SHA-256 of the compact JSON array [title, publishedAtOrNull, author, summary]
 */
object EntryKeys {
    fun of(
        rawId: String?,
        normalizedLink: String?,
        title: String,
        publishedAt: Long?,
        author: String?,
        summary: String,
    ): String {
        val id = rawId?.trim()
        if (!id.isNullOrEmpty()) return "id:$id"
        if (!normalizedLink.isNullOrEmpty()) return "url:$normalizedLink"
        return "fallback:" + fallbackDigest(title, publishedAt, author.orEmpty(), summary)
    }

    /** Inputs must already be normalized (NFC, whitespace collapsed, trimmed); a missing author is "". */
    fun fallbackDigest(title: String, publishedAt: Long?, author: String, summary: String): String {
        val json = compactJsonArray(listOf(title, publishedAt, author, summary))
        val digest = MessageDigest.getInstance("SHA-256").digest(json.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * Compact JSON: no whitespace between elements, non-ASCII emitted as UTF-8 as-is, only `"`, `\` and
     * control characters escaped (same output as Python `json.dumps(ensure_ascii=False, separators=(',',':'))`).
     */
    fun compactJsonArray(values: List<Any?>): String = buildString {
        append('[')
        values.forEachIndexed { index, value ->
            if (index > 0) append(',')
            when (value) {
                null -> append("null")
                is Long, is Int -> append(value.toString())
                is String -> appendJsonString(value)
                else -> error("Unsupported JSON value type")
            }
        }
        append(']')
    }

    private fun StringBuilder.appendJsonString(s: String) {
        append('"')
        for (c in s) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
        }
        append('"')
    }
}
