package io.github.yuubiz.feeddeck.backend.local.parser

import java.text.Normalizer

object TextNormalizer {
    private val WHITESPACE_RUN = Regex("[\\s\\u00A0]+")

    /** NFC, collapse runs of whitespace (incl. NBSP) into one space, trim. */
    fun normalize(value: String?): String {
        if (value.isNullOrEmpty()) return ""
        val nfc = Normalizer.normalize(value, Normalizer.Form.NFC)
        return WHITESPACE_RUN.replace(nfc, " ").trim()
    }

    fun normalize(value: String?, maxCodePoints: Int): String = truncateCodePoints(normalize(value), maxCodePoints)

    /** Truncates to at most [max] Unicode code points without splitting a surrogate pair. */
    fun truncateCodePoints(value: String, max: Int): String {
        if (value.length <= max) return value
        if (value.codePointCount(0, value.length) <= max) return value
        val end = value.offsetByCodePoints(0, max)
        return value.substring(0, end)
    }

    /** NFC + trim only (category path segments keep inner whitespace). */
    fun nfcTrim(value: String?): String =
        if (value.isNullOrEmpty()) "" else Normalizer.normalize(value, Normalizer.Form.NFC).trim()
}
