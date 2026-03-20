/*
 * Copyright © 2024 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards.types.transformers

import com.amazonaws.util.Base64
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.sudoplatform.sudovirtualcards.types.BaseProvisioningData
import com.sudoplatform.sudovirtualcards.types.ProviderProvisioningData
import com.sudoplatform.sudovirtualcards.types.StripeCardProvisioningData
import java.lang.reflect.Type

/**
 * Transformer responsible for transforming the Funding Source-specific data types to the
 * entity type that is exposed to users.
 */
internal object ProviderDataTransformer {
    /**
     * Transform provisioning data
     *
     * @param provisioningData [String] Base-64 encoded string representation of provisioning data
     * @return The [ProviderProvisioningData] type.
     */

    fun toProvisioningData(provisioningData: String): ProviderProvisioningData {
        val provisioningDataBytes = Base64.decode(provisioningData)
        return GsonBuilder()
            .registerTypeAdapter(ProviderProvisioningData::class.java, ProvisioningDataDeserializer())
            .create()
            .fromJson(String(provisioningDataBytes, Charsets.UTF_8), ProviderProvisioningData::class.java)
    }

    fun extractAsStringOrThrow(
        jObject: JsonObject,
        fieldName: String,
    ): String {
        if (jObject.has(fieldName)) {
            return jObject[fieldName].asString
        }
        throw IllegalArgumentException("Missing provisioning data $fieldName information")
    }

    fun extractAsIntOrThrow(
        jObject: JsonObject,
        fieldName: String,
    ): Int {
        if (jObject.has(fieldName)) {
            return jObject[fieldName].asInt
        }
        throw IllegalArgumentException("Missing provisioning data $fieldName information")
    }
}

class ProvisioningDataDeserializer : JsonDeserializer<ProviderProvisioningData> {
    @Throws(JsonParseException::class)
    override fun deserialize(
        jElement: JsonElement,
        typeOfT: Type?,
        context: JsonDeserializationContext?,
    ): ProviderProvisioningData {
        val jObject = jElement.asJsonObject
        val provider = ProviderDataTransformer.extractAsStringOrThrow(jObject, "provider")
        return when (provider) {
            "stripe" -> context!!.deserialize(jElement, StripeCardProvisioningData::class.java)
            else -> context!!.deserialize(jElement, BaseProvisioningData::class.java)
        }
    }
}
