package io.github.yuubiz.folirel.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import io.github.yuubiz.folirel.data.preferences.AppSettings
import io.github.yuubiz.folirel.data.preferences.CustomColors
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The Custom colour set: one base colour (the seed) the user chose, and a complete light and dark [ColorScheme] made from it.
 *
 * The scheme is not the seed dropped in as the primary colour. The seed gives a hue and a chroma, from which five tonal palettes are made
 * (primary, secondary and tertiary accents, a nearly neutral palette for surfaces and a slightly more coloured one for variants and
 * outlines), and every role is a fixed tone of one of them, the way Material 3 builds its own schemes: the tone is the CIE L* lightness, so
 * the difference between the tones of a text colour and its background is what sets the contrast, whatever the hue is. The user never
 * chooses a text or icon colour; "on" colours are tones of the same palettes, chosen far from their background, and the pairs are checked
 * here ([readable]) and not only in tests: a text colour that is below its minimum contrast is moved away from its background until it is not.
 * Black, white, greys, a bright yellow, a saturated red or a dark navy as the seed therefore all give readable schemes (a grey seed gives a
 * grey scheme).
 *
 * Light and dark come from the same seed. Nothing is looked up or stored here: the same seed always gives the same colours.
 *
 * Since 0.9.5 the reader can also set five colours of each mode by hand ([detailed]); those are the reader's choice and are not checked
 * or corrected (the editor only warns about low contrast, [hasLowContrast]).
 */
object CustomPalette {
    val DEFAULT_SEED: Int = AppSettings.DEFAULT_CUSTOM_SEED

    /**
     * The Custom scheme to draw: the [colors] the reader set by hand for this mode ([detailed], whatever the seed), or, when there are
     * none, the scheme made from [seed]. A store written before 0.9.5 has none, so it looks exactly as it did.
     */
    fun scheme(seed: Int, dark: Boolean, colors: CustomColors?): ColorScheme = if (colors == null) scheme(seed, dark) else detailed(colors, dark)

    /** The five colours the detail editor starts from: those of the scheme made from [seed] (what the reader sees before editing). */
    fun generatedColors(seed: Int, dark: Boolean): CustomColors {
        val s = scheme(seed, dark)
        return CustomColors(s.primary.toArgb(), s.primaryContainer.toArgb(), s.secondary.toArgb(), s.surface.toArgb(), s.onSurface.toArgb())
    }

    /**
     * A complete scheme from the five colours set by hand (0.9.5). The five are used exactly as given, never corrected; the other roles
     * are made from them alone (not from the seed), so changing the base colour does not change a mode the reader set by hand:
     * - text on a coloured role is whichever of the reader's text and background colours stands out more from it;
     * - secondary text, lines and the surface steps of cards and sheets lie between the background and the text (neutral steps, not tinted
     *   towards the accent), the secondary container between the background and the secondary colour;
     * - tertiary (hardly used) is the secondary colour; the error colours are the fixed ones of the generated scheme.
     */
    fun detailed(colors: CustomColors, dark: Boolean): ColorScheme {
        val a = Color(colors.accent)
        val ac = Color(colors.accentContainer)
        val s = Color(colors.secondary)
        val bg = Color(colors.background)
        val text = Color(colors.text)
        fun on(color: Color): Color = if (contrast(text.toArgb(), color.toArgb()) >= contrast(bg.toArgb(), color.toArgb())) text else bg
        fun step(share: Float) = lerp(bg, text, share)
        val secondaryContainer = lerp(bg, s, if (dark) 0.35f else 0.25f)
        val base = if (dark) darkColorScheme() else lightColorScheme()
        return base.copy(
            primary = a, onPrimary = on(a), primaryContainer = ac, onPrimaryContainer = on(ac), inversePrimary = ac,
            secondary = s, onSecondary = on(s), secondaryContainer = secondaryContainer, onSecondaryContainer = on(secondaryContainer),
            tertiary = s, onTertiary = on(s), tertiaryContainer = secondaryContainer, onTertiaryContainer = on(secondaryContainer),
            background = bg, onBackground = text, surface = bg, onSurface = text,
            surfaceVariant = step(if (dark) 0.16f else 0.09f), onSurfaceVariant = lerp(text, bg, 0.28f),
            surfaceTint = a,
            inverseSurface = text, inverseOnSurface = bg,
            outline = lerp(text, bg, 0.5f), outlineVariant = lerp(text, bg, 0.78f),
            error = if (dark) Color(0xFFF2B8B5) else Color(0xFFB3261E), onError = if (dark) Color(0xFF601410) else Color.White,
            errorContainer = if (dark) Color(0xFF8C1D18) else Color(0xFFF9DEDC), onErrorContainer = if (dark) Color(0xFFF9DEDC) else Color(0xFF410E0B),
            scrim = Color.Black,
            surfaceDim = if (dark) bg else step(0.12f), surfaceBright = if (dark) step(0.18f) else bg,
            surfaceContainerLowest = if (dark) lerp(bg, Color.Black, 0.4f) else bg,
            surfaceContainerLow = step(0.04f),
            surfaceContainer = step(if (dark) 0.08f else 0.06f),
            surfaceContainerHigh = step(if (dark) 0.12f else 0.08f),
            surfaceContainerHighest = step(if (dark) 0.17f else 0.10f),
        )
    }

    /** The contrast below which the detail editor warns (it never refuses): ordinary text on a surface. */
    const val LOW_CONTRAST = 4.5

    /**
     * Whether the text of [colors] is hard to read on the background or on a card (the surfaces most text is on). Only a warning for the
     * editor; the colours are saved as they are.
     */
    fun hasLowContrast(colors: CustomColors, dark: Boolean): Boolean {
        val card = detailed(colors, dark).surfaceContainerHigh.toArgb()
        return contrast(colors.text, colors.background) < LOW_CONTRAST || contrast(colors.text, card) < LOW_CONTRAST
    }

    /** A scheme for [seed] (an RGB colour; alpha is ignored), light or [dark]. */
    fun scheme(seed: Int, dark: Boolean): ColorScheme {
        val lch = Lch.of(seed)
        // A seed with no real colour (black, white, grey) stays neutral; any other seed gives accents of a usable strength.
        val neutralSeed = lch.c < NEUTRAL_CHROMA_LIMIT
        val accent = if (neutralSeed) lch.c else lch.c.coerceIn(ACCENT_CHROMA_MIN, ACCENT_CHROMA_MAX)
        val primary = Tones(lch.h, accent)
        val secondary = Tones(lch.h, accent / 3.0)
        val tertiary = Tones(lch.h + 60.0, accent / 2.0)
        val neutral = Tones(lch.h, min(NEUTRAL_CHROMA, accent * 0.3))
        val variant = Tones(lch.h, min(VARIANT_CHROMA, accent * 0.45))
        return if (dark) darkScheme(primary, secondary, tertiary, neutral, variant) else lightScheme(primary, secondary, tertiary, neutral, variant)
    }

    private fun lightScheme(p: Tones, s: Tones, t: Tones, n: Tones, nv: Tones): ColorScheme {
        val surface = n.argb(98.0)
        val surfaceVariant = nv.argb(90.0)
        val highest = n.argb(89.0)
        val primaryContainer = p.argb(90.0)
        val primary = readable(p, 40.0, listOf(surface, highest), TEXT, towardsLight = false)
        val secondary = readable(s, 40.0, listOf(surface, highest), TEXT, towardsLight = false)
        val tertiary = readable(t, 40.0, listOf(surface, highest), TEXT, towardsLight = false)
        val secondaryContainer = s.argb(90.0)
        val tertiaryContainer = t.argb(90.0)
        return lightColorScheme().copy(
            primary = Color(primary), onPrimary = Color(readable(p, 100.0, listOf(primary), TEXT, towardsLight = true)),
            primaryContainer = Color(primaryContainer), onPrimaryContainer = Color(readable(p, 10.0, listOf(primaryContainer), TEXT, towardsLight = false)),
            inversePrimary = Color(p.argb(80.0)),
            secondary = Color(secondary), onSecondary = Color(readable(s, 100.0, listOf(secondary), TEXT, towardsLight = true)),
            secondaryContainer = Color(secondaryContainer), onSecondaryContainer = Color(readable(s, 10.0, listOf(secondaryContainer), TEXT, towardsLight = false)),
            tertiary = Color(tertiary), onTertiary = Color(readable(t, 100.0, listOf(tertiary), TEXT, towardsLight = true)),
            tertiaryContainer = Color(tertiaryContainer), onTertiaryContainer = Color(readable(t, 10.0, listOf(tertiaryContainer), TEXT, towardsLight = false)),
            background = Color(surface), onBackground = Color(readable(n, 10.0, listOf(surface, highest), BODY, towardsLight = false)),
            surface = Color(surface), onSurface = Color(readable(n, 10.0, listOf(surface, highest), BODY, towardsLight = false)),
            surfaceVariant = Color(surfaceVariant),
            onSurfaceVariant = Color(readable(nv, 30.0, listOf(surfaceVariant, surface, highest, primaryContainer), TEXT, towardsLight = false)),
            surfaceTint = Color(primary),
            inverseSurface = Color(n.argb(20.0)), inverseOnSurface = Color(n.argb(95.0)),
            outline = Color(readable(nv, 50.0, listOf(surface), NON_TEXT, towardsLight = false)), outlineVariant = Color(nv.argb(80.0)),
            error = Color(0xFFB3261E), onError = Color.White, errorContainer = Color(0xFFF9DEDC), onErrorContainer = Color(0xFF410E0B),
            scrim = Color.Black,
            surfaceDim = Color(n.argb(86.0)), surfaceBright = Color(surface),
            surfaceContainerLowest = Color(n.argb(100.0)), surfaceContainerLow = Color(n.argb(95.0)), surfaceContainer = Color(n.argb(93.0)),
            surfaceContainerHigh = Color(n.argb(91.0)), surfaceContainerHighest = Color(highest),
        )
    }

    private fun darkScheme(p: Tones, s: Tones, t: Tones, n: Tones, nv: Tones): ColorScheme {
        val surface = n.argb(DARK_SURFACE_TONE)
        val surfaceVariant = nv.argb(30.0)
        val highest = n.argb(22.0)
        val primaryContainer = p.argb(30.0)
        val primary = readable(p, 80.0, listOf(surface, highest), TEXT, towardsLight = true)
        val secondary = readable(s, 80.0, listOf(surface, highest), TEXT, towardsLight = true)
        val tertiary = readable(t, 80.0, listOf(surface, highest), TEXT, towardsLight = true)
        val secondaryContainer = s.argb(30.0)
        val tertiaryContainer = t.argb(30.0)
        return darkColorScheme().copy(
            primary = Color(primary), onPrimary = Color(readable(p, 20.0, listOf(primary), TEXT, towardsLight = false)),
            primaryContainer = Color(primaryContainer), onPrimaryContainer = Color(readable(p, 90.0, listOf(primaryContainer), TEXT, towardsLight = true)),
            inversePrimary = Color(p.argb(40.0)),
            secondary = Color(secondary), onSecondary = Color(readable(s, 20.0, listOf(secondary), TEXT, towardsLight = false)),
            secondaryContainer = Color(secondaryContainer), onSecondaryContainer = Color(readable(s, 90.0, listOf(secondaryContainer), TEXT, towardsLight = true)),
            tertiary = Color(tertiary), onTertiary = Color(readable(t, 20.0, listOf(tertiary), TEXT, towardsLight = false)),
            tertiaryContainer = Color(tertiaryContainer), onTertiaryContainer = Color(readable(t, 90.0, listOf(tertiaryContainer), TEXT, towardsLight = true)),
            background = Color(surface), onBackground = Color(readable(n, 90.0, listOf(surface, highest), BODY, towardsLight = true)),
            surface = Color(surface), onSurface = Color(readable(n, 90.0, listOf(surface, highest), BODY, towardsLight = true)),
            surfaceVariant = Color(surfaceVariant),
            onSurfaceVariant = Color(readable(nv, 80.0, listOf(surfaceVariant, surface, highest, primaryContainer), TEXT, towardsLight = true)),
            surfaceTint = Color(primary),
            inverseSurface = Color(n.argb(90.0)), inverseOnSurface = Color(n.argb(20.0)),
            outline = Color(readable(nv, 60.0, listOf(surface), NON_TEXT, towardsLight = true)), outlineVariant = Color(nv.argb(30.0)),
            error = Color(0xFFF2B8B5), onError = Color(0xFF601410), errorContainer = Color(0xFF8C1D18), onErrorContainer = Color(0xFFF9DEDC),
            scrim = Color.Black,
            surfaceDim = Color(surface), surfaceBright = Color(n.argb(24.0)),
            surfaceContainerLowest = Color(n.argb(4.0)), surfaceContainerLow = Color(n.argb(10.0)), surfaceContainer = Color(n.argb(13.0)),
            surfaceContainerHigh = Color(n.argb(17.0)), surfaceContainerHighest = Color(highest),
        )
    }

    /**
     * The colour of [tones] at [tone] (CIE L*), moved towards light or dark until its contrast with every colour in [backgrounds] is at least
     * [minimum]. The tones given above already pass for every seed; this is the guarantee that does not rest on that.
     */
    private fun readable(tones: Tones, tone: Double, backgrounds: List<Int>, minimum: Double, towardsLight: Boolean): Int {
        var t = tone
        while (true) {
            val c = tones.argb(t)
            if (backgrounds.all { contrast(c, it) >= minimum }) return c
            if (t <= 0.0 || t >= 100.0) return c
            t = (if (towardsLight) t + 1.0 else t - 1.0).coerceIn(0.0, 100.0)
        }
    }

    /** WCAG contrast ratio of two opaque ARGB colours. */
    internal fun contrast(a: Int, b: Int): Double {
        val x = luminance(a)
        val y = luminance(b)
        return (max(x, y) + 0.05) / (min(x, y) + 0.05)
    }

    private fun luminance(argb: Int): Double {
        val r = Lch.linear(((argb shr 16) and 0xFF) / 255.0)
        val g = Lch.linear(((argb shr 8) and 0xFF) / 255.0)
        val b = Lch.linear((argb and 0xFF) / 255.0)
        return 0.2126729 * r + 0.7151522 * g + 0.0721750 * b
    }

    /** Body text on a surface; text of any size on a container; the strength of a line or a control border. */
    private const val BODY = 7.0
    private const val TEXT = 4.5
    private const val NON_TEXT = 3.0

    private const val NEUTRAL_CHROMA_LIMIT = 8.0
    private const val ACCENT_CHROMA_MIN = 40.0
    private const val ACCENT_CHROMA_MAX = 90.0
    private const val NEUTRAL_CHROMA = 12.0
    private const val VARIANT_CHROMA = 18.0
    private const val DARK_SURFACE_TONE = 7.0

    /** A tonal palette: one hue and chroma, any tone (L*) from black to white; the chroma is reduced where the tone cannot show it. */
    private class Tones(hue: Double, private val chroma: Double) {
        private val h = ((hue % 360.0) + 360.0) % 360.0
        fun argb(tone: Double): Int = Lch.argb(tone, chroma, h)
    }
}

/**
 * The part of CIE L*C*h(ab) the colour sets need: from sRGB to lightness, chroma and hue and back, with the chroma reduced at a fixed
 * lightness and hue until the colour is inside sRGB. Lightness is L*, so tones are perceptually even and the contrast between two tones
 * can be reasoned about without looking at the hue.
 */
internal object Lch {
    class Value(val l: Double, val c: Double, val h: Double)

    private const val XN = 0.95047
    private const val ZN = 1.08883
    private const val EPS = 216.0 / 24389.0
    private const val KAPPA = 24389.0 / 27.0

    fun linear(c: Double): Double = if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)

    private fun encode(c: Double): Double = if (c <= 0.0031308) 12.92 * c else 1.055 * c.pow(1 / 2.4) - 0.055

    fun of(argb: Int): Value {
        val r = linear(((argb shr 16) and 0xFF) / 255.0)
        val g = linear(((argb shr 8) and 0xFF) / 255.0)
        val b = linear((argb and 0xFF) / 255.0)
        val x = (0.4124564 * r + 0.3575761 * g + 0.1804375 * b) / XN
        val y = 0.2126729 * r + 0.7151522 * g + 0.0721750 * b
        val z = (0.0193339 * r + 0.1191920 * g + 0.9503041 * b) / ZN
        val fx = f(x)
        val fy = f(y)
        val fz = f(z)
        val l = 116 * fy - 16
        val a = 500 * (fx - fy)
        val bb = 200 * (fy - fz)
        val c = hypot(a, bb)
        val h = if (c < 1e-6) 0.0 else Math.toDegrees(atan2(bb, a)).let { if (it < 0) it + 360 else it }
        return Value(l, c, h)
    }

    private fun f(t: Double) = if (t > EPS) cbrt(t) else (KAPPA * t + 16) / 116

    private fun finv(t: Double): Double {
        val t3 = t * t * t
        return if (t3 > EPS) t3 else (116 * t - 16) / KAPPA
    }

    /** Linear sRGB of the colour, not clipped. */
    private fun rgb(l: Double, c: Double, h: Double): DoubleArray {
        val hr = Math.toRadians(h)
        val fy = (l + 16) / 116
        val fx = fy + c * cos(hr) / 500
        val fz = fy - c * sin(hr) / 200
        val x = finv(fx) * XN
        val y = finv(fy)
        val z = finv(fz) * ZN
        return doubleArrayOf(
            3.2404542 * x - 1.5371385 * y - 0.4985314 * z,
            -0.9692660 * x + 1.8760108 * y + 0.0415560 * z,
            0.0556434 * x - 0.2040259 * y + 1.0572252 * z,
        )
    }

    private fun inGamut(v: DoubleArray) = v.all { it >= -GAMUT_TOLERANCE && it <= 1 + GAMUT_TOLERANCE }

    /** The opaque ARGB colour at lightness [l] (0..100), hue [h] and at most chroma [c]: less where sRGB cannot show it. */
    fun argb(l: Double, c: Double, h: Double): Int {
        if (l <= 0.0) return BLACK
        if (l >= 100.0) return WHITE
        var chroma = c
        if (!inGamut(rgb(l, chroma, h))) {
            var lo = 0.0
            var hi = chroma
            repeat(BISECTIONS) {
                val mid = (lo + hi) / 2
                if (inGamut(rgb(l, mid, h))) lo = mid else hi = mid
            }
            chroma = lo
        }
        val v = rgb(l, chroma, h)
        fun channel(x: Double) = (encode(x.coerceIn(0.0, 1.0)) * 255).roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (channel(v[0]) shl 16) or (channel(v[1]) shl 8) or channel(v[2])
    }

    private const val GAMUT_TOLERANCE = 1e-4
    private const val BISECTIONS = 24
    private const val BLACK = 0xFF000000.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()
}

/** The seed as a person sees and types it (`#RRGGBB`) and as a picker moves it (hue, saturation, value). Pure; nothing is stored here. */
object SeedColor {
    /** `#RRGGBB` of [seed] (upper case, alpha ignored). */
    fun hex(seed: Int): String = CustomColors.hex(seed)

    /**
     * The opaque colour of [text], or null. Exactly six hexadecimal digits, with or without the leading `#`, and white space around is
     * ignored; nothing else (no 3-digit form, no alpha, no colour names) is accepted, so a half-typed or wrong value is never stored.
     */
    fun parse(text: String): Int? = CustomColors.parseHex(text)

    class Hsv(val hue: Float, val saturation: Float, val value: Float)

    fun hsv(seed: Int): Hsv {
        val r = ((seed shr 16) and 0xFF) / 255f
        val g = ((seed shr 8) and 0xFF) / 255f
        val b = (seed and 0xFF) / 255f
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val d = mx - mn
        val h = when {
            d == 0f -> 0f
            mx == r -> 60f * (((g - b) / d) % 6f)
            mx == g -> 60f * ((b - r) / d + 2f)
            else -> 60f * ((r - g) / d + 4f)
        }.let { if (it < 0f) it + 360f else it }
        return Hsv(h, if (mx == 0f) 0f else d / mx, mx)
    }

    /** The opaque colour at [hue] (0..360), [saturation] and [value] (0..1); out-of-range input is clamped. */
    fun fromHsv(hue: Float, saturation: Float, value: Float): Int {
        val h = ((hue % 360f) + 360f) % 360f
        val s = saturation.coerceIn(0f, 1f)
        val v = value.coerceIn(0f, 1f)
        val c = v * s
        val x = c * (1f - abs((h / 60f) % 2f - 1f))
        val m = v - c
        val (r, g, b) = when ((h / 60f).toInt()) {
            0 -> Triple(c, x, 0f)
            1 -> Triple(x, c, 0f)
            2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c)
            4 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        fun ch(f: Float) = ((f + m) * 255f).roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
    }
}
