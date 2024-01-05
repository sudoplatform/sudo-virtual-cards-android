/*
 * Copyright © 2023 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards.types.transformers

import com.amazonaws.util.Base64
import com.sudoplatform.sudouser.PublicKey
import com.sudoplatform.sudovirtualcards.graphql.CreatePublicKeyMutation
import com.sudoplatform.sudovirtualcards.graphql.GetPublicKeyQuery
import com.sudoplatform.sudovirtualcards.keys.PublicKeyWithKeyRingId

/**
 * Transformer responsible for transforming the keys GraphQL data types to the
 * entity type that is exposed to users.
 */
internal object KeyTransformer {

    /**
     * Transform the results of the [CreatePublicKeyMutation.CreatePublicKeyForVirtualCards] mutation.
     *
     * @param result [CreatePublicKeyMutation.CreatePublicKeyForVirtualCards] The GraphQL mutation results.
     * @return The [PublicKey] entity type.
     */
    fun toPublicKeyWithKeyRingId(result: CreatePublicKeyMutation.CreatePublicKeyForVirtualCards): PublicKeyWithKeyRingId {
        val publicKeyWithKeyRingId = result.fragments().publicKey()
        return PublicKeyWithKeyRingId(
            publicKey = PublicKey(
                keyId = publicKeyWithKeyRingId.keyId(),
                publicKey = Base64.decode(publicKeyWithKeyRingId.publicKey()),
                algorithm = publicKeyWithKeyRingId.algorithm(),
            ),
            keyRingId = publicKeyWithKeyRingId.keyRingId(),
        )
    }

    /**
     * Transform the results of the [GetPublicKeyQuery.GetPublicKeyForVirtualCards] query.
     *
     * @param result [GetPublicKeyQuery.GetPublicKeyForVirtualCards] The GraphQL query results.
     * @return The [PublicKey] entity type.
     */
    fun toPublicKeyWithKeyRingId(result: GetPublicKeyQuery.GetPublicKeyForVirtualCards): PublicKeyWithKeyRingId {
        val publicKeyWithKeyRingId = result.fragments().publicKey()
        return PublicKeyWithKeyRingId(
            publicKey = PublicKey(
                keyId = publicKeyWithKeyRingId.keyId(),
                publicKey = Base64.decode(publicKeyWithKeyRingId.publicKey()),
                algorithm = publicKeyWithKeyRingId.algorithm(),
            ),
            keyRingId = publicKeyWithKeyRingId.keyRingId(),
        )
    }
}
