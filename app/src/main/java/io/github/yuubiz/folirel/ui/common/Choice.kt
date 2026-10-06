package io.github.yuubiz.folirel.ui.common

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/** A choice that is either "inherit" or a value: the first row of each group of a category's own display settings. */
internal sealed interface Pick<out T> {
    data object Inherit : Pick<Nothing>
    data class Value<T>(val value: T) : Pick<T>
}

@Composable
internal fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
    )
}

/**
 * A short single choice. Segmented buttons when the labels fit; selectable rows (radio buttons, 48 dp high) when the width is
 * narrow or the text is large, where a row of buttons would cut the labels (Japanese, 200 % text).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun <T> Choice(
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    tag: (T) -> String,
    onSelect: (T) -> Unit,
    enabled: Boolean = true,
    /** Long labels: always rows. */
    forceRows: Boolean = false,
) {
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val roomPerOption = maxWidth / options.size
        val segmented = !forceRows && fontScale <= 1.15f && roomPerOption >= 96.dp && options.size <= 3
        if (segmented) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                options.forEachIndexed { index, option ->
                    SegmentedButton(
                        selected = option == selected,
                        onClick = { onSelect(option) },
                        shape = SegmentedButtonDefaults.itemShape(index, options.size),
                        enabled = enabled,
                        modifier = Modifier.testTag(tag(option)),
                        label = { Text(label(option), maxLines = 1) },
                    )
                }
            }
        } else {
            Column(Modifier.fillMaxWidth()) {
                options.forEach { option ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .selectable(selected = option == selected, enabled = enabled, role = Role.RadioButton, onClick = { onSelect(option) })
                            .testTag(tag(option)),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = option == selected, onClick = null, enabled = enabled)
                        Text(label(option), modifier = Modifier.padding(start = 12.dp))
                    }
                }
            }
        }
    }
}
