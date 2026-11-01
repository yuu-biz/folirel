package io.github.yuubiz.folirel.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.yuubiz.folirel.R

/** A heading of a group of settings on one page. */
@Composable
fun GroupHeader(text: String) {
    HorizontalDivider()
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp).semantics { heading() },
    )
}

/** A line of explanation under a setting. */
@Composable
fun SettingNote(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 2.dp),
    )
}

/** A row of the Settings top page: the name of a page and, under it, the values it holds now. The whole row is the target. */
@Composable
fun NavRow(title: String, summary: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** An on/off setting. The whole row (text included) switches it; the switch itself only shows the state. */
@Composable
fun SwitchRow(title: String, note: String?, checked: Boolean, modifier: Modifier = Modifier, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title)
            if (note != null) Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

/**
 * A setting with many fixed values (an interval, a limit, a size): one row with the current value, which opens a dialog listing the
 * values. A choice applies at once and closes the dialog; the list is never spread over the page. [options] are in the order shown;
 * [optionTag] tags each row of the dialog, [modifier] the row of the page.
 */
@Composable
fun <T> SelectionRow(
    title: String,
    valueLabel: String,
    options: List<T>,
    selected: T,
    optionLabel: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    optionTag: (T) -> String = { "" },
    dialogNote: String? = null,
    /** Side padding of the row: 16 dp on a page, 0 where the container (a sheet) already has its own. */
    sidePadding: Dp = 16.dp,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(role = Role.DropdownList) { open = true }
            .semantics { stateDescription = valueLabel }
            .padding(horizontal = sidePadding, vertical = 4.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(valueLabel, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
        Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(title) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (dialogNote != null) SettingNote(dialogNote, Modifier.padding(horizontal = 0.dp, vertical = 4.dp))
                    Column(Modifier.selectableGroup()) {
                        options.forEach { option ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .selectable(selected = option == selected, role = Role.RadioButton, onClick = { open = false; onSelect(option) })
                                    .testTag(optionTag(option)),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = option == selected, onClick = null)
                                Text(optionLabel(option), modifier = Modifier.padding(start = 12.dp))
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/**
 * A size in percent on a slider with [stepPercent] steps. While the thumb is dragged the page only previews ([onPreview], so a
 * preview can change at once); the value is stored when the finger is lifted ([onCommit]). A change that is not a drag (a tap on
 * the track, the keyboard, an accessibility action) is stored at once. The row shows the value it was given until the store
 * has caught up, so a late answer from an earlier save never moves the thumb back.
 */
@Composable
fun PercentSliderRow(
    title: String,
    percent: Int,
    minPercent: Int,
    maxPercent: Int,
    stepPercent: Int,
    defaultPercent: Int,
    onPreview: (Int?) -> Unit,
    onCommit: (Int) -> Unit,
    modifier: Modifier = Modifier,
    /** Raised by the caller when a write failed: what the slider was only showing (not stored) is dropped and the stored value shows again. */
    saveFailures: Int = 0,
) {
    var local by remember { mutableStateOf<Int?>(null) }
    // The last value handed to the store that the store has not shown back yet: while it is set, a value equal to the stored one still has to be written (the earlier write is still on its way).
    var requested by remember { mutableStateOf<Int?>(null) }
    val shown = local ?: percent
    LaunchedEffect(saveFailures) {
        if (saveFailures > 0) {
            local = null
            requested = null
            onPreview(null)
        }
    }
    LaunchedEffect(percent) {
        if (local == percent) {
            local = null
            onPreview(null)
        }
        if (requested == percent) requested = null
    }
    val source = remember { MutableInteractionSource() }
    val dragging by source.collectIsDraggedAsState()
    val pressed by source.collectIsPressedAsState()
    fun commit(value: Int) {
        if (value == percent && requested == null) {
            // Nothing to store: the stored size is already this one, so the preview has nothing left to show either.
            local = null
            onPreview(null)
            return
        }
        requested = value
        onCommit(value)
    }
    val valueText = stringResource(R.string.percent_value, shown)
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f))
            Text(valueText, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = shown.toFloat(),
            onValueChange = { raw ->
                val snapped = (Math.round((raw - minPercent) / stepPercent.toFloat()) * stepPercent + minPercent).coerceIn(minPercent, maxPercent)
                local = snapped
                onPreview(snapped)
                if (!dragging && !pressed) commit(snapped)
            },
            onValueChangeFinished = { local?.let { commit(it) } },
            valueRange = minPercent.toFloat()..maxPercent.toFloat(),
            steps = (maxPercent - minPercent) / stepPercent - 1,
            interactionSource = source,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .testTag("text_size_slider")
                .semantics {
                    contentDescription = title
                    stateDescription = valueText
                },
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.percent_value, minPercent), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(
                onClick = {
                    local = defaultPercent
                    onPreview(defaultPercent)
                    commit(defaultPercent)
                },
                enabled = shown != defaultPercent,
                modifier = Modifier.heightIn(min = 48.dp).testTag("text_size_reset"),
            ) { Text(stringResource(R.string.text_size_reset, defaultPercent)) }
            Text(stringResource(R.string.percent_value, maxPercent), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
