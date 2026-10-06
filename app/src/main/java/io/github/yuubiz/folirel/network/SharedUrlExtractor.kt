package io.github.yuubiz.folirel.network

/**
 * Takes the web addresses out of text that another app shared ("Share to Subscribe"). Pure: nothing is fetched or opened.
 *
 * Only `http://` and `https://` are looked for, so `javascript:`, `file:`, `content:`, `data:` and the like never become
 * candidates (and an address that sits behind another scheme, as in `view-source:https://...`, is not taken either). Each
 * address ends at white space, a quote, an angle bracket or Japanese punctuation/brackets, loses trailing sentence
 * punctuation (and a closing bracket that has no opener inside the address), and then must pass [UrlNormalizer].
 */
object SharedUrlExtractor {
    /** The shared text itself is not read above this size (characters). */
    const val MAX_TEXT_CHARS = 64 * 1024

    /** Addresses offered to the user. */
    const val MAX_URLS = 8

    sealed interface Result {
        data class Found(val urls: List<String>) : Result
        data object None : Result
        data object TooLarge : Result
    }

    fun extract(text: String?): Result {
        if (text.isNullOrBlank()) return Result.None
        if (text.length > MAX_TEXT_CHARS) return Result.TooLarge
        val found = LinkedHashSet<String>()
        var from = 0
        while (found.size < MAX_URLS) {
            val start = nextStart(text, from) ?: break
            var end = start
            while (end < text.length && !isTerminator(text[end])) end++
            from = maxOf(end, start + 1)
            // An over-long token is dropped before any further work on it (the trimming below is quadratic in its length).
            if (end - start > UrlNormalizer.MAX_URL_LENGTH || !standsAlone(text, start)) continue
            UrlNormalizer.normalizedOrNull(trimTrailing(text.substring(start, end)))?.let { found += it }
        }
        return if (found.isEmpty()) Result.None else Result.Found(found.toList())
    }

    /** Start of the next `http://` / `https://` at or after [from]. */
    private fun nextStart(text: String, from: Int): Int? {
        var i = from
        while (i < text.length) {
            i = text.indexOf("://", i)
            if (i < 0) return null
            if (i >= 5 && text.regionMatches(i - 5, "https", 0, 5, ignoreCase = true)) return i - 5
            if (i >= 4 && text.regionMatches(i - 4, "http", 0, 4, ignoreCase = true)) return i - 4
            i += 3
        }
        return null
    }

    /** False when the scheme is only the tail of a longer word or sits behind another scheme (`xhttps://`, `view-source:https://`). */
    private fun standsAlone(text: String, start: Int): Boolean {
        if (start == 0) return true
        val before = text[start - 1]
        if (before.isAsciiAlphanumeric() || before == '+' || before == '.' || before == '-') return false
        // Japanese text may touch the address (written without a space), so only ASCII letters count as part of a scheme.
        return !(before == ':' && start >= 2 && text[start - 2].isAsciiAlphanumeric())
    }

    private fun Char.isAsciiAlphanumeric() = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'

    private fun isTerminator(c: Char): Boolean =
        c.isWhitespace() || c.isISOControl() || c in "<>\"'`" || c in JAPANESE_STOPS

    private fun trimTrailing(url: String): String {
        var s = url
        while (s.isNotEmpty()) {
            val c = s.last()
            // A closing bracket belongs to the address only when it closes one opened inside it (Wikipedia titles); else it is prose.
            val drop = when (c) {
                ')' -> s.count { it == ')' } > s.count { it == '(' }
                ']' -> s.count { it == ']' } > s.count { it == '[' }
                '}' -> s.count { it == '}' } > s.count { it == '{' }
                else -> c in TRAILING_PUNCTUATION
            }
            if (!drop) break
            s = s.dropLast(1)
        }
        return s
    }

    private const val TRAILING_PUNCTUATION = ".,;:!?*"

    /** Ideographic space, full-width brackets and sentence punctuation: they end an address that is written inside Japanese text. */
    private const val JAPANESE_STOPS = "　、。，．！？：；「」『』【】（）〈〉《》〔〕［］｛｝"
}
