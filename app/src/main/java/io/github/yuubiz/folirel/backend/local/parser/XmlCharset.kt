package io.github.yuubiz.folirel.backend.local.parser

import java.nio.charset.Charset

/**
 * Decodes an XML byte stream to text before parsing, so the parser cannot re-detect the encoding.
 *
 * Precedence (docs/fetch-and-security.md section 1, V1 rule, deliberately differs from RFC 7303):
 * BOM → XML declaration → HTTP Content-Type charset → UTF-8.
 * Unknown/unsupported declared names fall through to the next source.
 * Compatibility rule: Shift_JIS labels decode with windows-31j (CP932) when available, because Japanese
 * feeds labelled Shift_JIS commonly contain CP932 extension characters.
 */
object XmlCharset {
    enum class Source { BOM, XML_DECLARATION, HTTP_HEADER, DEFAULT }

    data class Decoded(val text: String, val charset: Charset, val source: Source)

    private val ENCODING_ATTR = Regex("encoding\\s*=\\s*[\"']([A-Za-z0-9._:-]+)[\"']")
    private val SHIFT_JIS_ALIASES = setOf("shift_jis", "shift-jis", "sjis", "x-sjis", "ms_kanji", "csshiftjis")

    fun decode(bytes: ByteArray, httpCharset: String?): Decoded {
        bomCharset(bytes)?.let { (charset, bomLength) ->
            return Decoded(stripBom(String(bytes, bomLength, bytes.size - bomLength, charset)), charset, Source.BOM)
        }
        val utf16 = utf16WithoutBom(bytes)
        val declared = declaredEncoding(bytes, utf16)
        if (utf16 != null) {
            // A UTF-16 byte pattern can only be decoded as UTF-16, whatever the label says.
            return Decoded(stripBom(String(bytes, utf16)), utf16, if (declared != null) Source.XML_DECLARATION else Source.DEFAULT)
        }
        declared?.let { lookup(it) }?.let { return Decoded(stripBom(String(bytes, it)), it, Source.XML_DECLARATION) }
        httpCharset?.let { lookup(it) }?.let { return Decoded(stripBom(String(bytes, it)), it, Source.HTTP_HEADER) }
        return Decoded(stripBom(String(bytes, Charsets.UTF_8)), Charsets.UTF_8, Source.DEFAULT)
    }

    fun lookup(name: String): Charset? {
        val label = name.trim().trim('"', '\'')
        if (label.isEmpty()) return null
        if (label.lowercase() in SHIFT_JIS_ALIASES) {
            return runCatching { Charset.forName("windows-31j") }.getOrNull()
                ?: runCatching { Charset.forName("Shift_JIS") }.getOrNull()
        }
        return try {
            Charset.forName(label)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun bomCharset(b: ByteArray): Pair<Charset, Int>? = when {
        b.size >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte() -> Charsets.UTF_8 to 3
        b.size >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte() -> Charsets.UTF_16BE to 2
        b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte() -> Charsets.UTF_16LE to 2
        else -> null
    }

    private fun utf16WithoutBom(b: ByteArray): Charset? = when {
        b.size >= 4 && b[0] == 0.toByte() && b[1] == '<'.code.toByte() && b[2] == 0.toByte() && b[3] == '?'.code.toByte() -> Charsets.UTF_16BE
        b.size >= 4 && b[0] == '<'.code.toByte() && b[1] == 0.toByte() && b[2] == '?'.code.toByte() && b[3] == 0.toByte() -> Charsets.UTF_16LE
        else -> null
    }

    private fun declaredEncoding(b: ByteArray, utf16: Charset?): String? {
        val prefix = if (utf16 != null) {
            String(b, 0, minOf(b.size, 512), utf16)
        } else {
            String(b, 0, minOf(b.size, 256), Charsets.ISO_8859_1)
        }
        if (!prefix.startsWith("<?xml")) return null
        val end = prefix.indexOf("?>")
        if (end < 0) return null
        return ENCODING_ATTR.find(prefix.substring(0, end))?.groupValues?.get(1)
    }

    private fun stripBom(text: String): String = if (text.startsWith('\uFEFF')) text.substring(1) else text
}
