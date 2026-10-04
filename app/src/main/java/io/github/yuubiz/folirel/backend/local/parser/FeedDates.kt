package io.github.yuubiz.folirel.backend.local.parser

import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * RSS (RFC 822/1123 variants) and ISO 8601 / RFC 3339 dates. Locale independent: month names are matched
 * explicitly. A value without a usable time zone returns null; the device time zone is never assumed.
 */
object FeedDates {
    private val RFC822 = Regex(
        "^(?:[A-Za-z]{2,9}\\.?,?\\s*)?(\\d{1,2})[\\s-]+([A-Za-z]{3,9})\\.?[\\s-]+(\\d{2,4})\\s+" +
            "(\\d{1,2}):(\\d{2})(?::(\\d{2}))?(?:\\.\\d+)?\\s*([A-Za-z]{1,5}|[+-]\\d{2}:?\\d{2})?\\s*(?:\\([^)]*\\))?$",
    )
    private val ISO_SHAPE = Regex("^\\d{4}-\\d{2}-\\d{2}[Tt ]\\d{2}:\\d{2}.*$")
    private val OFFSET_NO_COLON = Regex("([+-]\\d{2})(\\d{2})$")

    private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

    private val ZONES = mapOf(
        "GMT" to 0, "UT" to 0, "UTC" to 0, "Z" to 0,
        "EST" to -5 * 60, "EDT" to -4 * 60, "CST" to -6 * 60, "CDT" to -5 * 60,
        "MST" to -7 * 60, "MDT" to -6 * 60, "PST" to -8 * 60, "PDT" to -7 * 60,
        // Common in Japanese feeds; unambiguous fixed offset.
        "JST" to 9 * 60,
    )

    fun parse(raw: String?): Long? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null
        return if (ISO_SHAPE.matches(value)) parseIso(value) else parseRfc822(value)
    }

    private fun parseIso(value: String): Long? {
        var v = value.replace('t', 'T').replace('z', 'Z')
        if (v.length > 10 && v[10] == ' ') v = v.substring(0, 10) + "T" + v.substring(11)
        v = v.replace(OFFSET_NO_COLON) { "${it.groupValues[1]}:${it.groupValues[2]}" }
        return try {
            OffsetDateTime.parse(v, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant().toEpochMilli()
        } catch (_: RuntimeException) {
            null // includes values without an offset
        }
    }

    private fun parseRfc822(value: String): Long? {
        val m = RFC822.matchEntire(value) ?: return null
        val (dayS, monthS, yearS, hourS, minuteS, secondS, zoneS) = m.destructured
        val month = MONTHS.indexOf(monthS.take(3).lowercase()) + 1
        if (month == 0) return null
        var year = yearS.toInt()
        if (yearS.length == 2) year += if (year < 50) 2000 else 1900 else if (yearS.length == 3) year += 1900
        val offsetMinutes = zoneOffsetMinutes(zoneS) ?: return null
        return try {
            val local = LocalDateTime.of(year, month, dayS.toInt(), hourS.toInt(), minuteS.toInt(), secondS.ifEmpty { "0" }.toInt())
            local.toInstant(ZoneOffset.ofTotalSeconds(offsetMinutes * 60)).toEpochMilli()
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun zoneOffsetMinutes(zone: String): Int? {
        if (zone.isEmpty()) return null
        if (zone[0] == '+' || zone[0] == '-') {
            val digits = zone.substring(1).replace(":", "")
            if (digits.length != 4) return null
            val hours = digits.substring(0, 2).toInt()
            val minutes = digits.substring(2).toInt()
            if (hours > 18 || minutes > 59) return null
            val total = hours * 60 + minutes
            return if (zone[0] == '-') -total else total
        }
        return ZONES[zone.uppercase()]
    }
}
