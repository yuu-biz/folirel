package io.github.yuubiz.folirel.ui.theme

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Whether top bars and the tab strip are drawn in the palette's container colour instead of the plain surface. True for every colour
 * set except Classic, which keeps the look it had before the colour sets existed (so updating does not change an install that never
 * picked one); set by [FolirelTheme].
 */
val LocalTintedBars = staticCompositionLocalOf { false }

/** The colour a top bar or the tab strip is drawn in. */
@Composable
fun barContainerColor(): Color = if (LocalTintedBars.current) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface

/** The colour of the text and icons on [barContainerColor]. */
@Composable
fun barContentColor(): Color = if (LocalTintedBars.current) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface

/** Colours for every `TopAppBar` of the app: Material's own for Classic, the palette's container for the others. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun appBarColors(): TopAppBarColors {
    if (!LocalTintedBars.current) return TopAppBarDefaults.topAppBarColors()
    val s = MaterialTheme.colorScheme
    return TopAppBarDefaults.topAppBarColors(
        containerColor = s.primaryContainer,
        scrolledContainerColor = s.primaryContainer,
        navigationIconContentColor = s.onPrimaryContainer,
        titleContentColor = s.onPrimaryContainer,
        actionIconContentColor = s.onPrimaryContainer,
    )
}
