/*
 * Copyright © 2024 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards.signing

import com.amazonaws.util.Base64
import com.sudoplatform.sudovirtualcards.keys.DeviceKeyManager
import com.sudoplatform.sudovirtualcards.types.transformers.KeyType

/**
 * The default implementation of the [SigningService].
 */
internal class DefaultSigningService(
    private val deviceKeyManager: DeviceKeyManager,
) : SigningService {

    companion object {
        private const val INVALID_KEY_TYPE_ERROR_MSG = "Key type must not be symmetric"
    }

    override fun signString(data: String, keyId: String, keyType: KeyType): String {
        when (keyType) {
            KeyType.SYMMETRIC_KEY -> throw IllegalArgumentException(INVALID_KEY_TYPE_ERROR_MSG)
            KeyType.PRIVATE_KEY -> {
                val signature = this.deviceKeyManager.signWithPrivateKeyId(
                    keyId = keyId,
                    data = data.toByteArray(),
                )
                return Base64.encode(signature).toString(Charsets.UTF_8)
            }
        }
    }
}
