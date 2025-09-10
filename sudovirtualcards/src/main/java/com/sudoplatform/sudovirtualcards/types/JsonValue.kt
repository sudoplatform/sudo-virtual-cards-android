/*
 * Copyright © 2024 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards.types

/**
 * A representation of a [JsonValue] type used to represent data that can pertain
 * to multiple primitive types.
 */
sealed class JsonValue<out T> {
    data class JsonString(
        val value: String,
    ) : JsonValue<String>()

    data class JsonInteger(
        val value: Int,
    ) : JsonValue<Int>()

    data class JsonDouble(
        val value: Double,
    ) : JsonValue<Double>()

    data class JsonBoolean(
        val value: Boolean,
    ) : JsonValue<Boolean>()

    data class JsonArray<V>(
        val value: List<V>,
    ) : JsonValue<Array<V>>()

    data class JsonMap(
        val value: Map<*, *>,
    ) : JsonValue<Map<String, Any>>()

    /**
     * Unwrap the value associated with the specific [JsonValue] type.
     */
    fun unwrap(): Any =
        when (this) {
            is JsonString -> this.value
            is JsonInteger -> this.value
            is JsonDouble -> this.value
            is JsonBoolean -> this.value
            is JsonArray<*> -> this.value
            is JsonMap -> this.value
        }
}
