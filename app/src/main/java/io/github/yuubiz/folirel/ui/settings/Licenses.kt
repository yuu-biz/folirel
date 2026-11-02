package io.github.yuubiz.folirel.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.yuubiz.folirel.R
import io.github.yuubiz.folirel.ui.common.GroupHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A licence or notice text as the library ships it. */
data class LicenseText(val title: String, val body: String)

data class LicensedLibrary(val name: String, val module: String, val version: String, val notices: List<LicenseText>)

/** One licence text and the libraries it covers. */
data class LicenseGroup(val text: LicenseText, val libraries: List<LicensedLibrary>)

/** The asset the build writes from the runtime classpath (LicenseCatalogTask in app/build.gradle.kts). */
const val LICENSE_CATALOG_ASSET = "licenses.json"

/** Groups the libraries by licence text, in the order the catalogue first names each text (Folirel's own licence first). */
fun parseLicenseCatalog(json: String): List<LicenseGroup> {
    val root = Json.parseToJsonElement(json).jsonObject
    val texts = root.getValue("texts").jsonObject.mapValues { (_, v) -> LicenseText(v.string("title"), v.string("body")) }
    val groups = linkedMapOf<String, MutableList<LicensedLibrary>>()
    root.getValue("libraries").jsonArray.forEach { element ->
        val lib = element.jsonObject
        val library = LicensedLibrary(
            name = lib.string("name"),
            module = lib.string("module"),
            version = lib.string("version"),
            notices = lib.keys("notices").map(texts::getValue),
        )
        lib.keys("licenses").forEach { groups.getOrPut(it) { mutableListOf() } += library }
    }
    return groups.map { (key, libraries) -> LicenseGroup(texts.getValue(key), libraries) }
}

private fun kotlinx.serialization.json.JsonElement.string(name: String) = jsonObject.getValue(name).jsonPrimitive.content

private fun JsonObject.keys(name: String) = getValue(name).jsonArray.map { it.jsonPrimitive.content }

/** Settings > About > Licenses: each licence once, the libraries under it, and its full text one tap away. */
@Composable
internal fun LicensesPage() {
    val context = LocalContext.current
    val groups by produceState<List<LicenseGroup>>(emptyList()) {
        value = withContext(Dispatchers.IO) { context.assets.open(LICENSE_CATALOG_ASSET).use { parseLicenseCatalog(it.readBytes().decodeToString()) } }
    }
    Column {
        groups.forEachIndexed { index, group ->
            GroupHeader(group.text.title)
            group.libraries.forEach { LibraryRow(it) }
            ExpandableText(
                show = stringResource(R.string.licenses_show_text),
                text = group.text.body,
                tag = "licenses_text_$index",
            )
        }
    }
}

@Composable
private fun LibraryRow(library: LicensedLibrary) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(library.name, style = MaterialTheme.typography.bodyMedium)
        Text(
            listOf(library.module, library.version).filter { it.isNotEmpty() }.joinToString(" "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        library.notices.forEach { notice -> ExpandableText(notice.title, notice.body, "licenses_notice_${library.module}") }
    }
}

@Composable
private fun ExpandableText(show: String, text: String, tag: String) {
    var open by rememberSaveable(tag) { mutableStateOf(false) }
    Column {
        TextButton(onClick = { open = !open }, modifier = Modifier.padding(horizontal = 4.dp).testTag(tag)) {
            Text(if (open) stringResource(R.string.licenses_hide_text) else show)
        }
        if (open) {
            Text(
                text.trim(),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).testTag("${tag}_body"),
            )
        }
    }
}
