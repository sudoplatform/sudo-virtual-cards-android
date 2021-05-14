/*
 * Copyright © 2020 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards.types

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * The details of how a [Transaction] is charged against a [Card] and [FundingSource] as well
 * as the amount of markup that was applied to the [Transaction].
 *
 * @property virtualCardAmount The amount charged to the [Card]
 * @property markup The rate(s) of markup applied to the transaction
 * @property markupAmount The amount of markup charged to the card for the [Transaction]
 * @property fundingSourceAmount The amount of the charge against the [FundingSource]
 * @property fundingSourceId The identifier of the [FundingSource]
 * @property description The description of this [TransactionChargeDetail]
 *
 * @since 2020-07-16
 */
@Parcelize
data class TransactionChargeDetail(
    val virtualCardAmount: CurrencyAmount,
    val markup: Markup,
    val markupAmount: CurrencyAmount,
    val fundingSourceAmount: CurrencyAmount,
    val fundingSourceId: String,
    val description: String
) : Parcelable
