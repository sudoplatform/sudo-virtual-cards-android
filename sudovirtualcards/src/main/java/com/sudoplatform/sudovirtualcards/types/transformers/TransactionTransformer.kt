/*
 * Copyright © 2022 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards.types.transformers

import androidx.annotation.VisibleForTesting
import com.sudoplatform.sudovirtualcards.graphql.GetTransactionQuery
import com.sudoplatform.sudovirtualcards.graphql.ListTransactionsByCardIdQuery
import com.sudoplatform.sudovirtualcards.graphql.OnTransactionDeleteSubscription
import com.sudoplatform.sudovirtualcards.graphql.OnTransactionUpdateSubscription
import com.sudoplatform.sudovirtualcards.graphql.type.TransactionType
import com.sudoplatform.sudovirtualcards.keys.DeviceKeyManager
import com.sudoplatform.sudovirtualcards.types.DeclineReason
import com.sudoplatform.sudovirtualcards.types.Markup
import com.sudoplatform.sudovirtualcards.types.PartialTransaction
import com.sudoplatform.sudovirtualcards.types.Transaction
import com.sudoplatform.sudovirtualcards.types.TransactionDetailCharge
import com.sudoplatform.sudovirtualcards.types.TransactionType as TransactionTypeEntity

/**
 * Transformer responsible for transforming the [Transaction] GraphQL data
 * types to the entity type that is exposed to users.
 */
internal object TransactionTransformer {

    /**
     * Transform the results of the [GetTransactionQuery].
     *
     * @param deviceKeyManager [DeviceKeyManager] Used to retrieve keys to unseal data.
     * @param result [GetTransactionQuery.GetTransaction] The GraphQL query results.
     * @return The [Transaction] entity type.
     */
    fun toEntityFromGetTransactionQueryResult(
        deviceKeyManager: DeviceKeyManager,
        result: GetTransactionQuery.GetTransaction
    ): Transaction {
        val keyInfo = KeyInfo(result.keyId(), KeyType.PRIVATE_KEY, result.algorithm())
        val unsealer = Unsealer(deviceKeyManager, keyInfo)
        return result.toTransaction(unsealer)
    }

    private fun GetTransactionQuery.GetTransaction.toTransaction(unsealer: Unsealer): Transaction {
        return Transaction(
            id = id(),
            owner = owner(),
            version = version(),
            cardId = cardId(),
            sequenceId = sequenceId(),
            type = type().toTransactionType(),
            billedAmount = unsealer.unsealAmount(billedAmount().currency(), billedAmount().amount()),
            transactedAmount = unsealer.unsealAmount(transactedAmount().currency(), transactedAmount().amount()),
            description = unsealer.unseal(description()),
            declineReason = declineReason()?.let { unsealer.unseal(it).toDeclineReason() },
            details = toEntityFromGetTransactionDetail(unsealer, detail()),
            transactedAt = unsealer.unseal(transactedAtEpochMs()).toDouble().toDate(),
            createdAt = createdAtEpochMs().toDate(),
            updatedAt = updatedAtEpochMs().toDate()
        )
    }

    /**
     * Transform the results of the [ListTransactionsByCardIdQuery.Item].
     *
     * @param deviceKeyManager [DeviceKeyManager] Used to retrieve keys to unseal data.
     * @param result [ListTransactionsByCardIdQuery.Item] The GraphQL query result.
     * @return The [Transaction] entity type.
     */
    fun toEntityFromListTransactionsByCardIdQueryResult(
        deviceKeyManager: DeviceKeyManager,
        result: ListTransactionsByCardIdQuery.Item
    ): Transaction {
        val keyInfo = KeyInfo(result.keyId(), KeyType.PRIVATE_KEY, result.algorithm())
        val unsealer = Unsealer(deviceKeyManager, keyInfo)
        return result.toTransaction(unsealer)
    }

    /**
     * Transform the results of the [ListTransactionsByCardIdQuery.Item] into a [PartialTransaction].
     *
     * @param result [ListTransactionsByCardIdQuery.Item] The GraphQL query result.
     * @return The [PartialTransaction] entity type.
     */
    fun toPartialEntityFromListTransactionsByCardIdQueryResult(result: ListTransactionsByCardIdQuery.Item): PartialTransaction {
        return PartialTransaction(
            id = result.id(),
            owner = result.owner(),
            version = result.version(),
            cardId = result.cardId(),
            sequenceId = result.sequenceId(),
            type = result.type().toTransactionType(),
            createdAt = result.createdAtEpochMs().toDate(),
            updatedAt = result.updatedAtEpochMs().toDate()
        )
    }

    private fun ListTransactionsByCardIdQuery.Item.toTransaction(unsealer: Unsealer): Transaction {
        return Transaction(
            id = id(),
            owner = owner(),
            version = version(),
            cardId = cardId(),
            sequenceId = sequenceId(),
            type = type().toTransactionType(),
            billedAmount = unsealer.unsealAmount(billedAmount().currency(), billedAmount().amount()),
            transactedAmount = unsealer.unsealAmount(transactedAmount().currency(), transactedAmount().amount()),
            description = unsealer.unseal(description()),
            declineReason = declineReason()?. let { unsealer.unseal(it).toDeclineReason() },
            details = toEntityFromListTransactionsDetail(unsealer, detail()),
            transactedAt = unsealer.unseal(transactedAtEpochMs()).toDouble().toDate(),
            createdAt = createdAtEpochMs().toDate(),
            updatedAt = updatedAtEpochMs().toDate()
        )
    }

    private fun TransactionType.toTransactionType(): TransactionTypeEntity {
        for (txnType in TransactionTypeEntity.values()) {
            if (txnType.name == this.name) {
                return txnType
            }
        }
        return TransactionTypeEntity.UNKNOWN
    }

    private fun toEntityFromGetTransactionDetail(
        unsealer: Unsealer,
        results: List<GetTransactionQuery.Detail>?
    ): List<TransactionDetailCharge> {
        return results?.map { it.toTransactionDetailCharge(unsealer) }
            ?.toList()
            ?: emptyList()
    }

    private fun GetTransactionQuery.Detail.toTransactionDetailCharge(unsealer: Unsealer): TransactionDetailCharge {
        return TransactionDetailCharge(
            virtualCardAmount = unsealer.unsealAmount(
                virtualCardAmount().currency(),
                virtualCardAmount().amount()
            ),
            markup = Markup(
                percent = unsealer.unseal(markup().percent()).toInt(),
                flat = unsealer.unseal(markup().flat()).toInt(),
                minCharge = markup().minCharge()?.let { unsealer.unseal(it).toInt() } ?: 0
            ),
            markupAmount = unsealer.unsealAmount(
                markupAmount().currency(),
                markupAmount().amount()
            ),
            fundingSourceAmount = unsealer.unsealAmount(
                fundingSourceAmount().currency(),
                fundingSourceAmount().amount()
            ),
            fundingSourceId = fundingSourceId(),
            description = unsealer.unseal(description())
        )
    }

    private fun ListTransactionsByCardIdQuery.Detail.toTransactionDetailCharge(unsealer: Unsealer): TransactionDetailCharge {
        return TransactionDetailCharge(
            virtualCardAmount = unsealer.unsealAmount(
                virtualCardAmount().currency(),
                virtualCardAmount().amount()
            ),
            markup = Markup(
                percent = unsealer.unseal(markup().percent()).toInt(),
                flat = unsealer.unseal(markup().flat()).toInt(),
                minCharge = markup().minCharge()?.let { unsealer.unseal(it).toInt() } ?: 0
            ),
            markupAmount = unsealer.unsealAmount(
                markupAmount().currency(),
                markupAmount().amount()
            ),
            fundingSourceAmount = unsealer.unsealAmount(
                fundingSourceAmount().currency(),
                fundingSourceAmount().amount()
            ),
            fundingSourceId = fundingSourceId(),
            description = unsealer.unseal(description())
        )
    }

    private fun toEntityFromListTransactionsDetail(
        unsealer: Unsealer,
        results: List<ListTransactionsByCardIdQuery.Detail>?
    ): List<TransactionDetailCharge> {
        return results?.map { it.toTransactionDetailCharge(unsealer) }
            ?.toList()
            ?: emptyList()
    }

    /**
     * Transform the data from the update transaction subscription
     *
     * @param deviceKeyManager [DeviceKeyManager] Used to retrieve keys to unseal data.
     * @param result [OnTransactionUpdateSubscription.OnTransactionUpdate] The GraphQL subscription data.
     * @return The [Transaction] entity type.
     */
    fun toEntityFromUpdateSubscription(
        deviceKeyManager: DeviceKeyManager,
        result: OnTransactionUpdateSubscription.OnTransactionUpdate
    ): Transaction {
        val keyInfo = KeyInfo(result.keyId(), KeyType.PRIVATE_KEY, result.algorithm())
        val unsealer = Unsealer(deviceKeyManager, keyInfo)
        return with(result) {
            Transaction(
                id = id(),
                owner = owner(),
                version = version(),
                cardId = cardId(),
                sequenceId = sequenceId(),
                type = type().toTransactionType(),
                billedAmount = unsealer.unsealAmount(billedAmount().currency(), billedAmount().amount()),
                transactedAmount = unsealer.unsealAmount(transactedAmount().currency(), transactedAmount().amount()),
                description = unsealer.unseal(description()),
                declineReason = declineReason()?. let { unsealer.unseal(it).toDeclineReason() },
                details = toEntityFromUpdateTransactionDetail(unsealer, detail()),
                transactedAt = unsealer.unseal(transactedAtEpochMs()).toDouble().toDate(),
                createdAt = createdAtEpochMs().toDate(),
                updatedAt = updatedAtEpochMs().toDate()
            )
        }
    }

    private fun toEntityFromUpdateTransactionDetail(
        unsealer: Unsealer,
        results: List<OnTransactionUpdateSubscription.Detail>?
    ): List<TransactionDetailCharge> {
        return results?.map { it.toTransactionDetailCharge(unsealer) }
            ?.toList()
            ?: emptyList()
    }

    private fun OnTransactionUpdateSubscription.Detail.toTransactionDetailCharge(unsealer: Unsealer): TransactionDetailCharge {
        return TransactionDetailCharge(
            virtualCardAmount = unsealer.unsealAmount(
                virtualCardAmount().currency(),
                virtualCardAmount().amount()
            ),
            markup = Markup(
                percent = unsealer.unseal(markup().percent()).toInt(),
                flat = unsealer.unseal(markup().flat()).toInt(),
                minCharge = markup().minCharge()?.let { unsealer.unseal(it).toInt() } ?: 0
            ),
            markupAmount = unsealer.unsealAmount(
                markupAmount().currency(),
                markupAmount().amount()
            ),
            fundingSourceAmount = unsealer.unsealAmount(

                fundingSourceAmount().currency(),
                fundingSourceAmount().amount()
            ),
            fundingSourceId = fundingSourceId(),
            description = unsealer.unseal(description())
        )
    }

    /**
     * Transform the data from the delete transaction subscription
     *
     * @param deviceKeyManager [DeviceKeyManager] Used to retrieve keys to unseal data.
     * @param result [OnTransactionDeleteSubscription.OnTransactionDelete] The GraphQL subscription data.
     * @return The [Transaction] entity type.
     */
    fun toEntityFromDeleteSubscription(
        deviceKeyManager: DeviceKeyManager,
        result: OnTransactionDeleteSubscription.OnTransactionDelete
    ): Transaction {
        val keyInfo = KeyInfo(result.keyId(), KeyType.PRIVATE_KEY, result.algorithm())
        val unsealer = Unsealer(deviceKeyManager, keyInfo)
        return with(result) {
            Transaction(
                id = id(),
                owner = owner(),
                version = version(),
                cardId = cardId(),
                sequenceId = sequenceId(),
                type = type().toTransactionType(),
                billedAmount = unsealer.unsealAmount(billedAmount().currency(), billedAmount().amount()),
                transactedAmount = unsealer.unsealAmount(transactedAmount().currency(), transactedAmount().amount()),
                description = unsealer.unseal(description()),
                declineReason = declineReason()?. let { unsealer.unseal(it).toDeclineReason() },
                details = toEntityFromDeleteTransactionDetail(unsealer, detail()),
                transactedAt = unsealer.unseal(transactedAtEpochMs()).toDouble().toDate(),
                createdAt = createdAtEpochMs().toDate(),
                updatedAt = updatedAtEpochMs().toDate()
            )
        }
    }

    private fun toEntityFromDeleteTransactionDetail(
        unsealer: Unsealer,
        results: List<OnTransactionDeleteSubscription.Detail>?
    ): List<TransactionDetailCharge> {
        return results?.map { it.toTransactionDetailCharge(unsealer) }
            ?.toList()
            ?: emptyList()
    }

    private fun OnTransactionDeleteSubscription.Detail.toTransactionDetailCharge(unsealer: Unsealer): TransactionDetailCharge {
        return TransactionDetailCharge(
            virtualCardAmount = unsealer.unsealAmount(
                virtualCardAmount().currency(),
                virtualCardAmount().amount()
            ),
            markup = Markup(
                percent = unsealer.unseal(markup().percent()).toInt(),
                flat = unsealer.unseal(markup().flat()).toInt(),
                minCharge = markup().minCharge()?.let { unsealer.unseal(it).toInt() } ?: 0
            ),
            markupAmount = unsealer.unsealAmount(
                markupAmount().currency(),
                markupAmount().amount()
            ),
            fundingSourceAmount = unsealer.unsealAmount(
                fundingSourceAmount().currency(),
                fundingSourceAmount().amount()
            ),
            fundingSourceId = fundingSourceId(),
            description = unsealer.unseal(description())
        )
    }
}

@VisibleForTesting
internal fun String.toDeclineReason(): DeclineReason {
    for (value in DeclineReason.values()) {
        if (value.name == this) {
            return value
        }
    }
    return DeclineReason.UNKNOWN
}
