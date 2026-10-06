package io.github.yuubiz.folirel.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.yuubiz.folirel.R
import io.github.yuubiz.folirel.model.HiddenKeywords
import kotlinx.coroutines.launch

/**
 * The hidden-keyword list: add, delete, switch on/off. One list for the whole app (not per category). A change applies at once and
 * is stored by the caller; closing the sheet changes nothing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HiddenKeywordsSheet(
    keywords: List<String>,
    enabled: Boolean,
    onEnabled: (Boolean) -> Unit,
    onAdd: suspend (String) -> HiddenKeywords.AddResult,
    onRemove: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var text by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<HiddenKeywords.AddResult?>(null) }
    fun submit() {
        scope.launch {
            val result = onAdd(text)
            error = result.takeUnless { it is HiddenKeywords.AddResult.Added }
            if (result is HiddenKeywords.AddResult.Added) text = ""
        }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.testTag("hidden_keywords_sheet"),
    ) {
        Column(
            Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.hidden_keywords_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.hidden_keywords_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.hidden_keywords_switch), modifier = Modifier.weight(1f))
                // With nothing in the list there is nothing to switch: the switch would mean nothing.
                Switch(
                    checked = enabled && keywords.isNotEmpty(),
                    onCheckedChange = onEnabled,
                    enabled = keywords.isNotEmpty(),
                    modifier = Modifier.testTag("hidden_keywords_sheet_switch"),
                )
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; error = null },
                    label = { Text(stringResource(R.string.hidden_keywords_hint)) },
                    singleLine = true,
                    isError = error != null,
                    supportingText = error?.let { shown -> { Text(stringResource(errorText(shown))) } },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier.weight(1f).testTag("hidden_keywords_input"),
                )
                Button(onClick = { submit() }, modifier = Modifier.heightIn(min = 56.dp).testTag("hidden_keywords_add")) {
                    Text(stringResource(R.string.hidden_keywords_add))
                }
            }
            keywords.forEach { keyword ->
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("hidden_keyword_row"), verticalAlignment = Alignment.CenterVertically) {
                    Text(keyword, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    IconButton(onClick = { onRemove(keyword) }, modifier = Modifier.testTag("hidden_keyword_remove")) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.hidden_keywords_remove, keyword))
                    }
                }
            }
        }
    }
}

private fun errorText(result: HiddenKeywords.AddResult): Int = when (result) {
    HiddenKeywords.AddResult.Blank -> R.string.hidden_keywords_error_blank
    HiddenKeywords.AddResult.Duplicate -> R.string.hidden_keywords_error_duplicate
    HiddenKeywords.AddResult.TooLong -> R.string.hidden_keywords_error_long
    HiddenKeywords.AddResult.ListFull -> R.string.hidden_keywords_error_full
    is HiddenKeywords.AddResult.Added -> R.string.hidden_keywords_error_blank
}
