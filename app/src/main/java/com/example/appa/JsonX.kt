package com.example.appa

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Shared JSON instance + element accessors (single home, used by Config and all clients). */
val jsonX = Json { ignoreUnknownKeys = true }

fun JsonElement?.str(def: String = ""): String =
    if (this == null || this == JsonNull) def
    else (this as? JsonPrimitive)?.content?.trim() ?: def

fun JsonElement?.dbl(def: Double = 0.0): Double =
    (this as? JsonPrimitive)?.content?.toDoubleOrNull() ?: def

fun JsonElement?.int(def: Int = 0): Int =
    (this as? JsonPrimitive)?.content?.toIntOrNull() ?: def

fun JsonElement?.arr(): JsonArray = (this as? JsonArray) ?: JsonArray(emptyList())

fun JsonElement?.obj(): JsonObject = (this as? JsonObject) ?: JsonObject(emptyMap())
