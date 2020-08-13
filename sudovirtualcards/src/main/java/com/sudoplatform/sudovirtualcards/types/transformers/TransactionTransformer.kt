/*
 * Copyright © 2020 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards.types.transformers

import androidx.annotation.VisibleForTesting
import com.sudoplatform.sudovirtualcards.graphql.GetTransactionQuery
import com.sudoplatform.sudovirtualcards.graphql.ListTransactionsQuery
import com.sudoplatform.sudovirtualcards.graphql.OnTransactionDeleteSubscription
import com.sudoplatform.sudovirtualcards.graphql.OnTransactionUpdateSubscription
import com.sudoplatform.sudovirtualcards.graphql.type.IDFilterInput
import com.sudoplatform.sudovirtualcards.graphql.type.TransactionFilterInput
import com.sudoplatform.sudovirtualcards.graphql.type.TransactionType
import com.sudoplatform.sudovirtualcards.keys.DeviceKeyManager
import com.sudoplatform.sudovirtualcards.types.CurrencyAmount
import com.sudoplatform.sudovirtualcards.types.DeclineReason
import com.sudoplatform.sudovirtualcards.types.Markup
import com.sudoplatform.sudovirtualcards.types.Transaction
import com.sudoplatform.sudovirtualcards.types.TransactionChargeDetail
import com.sudoplatform.sudovirtualcards.types.inputs.filters.TransactionFilter

/**
 * Transformer responsible for transforming the [Transaction] GraphQL data
 * types to the entity type that is exposed to users.
 *
 * @since 2020-07-16
 */
internal object TransactionTransformer {

    /**
     * Transform the results of the [GetTransactionQuery].
     *
     * @param result The GraphQL query results.
     * @return The [Transaction] entity type.
     */
    fun toEntityFromGetTransactionQueryResult(
        deviceKeyManager: DeviceKeyManager,
        result: GetTransactionQuery.GetTransaction
    ): Transaction {
        return result.toTransaction(Unsealer(deviceKeyManager, result.keyId(), result.algorithm()))
    }

    private fun GetTransactionQuery.GetTransaction.toTransaction(unsealer: Unsealer): Transaction {
        return Transaction(
            id = id(),
            owner = owner(),
            version = version(),
            cardId = cardId(),
            sequenceId = sequenceId(),
            type = type().toType(),
            billedAmount = unsealAmount(unsealer, billedAmount().currency(), billedAmount().amount()),
            transactedAmount = unsealAmount(unsealer, transactedAmount().currency(), transactedAmount().amount()),
            description = unsealer.unseal(description()),
            declineReason = declineReason()?.let { unsealer.unseal(it).toDeclineReason() },
            details = toEntityFromGetTransactionDetail(unsealer, detail()),
            transactedAt = unsealer.unseal(transactedAtEpochMs()).toDouble().toDate(),
            createdAt = createdAtEpochMs().toDate(),
            updatedAt = updatedAtEpochMs().toDate()
        )
    }

    /**
     * Transform the results of the [ListTransactionsQuery].
     *
     * @param results The GraphQL query results.
     * @return The list of [Transaction] entity types.
     */
    fun toEntityFromListTransactionsQueryResult(
        deviceKeyManager: DeviceKeyManager,
        results: List<ListTransactionsQuery.Item>
    ): List<Transaction> {
        return results.map { result ->
            result.toTransaction(Unsealer(deviceKeyManager, result.keyId(), result.algorithm()))
        }.toList()
    }

    private fun ListTransactionsQuery.Item.toTransaction(unsealer: Unsealer): Transaction {
        return Transaction(
            id = id(),
            owner = owner(),
            version = version(),
            cardId = cardId(),
            sequenceId = sequenceId(),
            type = type().toType(),
            billedAmount = unsealAmount(unsealer, billedAmount().currency(), billedAmount().amount()),
            transactedAmount = unsealAmount(unsealer, transactedAmount().currency(), transactedAmount().amount()),
            description = unsealer.unseal(description()),
            declineReason = declineReason()?. let { unsealer.unseal(it).toDeclineReason() },
            details = toEntityFromListTransactionsDetail(unsealer, detail()),
            transactedAt = unsealer.unseal(transactedAtEpochMs()).toDouble().toDate(),
            createdAt = createdAtEpochMs().toDate(),
            updatedAt = updatedAtEpochMs().toDate()
        )
    }

    private fun TransactionType.toType(): Transaction.Type {
        for (txnType in Transaction.Type.values()) {
            if (txnType.name == this.name) {
                return txnType
            }
        }
        return Transaction.Type.UNKNOWN
    }

    private fun toEntityFromGetTransactionDetail(
        unsealer: Unsealer,
        results: List<GetTransactionQuery.Detail>?
    ): List<TransactionChargeDetail> {
        return results?.map { it.toTransactionChargeDetail(unsealer) }
            ?.toList()
            ?: emptyList()
    }

    private fun GetTransactionQuery.Detail.toTransactionChargeDetail(unsealer: Unsealer): TransactionChargeDetail {
        return TransactionChargeDetail(
            virtualCardAmount = unsealAmount(
                unsealer,
                virtualCardAmount().currency(),
                virtualCardAmount().amount()
            ),
            markup = Markup(
                percent = unsealer.unseal(markup().percent()).toInt(),
                flat = unsealer.unseal(markup().flat()).toInt(),
                minCharge = markup().minCharge()?.let { unsealer.unseal(it).toInt() } ?: 0
            ),
            markupAmount = unsealAmount(
                unsealer,
                markupAmount().currency(),
                markupAmount().amount()
            ),
            fundingSourceAmount = unsealAmount(
                unsealer,
                fundingSourceAmount().currency(),
                fundingSourceAmount().amount()
            ),
            fundingSourceId = fundingSourceId(),
            description = unsealer.unseal(description())
        )
    }

    private fun ListTransactionsQuery.Detail.toTransactionChargeDetail(unsealer: Unsealer): TransactionChargeDetail {
        return TransactionChargeDetail(
            virtualCardAmount = unsealAmount(
                unsealer,
                virtualCardAmount().currency(),
                virtualCardAmount().amount()
            ),
            markup = Markup(
                percent = unsealer.unseal(markup().percent()).toInt(),
                flat = unsealer.unseal(markup().flat()).toInt(),
                minCharge = markup().minCharge()?.let { unsealer.unseal(it).toInt() } ?: 0
            ),
            markupAmount = unsealAmount(
                unsealer,
                markupAmount().currency(),
                markupAmount().amount()
            ),
            fundingSourceAmount = unsealAmount(
                unsealer,
                fundingSourceAmount().currency(),
                fundingSourceAmount().amount()
            ),
            fundingSourceId = fundingSourceId(),
            description = unsealer.unseal(description())
        )
    }

    private fun unsealAmount(unsealer: Unsealer, currency: String, sealedAmount: String): CurrencyAmount {
        return CurrencyAmount(
            currency = unsealer.unseal(currency),
            amount = unsealer.unseal(sealedAmount).toInt()
        )
    }

    private fun toEntityFromListTransactionsDetail(
        unsealer: Unsealer,
        results: List<ListTransactionsQuery.Detail>?
    ): List<TransactionChargeDetail> {
        return results?.map { it.toTransactionChargeDetail(unsealer) }
            ?.toList()
            ?: emptyList()
    }

    /**
     * Transform the data from the update transaction subscription
     *
     * @param result The GraphQL subscription data.
     * @return The [Transaction] entity type.
     */
    fun toEntityFromUpdateSubscription(
        deviceKeyManager: DeviceKeyManager,
        result: OnTransactionUpdateSubscription.OnTransactionUpdate
    ): Transaction {
        val unsealer = Unsealer(deviceKeyManager, result.keyId(), result.algorithm())
        return with(result) {
            Transaction(
                id = id(),
                owner = owner(),
                version = version(),
                cardId = cardId(),
                sequenceId = sequenceId(),
                type = type().toType(),
                billedAmount = unsealAmount(unsealer, billedAmount().currency(), billedAmount().amount()),
                transactedAmount = unsealAmount(unsealer, transactedAmount().currency(), transactedAmount().amount()),
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
    ): List<TransactionChargeDetail> {
        return results?.map { it.toTransactionChargeDetail(unsealer) }
            ?.toList()
            ?: emptyList()
    }

    private fun OnTransactionUpdateSubscription.Detail.toTransactionChargeDetail(unsealer: Unsealer): TransactionChargeDetail {
        return TransactionChargeDetail(
            virtualCardAmount = unsealAmount(
                unsealer,
                virtualCardAmount().currency(),
                virtualCardAmount().amount()
            ),
            markup = Markup(
                percent = unsealer.unseal(markup().percent()).toInt(),
                flat = unsealer.unseal(markup().flat()).toInt(),
                minCharge = markup().minCharge()?.let { unsealer.unseal(it).toInt() } ?: 0
            ),
            markupAmount = unsealAmount(
                unsealer,
                markupAmount().currency(),
                markupAmount().amount()
            ),
            fundingSourceAmount = unsealAmount(
                unsealer,
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
     * @param result The GraphQL subscription data.
     * @return The [Transaction] entity type.
     */
    fun toEntityFromDeleteSubscription(
        deviceKeyManager: DeviceKeyManager,
        result: OnTransactionDeleteSubscription.OnTransactionDelete
    ): Transaction {
        val unsealer = Unsealer(deviceKeyManager, result.keyId(), result.algorithm())
        return with(result) {
            Transaction(
                id = id(),
                owner = owner(),
                version = version(),
                cardId = cardId(),
                sequenceId = sequenceId(),
                type = type().toType(),
                billedAmount = unsealAmount(unsealer, billedAmount().currency(), billedAmount().amount()),
                transactedAmount = unsealAmount(unsealer, transactedAmount().currency(), transactedAmount().amount()),
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
    ): List<TransactionChargeDetail> {
        return results?.map { it.toTransactionChargeDetail(unsealer) }
            ?.toList()
            ?: emptyList()
    }

    private fun OnTransactionDeleteSubscription.Detail.toTransactionChargeDetail(unsealer: Unsealer): TransactionChargeDetail {
        return TransactionChargeDetail(
            virtualCardAmount = unsealAmount(
                unsealer,
                virtualCardAmount().currency(),
                virtualCardAmount().amount()
            ),
            markup = Markup(
                percent = unsealer.unseal(markup().percent()).toInt(),
                flat = unsealer.unseal(markup().flat()).toInt(),
                minCharge = markup().minCharge()?.let { unsealer.unseal(it).toInt() } ?: 0
            ),
            markupAmount = unsealAmount(
                unsealer,
                markupAmount().currency(),
                markupAmount().amount()
            ),
            fundingSourceAmount = unsealAmount(
                unsealer,
                fundingSourceAmount().currency(),
                fundingSourceAmount().amount()
            ),
            fundingSourceId = fundingSourceId(),
            description = unsealer.unseal(description())
        )
    }

    /**
     * Convert from the API definition of the transaction filter to the GraphQL definition.
     *
     * @param filter The API definition of the transaction filter, can be null.
     * @return The GraphQL definition of a transaction filter, can be null.
     */
    fun toGraphQLFilter(filter: TransactionFilter?): TransactionFilterInput? {
        if (filter == null || filter.propertyFilters.isEmpty()) {
            return null
        }
        val builder = TransactionFilterInput.builder()
        for (field in filter.propertyFilters) {
            when (field.property) {
                TransactionFilter.Property.CARD_ID -> builder.cardId(field.toFilterInput())
                TransactionFilter.Property.SEQUENCE_ID -> { } // Implemented in this SDK not on the server
            }
        }
        return builder.build()
    }

    private fun TransactionFilter.PropertyFilter.toFilterInput(): IDFilterInput {
        val builder = IDFilterInput.builder()
        when (comparison) {
            TransactionFilter.ComparisonOperator.EQUAL -> builder.eq(value.first)
            TransactionFilter.ComparisonOperator.NOT_EQUAL -> builder.ne(value.first)
            TransactionFilter.ComparisonOperator.LESS_THAN_EQUAL -> builder.le(value.first)
            TransactionFilter.ComparisonOperator.LESS_THAN -> builder.lt(value.first)
            TransactionFilter.ComparisonOperator.GREATER_THAN_EQUAL -> builder.ge(value.first)
            TransactionFilter.ComparisonOperator.GREATER_THAN -> builder.gt(value.first)
            TransactionFilter.ComparisonOperator.CONTAINS -> builder.contains(value.first)
            TransactionFilter.ComparisonOperator.NOT_CONTAINS -> builder.notContains(value.first)
            TransactionFilter.ComparisonOperator.BEGINS_WITH -> builder.beginsWith(value.first)
            TransactionFilter.ComparisonOperator.BETWEEN -> builder.between(listOf(value.first, value.second))
        }
        return builder.build()
    }

    /**
     * Perform local filtering of the transactions by sequenceId which isn't supported by the backend.
     */
    fun filter(transactions: List<ListTransactionsQuery.Item>, filters: TransactionFilter?): List<ListTransactionsQuery.Item> {

        // If no filter was provided or the filter was provided but does not contain sequenceId which is the
        // only property we have to filter locally then just return the original results
        val sequenceIdFilter = filters?.propertyFilters?.firstOrNull {
            it.property == TransactionFilter.Property.SEQUENCE_ID
        }
            ?: return transactions

        return transactions.filter { txn ->
            sequenceIdFilter.matches(txn)
        }
    }
}

/**
 * @return true if the [TransactionFilter.PropertyFilter] matches the transaction
 */
private fun TransactionFilter.PropertyFilter.matches(txn: ListTransactionsQuery.Item): Boolean {
    return when (comparison) {
        TransactionFilter.ComparisonOperator.EQUAL -> { txn.sequenceId() == value.first }
        TransactionFilter.ComparisonOperator.NOT_EQUAL -> { txn.sequenceId() != value.first }
        TransactionFilter.ComparisonOperator.LESS_THAN_EQUAL -> { txn.sequenceId() <= value.first }
        TransactionFilter.ComparisonOperator.LESS_THAN -> { txn.sequenceId() < value.first }
        TransactionFilter.ComparisonOperator.GREATER_THAN_EQUAL -> { txn.sequenceId() >= value.first }
        TransactionFilter.ComparisonOperator.GREATER_THAN -> { txn.sequenceId() > value.first }
        TransactionFilter.ComparisonOperator.CONTAINS -> { txn.sequenceId().contains(value.first) }
        TransactionFilter.ComparisonOperator.NOT_CONTAINS -> { !txn.sequenceId().contains(value.first) }
        TransactionFilter.ComparisonOperator.BEGINS_WITH -> { txn.sequenceId().startsWith(value.first) }
        TransactionFilter.ComparisonOperator.BETWEEN -> { txn.sequenceId() >= value.first && txn.sequenceId() <= value.second }
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
