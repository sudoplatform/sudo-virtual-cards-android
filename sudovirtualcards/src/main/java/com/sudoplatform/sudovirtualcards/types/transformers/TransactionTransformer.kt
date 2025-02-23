/*
 * Copyright © 2024 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards.types.transformers

import androidx.annotation.VisibleForTesting
import com.sudoplatform.sudovirtualcards.graphql.fragment.SealedTransaction
import com.sudoplatform.sudovirtualcards.graphql.type.TransactionType
import com.sudoplatform.sudovirtualcards.keys.DeviceKeyManager
import com.sudoplatform.sudovirtualcards.types.DeclineReason
import com.sudoplatform.sudovirtualcards.types.Markup
import com.sudoplatform.sudovirtualcards.types.PartialTransaction
import com.sudoplatform.sudovirtualcards.types.Transaction
import com.sudoplatform.sudovirtualcards.types.TransactionDetailCharge
import com.sudoplatform.sudovirtualcards.types.ChargeDetailState as ChargeDetailStateEntity
import com.sudoplatform.sudovirtualcards.types.TransactionType as TransactionTypeEntity

/**
 * Transformer responsible for transforming the [Transaction] GraphQL data
 * types to the entity type that is exposed to users.
 */
internal object TransactionTransformer {

    /**
     * Transform the results of the [SealedTransaction] GraphQL type to its entity type.
     *
     * @param deviceKeyManager [DeviceKeyManager] Used to retrieve keys to unseal data.
     * @param transaction [SealedTransaction] The GraphQL type.
     * @return The [Transaction] entity type.
     */
    fun toEntity(
        deviceKeyManager: DeviceKeyManager,
        transaction: SealedTransaction,
    ): Transaction {
        val keyInfo = KeyInfo(transaction.keyId, KeyType.PRIVATE_KEY, transaction.algorithm)
        val unsealer = Unsealer(deviceKeyManager, keyInfo)
        return transaction.toTransaction(unsealer)
    }

    private fun SealedTransaction.toTransaction(unsealer: Unsealer): Transaction {
        return Transaction(
            id = id,
            owner = owner,
            version = version,
            cardId = cardId,
            sequenceId = sequenceId,
            type = type.toTransactionType(),
            billedAmount = unsealer.unsealAmount(billedAmount.sealedCurrencyAmountAttribute),
            transactedAmount = unsealer.unsealAmount(transactedAmount.sealedCurrencyAmountAttribute),
            description = unsealer.unseal(description),
            declineReason = declineReason?.let { unsealer.unseal(it).toDeclineReason() },
            details = toEntityFromTransactionDetail(unsealer, detail),
            transactedAt = unsealer.unseal(transactedAtEpochMs).toDouble().toDate(),
            settledAt = settledAtEpochMs?.let { unsealer.unseal(settledAtEpochMs).toDouble().toDate() },
            createdAt = createdAtEpochMs.toDate(),
            updatedAt = updatedAtEpochMs.toDate(),
        )
    }

    /**
     * Transform the [SealedTransaction] GraphQL type into a [PartialTransaction].
     *
     * @param transaction [SealedTransaction] The GraphQL type.
     * @return The [PartialTransaction] entity type.
     */
    fun toPartialEntity(transaction: SealedTransaction): PartialTransaction {
        return PartialTransaction(
            id = transaction.id,
            owner = transaction.owner,
            version = transaction.version,
            cardId = transaction.cardId,
            sequenceId = transaction.sequenceId,
            type = transaction.type.toTransactionType(),
            createdAt = transaction.createdAtEpochMs.toDate(),
            updatedAt = transaction.updatedAtEpochMs.toDate(),
        )
    }

    private fun TransactionType.toTransactionType(): TransactionTypeEntity {
        for (txnType in TransactionTypeEntity.entries) {
            if (txnType.name == this.name) {
                return txnType
            }
        }
        return TransactionTypeEntity.UNKNOWN
    }

    private fun toEntityFromTransactionDetail(
        unsealer: Unsealer,
        results: List<SealedTransaction.Detail>?,
    ): List<TransactionDetailCharge> {
        return results?.map { it.toTransactionDetailCharge(unsealer) }
            ?.toList()
            ?: emptyList()
    }

    private fun SealedTransaction.Detail.toTransactionDetailCharge(unsealer: Unsealer): TransactionDetailCharge {
        val sealedDetail = sealedTransactionDetailChargeAttribute
        val sealedMarkup = sealedTransactionDetailChargeAttribute.markup.sealedMarkupAttribute
        return TransactionDetailCharge(
            virtualCardAmount = unsealer.unsealAmount(
                sealedDetail.virtualCardAmount.sealedCurrencyAmountAttribute,
            ),
            markup = Markup(
                percent = unsealer.unseal(sealedMarkup.percent).toInt(),
                flat = unsealer.unseal(sealedMarkup.flat).toInt(),
                minCharge = sealedMarkup.minCharge?.let { unsealer.unseal(it).toInt() } ?: 0,
            ),
            markupAmount = unsealer.unsealAmount(
                sealedDetail.markupAmount.sealedCurrencyAmountAttribute,
            ),
            fundingSourceAmount = unsealer.unsealAmount(
                sealedDetail.fundingSourceAmount.sealedCurrencyAmountAttribute,
            ),
            transactedAt = sealedDetail.transactedAtEpochMs?.let { unsealer.unseal(sealedDetail.transactedAtEpochMs).toDouble().toDate() },
            settledAt = sealedDetail.settledAtEpochMs?.let { unsealer.unseal(sealedDetail.settledAtEpochMs).toDouble().toDate() },
            fundingSourceId = sealedDetail.fundingSourceId,
            description = unsealer.unseal(sealedDetail.description),
            state = sealedDetail.state?.let { unsealer.unseal(it).toChargeDetailState() } ?: ChargeDetailStateEntity.CLEARED,
        )
    }
}

@VisibleForTesting
internal fun String.toDeclineReason(): DeclineReason {
    for (value in DeclineReason.entries) {
        if (value.name == this) {
            return value
        }
    }
    return DeclineReason.UNKNOWN
}

@VisibleForTesting
internal fun String.toChargeDetailState(): ChargeDetailStateEntity {
    for (value in ChargeDetailStateEntity.entries) {
        if (value.name == this) {
            return value
        }
    }
    return ChargeDetailStateEntity.UNKNOWN
}
