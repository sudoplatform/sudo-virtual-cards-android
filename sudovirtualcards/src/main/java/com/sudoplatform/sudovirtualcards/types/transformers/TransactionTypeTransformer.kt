/*
 * Copyright © 2024 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards.types.transformers

import com.sudoplatform.sudovirtualcards.graphql.type.TransactionType
import com.sudoplatform.sudovirtualcards.types.TransactionType as TransactionTypeEntity

/**
 * Transformer responsible for transforming the [TransactionType] entity type to the
 * GraphQL type.
 */
internal object TransactionTypeTransformer {

    /**
     * Transform the input type [TransactionTypeEntity] into the corresponding GraphQL type [TransactionType].
     */
    fun TransactionTypeEntity.toTransactionType(): TransactionType? {
        for (txnType in TransactionType.values()) {
            if (txnType.name == this.name) {
                return txnType
            }
        }
        return null
    }
}
