package io.github.yuubiz.folirel.ui.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.yuubiz.folirel.R

/** Whether the logo for a dark surface (white lettering) is the one that reads on [surface]. */
internal fun brandLogoForDarkSurface(surface: Color): Boolean = surface.luminance() < 0.5f

/**
 * The horizontal Folirel logo. The brand colours are part of the drawables and are drawn as they are: the picture is
 * neither tinted nor taken from the colour set, so the user's palette never changes the logo. Only the variant follows
 * the surface the app actually draws (the app's own Light/Dark choice, not the system's), so the contrast holds.
 */
@Composable
fun BrandLogo(modifier: Modifier = Modifier, height: Dp = 36.dp) {
    val dark = brandLogoForDarkSurface(MaterialTheme.colorScheme.surface)
    Image(
        painter = painterResource(if (dark) R.drawable.folirel_logo_dark else R.drawable.folirel_logo),
        contentDescription = stringResource(R.string.app_name),
        modifier = modifier.height(height),
        alignment = Alignment.CenterStart,
        contentScale = ContentScale.Fit,
    )
}
