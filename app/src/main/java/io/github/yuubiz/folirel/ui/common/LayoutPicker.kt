package io.github.yuubiz.folirel.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.yuubiz.folirel.R
import io.github.yuubiz.folirel.model.ListMode

/** The name of a layout, the same text everywhere it is chosen or named (Settings, the display panel, a category's own settings). */
@Composable
fun listModeName(mode: ListMode): String = stringResource(
    when (mode) {
        ListMode.LIST -> R.string.list_mode_list
        ListMode.COMPACT_LIST -> R.string.list_mode_compact_list
        ListMode.MAGAZINE -> R.string.list_mode_magazine
        ListMode.MASONRY -> R.string.list_mode_masonry
        ListMode.BENTO -> R.string.list_mode_bento
    },
)

/**
 * How many tiles of a picker (layouts, colour sets) sit in one row. A tile is at least 88 dp wide, and wider as the text grows, so a
 * name is never broken in the middle of a word: all five when they fit, otherwise three and two (never four and one), and at
 * large text two or one.
 */
internal fun layoutPickerPerRow(widthDp: Float, fontScale: Float = 1f): Int {
    val tile = LAYOUT_TILE_MIN_DP * maxOf(1f, fontScale)
    val fit = ((widthDp + LAYOUT_TILE_GAP_DP) / (tile + LAYOUT_TILE_GAP_DP)).toInt().coerceIn(1, 5)
    return if (fit == 4) 3 else fit
}

private const val LAYOUT_TILE_MIN_DP = 88f
private const val LAYOUT_TILE_GAP_DP = 8f

/**
 * The five layouts as small schematic tiles with their names, the one chooser used by Settings, the display panel and a category's
 * own settings (P2-B02, P2-C02). The choice is shown by the border, the container colour and a check mark, never by colour alone,
 * and is exposed as a radio group. The tile order is the order of [ListMode].
 *
 * With [inheritLabel] set (a category), a first row "Use the default (…)" is part of the same group; [selected] is then null for it
 * and [onSelect] is called with null when it is chosen. Tiles are tagged [tagPrefix] + the mode name, the inherit row
 * [tagPrefix] + "INHERIT".
 */
@Composable
fun LayoutPicker(
    selected: ListMode?,
    onSelect: (ListMode?) -> Unit,
    modifier: Modifier = Modifier,
    inheritLabel: String? = null,
    tagPrefix: String = "layout_",
) {
    Column(modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (inheritLabel != null) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .selectable(selected = selected == null, role = Role.RadioButton, onClick = { onSelect(null) })
                    .testTag(tagPrefix + "INHERIT"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = selected == null, onClick = null)
                Text(inheritLabel, modifier = Modifier.padding(start = 12.dp))
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val perRow = layoutPickerPerRow(maxWidth.value, LocalDensity.current.fontScale)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ListMode.entries.chunked(perRow).forEach { rowModes ->
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        rowModes.forEach { mode ->
                            LayoutTile(mode, mode == selected, { onSelect(mode) }, Modifier.weight(1f).fillMaxHeight().testTag(tagPrefix + mode.name))
                        }
                        // A short last row keeps the width of its tiles.
                        repeat(perRow - rowModes.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun LayoutTile(mode: ListMode, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = if (selected) colors.primaryContainer else colors.surfaceContainerLow,
        contentColor = if (selected) colors.onPrimaryContainer else colors.onSurface,
        border = if (selected) BorderStroke(2.dp, colors.primary) else BorderStroke(1.dp, colors.outline),
        modifier = modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
    ) {
        Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            TileGlyph(mode, selected)
            Text(
                listModeName(mode),
                style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp).fillMaxWidth(),
            )
        }
    }
}

/** The picture of a tile, with the check mark of the chosen one on a badge in its corner. */
@Composable
private fun TileGlyph(mode: ListMode, selected: Boolean) {
    Box(Modifier.fillMaxWidth().height(52.dp)) {
        LayoutGlyph(mode, Modifier.fillMaxWidth().height(52.dp))
        if (selected) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary, modifier = Modifier.align(Alignment.TopEnd).size(18.dp)) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.padding(2.dp))
            }
        }
    }
}

/**
 * A drawing of how a layout arranges articles: the grey bars are text, the coloured blocks are pictures. It is a hint, not a
 * preview of anybody's articles, so it uses fixed proportions.
 */
@Composable
internal fun LayoutGlyph(mode: ListMode, modifier: Modifier = Modifier) {
    val ink = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
    val paper = MaterialTheme.colorScheme.surface
    val accent = MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val g = 3.dp.toPx()
        val r = CornerRadius(2.dp.toPx())
        fun rect(x: Float, y: Float, rw: Float, rh: Float, color: Color) {
            if (rw > 0f && rh > 0f) drawRoundRect(color, Offset(x, y), Size(rw, rh), r)
        }
        // A card: paper with a thin outline of ink, so tiles read as cards on the container colour.
        fun card(x: Float, y: Float, cw: Float, ch: Float) {
            drawRoundRect(paper, Offset(x, y), Size(cw, ch), r)
            drawRoundRect(ink.copy(alpha = 0.35f), Offset(x, y), Size(cw, ch), r, style = Stroke(1f))
        }
        when (mode) {
            ListMode.LIST -> {
                val rows = 3
                val rh = (h - g * (rows - 1)) / rows
                repeat(rows) { i ->
                    val y = i * (rh + g)
                    rect(0f, y, rh, rh, accent.copy(alpha = 0.7f))
                    rect(rh + g, y + rh * 0.12f, (w - rh - g) * 0.9f, rh * 0.28f, ink)
                    rect(rh + g, y + rh * 0.6f, (w - rh - g) * 0.5f, rh * 0.22f, ink.copy(alpha = 0.6f))
                }
            }
            ListMode.COMPACT_LIST -> {
                val rows = 5
                val rh = (h - g * (rows - 1)) / rows
                repeat(rows) { i ->
                    val y = i * (rh + g)
                    rect(0f, y + rh * 0.15f, rh * 0.7f, rh * 0.7f, accent.copy(alpha = 0.7f))
                    rect(rh + g, y + rh * 0.2f, (w - rh - g) * (if (i % 2 == 0) 0.95f else 0.75f), rh * 0.6f, ink)
                }
            }
            ListMode.MAGAZINE -> {
                // Mixed: a wide card above two cards of the width of half.
                val topH = h * 0.42f
                card(0f, 0f, w, topH)
                rect(g, g, topH - 2 * g, topH - 2 * g, accent.copy(alpha = 0.7f))
                rect(topH + g, g * 2, (w - topH - 2 * g) * 0.9f, topH * 0.22f, ink)
                rect(topH + g, g * 2 + topH * 0.4f, (w - topH - 2 * g) * 0.6f, topH * 0.2f, ink.copy(alpha = 0.6f))
                val cw = (w - g) / 2
                val ch = h - topH - g
                repeat(2) { i ->
                    val x = i * (cw + g)
                    val y = topH + g
                    card(x, y, cw, ch)
                    rect(x + g, y + g, cw - 2 * g, ch * 0.5f, accent.copy(alpha = 0.7f))
                    rect(x + g, y + ch * 0.5f + 2 * g, (cw - 2 * g) * 0.85f, ch * 0.16f, ink)
                }
            }
            ListMode.MASONRY -> {
                val cw = (w - g) / 2
                val left = listOf(0.5f, 0.22f, 0.28f)
                val right = listOf(0.24f, 0.46f, 0.3f)
                listOf(left, right).forEachIndexed { col, shares ->
                    var y = 0f
                    val usable = h - g * (shares.size - 1)
                    shares.forEachIndexed { i, share ->
                        val ch = usable * share
                        val x = col * (cw + g)
                        card(x, y, cw, ch)
                        rect(x + g, y + g, cw - 2 * g, (ch - 2 * g) * (if (ch > h * 0.35f) 0.65f else 0.5f), accent.copy(alpha = if ((i + col) % 2 == 0) 0.7f else 0.45f))
                        y += ch + g
                    }
                }
            }
            ListMode.BENTO -> {
                // A large tile and smaller ones around it, filling the block without gaps.
                val bigW = w * 0.5f
                val bigH = h
                drawRoundRect(accent.copy(alpha = 0.7f), Offset(0f, 0f), Size(bigW - g / 2, bigH), r)
                rect(g, h - g * 2 - h * 0.14f, (bigW - 3 * g) * 0.85f, h * 0.14f, paper.copy(alpha = 0.9f))
                val x = bigW + g / 2
                val tw = w - x
                val th = (h - 2 * g) / 3
                card(x, 0f, tw, th)
                rect(x + g, g, tw * 0.5f, th - 2 * g, accent.copy(alpha = 0.45f))
                card(x, th + g, tw, th)
                rect(x + g, th + 2 * g, tw - 2 * g, th * 0.3f, ink)
                card(x, 2 * (th + g), tw, th)
                rect(x + tw * 0.5f, 2 * (th + g) + g, tw * 0.5f - g, th - 2 * g, accent.copy(alpha = 0.45f))
            }
        }
    }
}
