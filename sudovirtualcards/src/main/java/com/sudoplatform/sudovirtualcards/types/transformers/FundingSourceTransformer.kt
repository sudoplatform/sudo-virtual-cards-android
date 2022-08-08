/*
 * Copyright © 2022 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards.types.transformers

import com.amazonaws.util.Base64
import com.google.gson.Gson
import com.sudoplatform.sudovirtualcards.SudoVirtualCardsClient
import com.sudoplatform.sudovirtualcards.graphql.ListFundingSourcesQuery
import com.sudoplatform.sudovirtualcards.graphql.fragment.FundingSource as FundingSourceFragment
import com.sudoplatform.sudovirtualcards.graphql.fragment.ProvisionalFundingSource as ProvisionalFundingSourceFragment
import com.sudoplatform.sudovirtualcards.graphql.type.CreditCardNetwork
import com.sudoplatform.sudovirtualcards.graphql.type.FundingSourceState
import com.sudoplatform.sudovirtualcards.graphql.type.ProvisionalFundingSourceState
import com.sudoplatform.sudovirtualcards.types.FundingSource
import com.sudoplatform.sudovirtualcards.types.ProvisionalFundingSource
import com.sudoplatform.sudovirtualcards.types.ProvisioningData

/**
 * Transformer responsible for transforming the [FundingSource] GraphQL data types to the
 * entity type that is exposed to users.
 */
internal object FundingSourceTransformer {

    /**
     * Transform the results of the complete funding source mutation.
     *
     * @param fundingSource [FundingSourceFragment] The GraphQL mutation results.
     * @return The [FundingSource] entity type.
     */
    fun toEntity(fundingSource: FundingSourceFragment): FundingSource {
        return FundingSource(
            id = fundingSource.id(),
            owner = fundingSource.owner(),
            version = fundingSource.version(),
            createdAt = fundingSource.createdAtEpochMs().toDate(),
            updatedAt = fundingSource.updatedAtEpochMs().toDate(),
            state = fundingSource.state().toEntityState(),
            currency = fundingSource.currency(),
            last4 = fundingSource.last4(),
            network = fundingSource.network().toEntityNetwork()
        )
    }

    /**
     * Transform the results of the setup funding source mutation.
     *
     * @param provisionalFundingSource [ProvisionalFundingSourceFragment] The GraphQL mutation results.
     * @return The [ProvisionalFundingSource] entity type.
     */
    fun toEntity(
        provisionalFundingSource: ProvisionalFundingSourceFragment,
    ): ProvisionalFundingSource {
        val provisioningDataBytes = Base64.decode(provisionalFundingSource.provisioningData())
        val provisioningData = Gson().fromJson(String(provisioningDataBytes, Charsets.UTF_8), ProvisioningData::class.java)
        return ProvisionalFundingSource(
            id = provisionalFundingSource.id(),
            owner = provisionalFundingSource.owner(),
            version = provisionalFundingSource.version(),
            createdAt = provisionalFundingSource.createdAtEpochMs().toDate(),
            updatedAt = provisionalFundingSource.updatedAtEpochMs().toDate(),
            state = provisionalFundingSource.state().toEntityProvisioningState(),
            provisioningData = provisioningData
        )
    }

    /**
     * Transform the results of the list funding sources query.
     *
     * @param result [List<ListFundingSourcesQuery.Item>] The GraphQL query results.
     * @return The list of [FundingSource]s entity type.
     */
    fun toEntity(result: List<ListFundingSourcesQuery.Item>): List<FundingSource> {
        return result.map {
            val fundingSource = it.fragments().fundingSource()
                ?: throw SudoVirtualCardsClient.FundingSourceException.FailedException("unexpected null funding source")
            toEntity(fundingSource)
        }.toList()
    }
}

private fun ProvisionalFundingSourceState.toEntityProvisioningState(): ProvisionalFundingSource.ProvisioningState {
    for (value in ProvisionalFundingSource.ProvisioningState.values()) {
        if (value.name == this.name) {
            return value
        }
    }
    return ProvisionalFundingSource.ProvisioningState.UNKNOWN
}

private fun FundingSourceState.toEntityState(): FundingSource.State {
    for (value in FundingSource.State.values()) {
        if (value.name == this.name) {
            return value
        }
    }
    return FundingSource.State.UNKNOWN
}

private fun CreditCardNetwork.toEntityNetwork(): FundingSource.CreditCardNetwork {
    for (value in FundingSource.CreditCardNetwork.values()) {
        if (value.name == this.name) {
            return value
        }
    }
    return FundingSource.CreditCardNetwork.UNKNOWN
}
