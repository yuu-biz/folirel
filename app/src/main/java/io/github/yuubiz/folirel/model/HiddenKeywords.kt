package io.github.yuubiz.folirel.model

import io.github.yuubiz.folirel.backend.local.parser.TextNormalizer
import java.util.Locale

/**
 * Rules of the global hidden-keyword list (title and summary are matched, see ArticleQuery). A keyword is normalized like the
 * stored article text, so a Japanese keyword matches after Unicode normalization and an ASCII one ignores case (SQLite LIKE).
 * Hiding is a display condition: it never changes read, saved or what is fetched.
 */
object HiddenKeywords {
    const val MAX_COUNT = 32
    const val MAX_CODE_POINTS = 64

    sealed interface AddResult {
        data class Added(val list: List<String>) : AddResult
        data object Blank : AddResult
        data object Duplicate : AddResult
        data object TooLong : AddResult
        data object ListFull : AddResult
    }

    /** What the keyword looks like once stored: NFC, whitespace runs collapsed, trimmed. */
    fun normalize(raw: String): String = TextNormalizer.normalize(raw)

    /** ASCII letters compare without case, exactly as the query will match them. */
    private fun identity(keyword: String): String = keyword.lowercase(Locale.ROOT)

    fun add(current: List<String>, raw: String): AddResult {
        val keyword = normalize(raw)
        if (keyword.isEmpty()) return AddResult.Blank
        // Too long is refused, not cut: a cut keyword would hide more than the reader typed.
        if (keyword.codePointCount(0, keyword.length) > MAX_CODE_POINTS) return AddResult.TooLong
        if (current.any { identity(it) == identity(keyword) }) return AddResult.Duplicate
        if (current.size >= MAX_COUNT) return AddResult.ListFull
        return AddResult.Added(current + keyword)
    }

    fun remove(current: List<String>, keyword: String): List<String> = current.filterNot { it == keyword }

    /** One keyword per line. A normalized keyword has no line break (whitespace runs collapse to one space), so this is lossless. */
    fun encode(list: List<String>): String = list.joinToString("\n")

    /** Reads a stored value defensively (hand-edited or from a later version): invalid, duplicate and surplus entries are dropped. */
    fun decode(stored: String?): List<String> {
        if (stored.isNullOrEmpty()) return emptyList()
        var list = emptyList<String>()
        for (line in stored.split('\n')) {
            val result = add(list, line)
            if (result is AddResult.Added) list = result.list
        }
        return list
    }
}
