/*
 * Copyright © 2024 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards.signing

import com.sudoplatform.sudovirtualcards.types.transformers.KeyType

/**
 * Responsible for performing signing operations on data in the virtual cards service.
 */
internal interface SigningService {
    /**
     * Sign the [data] with the key [keyId] based on the [keyType].
     *
     * @property data [String] Plain text data to sign.
     * @property keyId [String] Identifier of the key used to sign the data.
     * @property keyType [KeyType] The type of key to use to sign the data.
     * @return Signed data.
     */
    fun signString(
        data: String,
        keyId: String,
        keyType: KeyType,
    ): String
}
