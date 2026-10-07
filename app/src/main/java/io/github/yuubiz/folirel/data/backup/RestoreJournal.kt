package io.github.yuubiz.folirel.data.backup

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.byteArrayPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.io.File
import java.io.FileOutputStream
import java.util.Base64

/**
 * The note a restore leaves while it changes data, in app-private storage. The settings are written before the database, so the note
 * holds the settings as they were and the [token] the database transaction stores in the same commit. When the app starts and finds a
 * note, the restore did not finish: if the database carries the token it was committed (the new settings are already in place),
 * otherwise the old settings are put back. Either way the app is never left with the settings of one state and the data of the other.
 */
internal class RestoreJournal(private val file: File) {
    data class Entry(val token: Int, val oldSettings: Preferences)

    fun exists(): Boolean = file.exists()

    /** Written and synced to disk before anything changes; fails (and nothing changes) if the settings cannot be represented. */
    fun write(entry: Entry) {
        val text = buildJsonObject {
            put("token", entry.token)
            put("settings", encode(entry.oldSettings))
        }.toString()
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(temp).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        java.nio.file.Files.move(temp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }

    fun read(): Entry {
        val o = Json.parseToJsonElement(file.readText(Charsets.UTF_8)).jsonObject
        return Entry(o.getValue("token").jsonPrimitive.int, decode(o.getValue("settings").jsonArray))
    }

    fun delete(): Boolean = !file.exists() || file.delete()

    private fun encode(p: Preferences): JsonArray = buildJsonArray {
        for ((key, value) in p.asMap()) {
            add(buildJsonObject {
                put("k", key.name)
                when (value) {
                    is Boolean -> { put("t", "b"); put("v", value) }
                    is Int -> { put("t", "i"); put("v", value) }
                    is Long -> { put("t", "l"); put("v", value) }
                    // Exact bits: a text scale must come back as the same float.
                    is Float -> { put("t", "f"); put("v", value.toRawBits()) }
                    is Double -> { put("t", "d"); put("v", value.toRawBits()) }
                    is String -> { put("t", "s"); put("v", value) }
                    is ByteArray -> { put("t", "y"); put("v", Base64.getEncoder().encodeToString(value)) }
                    is Set<*> -> { put("t", "ss"); put("v", JsonArray(value.map { JsonPrimitive(it as String) })) }
                    else -> throw IllegalStateException("setting of an unknown type")
                }
            })
        }
    }

    private fun decode(array: JsonArray): Preferences {
        val p = mutablePreferencesOf()
        for (item in array) {
            val o: JsonObject = item.jsonObject
            val name = o.getValue("k").jsonPrimitive.content
            val v = o.getValue("v")
            when (o.getValue("t").jsonPrimitive.content) {
                "b" -> p[booleanPreferencesKey(name)] = v.jsonPrimitive.content.toBooleanStrict()
                "i" -> p[intPreferencesKey(name)] = v.jsonPrimitive.int
                "l" -> p[longPreferencesKey(name)] = v.jsonPrimitive.long
                "f" -> p[floatPreferencesKey(name)] = Float.fromBits(v.jsonPrimitive.int)
                "d" -> p[doublePreferencesKey(name)] = Double.fromBits(v.jsonPrimitive.long)
                "s" -> p[stringPreferencesKey(name)] = v.jsonPrimitive.content
                "y" -> p[byteArrayPreferencesKey(name)] = Base64.getDecoder().decode(v.jsonPrimitive.content)
                "ss" -> p[stringSetPreferencesKey(name)] = v.jsonArray.map { it.jsonPrimitive.content }.toSet()
                else -> throw IllegalStateException("unknown setting type")
            }
        }
        return p.toPreferences()
    }
}
