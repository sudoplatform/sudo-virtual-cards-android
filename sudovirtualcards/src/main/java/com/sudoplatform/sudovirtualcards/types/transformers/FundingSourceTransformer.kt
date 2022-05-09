/*
 * Copyright © 2022 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards.types.transformers

import com.amazonaws.util.Base64
import com.google.gson.Gson
import com.sudoplatform.sudovirtualcards.graphql.CancelFundingSourceMutation
import com.sudoplatform.sudovirtualcards.graphql.CompleteFundingSourceMutation
import com.sudoplatform.sudovirtualcards.graphql.GetFundingSourceQuery
import com.sudoplatform.sudovirtualcards.graphql.ListFundingSourcesQuery
import com.sudoplatform.sudovirtualcards.graphql.SetupFundingSourceMutation
import com.sudoplatform.sudovirtualcards.graphql.type.CreditCardNetwork
import com.sudoplatform.sudovirtualcards.graphql.type.FundingSourceState
import com.sudoplatform.sudovirtualcards.graphql.type.ProvisionalFundingSourceState
import com.sudoplatform.sudovirtualcards.graphql.type.StateReason
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
     * @param result [CompleteFundingSourceMutation.CompleteFundingSource] The GraphQL mutation results.
     * @return The [FundingSource] entity type.
     */
    fun toEntityFromCreateFundingSourceMutationResult(result: CompleteFundingSourceMutation.CompleteFundingSource): FundingSource {
        return FundingSource(
            id = result.id(),
            owner = result.owner(),
            version = result.version(),
            createdAt = result.createdAtEpochMs().toDate(),
            updatedAt = result.updatedAtEpochMs().toDate(),
            state = result.state().toEntityState(),
            currency = result.currency(),
            last4 = result.last4(),
            network = result.network().toEntityNetwork()
        )
    }

    /**
     * Transform the results of the setup funding source mutation.
     *
     * @param result [SetupFundingSourceMutation.SetupFundingSource] The GraphQL mutation results.
     * @return The [ProvisionalFundingSource] entity type.
     */
    fun toEntityFromSetupFundingSourceMutationResult(
        result: SetupFundingSourceMutation.SetupFundingSource,
    ): ProvisionalFundingSource {
        val provisioningDataBytes = Base64.decode(result.provisioningData())
        val provisioningData = Gson().fromJson(String(provisioningDataBytes, Charsets.UTF_8), ProvisioningData::class.java)
        return ProvisionalFundingSource(
            id = result.id(),
            owner = result.owner(),
            version = result.version(),
            createdAt = result.createdAtEpochMs().toDate(),
            updatedAt = result.updatedAtEpochMs().toDate(),
            state = result.state().toEntityProvisioningState(),
            stateReason = result.stateReason().toEntityStateReason(),
            provisioningData = provisioningData
        )
    }

    /**
     * Transform the results of the cancel funding source mutation.
     *
     * @param result [CancelFundingSourceMutation.CancelFundingSource] The GraphQL mutation results.
     * @return The [FundingSource] entity type.
     */
    fun toEntityFromCancelFundingSourceMutationResult(result: CancelFundingSourceMutation.CancelFundingSource): FundingSource {
        return FundingSource(
            id = result.id(),
            owner = result.owner(),
            version = result.version(),
            createdAt = result.createdAtEpochMs().toDate(),
            updatedAt = result.updatedAtEpochMs().toDate(),
            state = result.state().toEntityState(),
            currency = result.currency(),
            last4 = result.last4(),
            network = result.network().toEntityNetwork()
        )
    }

    /**
     * Transform the results of the get funding source query.
     *
     * @param result [GetFundingSourceQuery.GetFundingSource] The GraphQL query results.
     * @return The [FundingSource] entity type.
     */
    fun toEntityFromGetFundingSourceQueryResult(result: GetFundingSourceQuery.GetFundingSource): FundingSource {
        return FundingSource(
            id = result.id(),
            owner = result.owner(),
            version = result.version(),
            createdAt = result.createdAtEpochMs().toDate(),
            updatedAt = result.updatedAtEpochMs().toDate(),
            state = result.state().toEntityState(),
            currency = result.currency(),
            last4 = result.last4(),
            network = result.network().toEntityNetwork()
        )
    }

    /**
     * Transform the results of the list funding sources query.
     *
     * @param result [List<ListFundingSourcesQuery.Item>] The GraphQL query results.
     * @return The list of [FundingSource]s entity type.
     */
    fun toEntityFromListFundingSourcesQueryResult(result: List<ListFundingSourcesQuery.Item>): List<FundingSource> {
        return result.map { fundingSource ->
            FundingSource(
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

private fun StateReason.toEntityStateReason(): ProvisionalFundingSource.StateReason {
    for (value in ProvisionalFundingSource.StateReason.values()) {
        if (value.name == this.name) {
            return value
        }
    }
    return ProvisionalFundingSource.StateReason.UNKNOWN
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
