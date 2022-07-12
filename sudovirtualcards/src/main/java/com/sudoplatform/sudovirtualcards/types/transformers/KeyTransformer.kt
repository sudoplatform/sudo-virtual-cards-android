/*
 * Copyright © 2022 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards.types.transformers

import com.amazonaws.util.Base64
import com.sudoplatform.sudouser.PublicKey
import com.sudoplatform.sudovirtualcards.graphql.CreatePublicKeyForVirtualCardsMutation
import com.sudoplatform.sudovirtualcards.graphql.GetPublicKeyForVirtualCardsQuery
import com.sudoplatform.sudovirtualcards.keys.PublicKeyWithKeyRingId

/**
 * Transformer responsible for transforming the keys GraphQL data types to the
 * entity type that is exposed to users.
 */
internal object KeyTransformer {

    /**
     * Transform the results of the [CreatePublicKeyForVirtualCardsMutation].
     *
     * @param result [CreatePublicKeyForVirtualCardsMutation.CreatePublicKeyForVirtualCards] The GraphQL mutation results.
     * @return The [PublicKey] entity type.
     */
    fun toPublicKeyWithKeyRingId(result: CreatePublicKeyForVirtualCardsMutation.CreatePublicKeyForVirtualCards): PublicKeyWithKeyRingId {
        return PublicKeyWithKeyRingId(
            publicKey = PublicKey(
                keyId = result.keyId(),
                publicKey = Base64.decode(result.publicKey()),
                algorithm = result.algorithm()
            ),
            keyRingId = result.keyRingId()
        )
    }

    /**
     * Transform the results of the [GetPublicKeyForVirtualCardsQuery].
     *
     * @param result [GetPublicKeyForVirtualCardsQuery.GetPublicKeyForVirtualCards] The GraphQL query results.
     * @return The [PublicKey] entity type.
     */
    fun toPublicKeyWithKeyRingId(result: GetPublicKeyForVirtualCardsQuery.GetPublicKeyForVirtualCards): PublicKeyWithKeyRingId {
        return PublicKeyWithKeyRingId(
            publicKey = PublicKey(
                keyId = result.keyId(),
                publicKey = Base64.decode(result.publicKey()),
                algorithm = result.algorithm()
            ),
            keyRingId = result.keyRingId()
        )
    }
}
