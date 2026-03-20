/*
 * Copyright © 2024 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards.types.transformers

import androidx.annotation.VisibleForTesting
import com.amazonaws.util.Base64
import com.google.gson.Gson
import com.sudoplatform.sudovirtualcards.SudoVirtualCardsClient
import com.sudoplatform.sudovirtualcards.graphql.GetVirtualCardsConfigQuery
import com.sudoplatform.sudovirtualcards.graphql.fragment.FundingSourceSupportDetail
import com.sudoplatform.sudovirtualcards.graphql.fragment.FundingSourceSupportInfo
import com.sudoplatform.sudovirtualcards.graphql.fragment.VirtualCardsConfig
import com.sudoplatform.sudovirtualcards.graphql.type.CardType
import com.sudoplatform.sudovirtualcards.types.CurrencyAmount
import com.sudoplatform.sudovirtualcards.types.CurrencyVelocity
import com.sudoplatform.sudovirtualcards.types.FundingSourceClientConfiguration
import com.sudoplatform.sudovirtualcards.types.FundingSourceTypes
import com.sudoplatform.sudovirtualcards.types.PricingPolicy
import com.sudoplatform.sudovirtualcards.types.CardType as CardTypeEntity
import com.sudoplatform.sudovirtualcards.types.FundingSourceSupportDetail as FundingSourceSupportDetailEntity
import com.sudoplatform.sudovirtualcards.types.FundingSourceSupportInfo as FundingSourceSupportInfoEntity
import com.sudoplatform.sudovirtualcards.types.VirtualCardsConfig as VirtualCardsConfigEntity

/**
 * Transformer responsible for transforming the [VirtualCardsConfig] GraphQL data
 * type to the entity type that is exposed to users.
 */
internal object VirtualCardsConfigTransformer {
    /**
     * Transform the results of the [GetVirtualCardsConfigQuery].
     *
     * @param result [VirtualCardsConfig] The GraphQL query results.
     * @return The [VirtualCardsConfig] entity type.
     */
    fun toEntityFromGetVirtualCardsConfigQueryResult(result: VirtualCardsConfig): VirtualCardsConfigEntity =
        VirtualCardsConfigEntity(
            maxFundingSourceVelocity = result.maxFundingSourceVelocity,
            maxFundingSourceFailureVelocity = result.maxFundingSourceFailureVelocity,
            maxFundingSourcePendingVelocity = result.maxFundingSourcePendingVelocity,
            maxCardCreationVelocity = result.maxCardCreationVelocity,
            maxTransactionVelocity = this.toEntityFromMaxTransactionVelocity(result.maxTransactionVelocity),
            maxTransactionAmount = this.toEntityFromMaxTransactionAmount(result.maxTransactionAmount),
            virtualCardCurrencies = result.virtualCardCurrencies,
            fundingSourceSupportInfo =
                result.fundingSourceSupportInfo.map {
                    toEntityFromFundingSourceSupportInfo(
                        it.fundingSourceSupportInfo,
                    )
                },
            fundingSourceClientConfiguration =
                result.fundingSourceClientConfiguration?.data.let {
                    if (it != null) {
                        decodeFundingSourceClientConfiguration(it)
                    } else {
                        emptyList()
                    }
                },
            pricingPolicy =
                result.pricingPolicy?.data.let {
                    if (it != null) {
                        decodePricingPolicy(it)
                    } else {
                        null
                    }
                },
        )

    private fun toEntityFromMaxTransactionVelocity(
        maxTransactionVelocity: List<VirtualCardsConfig.MaxTransactionVelocity>,
    ): List<CurrencyVelocity> =
        maxTransactionVelocity.map {
            CurrencyVelocity(it.currency, it.velocity)
        }

    private fun toEntityFromMaxTransactionAmount(
        maxTransactionAmount: List<VirtualCardsConfig.MaxTransactionAmount>,
    ): List<CurrencyAmount> =
        maxTransactionAmount.map {
            CurrencyAmount(it.currency, it.amount)
        }

    private fun toEntityFromFundingSourceSupportInfo(fundingSourceSupportInfo: FundingSourceSupportInfo): FundingSourceSupportInfoEntity =
        FundingSourceSupportInfoEntity(
            fundingSourceSupportInfo.providerType,
            fundingSourceSupportInfo.fundingSourceType,
            fundingSourceSupportInfo.network,
            fundingSourceSupportInfo.detail.map {
                it.fundingSourceSupportDetail.toFundingSourceDetailEntity()
            },
        )

    private fun FundingSourceSupportDetail.toFundingSourceDetailEntity(): FundingSourceSupportDetailEntity =
        FundingSourceSupportDetailEntity(this.cardType.toCardTypeEntity())

    private fun CardType.toCardTypeEntity(): CardTypeEntity =
        when (this) {
            CardType.CREDIT -> CardTypeEntity.CREDIT
            CardType.DEBIT -> CardTypeEntity.DEBIT
            CardType.PREPAID -> CardTypeEntity.PREPAID
            CardType.OTHER -> CardTypeEntity.OTHER
            CardType.UNKNOWN__ -> throw SudoVirtualCardsClient.VirtualCardException.FailedException(
                "Unrecognized CardType",
            )
        }
}

/**
 * Decodes the pricing policy data.
 *
 * @param policyData [String] The pricing policy as a JSON string.
 * @return The decoded pricing policy object.
 */
@VisibleForTesting
internal fun decodePricingPolicy(policyData: String): PricingPolicy {
    val msg = "pricing policy data cannot be decoded"

    val decodedString: String
    try {
        decodedString = String(Base64.decode(policyData), Charsets.UTF_8)
    } catch (e: Exception) {
        throw SudoVirtualCardsClient.VirtualCardException.FailedException(
            "$msg: Base64 decoding failed: $policyData: $e",
        )
    }

    val pricingPolicy: PricingPolicy
    try {
        pricingPolicy = Gson().fromJson(decodedString, PricingPolicy::class.java)
    } catch (e: Exception) {
        throw SudoVirtualCardsClient.VirtualCardException.FailedException(
            "$msg: JSON parsing failed: $decodedString: $e",
        )
    }
    return pricingPolicy
}

/**
 * Decodes the funding source client configuration data.
 *
 * @param configData [String] The funding source client configuration as a JSON string.
 * @return A list of decoded funding source configuration objects.
 */
@VisibleForTesting
internal fun decodeFundingSourceClientConfiguration(configData: String): List<FundingSourceClientConfiguration> {
    val msg = "funding source client configuration cannot be decoded"

    val decodedString: String
    try {
        decodedString = String(Base64.decode(configData), Charsets.UTF_8)
    } catch (e: Exception) {
        throw SudoVirtualCardsClient.VirtualCardException.FailedException(
            "$msg: Base64 decoding failed: $configData: $e",
        )
    }

    val fundingSourceClientConfiguration: List<FundingSourceClientConfiguration>
    try {
        fundingSourceClientConfiguration = Gson().fromJson(decodedString, FundingSourceTypes::class.java).fundingSourceTypes
    } catch (e: Exception) {
        throw SudoVirtualCardsClient.VirtualCardException.FailedException(
            "$msg: JSON parsing failed: $decodedString: $e",
        )
    }
    return fundingSourceClientConfiguration
}
