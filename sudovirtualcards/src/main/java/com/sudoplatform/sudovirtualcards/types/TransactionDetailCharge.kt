/*
 * Copyright © 2024 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards.types

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import java.util.Date

/**
 * The details of how a [Transaction] is charged against a [VirtualCard] and [FundingSource] as well
 * as the amount of markup that was applied to the [Transaction].
 *
 * @property virtualCardAmount [CurrencyAmount] The amount charged to the [VirtualCard].
 * @property markup [Markup] The rate(s) of markup applied to the transaction.
 * @property markupAmount [CurrencyAmount] The amount of markup charged to the virtual card for the [Transaction].
 * @property fundingSourceAmount [CurrencyAmount] The amount of the charge against the [FundingSource].
 * @property transactedAt [Date] Date when the interaction occurred at the funding source provider.
 * @property settledAt [Date] Date when the interaction with the funding source provider was completed.
 * @property fundingSourceId [String] The identifier of the [FundingSource].
 * @property description [String] The description of this [TransactionDetailCharge].
 * @property state [ChargeDetailState] The current state of the transaction charge detail.
 */
@Parcelize
data class TransactionDetailCharge(
    val virtualCardAmount: CurrencyAmount,
    val markup: Markup,
    val markupAmount: CurrencyAmount,
    val fundingSourceAmount: CurrencyAmount,
    val transactedAt: Date? = null,
    val settledAt: Date? = null,
    val fundingSourceId: String,
    val description: String,
    val state: ChargeDetailState,
) : Parcelable
