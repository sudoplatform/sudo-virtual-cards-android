/*
 * Copyright © 2022 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards

import android.content.Context
import com.amazonaws.mobileconnectors.appsync.AWSAppSyncClient
import com.amazonaws.mobileconnectors.appsync.fetcher.AppSyncResponseFetchers
import com.amazonaws.util.Base64
import com.apollographql.apollo.api.Error
import com.apollographql.apollo.exception.ApolloException
import com.google.gson.Gson
import com.sudoplatform.sudologging.AndroidUtilsLogDriver
import com.sudoplatform.sudologging.LogLevel
import com.sudoplatform.sudologging.Logger
import com.sudoplatform.sudoprofiles.SudoProfilesClient
import com.sudoplatform.sudouser.SudoUserClient
import com.sudoplatform.sudovirtualcards.extensions.enqueue
import com.sudoplatform.sudovirtualcards.extensions.enqueueFirst
import com.sudoplatform.sudovirtualcards.graphql.CancelFundingSourceMutation
import com.sudoplatform.sudovirtualcards.graphql.CardProvisionMutation
import com.sudoplatform.sudovirtualcards.graphql.GetCardQuery
import com.sudoplatform.sudovirtualcards.graphql.GetFundingSourceQuery
import com.sudoplatform.sudovirtualcards.graphql.GetFundingSourceClientConfigurationQuery
import com.sudoplatform.sudovirtualcards.graphql.GetProvisionalCardQuery
import com.sudoplatform.sudovirtualcards.graphql.GetTransactionQuery
import com.sudoplatform.sudovirtualcards.graphql.ListCardsQuery
import com.sudoplatform.sudovirtualcards.graphql.ListFundingSourcesQuery
import com.sudoplatform.sudovirtualcards.graphql.ListTransactionsByCardIdQuery
import com.sudoplatform.sudovirtualcards.graphql.CancelCardMutation
import com.sudoplatform.sudovirtualcards.graphql.UpdateCardMutation
import com.sudoplatform.sudovirtualcards.graphql.CompleteFundingSourceMutation
import com.sudoplatform.sudovirtualcards.graphql.SetupFundingSourceMutation
import com.sudoplatform.sudovirtualcards.graphql.type.CardCancelRequest
import com.sudoplatform.sudovirtualcards.graphql.type.CardProvisionRequest
import com.sudoplatform.sudovirtualcards.graphql.type.CardUpdateRequest
import com.sudoplatform.sudovirtualcards.graphql.type.CompleteFundingSourceRequest
import com.sudoplatform.sudovirtualcards.graphql.type.IdInput
import com.sudoplatform.sudovirtualcards.graphql.type.SetupFundingSourceRequest
import com.sudoplatform.sudovirtualcards.keys.DeviceKeyManager
import com.sudoplatform.sudovirtualcards.keys.PublicKeyService
import com.sudoplatform.sudovirtualcards.logging.LogConstants
import com.sudoplatform.sudovirtualcards.subscription.TransactionSubscriptionService
import com.sudoplatform.sudovirtualcards.subscription.TransactionSubscriber
import com.sudoplatform.sudovirtualcards.types.CachePolicy
import com.sudoplatform.sudovirtualcards.types.CreateKeysIfAbsentResult
import com.sudoplatform.sudovirtualcards.types.DateRange
import com.sudoplatform.sudovirtualcards.types.FundingSource
import com.sudoplatform.sudovirtualcards.types.FundingSourceClientConfiguration
import com.sudoplatform.sudovirtualcards.types.FundingSourceTypes
import com.sudoplatform.sudovirtualcards.types.KeyResult
import com.sudoplatform.sudovirtualcards.types.ListAPIResult
import com.sudoplatform.sudovirtualcards.types.ListOutput
import com.sudoplatform.sudovirtualcards.types.PartialVirtualCard
import com.sudoplatform.sudovirtualcards.types.PartialResult
import com.sudoplatform.sudovirtualcards.types.PartialTransaction
import com.sudoplatform.sudovirtualcards.types.ProvisionalFundingSource
import com.sudoplatform.sudovirtualcards.types.ProvisionalVirtualCard
import com.sudoplatform.sudovirtualcards.types.SingleAPIResult
import com.sudoplatform.sudovirtualcards.types.SortOrder
import com.sudoplatform.sudovirtualcards.types.Transaction
import com.sudoplatform.sudovirtualcards.types.VirtualCard
import com.sudoplatform.sudovirtualcards.types.inputs.CompleteFundingSourceInput
import com.sudoplatform.sudovirtualcards.types.inputs.ProvisionVirtualCardInput
import com.sudoplatform.sudovirtualcards.types.inputs.SetupFundingSourceInput
import com.sudoplatform.sudovirtualcards.types.inputs.UpdateVirtualCardInput
import com.sudoplatform.sudovirtualcards.types.transformers.DateRangeTransformer.toDateRangeInput
import com.sudoplatform.sudovirtualcards.types.transformers.FundingSourceTransformer
import com.sudoplatform.sudovirtualcards.types.transformers.TransactionTransformer
import com.sudoplatform.sudovirtualcards.types.transformers.Unsealer
import com.sudoplatform.sudovirtualcards.types.transformers.VirtualCardTransformer
import com.sudoplatform.sudovirtualcards.types.transformers.VirtualCardTransformer.toAddressInput
import com.sudoplatform.sudovirtualcards.types.transformers.VirtualCardTransformer.toMetadataInput
import java.util.concurrent.CancellationException

/**
 * Default implementation of the [SudoVirtualCardsClient] interface.
 *
 * @property context [Context] Application context.
 * @property appSyncClient [AWSAppSyncClient] GraphQL client used to make requests to AWS and call sudo virtual cards service API.
 * @property sudoUserClient [SudoUserClient] Used to determine if a user is signed in and gain access to the user owner ID.
 * @property sudoProfilesClient [SudoProfilesClient] Used to perform ownership proof lifecycle operations.
 * @property logger [Logger] Errors and warnings will be logged here.
 * @property deviceKeyManager [DeviceKeyManager] Used for device management of key storage.
 * @property publicKeyService [PublicKeyService] Service that handles registering public keys with the backend.
 */
internal class DefaultSudoVirtualCardsClient(
    private val context: Context,
    private val appSyncClient: AWSAppSyncClient,
    private val sudoUserClient: SudoUserClient,
    private val sudoProfilesClient: SudoProfilesClient,
    private val logger: Logger = Logger(LogConstants.SUDOLOG_TAG, AndroidUtilsLogDriver(LogLevel.INFO)),
    private val deviceKeyManager: DeviceKeyManager,
    private val publicKeyService: PublicKeyService
) : SudoVirtualCardsClient {

    companion object {
        /** Exception messages */
        private const val INVALID_TOKEN_MSG = "An invalid token error has occurred"
        private const val UNSEAL_CARD_ERROR_MSG = "Unable to unseal card data"
        private const val KEY_RETRIEVAL_ERROR_MSG = "Failed to retrieve a public key pair"
        private const val NO_RESULT_ERROR_MSG = "No result returned"
        private const val IDENTITY_NOT_VERIFIED_MSG = "Identity has not been verified"
        private const val IDENTITY_INSUFFICIENT_MSG = "Identity is insufficient"
        private const val FUNDING_SOURCE_NOT_FOUND_MSG = "Funding source not found"
        private const val PROVISIONAL_FUNDING_SOURCE_NOT_FOUND_MSG = "Provisional funding source not found"
        private const val FUNDING_SOURCE_STATE_MSG = "Funding source state is inappropriate for the requested operation"
        private const val FUNDING_SOURCE_NOT_SETUP_MSG = "Failed to setup funding source creation"
        private const val FUNDING_SOURCE_NOT_COMPLETE_MSG = "Failed to complete funding source creation"
        private const val FUNDING_SOURCE_COMPLETION_DATA_INVALID_MSG = "Invalid completion data to perform funding source creation"
        private const val DUPLICATE_FUNDING_SOURCE_MSG = "Duplicate funding source"
        private const val UNACCEPTABLE_FUNDING_SOURCE_MSG = "Funding source is not acceptable to be created"
        private const val UNSUPPORTED_CURRENCY_MSG = "Currency is not supported"
        private const val CARD_NOT_FOUND_MSG = "Virtual card not found"
        private const val INVALID_CARD_STATE_MSG = "The virtual card is in an invalid state"
        private const val FUNDING_SOURCE_NOT_ACTIVE_MSG = "Funding source is not active"
        private const val VELOCITY_EXCEEDED_MSG = "Velocity has been exceeded"
        private const val ENTITLEMENT_EXCEEDED_MSG = "Entitlements have been exceeded"
        private const val ACCOUNT_LOCKED_MSG = "Account is locked"

        /** Errors returned from the service */
        private const val ERROR_TYPE = "errorType"
        private const val SERVICE_ERROR = "ServiceError"
        private const val ERROR_INVALID_TOKEN = "InvalidTokenError"
        private const val ERROR_IDENTITY_NOT_VERIFIED = "IdentityVerificationNotVerifiedError"
        private const val ERROR_IDENTITY_INSUFFICIENT = "IdentityVerificationInsufficientError"
        private const val ERROR_FUNDING_SOURCE_NOT_FOUND = "FundingSourceNotFoundError"
        private const val ERROR_FUNDING_SOURCE_NOT_SETUP = "FundingSourceNotSetupErrorCode"
        private const val ERROR_FUNDING_SOURCE_STATE = "FundingSourceStateError"
        private const val ERROR_FUNDING_SOURCE_COMPLETION_DATA_INVALID = "FundingSourceCompletionDataInvalidError"
        private const val ERROR_PROVISIONAL_FUNDING_SOURCE_NOT_FOUND = "ProvisionalFundingSourceNotFoundError"
        private const val ERROR_DUPLICATE_FUNDING_SOURCE = "DuplicateFundingSourceError"
        private const val ERROR_UNACCEPTABLE_FUNDING_SOURCE = "UnacceptableFundingSourceError"
        private const val ERROR_UNSUPPORTED_CURRENCY = "UnsupportedCurrencyError"
        private const val ERROR_CARD_NOT_FOUND = "CardNotFoundError"
        private const val ERROR_INVALID_CARD_STATE = "CardStateError"
        private const val ERROR_FUNDING_SOURCE_NOT_ACTIVE = "FundingSourceNotActiveError"
        private const val ERROR_VELOCITY_EXCEEDED = "VelocityExceededError"
        private const val ERROR_ENTITLEMENT_EXCEEDED = "EntitlementExceededError"
        private const val ERROR_ACCOUNT_LOCKED = "AccountLockedError"
    }

    /**
     * Checksum's for each file are generated and are used to create a checksum that is used when
     * publishing to maven central. In order to retry a failed publish without needing to change any
     * functionality, we need a way to generate a different checksum for the source code. We can
     * change the value of this property which will generate a different checksum for publishing
     * and allow us to retry. The value of `version` doesn't need to be kept up-to-date with the
     * version of the code.
     */
    private val version: String = "4.0.1"

    /** This manages the subscriptions to transaction updates and deletes */
    private val transactionSubscriptions = TransactionSubscriptionService(appSyncClient, deviceKeyManager, sudoUserClient, logger)

    @Throws(SudoVirtualCardsClient.VirtualCardException::class)
    override suspend fun createKeysIfAbsent(): CreateKeysIfAbsentResult {
        try {
            val symmetricKeyResult = createSymmetricKeysIfAbsent()
            val keyPairResult = createAndRegisterKeyPairIfAbsent()
            return CreateKeysIfAbsentResult(symmetricKey = symmetricKeyResult, keyPair = keyPairResult)
        } catch (e: Throwable) {
            logger.error("unexpected error $e")
            when (e) {
                is CancellationException,
                is PublicKeyService.PublicKeyServiceException ->
                    throw SudoVirtualCardsClient.VirtualCardException.PublicKeyException(KEY_RETRIEVAL_ERROR_MSG, e)
                is SudoVirtualCardsClient.VirtualCardException -> throw e
                else -> throw SudoVirtualCardsClient.VirtualCardException.UnknownException(e)
            }
        }
    }

    @Throws(SudoVirtualCardsClient.FundingSourceException::class)
    override suspend fun setupFundingSource(input: SetupFundingSourceInput): ProvisionalFundingSource {
        try {
            val mutationInput = SetupFundingSourceRequest.builder()
                .type(input.type.toFundingSourceTypeInput(input.type))
                .currency(input.currency)
                .build()
            val mutation = SetupFundingSourceMutation.builder()
                .input(mutationInput)
                .build()

            val mutationResponse = appSyncClient.mutate(mutation)
                .enqueue()

            if (mutationResponse.hasErrors()) {
                logger.error("errors = ${mutationResponse.errors()}")
                throw interpretFundingSourceError(mutationResponse.errors().first())
            }

            val result = mutationResponse.data()?.setupFundingSource()
            result?.let {
                return FundingSourceTransformer.toEntityFromSetupFundingSourceMutationResult(result)
            }
            throw SudoVirtualCardsClient.FundingSourceException.SetupFailedException(FUNDING_SOURCE_NOT_SETUP_MSG)
        } catch (e: Throwable) {
            logger.error("unexpected error $e")
            when (e) {
                is ApolloException -> throw SudoVirtualCardsClient.FundingSourceException.SetupFailedException(cause = e)
                else -> throw interpretFundingSourceException(e)
            }
        }
    }

    @Throws(SudoVirtualCardsClient.FundingSourceException::class)
    override suspend fun completeFundingSource(input: CompleteFundingSourceInput): FundingSource {
        try {
            val encodedCompletionDataString = Gson().toJson(input.completionData)
            val completionData = Base64.encode(encodedCompletionDataString.toByteArray()).toString(Charsets.UTF_8)
            val mutationInput = CompleteFundingSourceRequest.builder()
                .id(input.id)
                .completionData(completionData)
                .updateCardFundingSource(input.updateCardFundingSource)
                .build()

            val mutation = CompleteFundingSourceMutation.builder()
                .input(mutationInput)
                .build()

            val mutationResponse = appSyncClient.mutate(mutation)
                .enqueue()

            if (mutationResponse.hasErrors()) {
                logger.error("errors = ${mutationResponse.errors()}")
                throw interpretFundingSourceError(mutationResponse.errors().first())
            }

            val result = mutationResponse.data()?.completeFundingSource()
            result?.let {
                return FundingSourceTransformer.toEntityFromCreateFundingSourceMutationResult(result)
            }
            throw SudoVirtualCardsClient.FundingSourceException.CompletionFailedException(FUNDING_SOURCE_NOT_COMPLETE_MSG)
        } catch (e: Throwable) {
            logger.error("unexpected error $e")
            when (e) {
                is ApolloException -> throw SudoVirtualCardsClient.FundingSourceException.CompletionFailedException(cause = e)
                else -> throw interpretFundingSourceException(e)
            }
        }
    }

    @Throws(SudoVirtualCardsClient.FundingSourceException::class)
    override suspend fun getFundingSourceClientConfiguration(): List<FundingSourceClientConfiguration> {
        try {
            val query = GetFundingSourceClientConfigurationQuery
                .builder()
                .build()

            val queryResponse = appSyncClient.query(query)
                .responseFetcher(AppSyncResponseFetchers.CACHE_AND_NETWORK)
                .enqueueFirst()

            if (queryResponse.hasErrors()) {
                logger.error("errors = ${queryResponse.errors()}")
                throw interpretFundingSourceError(queryResponse.errors().first())
            }

            val encodedDataString = queryResponse.data()?.fundingSourceClientConfiguration?.data()
            if (encodedDataString != null) {
                val configurationBytes = Base64.decode(encodedDataString)
                return Gson().fromJson(String(configurationBytes, Charsets.UTF_8), FundingSourceTypes::class.java).fundingSourceTypes
            } else {
                throw SudoVirtualCardsClient.FundingSourceException.FailedException()
            }
        } catch (e: Throwable) {
            logger.error("unexpected error $e")
            when (e) {
                is ApolloException -> throw SudoVirtualCardsClient.FundingSourceException.FailedException(cause = e)
                else -> throw interpretFundingSourceException(e)
            }
        }
    }

    @Throws(SudoVirtualCardsClient.FundingSourceException::class)
    override suspend fun getFundingSource(id: String, cachePolicy: CachePolicy): FundingSource? {
        try {
            val query = GetFundingSourceQuery.builder()
                .id(id)
                .build()

            val queryResponse = appSyncClient.query(query)
                .responseFetcher(cachePolicy.toResponseFetcher(cachePolicy))
                .enqueueFirst()

            if (queryResponse.hasErrors()) {
                logger.error("errors = ${queryResponse.errors()}")
                throw interpretFundingSourceError(queryResponse.errors().first())
            }

            val result = queryResponse.data()?.fundingSource ?: return null
            return FundingSourceTransformer.toEntityFromGetFundingSourceQueryResult(result)
        } catch (e: Throwable) {
            logger.error("unexpected error $e")
            when (e) {
                is ApolloException -> throw SudoVirtualCardsClient.FundingSourceException.FailedException(cause = e)
                else -> throw interpretFundingSourceException(e)
            }
        }
    }

    @Throws(SudoVirtualCardsClient.FundingSourceException::class)
    override suspend fun listFundingSources(
        limit: Int,
        nextToken: String?,
        cachePolicy: CachePolicy
    ): ListOutput<FundingSource> {
        try {
            val query = ListFundingSourcesQuery.builder()
                .limit(limit)
                .nextToken(nextToken)
                .build()

            val queryResponse = appSyncClient.query(query)
                .responseFetcher(cachePolicy.toResponseFetcher(cachePolicy))
                .enqueueFirst()

            if (queryResponse.hasErrors()) {
                logger.error("errors = ${queryResponse.errors()}")
                throw interpretFundingSourceError(queryResponse.errors().first())
            }

            val result = queryResponse.data()?.listFundingSources() ?: return ListOutput(emptyList(), null)
            val fundingSources = FundingSourceTransformer.toEntityFromListFundingSourcesQueryResult(result.items())
            return ListOutput(fundingSources, result.nextToken())
        } catch (e: Throwable) {
            logger.error("unexpected error $e")
            when (e) {
                is ApolloException -> throw SudoVirtualCardsClient.FundingSourceException.FailedException(cause = e)
                else -> throw interpretFundingSourceException(e)
            }
        }
    }

    @Throws(SudoVirtualCardsClient.FundingSourceException::class)
    override suspend fun cancelFundingSource(id: String): FundingSource {
        try {
            val mutationInput = IdInput.builder()
                .id(id)
                .build()
            val mutation = CancelFundingSourceMutation.builder()
                .input(mutationInput)
                .build()

            val mutationResponse = appSyncClient.mutate(mutation)
                .enqueue()

            if (mutationResponse.hasErrors()) {
                logger.error("errors = ${mutationResponse.errors()}")
                throw interpretFundingSourceError(mutationResponse.errors().first())
            }

            val result = mutationResponse.data()?.cancelFundingSource()
            result?.let {
                return FundingSourceTransformer.toEntityFromCancelFundingSourceMutationResult(result)
            }
            throw SudoVirtualCardsClient.FundingSourceException.CancelFailedException(NO_RESULT_ERROR_MSG)
        } catch (e: Throwable) {
            logger.error("unexpected error $e")
            when (e) {
                is ApolloException -> throw SudoVirtualCardsClient.FundingSourceException.CancelFailedException(cause = e)
                else -> throw interpretFundingSourceException(e)
            }
        }
    }

    @Throws(SudoVirtualCardsClient.VirtualCardException::class)
    override suspend fun provisionVirtualCard(input: ProvisionVirtualCardInput): ProvisionalVirtualCard {
        try {
            // Ensure there is a current key in the key ring so the card can be sealed
            ensurePublicKeyIsRegistered()

            val mutationInput = CardProvisionRequest.builder()
                .clientRefId(input.clientRefId)
                .ownerProofs(input.ownershipProofs)
                .keyRingId(deviceKeyManager.getKeyRingId())
                .fundingSourceId(input.fundingSourceId)
                .cardHolder(input.cardHolder)
                .alias(input.alias)
                .metadata(input.metadata.toMetadataInput(deviceKeyManager))
                .billingAddress(input.billingAddress.toAddressInput())
                .currency(input.currency)
                .build()
            val mutation = CardProvisionMutation.builder()
                .input(mutationInput)
                .build()

            val mutationResponse = appSyncClient.mutate(mutation)
                .enqueue()

            if (mutationResponse.hasErrors()) {
                logger.error("errors = ${mutationResponse.errors()}")
                throw interpretVirtualCardError(mutationResponse.errors().first())
            }

            val result = mutationResponse.data()?.cardProvision()
            result?.let {
                return VirtualCardTransformer.toEntityFromCardProvisionMutationResult(deviceKeyManager, result)
            }
            throw SudoVirtualCardsClient.VirtualCardException.ProvisionFailedException(NO_RESULT_ERROR_MSG)
        } catch (e: Throwable) {
            logger.error("unexpected error $e")
            when (e) {
                is ApolloException -> throw SudoVirtualCardsClient.VirtualCardException.ProvisionFailedException(cause = e)
                else -> throw interpretVirtualCardException(e)
            }
        }
    }

    @Throws(SudoVirtualCardsClient.VirtualCardException::class)
    override suspend fun getProvisionalCard(id: String, cachePolicy: CachePolicy): ProvisionalVirtualCard? {
        try {
            val query = GetProvisionalCardQuery.builder()
                .id(id)
                .build()

            val queryResponse = appSyncClient.query(query)
                .responseFetcher(cachePolicy.toResponseFetcher(cachePolicy))
                .enqueueFirst()

            if (queryResponse.hasErrors()) {
                logger.error("errors = ${queryResponse.errors()}")
                throw interpretVirtualCardError(queryResponse.errors().first())
            }

            val result = queryResponse.data()?.provisionalCard ?: return null
            return VirtualCardTransformer.toEntityFromGetProvisionalCardQueryResult(deviceKeyManager, result)
        } catch (e: Throwable) {
            logger.error("unexpected error $e")
            when (e) {
                is ApolloException -> throw SudoVirtualCardsClient.VirtualCardException.FailedException(cause = e)
                else -> throw interpretVirtualCardException(e)
            }
        }
    }

    @Throws(SudoVirtualCardsClient.VirtualCardException::class)
    override suspend fun getVirtualCard(id: String, cachePolicy: CachePolicy): VirtualCard? {
        try {
            val keyPairResult = publicKeyService.getCurrentKeyPair(PublicKeyService.MissingKeyPolicy.GENERATE_IF_MISSING)
                ?: throw SudoVirtualCardsClient.VirtualCardException.PublicKeyException(KEY_RETRIEVAL_ERROR_MSG)
            val keyId = keyPairResult.keyId

            val query = GetCardQuery.builder()
                .id(id)
                .keyId(keyId)
                .build()

            val queryResponse = appSyncClient.query(query)
                .responseFetcher(cachePolicy.toResponseFetcher(cachePolicy))
                .enqueueFirst()

            if (queryResponse.hasErrors()) {
                logger.error("errors = ${queryResponse.errors()}")
                throw interpretVirtualCardError(queryResponse.errors().first())
            }

            val result = queryResponse.data()?.card ?: return null
            return VirtualCardTransformer.toEntityFromGetCardQueryResult(deviceKeyManager, result)
        } catch (e: Throwable) {
            logger.error("unexpected error $e")
            when (e) {
                is ApolloException -> throw SudoVirtualCardsClient.VirtualCardException.FailedException(cause = e)
                else -> throw interpretVirtualCardException(e)
            }
        }
    }

    @Throws(SudoVirtualCardsClient.VirtualCardException::class)
    override suspend fun listVirtualCards(
        limit: Int,
        nextToken: String?,
        cachePolicy: CachePolicy,
    ): ListAPIResult<VirtualCard, PartialVirtualCard> {
        try {
            val query = ListCardsQuery.builder()
                .limit(limit)
                .nextToken(nextToken)
                .build()

            val queryResponse = appSyncClient.query(query)
                .responseFetcher(cachePolicy.toResponseFetcher(cachePolicy))
                .enqueueFirst()

            if (queryResponse.hasErrors()) {
                logger.error("errors = ${queryResponse.errors()}")
                throw interpretVirtualCardError(queryResponse.errors().first())
            }

            val queryResult = queryResponse.data()?.listCards()
            val sealedCards = queryResult?.items() ?: emptyList()
            val newNextToken = queryResult?.nextToken()

            val success: MutableList<VirtualCard> = mutableListOf()
            val partials: MutableList<PartialResult<PartialVirtualCard>> = mutableListOf()
            for (sealedCard in sealedCards) {
                try {
                    val unsealedCard = VirtualCardTransformer.toEntityFromListCardsQueryResult(deviceKeyManager, sealedCard)
                    success.add(unsealedCard)
                } catch (e: Exception) {
                    val partialCard = VirtualCardTransformer.toPartialVirtualCardFromListCardsQueryResult(sealedCard)
                    val partialResult = PartialResult(partialCard, e)
                    partials.add(partialResult)
                }
            }
            if (partials.isNotEmpty()) {
                val listPartialResult = ListAPIResult.ListPartialResult(success, partials, newNextToken)
                return ListAPIResult.Partial(listPartialResult)
            }
            val listSuccessResult = ListAPIResult.ListSuccessResult(success, newNextToken)
            return ListAPIResult.Success(listSuccessResult)
        } catch (e: Throwable) {
            logger.error("unexpected error $e")
            when (e) {
                is ApolloException -> throw SudoVirtualCardsClient.VirtualCardException.FailedException(cause = e)
                else -> throw interpretVirtualCardException(e)
            }
        }
    }

    @Throws(SudoVirtualCardsClient.VirtualCardException::class)
    override suspend fun updateVirtualCard(input: UpdateVirtualCardInput): SingleAPIResult<VirtualCard, PartialVirtualCard> {
        try {
            val keyPairResult = publicKeyService.getCurrentKeyPair()
                ?: throw SudoVirtualCardsClient.VirtualCardException.PublicKeyException(KEY_RETRIEVAL_ERROR_MSG)
            val keyId = keyPairResult.keyId

            val mutationInput = CardUpdateRequest.builder()
                .id(input.id)
                .keyId(keyId)
                .expectedVersion(input.expectedCardVersion)
                .cardHolder(input.cardHolder)
                .alias(input.alias)
                .metadata(input.metadata.toMetadataInput(deviceKeyManager))
                .billingAddress(input.billingAddress.toAddressInput())
                .build()

            val mutation = UpdateCardMutation.builder()
                .input(mutationInput)
                .build()

            val mutationResponse = appSyncClient.mutate(mutation)
                .enqueue()

            if (mutationResponse.hasErrors()) {
                logger.error("errors = ${mutationResponse.errors()}")
                throw interpretVirtualCardError(mutationResponse.errors().first())
            }

            val updatedCard = mutationResponse.data()?.updateCard()
            updatedCard?.let {
                try {
                    val unsealedUpdatedCard = VirtualCardTransformer.toEntityFromUpdateCardMutationResult(deviceKeyManager, updatedCard)
                    return SingleAPIResult.Success(unsealedUpdatedCard)
                } catch (e: Exception) {
                    val partialUpdatedCard = VirtualCardTransformer.toPartialEntityFromUpdateCardMutationResult(updatedCard)
                    val partialResult = PartialResult(partialUpdatedCard, e)
                    return SingleAPIResult.Partial(partialResult)
                }
            }
            throw SudoVirtualCardsClient.VirtualCardException.FailedException(NO_RESULT_ERROR_MSG)
        } catch (e: Throwable) {
            logger.error("unexpected error $e")
            when (e) {
                is ApolloException -> throw SudoVirtualCardsClient.VirtualCardException.UpdateFailedException(cause = e)
                else -> throw interpretVirtualCardException(e)
            }
        }
    }

    override suspend fun cancelVirtualCard(id: String): SingleAPIResult<VirtualCard, PartialVirtualCard> {
        try {
            val keyPairResult = publicKeyService.getCurrentKeyPair()
                ?: throw SudoVirtualCardsClient.VirtualCardException.PublicKeyException(KEY_RETRIEVAL_ERROR_MSG)
            val keyId = keyPairResult.keyId

            val mutationInput = CardCancelRequest.builder()
                .id(id)
                .keyId(keyId)
                .build()
            val mutation = CancelCardMutation.builder()
                .input(mutationInput)
                .build()

            val mutationResponse = appSyncClient.mutate(mutation)
                .enqueue()

            if (mutationResponse.hasErrors()) {
                logger.error("errors = ${mutationResponse.errors()}")
                throw interpretVirtualCardError(mutationResponse.errors().first())
            }

            val cancelledCard = mutationResponse.data()?.cancelCard()
            cancelledCard?.let {
                try {
                    val unsealedCancelledCard = VirtualCardTransformer.toEntityFromCancelCardMutationResult(deviceKeyManager, cancelledCard)
                    return SingleAPIResult.Success(unsealedCancelledCard)
                } catch (e: Exception) {
                    val partialCancelledCard = VirtualCardTransformer.toPartialVirtualCardFromCancelCardMutationResult(cancelledCard)
                    val partialResult = PartialResult(partialCancelledCard, e)
                    return SingleAPIResult.Partial(partialResult)
                }
            }
            throw SudoVirtualCardsClient.VirtualCardException.FailedException(NO_RESULT_ERROR_MSG)
        } catch (e: Throwable) {
            logger.error("unexpected error $e")
            when (e) {
                is ApolloException -> throw SudoVirtualCardsClient.VirtualCardException.CancelFailedException(cause = e)
                else -> throw interpretVirtualCardException(e)
            }
        }
    }

    @Throws(SudoVirtualCardsClient.TransactionException::class)
    override suspend fun getTransaction(id: String, cachePolicy: CachePolicy): Transaction? {
        try {
            val keyPairResult = publicKeyService.getCurrentKeyPair()
                ?: throw SudoVirtualCardsClient.TransactionException.PublicKeyException(KEY_RETRIEVAL_ERROR_MSG)
            val keyId = keyPairResult.keyId

            val query = GetTransactionQuery.builder()
                .id(id)
                .keyId(keyId)
                .build()

            val queryResponse = appSyncClient.query(query)
                .responseFetcher(cachePolicy.toResponseFetcher(cachePolicy))
                .enqueueFirst()

            if (queryResponse.hasErrors()) {
                logger.error("errors = ${queryResponse.errors()}")
                throw interpretTransactionError(queryResponse.errors().first())
            }

            val result = queryResponse.data()?.transaction ?: return null
            return TransactionTransformer.toEntityFromGetTransactionQueryResult(deviceKeyManager, result)
        } catch (e: Throwable) {
            logger.error("unexpected error $e")
            when (e) {
                is ApolloException -> throw SudoVirtualCardsClient.TransactionException.FailedException(cause = e)
                else -> throw interpretTransactionException(e)
            }
        }
    }

    @Throws(SudoVirtualCardsClient.TransactionException::class)
    override suspend fun listTransactionsByCardId(
        cardId: String,
        limit: Int,
        nextToken: String?,
        cachePolicy: CachePolicy,
        dateRange: DateRange?,
        sortOrder: SortOrder
    ): ListAPIResult<Transaction, PartialTransaction> {
        try {
            val query = ListTransactionsByCardIdQuery.builder()
                .cardId(cardId)
                .limit(limit)
                .nextToken(nextToken)
                .dateRange(dateRange.toDateRangeInput())
                .sortOrder(sortOrder.toSortOrderInput(sortOrder))
                .build()

            val queryResponse = appSyncClient.query(query)
                .responseFetcher(cachePolicy.toResponseFetcher(cachePolicy))
                .enqueueFirst()

            if (queryResponse.hasErrors()) {
                logger.error("errors = ${queryResponse.errors()}")
                throw interpretTransactionError(queryResponse.errors().first())
            }

            val queryResult = queryResponse.data()?.listTransactionsByCardId()
            val sealedTransactions = queryResult?.items() ?: emptyList()
            val newNextToken = queryResult?.nextToken()

            val success: MutableList<Transaction> = mutableListOf()
            val partials: MutableList<PartialResult<PartialTransaction>> = mutableListOf()
            for (sealedTransaction in sealedTransactions) {
                try {
                    val unsealerTransaction =
                        TransactionTransformer.toEntityFromListTransactionsByCardIdQueryResult(deviceKeyManager, sealedTransaction)
                    success.add(unsealerTransaction)
                } catch (e: Exception) {
                    val partialTransaction =
                        TransactionTransformer.toPartialEntityFromListTransactionsByCardIdQueryResult(sealedTransaction)
                    val partialResult = PartialResult(partialTransaction, e)
                    partials.add(partialResult)
                }
            }
            if (partials.isNotEmpty()) {
                val listPartialResult = ListAPIResult.ListPartialResult(success, partials, newNextToken)
                return ListAPIResult.Partial(listPartialResult)
            }
            val listSuccessResult = ListAPIResult.ListSuccessResult(success, newNextToken)
            return ListAPIResult.Success(listSuccessResult)
        } catch (e: Throwable) {
            logger.error("unexpected error $e")
            when (e) {
                is ApolloException -> throw SudoVirtualCardsClient.TransactionException.FailedException(cause = e)
                else -> throw interpretTransactionException(e)
            }
        }
    }

    override suspend fun subscribeToTransactions(id: String, subscriber: TransactionSubscriber) {
        transactionSubscriptions.subscribe(id, subscriber)
    }

    override suspend fun unsubscribeFromTransactions(id: String) {
        transactionSubscriptions.unsubscribe(id)
    }

    override suspend fun unsubscribeAll() {
        transactionSubscriptions.unsubscribeAll()
    }

    override fun close() {
        transactionSubscriptions.close()
    }

    override fun reset() {
        close()
        this.deviceKeyManager.removeAllKeys()
    }

    /** Private Methods */

    private fun createSymmetricKeysIfAbsent(): KeyResult {
        try {
            var symmetricKeyCreated = false
            var symmetricKeyId = deviceKeyManager.getCurrentSymmetricKeyId()
            if (symmetricKeyId == null) {
                symmetricKeyId = deviceKeyManager.generateNewCurrentSymmetricKey()
                symmetricKeyCreated = true
            }
            return KeyResult(symmetricKeyCreated, symmetricKeyId)
        } catch (e: Throwable) {
            logger.error("unexpected error $e")
            throw e
        }
    }

    private suspend fun createAndRegisterKeyPairIfAbsent(): KeyResult {
        try {
            val registerRequired: Boolean
            var created = false
            var keyPair = deviceKeyManager.getCurrentKeyPair()
            if (keyPair == null) {
                keyPair = deviceKeyManager.generateNewCurrentKeyPair()
                registerRequired = true
                created = true
            } else {
                var nextToken: String?
                var alreadyRegistered: Boolean
                do {
                    val keyRing = publicKeyService.getKeyRing(keyPair.keyRingId, CachePolicy.REMOTE_ONLY)
                    alreadyRegistered = keyRing?.keys?.find { it.keyId == keyPair.keyId } != null
                    nextToken = keyRing?.nextToken
                } while (!alreadyRegistered && nextToken != null)
                registerRequired = !alreadyRegistered
            }
            if (registerRequired) {
                publicKeyService.create(keyPair.keyId, keyPair.keyRingId, keyPair.publicKey)
            }
            return KeyResult(created, keyPair.keyId)
        } catch (e: Throwable) {
            logger.error("unexpected error $e")
            throw e
        }
    }

    // Ensure there is a current key in the key ring registered with the backend so the card can be sealed
    private suspend fun ensurePublicKeyIsRegistered() {

        val keyPair = publicKeyService.getCurrentKeyPair()
            ?: throw SudoVirtualCardsClient.VirtualCardException.PublicKeyException(KEY_RETRIEVAL_ERROR_MSG)

        // Get the key ring for the current key pair from the backend and check that it contains the current key pair
        val keyRing = publicKeyService.getKeyRing(keyPair.keyRingId, CachePolicy.REMOTE_ONLY)
        if (keyRing?.keys?.find { it.keyId == keyPair.keyId } != null) {
            // Key ring on the backend contains the current key pair
            return
        }

        // Register the current key pair with the backend
        publicKeyService.create(keyPair.keyId, keyPair.keyRingId, keyPair.publicKey)
    }

    private fun interpretFundingSourceException(e: Throwable): Throwable {
        return when (e) {
            is CancellationException,
            is SudoVirtualCardsClient.FundingSourceException -> e
            else -> SudoVirtualCardsClient.FundingSourceException.UnknownException(e)
        }
    }

    private fun interpretVirtualCardException(e: Throwable): Throwable {
        return when (e) {
            is CancellationException,
            is SudoVirtualCardsClient.VirtualCardException -> e
            is PublicKeyService.PublicKeyServiceException ->
                SudoVirtualCardsClient.VirtualCardException.PublicKeyException(KEY_RETRIEVAL_ERROR_MSG, e)
            is Unsealer.UnsealerException ->
                SudoVirtualCardsClient.VirtualCardException.UnsealingException(UNSEAL_CARD_ERROR_MSG, e)
            else -> SudoVirtualCardsClient.VirtualCardException.UnknownException(e)
        }
    }

    private fun interpretTransactionException(e: Throwable): Throwable {
        return when (e) {
            is CancellationException,
            is SudoVirtualCardsClient.TransactionException -> e
            is PublicKeyService.PublicKeyServiceException ->
                SudoVirtualCardsClient.TransactionException.PublicKeyException(KEY_RETRIEVAL_ERROR_MSG, e)
            is Unsealer.UnsealerException ->
                SudoVirtualCardsClient.TransactionException.UnsealingException(UNSEAL_CARD_ERROR_MSG, e)
            else -> SudoVirtualCardsClient.TransactionException.UnknownException(e)
        }
    }

    /** Apollo Errors */

    private fun interpretFundingSourceError(e: Error): SudoVirtualCardsClient.FundingSourceException {
        val error = e.customAttributes()[ERROR_TYPE]?.toString() ?: ""
        if (error.contains(ERROR_PROVISIONAL_FUNDING_SOURCE_NOT_FOUND)) {
            return SudoVirtualCardsClient.FundingSourceException.ProvisionalFundingSourceNotFoundException(
                PROVISIONAL_FUNDING_SOURCE_NOT_FOUND_MSG
            )
        }
        if (error.contains(ERROR_FUNDING_SOURCE_NOT_FOUND)) {
            return SudoVirtualCardsClient.FundingSourceException.FundingSourceNotFoundException(FUNDING_SOURCE_NOT_FOUND_MSG)
        }
        if (error.contains(ERROR_DUPLICATE_FUNDING_SOURCE)) {
            return SudoVirtualCardsClient.FundingSourceException.DuplicateFundingSourceException(DUPLICATE_FUNDING_SOURCE_MSG)
        }
        if (error.contains(ERROR_FUNDING_SOURCE_STATE)) {
            return SudoVirtualCardsClient.FundingSourceException.FundingSourceStateException(FUNDING_SOURCE_STATE_MSG)
        }
        if (error.contains(ERROR_FUNDING_SOURCE_NOT_SETUP)) {
            return SudoVirtualCardsClient.FundingSourceException.SetupFailedException(FUNDING_SOURCE_NOT_SETUP_MSG)
        }
        if (error.contains(ERROR_FUNDING_SOURCE_COMPLETION_DATA_INVALID)) {
            return SudoVirtualCardsClient.FundingSourceException.CompletionDataInvalidException(FUNDING_SOURCE_COMPLETION_DATA_INVALID_MSG)
        }
        if (error.contains(ERROR_UNACCEPTABLE_FUNDING_SOURCE)) {
            return SudoVirtualCardsClient.FundingSourceException.UnacceptableFundingSourceException(UNACCEPTABLE_FUNDING_SOURCE_MSG)
        }
        if (error.contains(ERROR_UNSUPPORTED_CURRENCY)) {
            return SudoVirtualCardsClient.FundingSourceException.UnsupportedCurrencyException(UNSUPPORTED_CURRENCY_MSG)
        }
        if (error.contains(ERROR_IDENTITY_NOT_VERIFIED) || error.contains(SERVICE_ERROR)) {
            return SudoVirtualCardsClient.FundingSourceException.IdentityVerificationException(IDENTITY_NOT_VERIFIED_MSG)
        }
        if (error.contains(ERROR_ACCOUNT_LOCKED)) {
            return SudoVirtualCardsClient.FundingSourceException.AccountLockedException(ACCOUNT_LOCKED_MSG)
        }
        if (error.contains(ERROR_VELOCITY_EXCEEDED)) {
            return SudoVirtualCardsClient.FundingSourceException.VelocityExceededException(VELOCITY_EXCEEDED_MSG)
        }
        if (error.contains(ERROR_ENTITLEMENT_EXCEEDED)) {
            return SudoVirtualCardsClient.FundingSourceException.EntitlementExceededException(ENTITLEMENT_EXCEEDED_MSG)
        }
        return SudoVirtualCardsClient.FundingSourceException.FailedException(e.toString())
    }

    private fun interpretVirtualCardError(e: Error): SudoVirtualCardsClient.VirtualCardException {
        val error = e.customAttributes()[ERROR_TYPE]?.toString() ?: ""
        if (error.contains(ERROR_INVALID_TOKEN)) {
            return SudoVirtualCardsClient.VirtualCardException.ProvisionFailedException(INVALID_TOKEN_MSG)
        }
        if (error.contains(ERROR_CARD_NOT_FOUND)) {
            return SudoVirtualCardsClient.VirtualCardException.CardNotFoundException(CARD_NOT_FOUND_MSG)
        }
        if (error.contains(ERROR_INVALID_CARD_STATE)) {
            return SudoVirtualCardsClient.VirtualCardException.CardStateException(INVALID_CARD_STATE_MSG)
        }
        if (error.contains(ERROR_FUNDING_SOURCE_NOT_FOUND)) {
            return SudoVirtualCardsClient.VirtualCardException.FundingSourceNotFoundException(FUNDING_SOURCE_NOT_FOUND_MSG)
        }
        if (error.contains(ERROR_FUNDING_SOURCE_NOT_ACTIVE)) {
            return SudoVirtualCardsClient.VirtualCardException.FundingSourceNotActiveException(FUNDING_SOURCE_NOT_ACTIVE_MSG)
        }
        if (error.contains(ERROR_VELOCITY_EXCEEDED)) {
            return SudoVirtualCardsClient.VirtualCardException.VelocityExceededException(VELOCITY_EXCEEDED_MSG)
        }
        if (error.contains(ERROR_ENTITLEMENT_EXCEEDED)) {
            return SudoVirtualCardsClient.VirtualCardException.EntitlementExceededException(ENTITLEMENT_EXCEEDED_MSG)
        }
        if (error.contains(ERROR_UNSUPPORTED_CURRENCY)) {
            return SudoVirtualCardsClient.VirtualCardException.UnsupportedCurrencyException(UNSUPPORTED_CURRENCY_MSG)
        }
        if (error.contains(ERROR_IDENTITY_NOT_VERIFIED)) {
            return SudoVirtualCardsClient.VirtualCardException.IdentityVerificationException(IDENTITY_NOT_VERIFIED_MSG)
        }
        if (error.contains(ERROR_IDENTITY_INSUFFICIENT)) {
            return SudoVirtualCardsClient.VirtualCardException.IdentityVerificationInsufficientException(IDENTITY_INSUFFICIENT_MSG)
        }
        if (error.contains(ERROR_ACCOUNT_LOCKED)) {
            return SudoVirtualCardsClient.VirtualCardException.AccountLockedException(ACCOUNT_LOCKED_MSG)
        }
        return SudoVirtualCardsClient.VirtualCardException.FailedException(e.toString())
    }

    private fun interpretTransactionError(e: Error): SudoVirtualCardsClient.TransactionException {
        return SudoVirtualCardsClient.TransactionException.FailedException(e.toString())
    }
}
