/*
 * Copyright © 2022 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards.types.transformers

import com.amazonaws.util.Base64
import com.sudoplatform.sudouser.PublicKey
import com.sudoplatform.sudovirtualcards.graphql.CreatePublicKeyForVirtualCardsMutation
import com.sudoplatform.sudovirtualcards.graphql.GetKeyRingForVirtualCardsQuery
import com.sudoplatform.sudovirtualcards.keys.KeyRing

/**
 * Transformer responsible for transforming the keys GraphQL data types to the
 * entity type that is exposed to users.
 */
internal object KeyTransformer {

    /**
     * Transform the results of the [GetKeyRingForVirtualCardsQuery].
     *
     * @param result [GetKeyRingForVirtualCardsQuery.GetKeyRingForVirtualCards] The GraphQL query results.
     * @return The [KeyRing] entity type.
     */
    fun toKeyRing(result: GetKeyRingForVirtualCardsQuery.GetKeyRingForVirtualCards): KeyRing {
        val keys = result.items().map {
            PublicKey(
                keyId = it.keyId(),
                publicKey = Base64.decode(it.publicKey()),
                algorithm = it.algorithm()
            )
        }
        val nextToken = result.nextToken()
        return KeyRing(
            id = result.items().firstOrNull()?.keyRingId() ?: "",
            keys = keys,
            nextToken = nextToken
        )
    }

    /**
     * Transform the results of the [CreatePublicKeyForVirtualCardsMutation].
     *
     * @param result [CreatePublicKeyForVirtualCardsMutation.CreatePublicKeyForVirtualCards] The GraphQL mutation results.
     * @return The [PublicKey] entity type.
     */
    fun toPublicKey(result: CreatePublicKeyForVirtualCardsMutation.CreatePublicKeyForVirtualCards): PublicKey {
        return PublicKey(
            keyId = result.keyId(),
            publicKey = Base64.decode(result.publicKey()),
            algorithm = result.algorithm()
        )
    }
}
