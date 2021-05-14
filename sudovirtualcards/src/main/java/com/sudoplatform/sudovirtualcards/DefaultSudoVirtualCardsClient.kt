/*
 * Copyright © 2020 Anonyome Labs, Inc. All rights reserved.
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
import com.sudoplatform.sudoprofiles.Sudo
import com.sudoplatform.sudoprofiles.SudoProfilesClient
import com.sudoplatform.sudouser.SudoUserClient
import com.sudoplatform.sudovirtualcards.extensions.enqueue
import com.sudoplatform.sudovirtualcards.extensions.enqueueFirst
import com.sudoplatform.sudovirtualcards.graphql.CancelCardMutation
import com.sudoplatform.sudovirtualcards.graphql.CancelFundingSourceMutation
import com.sudoplatform.sudovirtualcards.graphql.CardProvisionMutation
import com.sudoplatform.sudovirtualcards.graphql.CompleteFundingSourceMutation
import com.sudoplatform.sudovirtualcards.graphql.GetCardQuery
import com.sudoplatform.sudovirtualcards.graphql.GetFundingSourceClientConfigurationQuery
import com.sudoplatform.sudovirtualcards.graphql.GetFundingSourceQuery
import com.sudoplatform.sudovirtualcards.graphql.GetProvisionalCardQuery
import com.sudoplatform.sudovirtualcards.graphql.GetTransactionQuery
import com.sudoplatform.sudovirtualcards.graphql.ListCardsQuery
import com.sudoplatform.sudovirtualcards.graphql.ListFundingSourcesQuery
import com.sudoplatform.sudovirtualcards.graphql.ListTransactionsQuery
import com.sudoplatform.sudovirtualcards.graphql.SetupFundingSourceMutation
import com.sudoplatform.sudovirtualcards.graphql.UpdateCardMutation
import com.sudoplatform.sudovirtualcards.graphql.type.CardCancelRequest
import com.sudoplatform.sudovirtualcards.graphql.type.CardProvisionRequest
import com.sudoplatform.sudovirtualcards.graphql.type.CardUpdateRequest
import com.sudoplatform.sudovirtualcards.graphql.type.CompleteFundingSourceRequest
import com.sudoplatform.sudovirtualcards.graphql.type.FundingSourceType
import com.sudoplatform.sudovirtualcards.graphql.type.IdInput
import com.sudoplatform.sudovirtualcards.graphql.type.SetupFundingSourceRequest
import com.sudoplatform.sudovirtualcards.keys.DeviceKeyManager
import com.sudoplatform.sudovirtualcards.keys.PublicKeyService
import com.sudoplatform.sudovirtualcards.logging.LogConstants
import com.sudoplatform.sudovirtualcards.subscription.TransactionSubscriptionService
import com.sudoplatform.sudovirtualcards.subscription.TransactionSubscriber
import com.sudoplatform.sudovirtualcards.types.CachePolicy
import com.sudoplatform.sudovirtualcards.types.Card
import com.sudoplatform.sudovirtualcards.types.FundingSource
import com.sudoplatform.sudovirtualcards.types.ListOutput
import com.sudoplatform.sudovirtualcards.types.ProvisionalCard
import com.sudoplatform.sudovirtualcards.types.StripeClientConfiguration
import com.sudoplatform.sudovirtualcards.types.StripeData
import com.sudoplatform.sudovirtualcards.types.StripeSetup
import com.sudoplatform.sudovirtualcards.types.Transaction
import com.sudoplatform.sudovirtualcards.types.inputs.CreditCardFundingSourceInput
import com.sudoplatform.sudovirtualcards.types.inputs.ProvisionCardInput
import com.sudoplatform.sudovirtualcards.types.inputs.filters.TransactionFilter
import com.sudoplatform.sudovirtualcards.types.inputs.UpdateCardInput
import com.sudoplatform.sudovirtualcards.types.inputs.filters.CardFilter
import com.sudoplatform.sudovirtualcards.types.transformers.CardTransformer
import com.sudoplatform.sudovirtualcards.types.transformers.CardTransformer.toAddressInput
import com.sudoplatform.sudovirtualcards.types.transformers.FundingSourceTransformer
import com.sudoplatform.sudovirtualcards.types.transformers.TransactionTransformer
import com.sudoplatform.sudovirtualcards.types.transformers.Unsealer
import java.util.concurrent.CancellationException

/**
 * Default implementation of the [SudoVirtualCardsClient] interface.
 *
 * @property context Application context.
 * @property appSyncClient GraphQL client used to make requests to AWS and call sudo virtual cards service API.
 * @property sudoUserClient The [SudoUserClient] used to determine if a user is signed in and gain access to the user owner ID.
 * @property sudoProfilesClient The [SudoProfilesClient] used to perform ownership proof lifecycle operations.
 * @property paymentProcessInteractions The [PaymentProcessInteractions] used to process payment setup confirmation.
 * @property logger Errors and warnings will be logged here
 * @property deviceKeyManager On device management of key storage
 * @property publicKeyService Service that handles registering public keys with the backend
 *
 * @since 2020-05-21
 */
internal class DefaultSudoVirtualCardsClient(
    private val context: Context,
    private val appSyncClient: AWSAppSyncClient,
    private val sudoUserClient: SudoUserClient,
    private val sudoProfilesClient: SudoProfilesClient,
    private val paymentProcessInteractions: PaymentProcessInteractions = StripePaymentProcessInteractions(),
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
        private const val FUNDING_SOURCE_STATE_MSG = "Funding source state is inappropriate for the requested operation"
        private const val FUNDING_SOURCE_NOT_COMPLETE_MSG = "Failed to complete funding source creation"
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

    /** This manages the subscriptions to transaction updates and deletes */
    private val transactionSubscriptions = TransactionSubscriptionService(appSyncClient, deviceKeyManager, sudoUserClient, logger)

    @Throws(SudoVirtualCardsClient.FundingSourceException::class)
    override suspend fun createFundingSource(input: CreditCardFundingSourceInput): FundingSource {

        // Retrieve the funding source client configuration
        val configuration = getFundingSourceClientConfiguration()

        // Perform the funding source setup operation
        val stripeSetup = setupFundingSource()

        // Process Stripe data
        val completionData = paymentProcessInteractions.process(input, configuration, stripeSetup, context)

        // Perform the funding source completion operation
        return completeFundingSource(completionData, stripeSetup)
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
                logger.warning("errors = ${queryResponse.errors()}")
                throw interpretFundingSourceError(queryResponse.errors().first())
            }

            val result = queryResponse.data()?.fundingSource ?: return null
            return FundingSourceTransformer.toEntityFromGetFundingSourceQueryResult(result)
        } catch (e: Throwable) {
            logger.debug("unexpected error $e")
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
                logger.warning("errors = ${queryResponse.errors()}")
                throw interpretFundingSourceError(queryResponse.errors().first())
            }

            val result = queryResponse.data()?.listFundingSources() ?: return ListOutput(emptyList(), null)
            val fundingSources = FundingSourceTransformer.toEntityFromListFundingSourcesQueryResult(result.items())
            return ListOutput(fundingSources, result.nextToken())
        } catch (e: Throwable) {
            logger.debug("unexpected error $e")
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
                logger.warning("errors = ${mutationResponse.errors()}")
                throw interpretFundingSourceError(mutationResponse.errors().first())
            }

            val result = mutationResponse.data()?.cancelFundingSource()
            result?.let {
                return FundingSourceTransformer.toEntityFromCancelFundingSourceMutationResult(result)
            }
            throw SudoVirtualCardsClient.FundingSourceException.CancelFailedException(NO_RESULT_ERROR_MSG)
        } catch (e: Throwable) {
            logger.debug("unexpected error $e")
            when (e) {
                is ApolloException -> throw SudoVirtualCardsClient.FundingSourceException.CancelFailedException(cause = e)
                else -> throw interpretFundingSourceException(e)
            }
        }
    }

    @Throws(SudoVirtualCardsClient.CardException::class)
    override suspend fun provisionCard(input: ProvisionCardInput): ProvisionalCard {
        try {
            // Ensure user tokens are refreshed
            refreshTokens()
            // Ensure there is a current key in the key ring so the card can be sealed
            ensurePublicKeyIsRegistered()
            // Retrieve the ownership proof used to map a sudo to a card
            val ownerProof = getOwnershipProof(input.sudoId)

            val mutationInput = CardProvisionRequest.builder()
                .clientRefId(input.clientRefId)
                .ownerProofs(listOf(ownerProof))
                .keyRingId(deviceKeyManager.getKeyRingId())
                .fundingSourceId(input.fundingSourceId)
                .cardHolder(input.cardHolder)
                .alias(input.alias)
                .billingAddress(input.billingAddress.toAddressInput())
                .currency(input.currency)
                .build()
            val mutation = CardProvisionMutation.builder()
                .input(mutationInput)
                .build()

            val mutationResponse = appSyncClient.mutate(mutation)
                .enqueue()

            if (mutationResponse.hasErrors()) {
                logger.warning("errors = ${mutationResponse.errors()}")
                throw interpretCardError(mutationResponse.errors().first())
            }

            val provisionedCard = mutationResponse.data()?.cardProvision()
            if (provisionedCard != null) {
                return CardTransformer.toEntityFromCardProvisionMutationResult(deviceKeyManager, provisionedCard)
            }
            throw SudoVirtualCardsClient.CardException.ProvisionFailedException("No provisional card returned")
        } catch (e: Throwable) {
            logger.debug("unexpected error $e")
            when (e) {
                is ApolloException -> throw SudoVirtualCardsClient.CardException.ProvisionFailedException(cause = e)
                else -> throw interpretCardException(e)
            }
        }
    }

    @Throws(SudoVirtualCardsClient.CardException::class)
    override suspend fun getProvisionalCard(id: String, cachePolicy: CachePolicy): ProvisionalCard? {
        try {
            val query = GetProvisionalCardQuery.builder()
                .id(id)
                .build()

            val queryResponse = appSyncClient.query(query)
                .responseFetcher(cachePolicy.toResponseFetcher(cachePolicy))
                .enqueueFirst()

            if (queryResponse.hasErrors()) {
                logger.warning("errors = ${queryResponse.errors()}")
                throw interpretCardError(queryResponse.errors().first())
            }

            val queryResult = queryResponse.data()?.provisionalCard
                ?: throw SudoVirtualCardsClient.CardException.FailedException("get provisional card failed - no response")
            return CardTransformer.toProvisionalCardFromGetProvisionalCardQueryResult(deviceKeyManager, queryResult)
        } catch (e: Throwable) {
            logger.debug("unexpected error $e")
            when (e) {
                is ApolloException -> throw SudoVirtualCardsClient.CardException.FailedException(cause = e)
                else -> throw interpretCardException(e)
            }
        }
    }

    @Throws(SudoVirtualCardsClient.CardException::class)
    override suspend fun getCard(id: String, cachePolicy: CachePolicy): Card? {
        try {
            val keyPairResult = publicKeyService.getCurrentKeyPair(PublicKeyService.MissingKeyPolicy.GENERATE_IF_MISSING)
                ?: throw SudoVirtualCardsClient.CardException.PublicKeyException(KEY_RETRIEVAL_ERROR_MSG)
            val keyId = keyPairResult.keyId

            val query = GetCardQuery.builder()
                .id(id)
                .keyId(keyId)
                .build()

            val queryResponse = appSyncClient.query(query)
                .responseFetcher(cachePolicy.toResponseFetcher(cachePolicy))
                .enqueueFirst()

            if (queryResponse.hasErrors()) {
                logger.warning("errors = ${queryResponse.errors()}")
                throw interpretCardError(queryResponse.errors().first())
            }

            val result = queryResponse.data()?.card ?: return null
            return CardTransformer.toEntityFromGetCardQueryResult(deviceKeyManager, result)
        } catch (e: Throwable) {
            logger.debug("unexpected error $e")
            when (e) {
                is ApolloException -> throw SudoVirtualCardsClient.CardException.FailedException(cause = e)
                else -> throw interpretCardException(e)
            }
        }
    }

    @Throws(SudoVirtualCardsClient.CardException::class)
    override suspend fun listCards(
        limit: Int,
        nextToken: String?,
        cachePolicy: CachePolicy,
        filter: () -> CardFilter?
    ): ListOutput<Card> {
        try {
            val filters = filter.invoke()

            val query = ListCardsQuery.builder()
                .limit(limit)
                .nextToken(nextToken)
                .filter(CardTransformer.toGraphQLFilter(filters))
                .build()

            val queryResponse = appSyncClient.query(query)
                .responseFetcher(cachePolicy.toResponseFetcher(cachePolicy))
                .enqueueFirst()

            if (queryResponse.hasErrors()) {
                logger.warning("errors = ${queryResponse.errors()}")
                throw interpretCardError(queryResponse.errors().first())
            }

            val result = queryResponse.data()?.listCards() ?: return ListOutput(emptyList(), null)
            val cards = CardTransformer.toEntityFromListCardsQueryResult(deviceKeyManager, result.items())
            return ListOutput(cards, result.nextToken())
        } catch (e: Throwable) {
            logger.debug("unexpected error $e")
            when (e) {
                is ApolloException -> throw SudoVirtualCardsClient.CardException.FailedException(cause = e)
                else -> throw interpretCardException(e)
            }
        }
    }

    @Throws(SudoVirtualCardsClient.CardException::class)
    override suspend fun updateCard(input: UpdateCardInput): Card {
        try {
            val keyPairResult = publicKeyService.getCurrentKeyPair(PublicKeyService.MissingKeyPolicy.GENERATE_IF_MISSING)
                ?: throw SudoVirtualCardsClient.CardException.PublicKeyException(KEY_RETRIEVAL_ERROR_MSG)
            val keyId = keyPairResult.keyId

            val mutationInput = CardUpdateRequest.builder()
                .id(input.id)
                .keyId(keyId)
                .cardHolder(input.cardHolder)
                .alias(input.alias)
                .billingAddress(input.billingAddress.toAddressInput())
                .build()
            val mutation = UpdateCardMutation.builder()
                .input(mutationInput)
                .build()

            val mutationResponse = appSyncClient.mutate(mutation)
                .enqueue()

            if (mutationResponse.hasErrors()) {
                logger.warning("errors = ${mutationResponse.errors()}")
                throw interpretCardError(mutationResponse.errors().first())
            }

            val updatedCard = mutationResponse.data()?.updateCard()
            updatedCard?.let {
                return CardTransformer.toEntityFromUpdateCardMutationResult(deviceKeyManager, updatedCard)
            }
            throw SudoVirtualCardsClient.CardException.FailedException(NO_RESULT_ERROR_MSG)
        } catch (e: Throwable) {
            logger.debug("unexpected error $e")
            when (e) {
                is ApolloException -> throw SudoVirtualCardsClient.CardException.UpdateFailedException(cause = e)
                else -> throw interpretCardException(e)
            }
        }
    }

    override suspend fun cancelCard(id: String): Card {
        try {
            val keyPairResult = publicKeyService.getCurrentKeyPair(PublicKeyService.MissingKeyPolicy.GENERATE_IF_MISSING)
                ?: throw SudoVirtualCardsClient.CardException.PublicKeyException(KEY_RETRIEVAL_ERROR_MSG)
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
                logger.warning("errors = ${mutationResponse.errors()}")
                throw interpretCardError(mutationResponse.errors().first())
            }

            val cancelledCard = mutationResponse.data()?.cancelCard()
            cancelledCard?.let {
                return CardTransformer.toEntityFromCancelCardMutationResult(deviceKeyManager, cancelledCard)
            }
            throw SudoVirtualCardsClient.CardException.FailedException(NO_RESULT_ERROR_MSG)
        } catch (e: Throwable) {
            logger.debug("unexpected error $e")
            when (e) {
                is ApolloException -> throw SudoVirtualCardsClient.CardException.CancelFailedException(cause = e)
                else -> throw interpretCardException(e)
            }
        }
    }

    @Throws(SudoVirtualCardsClient.TransactionException::class)
    override suspend fun getTransaction(id: String, cachePolicy: CachePolicy): Transaction? {
        try {
            val keyPairResult = publicKeyService.getCurrentKeyPair(PublicKeyService.MissingKeyPolicy.GENERATE_IF_MISSING)
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
                logger.warning("errors = ${queryResponse.errors()}")
                throw interpretTransactionError(queryResponse.errors().first())
            }

            val result = queryResponse.data()?.transaction ?: return null
            return TransactionTransformer.toEntityFromGetTransactionQueryResult(deviceKeyManager, result)
        } catch (e: Throwable) {
            logger.debug("unexpected error $e")
            when (e) {
                is ApolloException -> throw SudoVirtualCardsClient.TransactionException.FailedException(cause = e)
                else -> throw interpretTransactionException(e)
            }
        }
    }

    @Throws(SudoVirtualCardsClient.TransactionException::class)
    override suspend fun listTransactions(
        limit: Int,
        nextToken: String?,
        cachePolicy: CachePolicy,
        filter: () -> TransactionFilter?
    ): ListOutput<Transaction> {
        try {
            val filters = filter.invoke()

            val query = ListTransactionsQuery.builder()
                .limit(limit)
                .nextToken(nextToken)
                .filter(TransactionTransformer.toGraphQLFilter(filters))
                .build()

            val queryResponse = appSyncClient.query(query)
                .responseFetcher(cachePolicy.toResponseFetcher(cachePolicy))
                .enqueueFirst()

            if (queryResponse.hasErrors()) {
                logger.warning("errors = ${queryResponse.errors()}")
                throw interpretTransactionError(queryResponse.errors().first())
            }

            val result = queryResponse.data()?.listTransactions()
                ?: return ListOutput(emptyList(), null)
            val filteredResult = TransactionTransformer.filter(result.items(), filters)
            val transactions = TransactionTransformer.toEntityFromListTransactionsQueryResult(deviceKeyManager, filteredResult)
            return ListOutput(transactions, result.nextToken())
        } catch (e: Throwable) {
            logger.debug("unexpected error $e")
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

    private suspend fun getFundingSourceClientConfiguration(): StripeClientConfiguration {
        try {
            val query = GetFundingSourceClientConfigurationQuery.builder().build()

            val queryResponse = appSyncClient.query(query)
                .responseFetcher(AppSyncResponseFetchers.CACHE_AND_NETWORK)
                .enqueueFirst()

            if (queryResponse.hasErrors()) {
                logger.debug("errors = ${queryResponse.errors()}")
                throw interpretFundingSourceError(queryResponse.errors().first())
            }

            val encodedDataString = queryResponse.data()?.fundingSourceClientConfiguration?.data()
            if (encodedDataString != null) {
                val configurationBytes = Base64.decode(encodedDataString)
                return Gson().fromJson(String(configurationBytes, Charsets.UTF_8), StripeClientConfiguration::class.java)
            } else {
                throw SudoVirtualCardsClient.FundingSourceException.FailedException()
            }
        } catch (e: Throwable) {
            logger.debug("unexpected error $e")
            when (e) {
                is ApolloException -> throw SudoVirtualCardsClient.FundingSourceException.FailedException(cause = e)
                else -> throw interpretFundingSourceException(e)
            }
        }
    }

    private suspend fun setupFundingSource(): StripeSetup {
        try {
            // Hard-coded currency support until further notice
            val mutationInput = SetupFundingSourceRequest.builder()
                .type(FundingSourceType.CREDIT_CARD)
                .currency("USD")
                .build()
            val mutation = SetupFundingSourceMutation.builder()
                .input(mutationInput)
                .build()

            val mutationResponse = appSyncClient.mutate(mutation)
                .enqueue()

            if (mutationResponse.hasErrors()) {
                logger.debug("errors = ${mutationResponse.errors()}")
                throw interpretFundingSourceError(mutationResponse.errors().first())
            }

            val setupResponseData = mutationResponse.data()?.setupFundingSource()
            if (setupResponseData != null) {
                val setupDataBytes = Base64.decode(setupResponseData.provisioningData())
                val stripeSetupData = Gson().fromJson(String(setupDataBytes, Charsets.UTF_8), StripeData::class.java)
                return StripeSetup(setupResponseData.id(), stripeSetupData)
            } else {
                throw SudoVirtualCardsClient.FundingSourceException.SetupFailedException()
            }
        } catch (e: Throwable) {
            logger.debug("unexpected error $e")
            when (e) {
                is ApolloException -> throw SudoVirtualCardsClient.FundingSourceException.SetupFailedException(cause = e)
                else -> throw interpretFundingSourceException(e)
            }
        }
    }

    private suspend fun completeFundingSource(completionData: String, stripeSetup: StripeSetup): FundingSource {
        try {
            val mutationInput = CompleteFundingSourceRequest.builder()
                .id(stripeSetup.id)
                .completionData(completionData)
                .build()
            val mutation = CompleteFundingSourceMutation.builder()
                .input(mutationInput)
                .build()

            val mutationResponse = appSyncClient.mutate(mutation)
                .enqueue()

            if (mutationResponse.hasErrors()) {
                logger.debug("errors = ${mutationResponse.errors()}")
                throw interpretFundingSourceError(mutationResponse.errors().first())
            }

            val completeResult = mutationResponse.data()?.completeFundingSource()
            if (completeResult != null) {
                return FundingSourceTransformer.toEntityFromCreateFundingSourceMutationResult(completeResult)
            } else {
                throw SudoVirtualCardsClient.FundingSourceException.CompletionFailedException()
            }
        } catch (e: Throwable) {
            logger.debug("unexpected error $e")
            when (e) {
                is ApolloException -> throw SudoVirtualCardsClient.FundingSourceException.CompletionFailedException(cause = e)
                else -> throw interpretFundingSourceException(e)
            }
        }
    }

    // Ensure there is a current key in the key ring registered with the backend so the card can be sealed
    private suspend fun ensurePublicKeyIsRegistered() {

        val keyPair = publicKeyService.getCurrentKeyPair(PublicKeyService.MissingKeyPolicy.GENERATE_IF_MISSING)
            ?: throw SudoVirtualCardsClient.CardException.PublicKeyException(KEY_RETRIEVAL_ERROR_MSG)

        // Get the key ring for the current key pair from the backend and check that it contains the current key pair
        val keyRing = publicKeyService.getKeyRing(keyPair.keyRingId, CachePolicy.REMOTE_ONLY)
        if (keyRing?.keys?.find { it.keyId == keyPair.keyId } != null) {
            // Key ring on the backend contains the current key pair
            return
        }

        // Register the current key pair with the backend
        publicKeyService.create(keyPair)
    }

    private suspend fun getOwnershipProof(sudoId: String): String {
        val virtualCardsAudience = "sudoplatform.virtual-cards.virtual-card"
        val sudo = Sudo(sudoId)
        return sudoProfilesClient.getOwnershipProof(sudo, virtualCardsAudience)
    }

    private suspend fun refreshTokens() {
        sudoUserClient.refreshTokens(sudoUserClient.getRefreshToken()!!)
    }

    private fun interpretFundingSourceException(e: Throwable): Throwable {
        return when (e) {
            is CancellationException,
            is SudoVirtualCardsClient.FundingSourceException -> e
            else -> SudoVirtualCardsClient.FundingSourceException.UnknownException(e)
        }
    }

    private fun interpretCardException(e: Throwable): Throwable {
        return when (e) {
            is CancellationException,
            is SudoVirtualCardsClient.CardException -> e
            is PublicKeyService.PublicKeyServiceException ->
                SudoVirtualCardsClient.CardException.PublicKeyException(KEY_RETRIEVAL_ERROR_MSG, e)
            is Unsealer.UnsealerException ->
                SudoVirtualCardsClient.CardException.UnsealingException(UNSEAL_CARD_ERROR_MSG, e)
            else -> SudoVirtualCardsClient.CardException.UnknownException(e)
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
        if (error.contains(ERROR_FUNDING_SOURCE_NOT_FOUND) || error.contains(ERROR_PROVISIONAL_FUNDING_SOURCE_NOT_FOUND)) {
            return SudoVirtualCardsClient.FundingSourceException.FundingSourceNotFoundException(FUNDING_SOURCE_NOT_FOUND_MSG)
        }
        if (error.contains(ERROR_DUPLICATE_FUNDING_SOURCE)) {
            return SudoVirtualCardsClient.FundingSourceException.DuplicateFundingSourceException(DUPLICATE_FUNDING_SOURCE_MSG)
        }
        if (error.contains(ERROR_FUNDING_SOURCE_STATE)) {
            return SudoVirtualCardsClient.FundingSourceException.FundingSourceStateException(FUNDING_SOURCE_STATE_MSG)
        }
        if (error.contains(ERROR_FUNDING_SOURCE_NOT_SETUP) || error.contains(ERROR_FUNDING_SOURCE_COMPLETION_DATA_INVALID)) {
            return SudoVirtualCardsClient.FundingSourceException.CompletionFailedException(FUNDING_SOURCE_NOT_COMPLETE_MSG)
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
        return SudoVirtualCardsClient.FundingSourceException.FailedException(e.toString())
    }

    private fun interpretCardError(e: Error): SudoVirtualCardsClient.CardException {
        val error = e.customAttributes()[ERROR_TYPE]?.toString() ?: ""
        if (error.contains(ERROR_INVALID_TOKEN)) {
            return SudoVirtualCardsClient.CardException.ProvisionFailedException(INVALID_TOKEN_MSG)
        }
        if (error.contains(ERROR_CARD_NOT_FOUND)) {
            return SudoVirtualCardsClient.CardException.CardNotFoundException(CARD_NOT_FOUND_MSG)
        }
        if (error.contains(ERROR_INVALID_CARD_STATE)) {
            return SudoVirtualCardsClient.CardException.CardStateException(INVALID_CARD_STATE_MSG)
        }
        if (error.contains(ERROR_FUNDING_SOURCE_NOT_FOUND)) {
            return SudoVirtualCardsClient.CardException.FundingSourceNotFoundException(FUNDING_SOURCE_NOT_FOUND_MSG)
        }
        if (error.contains(ERROR_FUNDING_SOURCE_NOT_ACTIVE)) {
            return SudoVirtualCardsClient.CardException.FundingSourceNotActiveException(FUNDING_SOURCE_NOT_ACTIVE_MSG)
        }
        if (error.contains(ERROR_VELOCITY_EXCEEDED)) {
            return SudoVirtualCardsClient.CardException.VelocityExceededException(VELOCITY_EXCEEDED_MSG)
        }
        if (error.contains(ERROR_ENTITLEMENT_EXCEEDED)) {
            return SudoVirtualCardsClient.CardException.EntitlementExceededException(ENTITLEMENT_EXCEEDED_MSG)
        }
        if (error.contains(ERROR_UNSUPPORTED_CURRENCY)) {
            return SudoVirtualCardsClient.CardException.UnsupportedCurrencyException(UNSUPPORTED_CURRENCY_MSG)
        }
        if (error.contains(ERROR_IDENTITY_NOT_VERIFIED)) {
            return SudoVirtualCardsClient.CardException.IdentityVerificationException(IDENTITY_NOT_VERIFIED_MSG)
        }
        if (error.contains(ERROR_IDENTITY_INSUFFICIENT)) {
            return SudoVirtualCardsClient.CardException.IdentityVerificationInsufficientException(IDENTITY_INSUFFICIENT_MSG)
        }
        if (error.contains(ERROR_ACCOUNT_LOCKED)) {
            return SudoVirtualCardsClient.CardException.AccountLockedException(ACCOUNT_LOCKED_MSG)
        }
        return SudoVirtualCardsClient.CardException.FailedException(e.toString())
    }

    private fun interpretTransactionError(e: Error): SudoVirtualCardsClient.TransactionException {
        return SudoVirtualCardsClient.TransactionException.FailedException(e.toString())
    }
}
