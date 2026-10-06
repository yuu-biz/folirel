package io.github.yuubiz.folirel.ui.settings

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.yuubiz.folirel.R
import io.github.yuubiz.folirel.data.preferences.AppSettings
import io.github.yuubiz.folirel.data.preferences.ColorPalette
import io.github.yuubiz.folirel.data.preferences.ThemeMode
import io.github.yuubiz.folirel.ui.common.Choice
import io.github.yuubiz.folirel.ui.common.GroupHeader
import io.github.yuubiz.folirel.ui.common.PercentSliderRow
import io.github.yuubiz.folirel.ui.common.SettingNote
import io.github.yuubiz.folirel.ui.common.layoutPickerPerRow
import io.github.yuubiz.folirel.ui.theme.Palettes
import io.github.yuubiz.folirel.ui.theme.isDarkTheme

@Composable
internal fun paletteName(palette: ColorPalette): String = stringResource(
    when (palette) {
        ColorPalette.CLASSIC -> R.string.palette_classic
        ColorPalette.LEAF -> R.string.palette_leaf
        ColorPalette.SAND -> R.string.palette_sand
        ColorPalette.VIOLET -> R.string.palette_violet
        ColorPalette.DYNAMIC -> R.string.palette_dynamic
    },
)

@Composable
internal fun themeModeName(mode: ThemeMode): String = stringResource(
    when (mode) {
        ThemeMode.SYSTEM -> R.string.theme_system
        ThemeMode.LIGHT -> R.string.theme_light
        ThemeMode.DARK -> R.string.theme_dark
    },
)

/**
 * Appearance (P2-A): light or dark, the colour set, the text size, and a preview that follows all three. Light/dark and the colour
 * set are independent; every change is a stored setting and nothing else (no refresh, no article, no category, no scroll position).
 */
@Composable
internal fun AppearancePage(s: AppSettings, vm: SettingsViewModel) {
    var previewPercent by remember { mutableStateOf<Int?>(null) }
    val storedPercent = AppSettings.textScalePercent(s.textScale)
    val sdk = Build.VERSION.SDK_INT
    val effective = s.palette.effective(sdk)
    Column(Modifier.fillMaxWidth()) {
        AppearancePreview(previewPercent, storedPercent, Modifier.padding(horizontal = 16.dp, vertical = 12.dp))

        GroupHeader(stringResource(R.string.settings_theme))
        Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            Choice(
                options = ThemeMode.entries,
                selected = s.theme,
                label = { themeModeName(it) },
                tag = { "theme_${it.name}" },
                onSelect = { mode -> vm.update { setTheme(mode) } },
            )
        }

        GroupHeader(stringResource(R.string.settings_palette))
        PaletteChooser(
            effective = effective,
            dark = isDarkTheme(s.theme, isSystemInDarkTheme()),
            sdk = sdk,
            onSelect = { palette -> vm.update { setPalette(palette) } },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        if (s.palette != effective) SettingNote(stringResource(R.string.palette_dynamic_fallback_note), Modifier.testTag("palette_fallback_note"))
        SettingNote(stringResource(R.string.palette_note))

        HorizontalDivider(Modifier.padding(top = 8.dp))
        PercentSliderRow(
            title = stringResource(R.string.settings_text_size),
            percent = storedPercent,
            minPercent = AppSettings.TEXT_SCALE_MIN_PERCENT,
            maxPercent = AppSettings.TEXT_SCALE_MAX_PERCENT,
            stepPercent = AppSettings.TEXT_SCALE_STEP_PERCENT,
            defaultPercent = 100,
            onPreview = { previewPercent = it },
            onCommit = { percent -> vm.update { setTextScale(AppSettings.textScaleOf(percent)) } },
            modifier = Modifier.testTag("text_size_slider_row"),
        )
        SettingNote(stringResource(R.string.text_size_note))
    }
}

/**
 * The colour sets as swatches with their names (P2-A04). The chosen one is shown by its border, a check mark and its semantics, not
 * by colour alone. [effective] is the palette that is drawn: a stored Dynamic on a system without it is shown as Classic here, in the
 * same words the theme uses, and Dynamic is disabled with the reason said in words and in its state description.
 */
@Composable
internal fun PaletteChooser(effective: ColorPalette, dark: Boolean, sdk: Int, onSelect: (ColorPalette) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val perRow = layoutPickerPerRow(maxWidth.value, LocalDensity.current.fontScale)
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ColorPalette.entries.chunked(perRow).forEach { rowPalettes ->
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    rowPalettes.forEach { palette ->
                        val available = ColorPalette.isAvailable(palette, sdk)
                        val swatches = if (available) {
                            val scheme = Palettes.scheme(palette, dark, context)
                            listOf(scheme.primary, scheme.primaryContainer, scheme.secondary, scheme.surface)
                        } else {
                            emptyList()
                        }
                        PaletteChip(palette, palette == effective, available, swatches, { onSelect(palette) }, Modifier.weight(1f).fillMaxHeight())
                    }
                    repeat(perRow - rowPalettes.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun PaletteChip(palette: ColorPalette, selected: Boolean, available: Boolean, swatches: List<Color>, onClick: () -> Unit, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val unavailableState = stringResource(R.string.palette_dynamic_unavailable_state)
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = if (selected) colors.primaryContainer else colors.surfaceContainerLow,
        contentColor = if (selected) colors.onPrimaryContainer else colors.onSurface,
        border = if (selected) BorderStroke(2.dp, colors.primary) else BorderStroke(1.dp, colors.outline),
        modifier = modifier
            .testTag("palette_${palette.name}")
            .selectable(selected = selected, enabled = available, role = Role.RadioButton, onClick = onClick)
            .semantics { if (!available) stateDescription = unavailableState },
    ) {
        Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.height(28.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                if (available) {
                    swatches.forEach { c ->
                        Box(Modifier.width(16.dp).fillMaxHeight().background(c, RoundedCornerShape(4.dp)).border(1.dp, colors.outline.copy(alpha = 0.6f), RoundedCornerShape(4.dp)))
                    }
                } else {
                    // No colours to show: four empty slots, so the chip is plainly "not there" and not a broken swatch.
                    repeat(4) {
                        Box(Modifier.width(16.dp).fillMaxHeight().border(1.dp, colors.outline.copy(alpha = 0.5f), RoundedCornerShape(4.dp)))
                    }
                }
            }
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                if (selected) {
                    Surface(shape = CircleShape, color = colors.primary, modifier = Modifier.size(16.dp)) {
                        Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.palette_selected), tint = colors.onPrimary, modifier = Modifier.padding(2.dp))
                    }
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    paletteName(palette),
                    style = MaterialTheme.typography.labelMedium,
                    textAlign = TextAlign.Center,
                    color = if (available) Color.Unspecified else colors.onSurfaceVariant,
                )
            }
            if (!available) {
                Text(
                    stringResource(R.string.palette_dynamic_unavailable),
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                    color = colors.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * A small article UI built from fixed sample text (never real articles or network images), so the effect of the colour set, light or
 * dark and the text size can be seen before leaving the page (P2-A05). It shows the tab accent, the card and its surface, a title,
 * the source line with the unread and saved marks, and a picture that is deliberately not themed. While the slider is dragged
 * [previewPercent] sizes the text of the preview alone; the rest of the app changes when the value is stored.
 */
@Composable
internal fun AppearancePreview(previewPercent: Int?, storedPercent: Int, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val shown = previewPercent?.let { p -> Density(density.density, density.fontScale / (storedPercent / 100f) * (p / 100f)) } ?: density
    CompositionLocalProvider(LocalDensity provides shown) {
        val colors = MaterialTheme.colorScheme
        Surface(
            shape = MaterialTheme.shapes.large,
            color = colors.surface,
            border = BorderStroke(1.dp, colors.outlineVariant),
            modifier = modifier.fillMaxWidth().testTag("appearance_preview"),
        ) {
            Column {
                Text(stringResource(R.string.preview_title), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, modifier = Modifier.padding(start = 12.dp, top = 8.dp))
                Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column {
                        Text(stringResource(R.string.preview_tab), style = MaterialTheme.typography.titleSmall, color = colors.primary)
                        Box(Modifier.padding(top = 2.dp).width(24.dp).height(3.dp).background(colors.primary, RoundedCornerShape(2.dp)))
                    }
                    Text(stringResource(R.string.preview_tab_other), style = MaterialTheme.typography.titleSmall, color = colors.onSurfaceVariant)
                }
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = colors.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 12.dp, top = 4.dp),
                ) {
                    Row(Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        // A photograph: fixed colours that do not come from the theme, which never tints content images.
                        Box(
                            Modifier
                                .testTag("preview_photo")
                                .size(64.dp)
                                .background(Brush.linearGradient(listOf(Color(0xFF6FA8C9), Color(0xFFE2B36E))), RoundedCornerShape(8.dp)),
                        )
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.preview_headline), style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold), color = colors.onSurface)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(8.dp).background(colors.primary, CircleShape))
                                Text(
                                    stringResource(R.string.preview_unread) + " · " + stringResource(R.string.preview_source),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colors.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 4.dp).weight(1f, fill = false),
                                )
                                Icon(
                                    painter = painterResource(R.drawable.ic_bookmark),
                                    contentDescription = stringResource(R.string.preview_saved),
                                    tint = colors.primary,
                                    modifier = Modifier.padding(start = 6.dp).size(16.dp),
                                )
                            }
                            Text(
                                stringResource(R.string.preview_summary),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                                maxLines = 3,
                            )
                        }
                    }
                }
            }
        }
    }
}
