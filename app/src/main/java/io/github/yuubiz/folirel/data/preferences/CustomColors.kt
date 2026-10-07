package io.github.yuubiz.folirel.data.preferences

/**
 * The colours a reader set by hand for one mode (light or dark) of the Custom colour set (0.9.5). They are the four colours the colour
 * set chip shows ([accent] = primary, [accentContainer] = primaryContainer, [secondary], [background] = surface) and the text colour
 * ([text] = onSurface); every other role of the scheme is made from these five. Opaque ARGB integers, used exactly as given: the app does
 * not correct them, also when they are hard to read (it only warns).
 *
 * A mode without stored colours follows the base colour ([AppSettings.customSeed]); stored colours are kept when the base colour changes,
 * until the reader regenerates that mode from it.
 */
data class CustomColors(
    val accent: Int,
    val accentContainer: Int,
    val secondary: Int,
    val background: Int,
    val text: Int,
) {
    enum class Role { ACCENT, ACCENT_CONTAINER, SECONDARY, BACKGROUND, TEXT }

    operator fun get(role: Role): Int = when (role) {
        Role.ACCENT -> accent
        Role.ACCENT_CONTAINER -> accentContainer
        Role.SECONDARY -> secondary
        Role.BACKGROUND -> background
        Role.TEXT -> text
    }

    fun with(role: Role, color: Int): CustomColors {
        val c = AppSettings.opaque(color)
        return when (role) {
            Role.ACCENT -> copy(accent = c)
            Role.ACCENT_CONTAINER -> copy(accentContainer = c)
            Role.SECONDARY -> copy(secondary = c)
            Role.BACKGROUND -> copy(background = c)
            Role.TEXT -> copy(text = c)
        }
    }

    /** `accent=#RRGGBB;container=#RRGGBB;secondary=#RRGGBB;background=#RRGGBB;text=#RRGGBB`, the stored form. */
    fun encode(): String = Role.entries.joinToString(";") { "${KEYS.getValue(it)}=${hex(get(it))}" }

    companion object {
        private val KEYS = mapOf(
            Role.ACCENT to "accent",
            Role.ACCENT_CONTAINER to "container",
            Role.SECONDARY to "secondary",
            Role.BACKGROUND to "background",
            Role.TEXT to "text",
        )

        /** `#RRGGBB` of [color] (upper case, alpha ignored). */
        fun hex(color: Int): String = "#%06X".format(color and 0xFFFFFF)

        /**
         * The opaque colour of [text], or null. Exactly six hexadecimal digits, with or without the leading `#`, white space around ignored;
         * nothing else (no 3-digit form, no alpha, no colour names) is accepted, so a half-typed or wrong value is never stored.
         */
        fun parseHex(text: String): Int? {
            val t = text.trim().removePrefix("#")
            if (t.length != 6 || !t.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
            return 0xFF000000.toInt() or t.toInt(16)
        }

        /**
         * The colours of a stored value, or null when there is none or it is not complete and valid: every one of the five roles must be
         * there exactly once with a valid `#RRGGBB`. Anything else reads as "not set", so the mode follows the base colour.
         */
        fun decode(value: String?): CustomColors? {
            if (value.isNullOrBlank()) return null
            val byKey = HashMap<String, Int>()
            for (part in value.split(';')) {
                val key = part.substringBefore('=', missingDelimiterValue = "").trim()
                val color = parseHex(part.substringAfter('=', missingDelimiterValue = "")) ?: return null
                if (key !in KEYS.values || byKey.put(key, color) != null) return null
            }
            if (byKey.size != KEYS.size) return null
            fun of(role: Role) = byKey.getValue(KEYS.getValue(role))
            return CustomColors(of(Role.ACCENT), of(Role.ACCENT_CONTAINER), of(Role.SECONDARY), of(Role.BACKGROUND), of(Role.TEXT))
        }
    }
}
