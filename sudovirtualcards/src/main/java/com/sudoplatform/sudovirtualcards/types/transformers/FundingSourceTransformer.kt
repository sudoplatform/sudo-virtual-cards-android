/*
 * Copyright © 2024 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards.types.transformers

import com.apollographql.apollo3.api.Optional
import com.sudoplatform.sudovirtualcards.SudoVirtualCardsClient
import com.sudoplatform.sudovirtualcards.graphql.CancelFundingSourceMutation
import com.sudoplatform.sudovirtualcards.graphql.CancelProvisionalFundingSourceMutation
import com.sudoplatform.sudovirtualcards.graphql.CompleteFundingSourceMutation
import com.sudoplatform.sudovirtualcards.graphql.GetFundingSourceQuery
import com.sudoplatform.sudovirtualcards.graphql.ListFundingSourcesQuery
import com.sudoplatform.sudovirtualcards.graphql.ListProvisionalFundingSourcesQuery
import com.sudoplatform.sudovirtualcards.graphql.OnFundingSourceUpdateSubscription
import com.sudoplatform.sudovirtualcards.graphql.RefreshFundingSourceMutation
import com.sudoplatform.sudovirtualcards.graphql.ReviewUnfundedFundingSourceMutation
import com.sudoplatform.sudovirtualcards.graphql.SandboxSetFundingSourceToRequireRefreshMutation
import com.sudoplatform.sudovirtualcards.graphql.type.CreditCardNetwork
import com.sudoplatform.sudovirtualcards.graphql.type.ProvisionalFundingSourceState
import com.sudoplatform.sudovirtualcards.keys.DeviceKeyManager
import com.sudoplatform.sudovirtualcards.types.BankAccountFundingSource
import com.sudoplatform.sudovirtualcards.types.CardType
import com.sudoplatform.sudovirtualcards.types.CreditCardFundingSource
import com.sudoplatform.sudovirtualcards.types.CurrencyAmount
import com.sudoplatform.sudovirtualcards.types.FundingSource
import com.sudoplatform.sudovirtualcards.types.FundingSourceFlags
import com.sudoplatform.sudovirtualcards.types.FundingSourceState
import com.sudoplatform.sudovirtualcards.types.FundingSourceType
import com.sudoplatform.sudovirtualcards.types.ProvisionalFundingSource
import com.sudoplatform.sudovirtualcards.types.TransactionVelocity
import com.sudoplatform.sudovirtualcards.types.inputs.FundingSourceFilterInput
import com.sudoplatform.sudovirtualcards.types.inputs.FundingSourceStateFilterInput
import com.sudoplatform.sudovirtualcards.types.inputs.ProvisionalFundingSourceFilterInput
import com.sudoplatform.sudovirtualcards.types.inputs.ProvisionalFundingSourceStateFilterInput
import toIDFilterInput
import com.sudoplatform.sudovirtualcards.graphql.fragment.BankAccountFundingSource as BankAccountFundingSourceFragment
import com.sudoplatform.sudovirtualcards.graphql.fragment.CreditCardFundingSource as CreditCardFundingSourceFragment
import com.sudoplatform.sudovirtualcards.graphql.fragment.ProvisionalFundingSource as ProvisionalFundingSourceFragment
import com.sudoplatform.sudovirtualcards.graphql.type.BankAccountType as GraphqlBankAccountType
import com.sudoplatform.sudovirtualcards.graphql.type.CardType as GraphqlCardType
import com.sudoplatform.sudovirtualcards.graphql.type.FundingSourceFilterInput as GraphQlFundingSourceFilterInput
import com.sudoplatform.sudovirtualcards.graphql.type.FundingSourceFlags as GraphqlFundingSourceFlags
import com.sudoplatform.sudovirtualcards.graphql.type.FundingSourceState as GraphqlFundingSourceState
import com.sudoplatform.sudovirtualcards.graphql.type.FundingSourceStateFilterInput as GraphQlFundingSourceStateFilterInput
import com.sudoplatform.sudovirtualcards.graphql.type.FundingSourceType as GraphqlFundingSourceType
import com.sudoplatform.sudovirtualcards.graphql.type.ProvisionalFundingSourceFilterInput as GraphQlProvisionalFundingSourceFilterInput
import com.sudoplatform.sudovirtualcards.graphql.type.ProvisionalFundingSourceStateFilterInput as GraphQlProvisionalFundingSourceStateFilterInput

const val FUNDING_SOURCE_NULL_ERROR_MSG = "Unexpected null funding source"
const val UNSUPPORTED_FUNDING_SOURCE_TYPE_ERROR_MSG = "Unsupported funding source type"

/**
 * Transformer responsible for transforming the [FundingSource] GraphQL data types to the
 * entity type that is exposed to users.
 */
internal object FundingSourceTransformer {

    const val GraphQlCreditCardFundingSourceName = "CreditCardFundingSource"
    const val GraphQlBankAccountFundingSourceName = "BankAccountFundingSource"

    /**
     * Transform the results of the complete funding source mutation.
     *
     * @param deviceKeyManager [DeviceKeyManager] Used to retrieve keys to unseal data.
     * @param result [CompleteFundingSourceMutation.CompleteFundingSource] The GraphQL mutation results.
     * @return The [FundingSource] entity type.
     */
    fun toEntityFromCompleteFundingSourceMutationResult(
        deviceKeyManager: DeviceKeyManager,
        result: CompleteFundingSourceMutation.CompleteFundingSource,
    ): FundingSource {
        return when (result.__typename) {
            GraphQlCreditCardFundingSourceName -> {
                val fundingSource = result.onCreditCardFundingSource?.creditCardFundingSource
                    ?: throw SudoVirtualCardsClient.FundingSourceException.FailedException(FUNDING_SOURCE_NULL_ERROR_MSG)
                this.toEntity(fundingSource)
            }
            GraphQlBankAccountFundingSourceName -> {
                val fundingSource = result.onBankAccountFundingSource?.bankAccountFundingSource
                    ?: throw SudoVirtualCardsClient.FundingSourceException.FailedException(FUNDING_SOURCE_NULL_ERROR_MSG)
                this.toEntity(deviceKeyManager, fundingSource)
            }
            else -> {
                throw SudoVirtualCardsClient.FundingSourceException.FailedException(UNSUPPORTED_FUNDING_SOURCE_TYPE_ERROR_MSG)
            }
        }
    }

    /**
     * Transform the results of the refresh funding source mutation.
     *
     * @param deviceKeyManager [DeviceKeyManager] Used to retrieve keys to unseal data.
     * @param result [RefreshFundingSourceMutation.RefreshFundingSource] The GraphQL mutation results.
     * @return The [FundingSource] entity type.
     */
    fun toEntityFromRefreshFundingSourceMutationResult(
        deviceKeyManager: DeviceKeyManager,
        result: RefreshFundingSourceMutation.RefreshFundingSource,
    ): FundingSource {
        return when (result.__typename) {
            "CreditCardFundingSource" -> {
                val fundingSource = result.onCreditCardFundingSource?.creditCardFundingSource
                    ?: throw SudoVirtualCardsClient.FundingSourceException.FailedException(FUNDING_SOURCE_NULL_ERROR_MSG)
                this.toEntity(fundingSource)
            }
            "BankAccountFundingSource" -> {
                val fundingSource = result.onBankAccountFundingSource?.bankAccountFundingSource
                    ?: throw SudoVirtualCardsClient.FundingSourceException.FailedException(FUNDING_SOURCE_NULL_ERROR_MSG)
                this.toEntity(deviceKeyManager, fundingSource)
            }
            else -> {
                throw SudoVirtualCardsClient.FundingSourceException.FailedException(UNSUPPORTED_FUNDING_SOURCE_TYPE_ERROR_MSG)
            }
        }
    }

    /**
     * Transform the results of the cancel funding source mutation.
     *
     * @param deviceKeyManager [DeviceKeyManager] Used to retrieve keys to unseal data.
     * @param result [CancelFundingSourceMutation.CancelFundingSource] The GraphQL mutation results.
     * @return The [FundingSource] entity type.
     */
    fun toEntityFromCancelFundingSourceMutationResult(
        deviceKeyManager: DeviceKeyManager,
        result: CancelFundingSourceMutation.CancelFundingSource,
    ): FundingSource {
        return when (result.__typename) {
            "CreditCardFundingSource" -> {
                val fundingSource = result.onCreditCardFundingSource?.creditCardFundingSource
                    ?: throw SudoVirtualCardsClient.FundingSourceException.FailedException(FUNDING_SOURCE_NULL_ERROR_MSG)
                this.toEntity(fundingSource)
            }
            "BankAccountFundingSource" -> {
                val fundingSource = result.onBankAccountFundingSource?.bankAccountFundingSource
                    ?: throw SudoVirtualCardsClient.FundingSourceException.FailedException(FUNDING_SOURCE_NULL_ERROR_MSG)
                this.toEntity(deviceKeyManager, fundingSource)
            }
            else -> {
                throw SudoVirtualCardsClient.FundingSourceException.FailedException(UNSUPPORTED_FUNDING_SOURCE_TYPE_ERROR_MSG)
            }
        }
    }

    /**
     * Transform the results of the cancel provisional funding source mutation.
     *
     * @param result [CancelProvisionalFundingSourceMutation.CancelProvisionalFundingSource] The GraphQL mutation results.
     * @return The [ProvisionalFundingSource] entity type.
     */
    fun toEntityFromCancelProvisionalFundingSourceMutationResult(
        result: CancelProvisionalFundingSourceMutation.CancelProvisionalFundingSource,
    ): ProvisionalFundingSource {
        val provisionalFundingSource = result.provisionalFundingSource
        val provisioningData = ProviderDataTransformer.toProvisioningData(provisionalFundingSource.provisioningData)
        return ProvisionalFundingSource(
            id = provisionalFundingSource.id,
            owner = provisionalFundingSource.owner,
            version = provisionalFundingSource.version,
            createdAt = provisionalFundingSource.createdAtEpochMs.toDate(),
            updatedAt = provisionalFundingSource.updatedAtEpochMs.toDate(),
            type = provisionalFundingSource.type.toEntityFundingSourceType(),
            state = provisionalFundingSource.state.toEntityProvisioningState(),
            last4 = provisionalFundingSource.last4 ?: "",
            provisioningData = provisioningData,
        )
    }

    /**
     * Transform the results of the review unfunded funding source mutation.
     *
     * @param deviceKeyManager [DeviceKeyManager] Used to retrieve keys to unseal data.
     * @param result [ReviewUnfundedFundingSourceMutation.ReviewUnfundedFundingSource] The GraphQL mutation results.
     * @return The [FundingSource] entity type.
     */
    fun toEntityFromReviewUnfundedFundingSourceMutationResult(
        deviceKeyManager: DeviceKeyManager,
        result: ReviewUnfundedFundingSourceMutation.ReviewUnfundedFundingSource,
    ): FundingSource {
        return when (result.__typename) {
            "CreditCardFundingSource" -> {
                val fundingSource = result.onCreditCardFundingSource?.creditCardFundingSource
                    ?: throw SudoVirtualCardsClient.FundingSourceException.FailedException(FUNDING_SOURCE_NULL_ERROR_MSG)
                this.toEntity(fundingSource)
            }
            "BankAccountFundingSource" -> {
                val fundingSource = result.onBankAccountFundingSource?.bankAccountFundingSource
                    ?: throw SudoVirtualCardsClient.FundingSourceException.FailedException(FUNDING_SOURCE_NULL_ERROR_MSG)
                this.toEntity(deviceKeyManager, fundingSource)
            }
            else -> {
                throw SudoVirtualCardsClient.FundingSourceException.FailedException(UNSUPPORTED_FUNDING_SOURCE_TYPE_ERROR_MSG)
            }
        }
    }

    /**
     * Transform the results of the get funding source query.
     *
     * @param deviceKeyManager [DeviceKeyManager] Used to retrieve keys to unseal data.
     * @param result [GetFundingSourceQuery.GetFundingSource] The GraphQL mutation results.
     * @return The [FundingSource] entity type.
     */
    fun toEntityFromGetFundingSourceQueryResult(
        deviceKeyManager: DeviceKeyManager,
        result: GetFundingSourceQuery.GetFundingSource,
    ): FundingSource {
        return when (result.__typename) {
            "CreditCardFundingSource" -> {
                val fundingSource = result.onCreditCardFundingSource?.creditCardFundingSource
                    ?: throw SudoVirtualCardsClient.FundingSourceException.FailedException(FUNDING_SOURCE_NULL_ERROR_MSG)
                this.toEntity(fundingSource)
            }
            "BankAccountFundingSource" -> {
                val fundingSource = result.onBankAccountFundingSource?.bankAccountFundingSource
                    ?: throw SudoVirtualCardsClient.FundingSourceException.FailedException(FUNDING_SOURCE_NULL_ERROR_MSG)
                this.toEntity(deviceKeyManager, fundingSource)
            }
            else -> {
                throw SudoVirtualCardsClient.FundingSourceException.FailedException(UNSUPPORTED_FUNDING_SOURCE_TYPE_ERROR_MSG)
            }
        }
    }

    fun toEntityFromSandboxSetFundingSourceToRequireRefreshResult(
        deviceKeyManager: DeviceKeyManager,
        result: SandboxSetFundingSourceToRequireRefreshMutation.SandboxSetFundingSourceToRequireRefresh,
    ): FundingSource {
        return when (result.__typename) {
            "CreditCardFundingSource" -> {
                val fundingSource = result.onCreditCardFundingSource?.creditCardFundingSource
                    ?: throw SudoVirtualCardsClient.FundingSourceException.FailedException(FUNDING_SOURCE_NULL_ERROR_MSG)
                this.toEntity(fundingSource)
            }
            "BankAccountFundingSource" -> {
                val fundingSource = result.onBankAccountFundingSource?.bankAccountFundingSource
                    ?: throw SudoVirtualCardsClient.FundingSourceException.FailedException(FUNDING_SOURCE_NULL_ERROR_MSG)
                this.toEntity(deviceKeyManager, fundingSource)
            }
            else -> {
                throw SudoVirtualCardsClient.FundingSourceException.FailedException(UNSUPPORTED_FUNDING_SOURCE_TYPE_ERROR_MSG)
            }
        }
    }

    /**
     * Transform the results of the funding source update notification
     *
     * @param deviceKeyManager [DeviceKeyManager] Used to retrieve keys to unseal data.
     * @param result [OnFundingSourceUpdateSubscription.OnFundingSourceUpdate] The GraphQL mutation results.
     * @return The [FundingSource] entity type.
     */
    fun toEntityFromFundingSourceUpdateSubscriptionResult(
        deviceKeyManager: DeviceKeyManager,
        result: OnFundingSourceUpdateSubscription.OnFundingSourceUpdate,
    ): FundingSource {
        return when (result.__typename) {
            "CreditCardFundingSource" -> {
                val fundingSource = result.onCreditCardFundingSource?.creditCardFundingSource
                    ?: throw SudoVirtualCardsClient.FundingSourceException.FailedException(FUNDING_SOURCE_NULL_ERROR_MSG)
                this.toEntity(fundingSource)
            }
            "BankAccountFundingSource" -> {
                val fundingSource = result.onBankAccountFundingSource?.bankAccountFundingSource
                    ?: throw SudoVirtualCardsClient.FundingSourceException.FailedException(FUNDING_SOURCE_NULL_ERROR_MSG)
                this.toEntity(deviceKeyManager, fundingSource)
            }
            else -> {
                throw SudoVirtualCardsClient.FundingSourceException.FailedException(UNSUPPORTED_FUNDING_SOURCE_TYPE_ERROR_MSG)
            }
        }
    }

    /**
     * Transform the [ProvisionalFundingSourceFragment] GraphQL type to its entity type.
     *
     * @param provisionalFundingSource [ProvisionalFundingSourceFragment] The GraphQL type.
     * @return The [ProvisionalFundingSource] entity type.
     */
    fun toEntity(
        provisionalFundingSource: ProvisionalFundingSourceFragment,
    ): ProvisionalFundingSource {
        val provisioningData = ProviderDataTransformer.toProvisioningData(provisionalFundingSource.provisioningData)
        return ProvisionalFundingSource(
            id = provisionalFundingSource.id,
            owner = provisionalFundingSource.owner,
            version = provisionalFundingSource.version,
            createdAt = provisionalFundingSource.createdAtEpochMs.toDate(),
            updatedAt = provisionalFundingSource.updatedAtEpochMs.toDate(),
            type = provisionalFundingSource.type.toEntityFundingSourceType(),
            state = provisionalFundingSource.state.toEntityProvisioningState(),
            last4 = provisionalFundingSource.last4 ?: "",
            provisioningData = provisioningData,
        )
    }

    /**
     * Transform the results of the list provisional funding sources query.
     *
     * @param result [List<ListProvisionalFundingSourcesQuery.Item>] The GraphQL type.
     * @return The list of [ProvisionalFundingSource]s entity type.
     */
    fun toEntity(result: List<ListProvisionalFundingSourcesQuery.Item>): List<ProvisionalFundingSource> {
        return result.map {
            val provisionalFundingSource = it.provisionalFundingSource
            val provisioningData = ProviderDataTransformer.toProvisioningData(provisionalFundingSource.provisioningData)
            ProvisionalFundingSource(
                id = provisionalFundingSource.id,
                owner = provisionalFundingSource.owner,
                version = provisionalFundingSource.version,
                createdAt = provisionalFundingSource.createdAtEpochMs.toDate(),
                updatedAt = provisionalFundingSource.updatedAtEpochMs.toDate(),
                type = provisionalFundingSource.type.toEntityFundingSourceType(),
                state = provisionalFundingSource.state.toEntityProvisioningState(),
                last4 = provisionalFundingSource.last4 ?: "",
                provisioningData = provisioningData,
            )
        }.toList()
    }

    /**
     * Transform the results of the list funding sources query.
     *
     * @param result [List<ListFundingSourcesQuery.Item>] The GraphQL query results.
     * @return The list of [FundingSource]s entity type.
     */
    fun toEntity(deviceKeyManager: DeviceKeyManager, result: List<ListFundingSourcesQuery.Item>): List<FundingSource> {
        return result.map {
            when (it.__typename) {
                "CreditCardFundingSource" -> {
                    val fundingSource = it.onCreditCardFundingSource?.creditCardFundingSource
                        ?: throw SudoVirtualCardsClient.FundingSourceException.FailedException(FUNDING_SOURCE_NULL_ERROR_MSG)
                    toEntity(fundingSource)
                }
                "BankAccountFundingSource" -> {
                    val fundingSource = it.onBankAccountFundingSource?.bankAccountFundingSource
                        ?: throw SudoVirtualCardsClient.FundingSourceException.FailedException(FUNDING_SOURCE_NULL_ERROR_MSG)
                    toEntity(deviceKeyManager, fundingSource)
                }
                else -> {
                    throw SudoVirtualCardsClient.FundingSourceException.FailedException(UNSUPPORTED_FUNDING_SOURCE_TYPE_ERROR_MSG)
                }
            }
        }.toList()
    }

    /**
     * Transform the input type [ProvisionalFundingSourceFilterInput] into the corresponding GraphQL
     * type [GraphQlProvisionalFundingSourceFilterInput].
     */
    fun ProvisionalFundingSourceFilterInput?.toProvisionalFundingSourceFilterInput(): GraphQlProvisionalFundingSourceFilterInput? {
        if (this == null) {
            return null
        }
        return GraphQlProvisionalFundingSourceFilterInput(
            and = Optional.presentIfNotNull(and?.mapNotNull { it.toProvisionalFundingSourceFilterInput() }),
            id = Optional.presentIfNotNull(id?.toIDFilterInput()),
            not = Optional.presentIfNotNull(not?.toProvisionalFundingSourceFilterInput()),
            or = Optional.presentIfNotNull(or?.mapNotNull { it.toProvisionalFundingSourceFilterInput() }),
            state = Optional.presentIfNotNull(state?.toProvisionalFundingSourceStateFilterInput()),

        )
    }

    /**
     * Transform the input type [FundingSourceFilterInput] into the corresponding GraphQL
     * type [GraphQlFundingSourceFilterInput].
     */
    fun FundingSourceFilterInput?.toFundingSourceFilterInput(): GraphQlFundingSourceFilterInput? {
        if (this == null) {
            return null
        }
        return GraphQlFundingSourceFilterInput(
            and = Optional.presentIfNotNull(and?.mapNotNull { it.toFundingSourceFilterInput() }),
            id = Optional.presentIfNotNull(id?.toIDFilterInput()),
            not = Optional.presentIfNotNull(not?.toFundingSourceFilterInput()),
            or = Optional.presentIfNotNull(or?.mapNotNull { it.toFundingSourceFilterInput() }),
            state = Optional.presentIfNotNull(state?.toFundingSourceStateFilterInput()),
        )
    }

    /**
     * Transform the input type [ProvisionalFundingSourceStateFilterInput] into the corresponding GraphQL
     * type [GraphQlProvisionalFundingSourceStateFilterInput].
     */
    private fun ProvisionalFundingSourceStateFilterInput?.toProvisionalFundingSourceStateFilterInput():
        GraphQlProvisionalFundingSourceStateFilterInput? {
        if (this == null) {
            return null
        }
        return GraphQlProvisionalFundingSourceStateFilterInput(
            eq = Optional.presentIfNotNull(eq?.toGraphQlProvisioningState()),
            ne = Optional.presentIfNotNull(ne?.toGraphQlProvisioningState()),
        )
    }

    /**
     * Transform the input type [FundingSourceStateFilterInput] into the corresponding GraphQL
     * type [GraphQlFundingSourceStateFilterInput].
     */
    private fun FundingSourceStateFilterInput?.toFundingSourceStateFilterInput(): GraphQlFundingSourceStateFilterInput? {
        if (this == null) {
            return null
        }
        return GraphQlFundingSourceStateFilterInput(
            eq = Optional.presentIfNotNull(eq?.toGraphQlFundingSourceSate()),
            ne = Optional.presentIfNotNull(ne?.toGraphQlFundingSourceSate()),
        )
    }

    /**
     * Transform the [BankAccountFundingSourceFragment] GraphQL type to its entity type.
     *
     * @param deviceKeyManager [DeviceKeyManager] Used to retrieve keys to unseal data.
     * @param fundingSource [BankAccountFundingSourceFragment] The GraphQL type.
     * @return The [FundingSource] entity type.
     */
    private fun toEntity(
        deviceKeyManager: DeviceKeyManager,
        fundingSource: BankAccountFundingSourceFragment,
    ): FundingSource {
        val institutionName = fundingSource.institutionName.sealedAttribute
        val nameKeyInfo = KeyInfo(institutionName.keyId, KeyType.PRIVATE_KEY, institutionName.algorithm)
        val nameUnsealer = Unsealer(deviceKeyManager, nameKeyInfo)

        return BankAccountFundingSource(
            id = fundingSource.id,
            owner = fundingSource.owner,
            version = fundingSource.version,
            createdAt = fundingSource.createdAtEpochMs.toDate(),
            updatedAt = fundingSource.updatedAtEpochMs.toDate(),
            state = fundingSource.state.toEntityState(),
            flags = fundingSource.flags.map { toEntityFlags(it) },
            currency = fundingSource.currency,
            transactionVelocity = fundingSource.transactionVelocity?.toEntityTransactionVelocity(),
            bankAccountType = fundingSource.bankAccountType.toEntityBankAccountType(),
            last4 = fundingSource.last4,
            institutionName = nameUnsealer.unseal(fundingSource.institutionName),
            institutionLogo = fundingSource.institutionLogo?.sealedAttribute?.let {
                val logoKeyInfo = KeyInfo(it.keyId, KeyType.PRIVATE_KEY, it.algorithm)
                val logoUnsealer = Unsealer(deviceKeyManager, logoKeyInfo)
                logoUnsealer.unseal(fundingSource.institutionLogo)
            },
            unfundedAmount = fundingSource.unfundedAmount?.toCurrencyAmount(),
        )
    }

    /**
     * Transform the [CreditCardFundingSourceFragment] GraphQL type to its entity type.
     *
     * @param fundingSource [CreditCardFundingSourceFragment] The GraphQL type.
     * @return The [FundingSource] entity type.
     */
    private fun toEntity(fundingSource: CreditCardFundingSourceFragment): FundingSource {
        return CreditCardFundingSource(
            id = fundingSource.id,
            owner = fundingSource.owner,
            version = fundingSource.version,
            createdAt = fundingSource.createdAtEpochMs.toDate(),
            updatedAt = fundingSource.updatedAtEpochMs.toDate(),
            state = fundingSource.state.toEntityState(),
            flags = fundingSource.flags.map { toEntityFlags(it) },
            currency = fundingSource.currency,
            transactionVelocity = fundingSource.transactionVelocity?.toEntityTransactionVelocity(),
            last4 = fundingSource.last4,
            network = fundingSource.network.toEntityNetwork(),
            cardType = fundingSource.cardType.toEntityCardType(),
        )
    }

    private fun ProvisionalFundingSourceState.toEntityProvisioningState(): ProvisionalFundingSource.ProvisioningState {
        for (value in ProvisionalFundingSource.ProvisioningState.entries) {
            if (value.name == this.name) {
                return value
            }
        }
        return ProvisionalFundingSource.ProvisioningState.UNKNOWN
    }
    private fun ProvisionalFundingSource.ProvisioningState.toGraphQlProvisioningState(): ProvisionalFundingSourceState {
        for (value in ProvisionalFundingSourceState.entries) {
            if (value.name == this.name) {
                return value
            }
        }
        throw IllegalArgumentException("Unrecognized Provisioning State")
    }

    private fun GraphqlFundingSourceState.toEntityState(): FundingSourceState {
        for (value in FundingSourceState.entries) {
            if (value.name == this.name) {
                return value
            }
        }
        return FundingSourceState.UNKNOWN
    }
    private fun FundingSourceState.toGraphQlFundingSourceSate(): GraphqlFundingSourceState {
        for (value in GraphqlFundingSourceState.entries) {
            if (value.name == this.name) {
                return value
            }
        }
        throw IllegalArgumentException("Unrecognized Funding Source State")
    }

    private fun toEntityFlags(input: GraphqlFundingSourceFlags): FundingSourceFlags {
        for (value in FundingSourceFlags.entries) {
            if (value.name == input.name) {
                return value
            }
        }
        return FundingSourceFlags.UNKNOWN
    }

    private fun CreditCardNetwork.toEntityNetwork(): CreditCardFundingSource.CreditCardNetwork {
        for (value in CreditCardFundingSource.CreditCardNetwork.entries) {
            if (value.name == this.name) {
                return value
            }
        }
        return CreditCardFundingSource.CreditCardNetwork.UNKNOWN
    }

    private fun GraphqlCardType.toEntityCardType(): CardType {
        for (value in CardType.entries) {
            if (value.name == this.name) {
                return value
            }
        }
        return CardType.UNKNOWN
    }

    private fun GraphqlFundingSourceType.toEntityFundingSourceType(): FundingSourceType {
        for (value in FundingSourceType.entries) {
            if (value.name == this.name) {
                return value
            }
        }
        return FundingSourceType.CREDIT_CARD
    }

    private fun GraphqlBankAccountType.toEntityBankAccountType(): BankAccountFundingSource.BankAccountType {
        for (value in BankAccountFundingSource.BankAccountType.entries) {
            if (value.name == this.name) {
                return value
            }
        }
        return BankAccountFundingSource.BankAccountType.UNKNOWN
    }

    private fun CreditCardFundingSourceFragment.TransactionVelocity.toEntityTransactionVelocity(): TransactionVelocity? {
        if (this.maximum == null && this.velocity == null) {
            return null
        }
        return TransactionVelocity(this.maximum, this.velocity)
    }

    private fun BankAccountFundingSourceFragment.TransactionVelocity.toEntityTransactionVelocity(): TransactionVelocity? {
        if (this.maximum == null && this.velocity == null) {
            return null
        }
        return TransactionVelocity(this.maximum, this.velocity)
    }

    private fun BankAccountFundingSourceFragment.UnfundedAmount.toCurrencyAmount(): CurrencyAmount {
        return CurrencyAmount(this.currency, this.amount)
    }
}
