package io.github.yuubiz.folirel.data.repository

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

/** Replaceable in tests for deterministic IDs. */
fun interface IdGenerator {
    fun newId(): String

    companion object {
        val Random = IdGenerator { UUID.randomUUID().toString() }
    }
}

/** Category path ⇄ JSON array. Segments may contain "/"; they never collide because they are array items. */
object CategoryPaths {
    fun encode(segments: List<String>): String = JsonArray(segments.map { JsonPrimitive(it) }).toString()

    fun decode(json: String): List<String> = Json.parseToJsonElement(json).jsonArray.map { it.jsonPrimitive.content }

    fun display(segments: List<String>): String = segments.joinToString(" / ")

    fun isSameOrDescendant(path: List<String>, ancestor: List<String>): Boolean =
        path.size >= ancestor.size && path.subList(0, ancestor.size) == ancestor
}
