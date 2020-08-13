/*
 * Copyright © 2020 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards.types.transformers

import com.sudoplatform.sudovirtualcards.graphql.CancelFundingSourceMutation
import com.sudoplatform.sudovirtualcards.graphql.CompleteFundingSourceMutation
import com.sudoplatform.sudovirtualcards.graphql.GetFundingSourceQuery
import com.sudoplatform.sudovirtualcards.graphql.ListFundingSourcesQuery
import com.sudoplatform.sudovirtualcards.graphql.type.CreditCardNetwork
import com.sudoplatform.sudovirtualcards.graphql.type.FundingSourceState
import com.sudoplatform.sudovirtualcards.types.FundingSource

/**
 * Transformer responsible for transforming the [FundingSource] GraphQL data types to the
 * entity type that is exposed to users.
 *
 * @since 2020-05-26
 */
internal object FundingSourceTransformer {

    /**
     * Transform the results of the complete funding source mutation.
     *
     * @param result The GraphQL mutation results.
     * @return The [FundingSource] entity type.
     */
    fun toEntityFromCreateFundingSourceMutationResult(result: CompleteFundingSourceMutation.CompleteFundingSource): FundingSource {
        return FundingSource(
            id = result.id(),
            owner = result.owner(),
            version = result.version(),
            state = result.state().toEntityState(),
            currency = result.currency(),
            last4 = result.last4(),
            network = result.network().toEntityNetwork()
        )
    }

    /**
     * Transform the results of the cancel funding source mutation.
     *
     * @param result The GraphQL mutation results.
     * @return The [FundingSource] entity type.
     */
    fun toEntityFromCancelFundingSourceMutationResult(result: CancelFundingSourceMutation.CancelFundingSource): FundingSource {
        return FundingSource(
            id = result.id(),
            owner = result.owner(),
            version = result.version(),
            state = result.state().toEntityState(),
            currency = result.currency(),
            last4 = result.last4(),
            network = result.network().toEntityNetwork()
        )
    }

    /**
     * Transform the results of the get funding source query.
     *
     * @param result The GraphQL query results.
     * @return The [FundingSource] entity type.
     */
    fun toEntityFromGetFundingSourceQueryResult(result: GetFundingSourceQuery.GetFundingSource): FundingSource {
        return FundingSource(
            id = result.id(),
            owner = result.owner(),
            version = result.version(),
            state = result.state().toEntityState(),
            currency = result.currency(),
            last4 = result.last4(),
            network = result.network().toEntityNetwork()
        )
    }

    /**
     * Transform the results of the list funding sources query.
     *
     * @param result The GraphQL query results.
     * @return The list of [FundingSource]s entity type.
     */
    fun toEntityFromListFundingSourcesQueryResult(result: List<ListFundingSourcesQuery.Item>): List<FundingSource> {
        return result.map { fundingSource ->
            FundingSource(
                id = fundingSource.id(),
                owner = fundingSource.owner(),
                version = fundingSource.version(),
                state = fundingSource.state().toEntityState(),
                currency = fundingSource.currency(),
                last4 = fundingSource.last4(),
                network = fundingSource.network().toEntityNetwork()
            )
        }.toList()
    }
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
