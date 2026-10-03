package io.github.yuubiz.feeddeck.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Initial letter of a source name for the fallback icon: the first letter or digit (a whole code point, so
 * Japanese and emoji-free names work), upper-cased; "?" when there is none.
 */
fun sourceInitial(name: String?): String {
    val text = name.orEmpty().trim()
    var i = 0
    while (i < text.length) {
        val cp = text.codePointAt(i)
        if (Character.isLetterOrDigit(cp)) return String(Character.toChars(cp)).uppercase()
        i += Character.charCount(cp)
    }
    return "?"
}

/** Stable muted background of the fallback icon, from the feed id (the same feed always gets the same colour). */
fun sourceIconColor(feedId: String): Color {
    val hue = ((feedId.hashCode().toLong() and 0x7fffffff) % 360).toFloat()
    return Color.hsv(hue, 0.35f, 0.62f)
}

/**
 * Small source icon in front of the feed name (UX-ICON): the feed's own icon once it was resolved
 * (see [FeedIconSource]), otherwise a coloured initial. Never a blank square. Decorative: the name next to it
 * carries the meaning.
 */
@Composable
fun SourceIcon(feedId: String, name: String?, modifier: Modifier = Modifier, size: Dp = 20.dp) {
    val bitmap = LocalFeedIcons.current?.bitmapFor(feedId)
    if (bitmap != null) {
        androidx.compose.foundation.Image(
            bitmap = bitmap,
            contentDescription = null,
            modifier = modifier.size(size).clip(RoundedCornerShape(4.dp)).testTag("source_icon_image"),
        )
    } else {
        Box(
            modifier = modifier.size(size).clip(CircleShape).background(sourceIconColor(feedId)).testTag("source_icon_initial"),
            contentAlignment = Alignment.Center,
        ) {
            Text(sourceInitial(name), color = Color.White, fontSize = (size.value * 0.55f).sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

/** Resolved feed icons as ready-to-draw bitmaps; `null` means "none yet" (the initial is drawn instead). */
interface FeedIconSource {
    @Composable
    fun bitmapFor(feedId: String): androidx.compose.ui.graphics.ImageBitmap?
}

/** Provided by the home screen; absent (null) in previews and tests that do not care about icons. */
val LocalFeedIcons = androidx.compose.runtime.compositionLocalOf<FeedIconSource?> { null }
