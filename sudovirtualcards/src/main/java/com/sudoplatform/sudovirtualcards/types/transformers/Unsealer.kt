/*
 * Copyright © 2022 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards.types.transformers

import androidx.annotation.VisibleForTesting
import com.amazonaws.util.Base64
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.sudoplatform.sudokeymanager.KeyManagerInterface
import com.sudoplatform.sudovirtualcards.graphql.GetProvisionalCardQuery
import com.sudoplatform.sudovirtualcards.graphql.fragment.SealedCard
import com.sudoplatform.sudovirtualcards.graphql.fragment.SealedCurrencyAmountAttribute
import com.sudoplatform.sudovirtualcards.keys.DefaultPublicKeyService
import com.sudoplatform.sudovirtualcards.keys.DeviceKeyManager
import com.sudoplatform.sudovirtualcards.types.BillingAddress
import com.sudoplatform.sudovirtualcards.types.CurrencyAmount
import com.sudoplatform.sudovirtualcards.types.Expiry
import com.sudoplatform.sudovirtualcards.types.JsonValue
import com.sudoplatform.sudovirtualcards.types.SymmetricKeyEncryptionAlgorithm

/**
 * Unpack and decrypt the sealed fields of a virtual card and transaction.
 */
internal class Unsealer(
    private val deviceKeyManager: DeviceKeyManager,
    private val keyInfo: KeyInfo
) {
    companion object {
        /** Size of the AES symmetric key. */
        @VisibleForTesting
        const val KEY_SIZE_AES = 256
    }

    private val algorithm: KeyManagerInterface.PublicKeyEncryptionAlgorithm = when (keyInfo.algorithm) {
        DefaultPublicKeyService.DEFAULT_ALGORITHM -> KeyManagerInterface.PublicKeyEncryptionAlgorithm.RSA_ECB_OAEPSHA1
        else -> KeyManagerInterface.PublicKeyEncryptionAlgorithm.RSA_ECB_PKCS1
    }

    sealed class UnsealerException(message: String? = null, cause: Throwable? = null) : RuntimeException(message, cause) {
        class SealedDataTooShortException(message: String? = null, cause: Throwable? = null) :
            UnsealerException(message, cause)
        class UnsupportedDataTypeException(message: String? = null, cause: Throwable? = null) :
            UnsealerException(message, cause)
        class UnsupportedAlgorithmException(message: String? = null, cause: Throwable? = null) :
            UnsealerException(message, cause)
    }

    /**
     * The sealed value is a base64 encoded string that when base64 decoded contains:
     * bytes 0..255   : symmetric decryption key that is encrypted with the public key
     * bytes 256..end : the data that is encrypted with the symmetric key
     */
    @Throws(UnsealerException::class)
    fun unseal(valueBase64: String): String {
        val valueBytes = Base64.decode(valueBase64)
        return decrypt(keyInfo, valueBytes)
    }

    private fun decrypt(keyInfo: KeyInfo, data: ByteArray): String {
        return when (keyInfo.keyType) {
            KeyType.PRIVATE_KEY -> {
                if (data.size < KEY_SIZE_AES) {
                    throw UnsealerException.SealedDataTooShortException("Sealed value too short")
                }
                val aesEncrypted = data.copyOfRange(0, KEY_SIZE_AES)
                val cipherData = data.copyOfRange(KEY_SIZE_AES, data.size)
                val aesDecrypted = deviceKeyManager.decryptWithPrivateKey(aesEncrypted, keyInfo.keyId, algorithm)
                String(deviceKeyManager.decryptWithSymmetricKey(aesDecrypted, cipherData), Charsets.UTF_8)
            }
            KeyType.SYMMETRIC_KEY -> {
                String(deviceKeyManager.decryptWithSymmetricKeyId(keyInfo.keyId, data))
            }
        }
    }

    /**
     * Unseal the fields of a GraphQL [CardProvisionMutation.BillingAddress] and convert them to
     * a [BillingAddress].
     */
    fun unseal(value: SealedCard.BillingAddress?): BillingAddress? {
        if (value == null) {
            return null
        }

        val billingAddress = value.fragments().sealedAddressAttribute()

        return BillingAddress(
            addressLine1 = unseal(billingAddress.addressLine1()),
            addressLine2 = billingAddress.addressLine2()?.let { unseal(it) },
            city = unseal(billingAddress.city()),
            state = unseal(billingAddress.state()),
            postalCode = unseal(billingAddress.postalCode()),
            country = unseal(billingAddress.country())
        )
    }

    /**
     * Unseal the fields of the GraphQL [GetProvisionalCardQuery.Expiry] and convert them
     * to an [Expiry].
     */
    fun unseal(value: SealedCard.Expiry): Expiry {
        val expiry = value.fragments().sealedExpiryAttribute()
        return Expiry(
            mm = unseal(expiry.mm()),
            yyyy = unseal(expiry.yyyy())
        )
    }

    /**
     * Unseal the fields of the GraphQL [GetProvisionalCardQuery.Metadata] and convert them
     * to a [Metadata] type.
     */
    fun unseal(value: SealedCard.Metadata): JsonValue<Any> {
        val metadata = value.fragments().sealedAttribute()
        return unsealJsonValue(metadata.algorithm(), metadata.base64EncodedSealedData())
    }

    /**
     * Unseal the fields that make up a sealed currency amount and convert them to a
     * [CurrencyAmount].
     */
    fun unsealAmount(sealedAmount: SealedCurrencyAmountAttribute): CurrencyAmount {
        return CurrencyAmount(
            currency = unseal(sealedAmount.currency()),
            amount = unseal(sealedAmount.amount()).toInt()
        )
    }

    private fun unsealJsonValue(algorithm: String, base64EncodedSealedData: String): JsonValue<Any> {
        if (!SymmetricKeyEncryptionAlgorithm.isAlgorithmSupported(algorithm)) {
            throw UnsealerException.UnsupportedAlgorithmException(algorithm)
        }
        val unsealedData = unseal(base64EncodedSealedData)
        val anyJson = JsonParser.parseString(unsealedData)
        return toJsonValue(anyJson)
    }

    private fun toJsonValue(value: JsonElement): JsonValue<Any> {
        return when {
            value.isJsonPrimitive -> {
                val primitive = value.asJsonPrimitive
                when {
                    primitive.isString -> JsonValue.JsonString(primitive.asString)
                    primitive.isNumber -> {
                        if (primitive.asDouble.rem(1).equals(0.0)) {
                            JsonValue.JsonInteger(primitive.asInt)
                        } else {
                            JsonValue.JsonDouble(primitive.asDouble)
                        }
                    }
                    primitive.isBoolean -> JsonValue.JsonBoolean(primitive.asBoolean)
                    else -> {
                        throw UnsealerException.UnsupportedDataTypeException()
                    }
                }
            }
            value.isJsonArray -> {
                val list = Gson().fromJson(value.asJsonArray, List::class.java)
                JsonValue.JsonArray(list)
            }
            value.isJsonObject -> {
                val map = Gson().fromJson(value.asJsonObject, Map::class.java)
                JsonValue.JsonMap(map)
            }
            else -> {
                throw UnsealerException.UnsupportedDataTypeException()
            }
        }
    }
}
