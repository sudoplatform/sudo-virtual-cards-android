/*
 * Copyright © 2023 Anonyome Labs, Inc. All rights reserved.
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
 * @property maxFundingSourcePendingVelocity [List<String] The maximum number of pending funding source
 * creations that can be performed within a defined period.
 * @property maxCardCreationVelocity [List<String>] The maximum number of virtual cards that can be
 * created within a defined period.
 * @property maxTransactionVelocity [List<CurrencyVelocity>] The maximum number of transactions that
 * can be created within a defined period.
 * @property maxTransactionAmount [List<CurrencyAmount>] The maximum transaction amount per currency.
 * @property virtualCardCurrencies [List<String>] The list of supported virtual card currencies.
 * @property fundingSourceSupportInfo [List<FundingSourceSupportInfo>] Funding source support info.
 * @property bankAccountFundingSourceCreationEnabled [Boolean] Flag determining whether bank account
 *  funding source creation flows are enabled. Mainly used to test edge cases around bank account funding.
 * @property fundingSourceClientConfiguration [List<FundingSourceClientConfiguration>] The funding source
 *  client configuration.
 * @property clientApplicationConfiguration [Map<String, ClientApplicationConfiguration>] The client application
 *  configuration keyed by application name.
 * @property pricingPolicy [PricingPolicy] The pricing policy for each funding source provider.
 */
@Parcelize
data class VirtualCardsConfig(
    val maxFundingSourceVelocity: List<String>,
    val maxFundingSourceFailureVelocity: List<String>,
    val maxFundingSourcePendingVelocity: List<String>,
    val maxCardCreationVelocity: List<String>,
    val maxTransactionVelocity: List<CurrencyVelocity>,
    val maxTransactionAmount: List<CurrencyAmount>,
    val virtualCardCurrencies: List<String>,
    val fundingSourceSupportInfo: List<FundingSourceSupportInfo>,
    val bankAccountFundingSourceExpendableEnabled: Boolean,
    val bankAccountFundingSourceCreationEnabled: Boolean?,
    val fundingSourceClientConfiguration: List<FundingSourceClientConfiguration>,
    val clientApplicationConfiguration: Map<String, ClientApplicationConfiguration>,
    val pricingPolicy: PricingPolicy?
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
