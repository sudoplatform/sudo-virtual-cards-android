/*
 * Copyright © 2022 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards.types.transformers

import com.sudoplatform.sudovirtualcards.graphql.GetVirtualCardsConfigQuery
import com.sudoplatform.sudovirtualcards.graphql.fragment.FundingSourceSupportInfo
import com.sudoplatform.sudovirtualcards.graphql.fragment.FundingSourceSupportDetail
import com.sudoplatform.sudovirtualcards.graphql.fragment.VirtualCardsConfig
import com.sudoplatform.sudovirtualcards.graphql.type.CardType
import com.sudoplatform.sudovirtualcards.types.CardType as CardTypeEntity
import com.sudoplatform.sudovirtualcards.types.CurrencyVelocity
import com.sudoplatform.sudovirtualcards.types.CurrencyAmount
import com.sudoplatform.sudovirtualcards.types.FundingSourceSupportInfo as FundingSourceSupportInfoEntity
import com.sudoplatform.sudovirtualcards.types.FundingSourceSupportDetail as FundingSourceSupportDetailEntity
import com.sudoplatform.sudovirtualcards.types.VirtualCardsConfig as VirtualCardsConfigEntity

/**
 * Transformer responsible for transforming the [VirtualCardsConfig] GraphQL data
 * type to the entity type that is exposed to users.
 */
internal object VirtualCardsConfigTransformer {

    private fun toEntityFromMaxTransactionVelocity(
        maxTransactionVelocity: List<VirtualCardsConfig.MaxTransactionVelocity>
    ): List<CurrencyVelocity> {
        return maxTransactionVelocity.map {
            CurrencyVelocity(it.currency(), it.velocity())
        }
    }

    private fun toEntityFromMaxTransactionAmount(
        maxTransactionAmount: List<VirtualCardsConfig.MaxTransactionAmount>
    ): List<CurrencyAmount> {
        return maxTransactionAmount.map {
            CurrencyAmount(it.currency(), it.amount())
        }
    }

    private fun toEntityFromFundingSourceSupportInfo(
        fundingSourceSupportInfo: FundingSourceSupportInfo
    ): FundingSourceSupportInfoEntity {
        return FundingSourceSupportInfoEntity(
            fundingSourceSupportInfo.providerType(),
            fundingSourceSupportInfo.fundingSourceType(),
            fundingSourceSupportInfo.network(),
            fundingSourceSupportInfo.detail().map {
                it.fragments().fundingSourceSupportDetail().toFundingSourceDetailEntity()
            }
        )
    }

    private fun FundingSourceSupportDetail.toFundingSourceDetailEntity(): FundingSourceSupportDetailEntity {
        return FundingSourceSupportDetailEntity(this.cardType().toCardTypeEntity())
    }

    private fun CardType.toCardTypeEntity(): CardTypeEntity {
        return when (this) {
            CardType.CREDIT -> CardTypeEntity.CREDIT
            CardType.DEBIT -> CardTypeEntity.DEBIT
            CardType.PREPAID -> CardTypeEntity.PREPAID
            CardType.OTHER -> CardTypeEntity.OTHER
        }
    }

    /**
     * Transform the results of the [GetVirtualCardsConfigQuery].
     *
     * @param result [VirtualCardsConfig] The GraphQL query results.
     * @return The [VirtualCardsConfig] entity type.
     */
    fun toEntityFromGetVirtualCardsConfigQueryResult(
        result: VirtualCardsConfig
    ): VirtualCardsConfigEntity {
        return VirtualCardsConfigEntity(
            maxFundingSourceVelocity = result.maxFundingSourceVelocity(),
            maxFundingSourceFailureVelocity = result.maxFundingSourceFailureVelocity(),
            maxCardCreationVelocity = result.maxCardCreationVelocity(),
            maxTransactionVelocity = this.toEntityFromMaxTransactionVelocity(result.maxTransactionVelocity()),
            maxTransactionAmount = this.toEntityFromMaxTransactionAmount(result.maxTransactionAmount()),
            virtualCardCurrencies = result.virtualCardCurrencies(),
            fundingSourceSupportInfo = result.fundingSourceSupportInfo().map {
                toEntityFromFundingSourceSupportInfo(
                    it.fragments().fundingSourceSupportInfo()
                )
            },
        )
    }
}
