package org.tdgs.ph.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

// The hub's dashboard model is large and every field may be missing or null,
// so it is read through a forgiving tree walker instead of strict DTOs.

fun JsonElement?.obj(key: String): JsonObject? = (this as? JsonObject)?.get(key) as? JsonObject
fun JsonElement?.arr(key: String): List<JsonElement> = ((this as? JsonObject)?.get(key) as? JsonArray) ?: emptyList()
fun JsonElement?.prim(key: String): JsonPrimitive? =
    ((this as? JsonObject)?.get(key)).let { if (it is JsonPrimitive && it !is JsonNull) it else null }

fun JsonElement?.str(key: String): String? = prim(key)?.let { if (it.isString) it.content else it.content.takeIf { c -> c != "null" } }
fun JsonElement?.dbl(key: String): Double? = prim(key)?.doubleOrNull?.takeIf { it.isFinite() }
fun JsonElement?.lng(key: String): Long? = prim(key)?.let { it.longOrNull ?: it.doubleOrNull?.takeIf { d -> d.isFinite() }?.toLong() }
fun JsonElement?.int(key: String): Int? = lng(key)?.toInt()
fun JsonElement?.bool(key: String): Boolean? = prim(key)?.booleanOrNull

fun JsonElement.asDoubleOrNull(): Double? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.doubleOrNull
