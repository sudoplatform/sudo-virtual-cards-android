/*
 * Copyright © 2022 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards.types

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Representation of a Virtual Card's configuration used in the Sudo Platform Virtual Cards SDK.
 *
 * @property maxFundingSourceVelocity [List<String>] The maximum number of funding sources that can be
 * successfully created within a defined period.
 * @property maxFundingSourceFailureVelocity [List<String] The maximum number of failed funding source
 * creations that can be performed within a defined period.
 * @property maxCardCreationVelocity [List<String>] The maximum number of virtual cards that can be
 * created within a defined period.
 * @property maxTransactionVelocity [List<CurrencyVelocity>] The maximum number of transactions that
 * can be created within a defined period.
 * @property maxTransactionAmount [List<CurrencyAmount>] The maximum transaction amount per currency.
 * @property virtualCardCurrencies [List<String>] The list of supported virtual card currencies.
 * @property fundingSourceSupportInfo [List<FundingSourceSupportInfo>] Funding source support info.
 */
@Parcelize
data class VirtualCardsConfig(
    val maxFundingSourceVelocity: List<String>,
    val maxFundingSourceFailureVelocity: List<String>,
    val maxCardCreationVelocity: List<String>,
    val maxTransactionVelocity: List<CurrencyVelocity>,
    val maxTransactionAmount: List<CurrencyAmount>,
    val virtualCardCurrencies: List<String>,
    val fundingSourceSupportInfo: List<FundingSourceSupportInfo>,
) : Parcelable

@Parcelize
data class CurrencyVelocity(
    val currency: String,
    val velocity: List<String>
) : Parcelable

@Parcelize
data class FundingSourceSupportInfo(
    val providerType: String,
    val fundingSourceType: String,
    val network: String,
    val detail: List<FundingSourceSupportDetail>
) : Parcelable

@Parcelize
data class FundingSourceSupportDetail(
    val cardType: CardType
) : Parcelable
