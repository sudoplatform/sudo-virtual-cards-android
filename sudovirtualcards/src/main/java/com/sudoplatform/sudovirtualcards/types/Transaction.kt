/*
 * Copyright © 2023 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards.types

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import java.util.Date

/**
 * Representation of a Virtual Card Transaction used in the Sudo Platform Virtual Cards SDK.
 *
 * @property id [String] Identifier of the transaction.
 * @property owner [String] Identifier of the user that owns the transaction.
 * @property version [Int] Current version of the transaction.
 * @property createdAt [Date] Date when the transaction was created.
 * @property updatedAt [Date] Date when the transaction was last updated.
 * @property transactedAt [Date] Date when the transaction occurred at the merchant.
 * @property settledAt [Date] Date when the transaction was completed.
 * @property cardId [String] Unique identifier of the [VirtualCard] associated with the transaction.
 * @property sequenceId [String] Identifier of the sequence of related transaction.
 * @property type [TransactionType] The type of the transaction.
 * @property billedAmount [CurrencyAmount] The amount billed for the transaction in the currency of the virtual card.
 * @property transactedAmount [CurrencyAmount] The amount of the transaction as charged by the merchant.
 * @property description [String] The description of the transaction.
 * @property declineReason [DeclineReason] Reason that the transaction was declined, null if the transaction was not declined.
 * @property details [List<TransactionDetailCharge>] The details of how the transaction was charged.
 */
@Parcelize
data class Transaction(
    val id: String,
    val owner: String,
    val version: Int,
    val createdAt: Date,
    val updatedAt: Date,
    val transactedAt: Date,
    val settledAt: Date? = null,
    val cardId: String,
    val sequenceId: String,
    val type: TransactionType,
    val billedAmount: CurrencyAmount,
    val transactedAmount: CurrencyAmount,
    val description: String,
    val declineReason: DeclineReason? = null,
    val details: List<TransactionDetailCharge> = emptyList(),
) : Parcelable

/**
 * Representation of a Virtual Card Transaction without its unsealed attributes used in
 * the Sudo Platform Virtual Cards SDK.
 *
 * @property id [String] Identifier of the transaction.
 * @property owner [String] Identifier of the user that owns the transaction.
 * @property version [Int] Current version of the transaction.
 * @property createdAt [Date] Date when the transaction was created.
 * @property updatedAt [Date] Date when the transaction was last updated.
 * @property cardId [String] Unique identifier of the [VirtualCard] associated with the transaction.
 * @property sequenceId [String] Identifier of the sequence of related transaction.
 * @property type [TransactionType] The type of the transaction.
 */
@Parcelize
data class PartialTransaction(
    val id: String,
    val owner: String,
    val version: Int,
    val createdAt: Date,
    val updatedAt: Date,
    val cardId: String,
    val sequenceId: String,
    val type: TransactionType,
) : Parcelable

/**
 * Representation of an enumeration depicting the type of [Transaction] in the Sudo Platform
 * Virtual Cards SDK.
 */
enum class TransactionType {
    /** Transaction is still being processed */
    PENDING,

    /** Transaction has been completely processed */
    COMPLETE,

    /** The transaction is a refund */
    REFUND,

    /** The transaction is the decline of a charge */
    DECLINE,

    /** API Evolution - if this occurs, it may mean you need to update the library. */
    UNKNOWN,
}
