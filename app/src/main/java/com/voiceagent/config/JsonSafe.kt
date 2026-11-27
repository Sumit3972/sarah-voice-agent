package com.voiceagent.config

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

fun JsonElement?.asObjOrNull(): JsonObject? = this as? JsonObject
fun JsonElement?.asArrOrNull(): JsonArray? = this as? JsonArray
fun JsonElement?.asPrimOrNull(): JsonPrimitive? = if (this is JsonPrimitive && this !is JsonNull) this else null
fun JsonElement?.asStrOrNull(): String? = asPrimOrNull()?.let { if (it.isString) it.content else it.content }
fun JsonElement?.asIntOrNull(): Int? = asPrimOrNull()?.intOrNull
fun JsonElement?.asDoubleOrNull(): Double? = asPrimOrNull()?.doubleOrNull
fun JsonElement?.asBoolOrNull(): Boolean? = asPrimOrNull()?.booleanOrNull
