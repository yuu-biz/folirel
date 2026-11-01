package io.github.yuubiz.folirel.ui.onboarding

import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.yuubiz.folirel.R

/**
 * S00: one explanation screen. Auto-update (every 2 h, unmetered only) is explained and can be switched off
 * before any network use. No notification or storage permission is requested.
 */
@Composable
fun OnboardingScreen(onFinish: (autoUpdate: Boolean, next: OnboardingNext) -> Unit) {
    var autoUpdate by rememberSaveable { mutableStateOf(true) }
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.onboarding_title), style = MaterialTheme.typography.headlineSmall)
            listOf(
                R.string.onboarding_point_local,
                R.string.onboarding_point_direct,
                R.string.onboarding_point_saved,
                R.string.onboarding_point_opml,
            ).forEach { Text(stringResource(R.string.onboarding_point, stringResource(it)), style = MaterialTheme.typography.bodyLarge) }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(value = autoUpdate, role = Role.Switch) { autoUpdate = it },
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.onboarding_auto_update))
                    Text(stringResource(R.string.onboarding_auto_update_note), style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = autoUpdate, onCheckedChange = null, modifier = Modifier.testTag("onboarding_auto_update"))
            }
            Button(onClick = { onFinish(autoUpdate, OnboardingNext.IMPORT) }, modifier = Modifier.fillMaxWidth().testTag("onboarding_import")) {
                Text(stringResource(R.string.action_import_opml))
            }
            OutlinedButton(onClick = { onFinish(autoUpdate, OnboardingNext.ADD_FEED) }, modifier = Modifier.fillMaxWidth().testTag("onboarding_add")) {
                Text(stringResource(R.string.action_add_feed))
            }
            TextButton(onClick = { onFinish(autoUpdate, OnboardingNext.SKIP) }, modifier = Modifier.align(Alignment.CenterHorizontally).testTag("onboarding_skip")) {
                Text(stringResource(R.string.onboarding_skip))
            }
        }
    }
}

enum class OnboardingNext { IMPORT, ADD_FEED, SKIP }
