/*
 * Copyright © 2023 Anonyome Labs, Inc. All rights reserved.
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
import com.sudoplatform.sudouser.SudoUserClient
import com.sudoplatform.sudovirtualcards.extensions.enqueue
import com.sudoplatform.sudovirtualcards.extensions.enqueueFirst
import com.sudoplatform.sudovirtualcards.graphql.CancelFundingSourceMutation
import com.sudoplatform.sudovirtualcards.graphql.GetCardQuery
import com.sudoplatform.sudovirtualcards.graphql.GetVirtualCardsConfigQuery
import com.sudoplatform.sudovirtualcards.graphql.GetFundingSourceQuery
import com.sudoplatform.sudovirtualcards.graphql.GetFundingSourceClientConfigurationQuery
import com.sudoplatform.sudovirtualcards.graphql.GetProvisionalCardQuery
import com.sudoplatform.sudovirtualcards.graphql.GetTransactionQuery
import com.sudoplatform.sudovirtualcards.graphql.ListCardsQuery
import com.sudoplatform.sudovirtualcards.graphql.ListFundingSourcesQuery
import com.sudoplatform.sudovirtualcards.graphql.ListTransactionsByCardIdQuery
import com.sudoplatform.sudovirtualcards.graphql.CancelVirtualCardMutation
import com.sudoplatform.sudovirtualcards.graphql.CompleteFundingSourceMutation
import com.sudoplatform.sudovirtualcards.graphql.ListTransactionsQuery
import com.sudoplatform.sudovirtualcards.graphql.ProvisionVirtualCardMutation
import com.sudoplatform.sudovirtualcards.graphql.RefreshFundingSourceMutation
import com.sudoplatform.sudovirtualcards.graphql.SetupFundingSourceMutation
import com.sudoplatform.sudovirtualcards.graphql.UpdateVirtualCardMutation
import com.sudoplatform.sudovirtualcards.graphql.type.CardCancelRequest
import com.sudoplatform.sudovirtualcards.graphql.type.CardProvisionRequest
import com.sudoplatform.sudovirtualcards.graphql.type.CardUpdateRequest
import com.sudoplatform.sudovirtualcards.graphql.type.CompleteFundingSourceRequest
import com.sudoplatform.sudovirtualcards.graphql.type.IdInput
import com.sudoplatform.sudovirtualcards.graphql.type.RefreshFundingSourceRequest
import com.sudoplatform.sudovirtualcards.graphql.type.SetupFundingSourceRequest
import com.sudoplatform.sudovirtualcards.keys.DeviceKeyManager
import com.sudoplatform.sudovirtualcards.keys.PublicKeyService
import com.sudoplatform.sudovirtualcards.logging.LogConstants
import com.sudoplatform.sudovirtualcards.signing.DefaultSigningService
import com.sudoplatform.sudovirtualcards.signing.Signature
import com.sudoplatform.sudovirtualcards.signing.SignatureData
import com.sudoplatform.sudovirtualcards.subscription.FundingSourceSubscriber
import com.sudoplatform.sudovirtualcards.subscription.SubscriptionService
import com.sudoplatform.sudovirtualcards.subscription.TransactionSubscriber
import com.sudoplatform.sudovirtualcards.types.CachePolicy
import com.sudoplatform.sudovirtualcards.types.CheckoutBankAccountProviderCompletionData
import com.sudoplatform.sudovirtualcards.types.CheckoutBankAccountProviderRefreshData
import com.sudoplatform.sudovirtualcards.types.CheckoutCardProviderCompletionData
import com.sudoplatform.sudovirtualcards.types.CreateKeysIfAbsentResult
import com.sudoplatform.sudovirtualcards.types.DateRange
import com.sudoplatform.sudovirtualcards.types.FundingSource
import com.sudoplatform.sudovirtualcards.types.FundingSourceClientConfiguration
import com.sudoplatform.sudovirtualcards.types.FundingSourceType
import com.sudoplatform.sudovirtualcards.types.FundingSourceTypes
import com.sudoplatform.sudovirtualcards.types.KeyResult
import com.sudoplatform.sudovirtualcards.types.ListAPIResult
import com.sudoplatform.sudovirtualcards.types.ListOutput
import com.sudoplatform.sudovirtualcards.types.PartialResult
import com.sudoplatform.sudovirtualcards.types.PartialTransaction
import com.sudoplatform.sudovirtualcards.types.PartialVirtualCard
import com.sudoplatform.sudovirtualcards.types.ProviderSetupData
import com.sudoplatform.sudovirtualcards.types.ProvisionalFundingSource
import com.sudoplatform.sudovirtualcards.types.ProvisionalVirtualCard
import com.sudoplatform.sudovirtualcards.types.SerializedCheckoutBankAccountCompletionData
import com.sudoplatform.sudovirtualcards.types.SerializedCheckoutBankAccountRefreshData
import com.sudoplatform.sudovirtualcards.types.SingleAPIResult
import com.sudoplatform.sudovirtualcards.types.StripeCardProviderCompletionData
import com.sudoplatform.sudovirtualcards.types.SortOrder
import com.sudoplatform.sudovirtualcards.types.Transaction
import com.sudoplatform.sudovirtualcards.types.VirtualCard
import com.sudoplatform.sudovirtualcards.types.VirtualCardsConfig
import com.sudoplatform.sudovirtualcards.types.inputs.CompleteFundingSourceInput
import com.sudoplatform.sudovirtualcards.types.inputs.ProvisionVirtualCardInput
import com.sudoplatform.sudovirtualcards.types.inputs.RefreshFundingSourceInput
import com.sudoplatform.sudovirtualcards.types.inputs.SetupFundingSourceInput
import com.sudoplatform.sudovirtualcards.types.inputs.UpdateVirtualCardInput
import com.sudoplatform.sudovirtualcards.types.transformers.DateRangeTransformer.toDateRangeInput
import com.sudoplatform.sudovirtualcards.types.transformers.FundingSourceTransformer
import com.sudoplatform.sudovirtualcards.types.transformers.KeyType
import com.sudoplatform.sudovirtualcards.types.transformers.ProviderDataTransformer
import com.sudoplatform.sudovirtualcards.types.transformers.TransactionTransformer
import com.sudoplatform.sudovirtualcards.types.transformers.Unsealer
import com.sudoplatform.sudovirtualcards.types.transformers.VirtualCardTransformer
import com.sudoplatform.sudovirtualcards.types.transformers.VirtualCardTransformer.toAddressInput
import com.sudoplatform.sudovirtualcards.types.transformers.VirtualCardTransformer.toMetadataInput
import com.sudoplatform.sudovirtualcards.types.transformers.VirtualCardsConfigTransformer
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.CancellationException

/**
 * Default implementation of the [SudoVirtualCardsClient] interface.
 *
 * @property context [Context] Application context.
 * @property appSyncClient [AWSAppSyncClient] GraphQL client used to make requests to AWS and call sudo virtual cards service API.
 * @property sudoUserClient [SudoUserClient] Used to determine if a user is signed in and gain access to the user owner ID.
 * @property logger [Logger] Errors and warnings will be logged here.
 * @property deviceKeyManager [DeviceKeyManager] Used for device management of key storage.
 * @property publicKeyService [PublicKeyService] Service that handles registering public keys with the backend.
 */
internal class DefaultSudoVirtualCardsClient(
    private val context: Context,
    private val appSyncClient: AWSAppSyncClient,
    private val sudoUserClient: SudoUserClient,
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
        private const val FUNDING_SOURCE_NOT_REFRESHED_MSG = "Failed to refresh funding source"
        private const val FUNDING_SOURCE_COMPLETION_DATA_INVALID_MSG = "Invalid completion data to perform funding source creation"
        private const val FUNDING_SOURCE_REQUIRES_USER_INTERACTION_MSG = "Funding source requires user interaction"
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
        private const val ERROR_FUNDING_SOURCE_REQUIRES_USER_INTERACTION = "FundingSourceRequiresUserInteractionError"
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
    private val version: String = "9.1.1"

    /** This manages the subscriptions to transaction updates and deletes */
    private val subscriptions = SubscriptionService(appSyncClient, deviceKeyManager, sudoUserClient, logger)

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
            val setupData = ProviderSetupData(
                applicationName = input.applicationData.applicationName,
            )
            val setupDataString = Gson().toJson(setupData)
            val encodedSetupData = Base64.encode(setupDataString.toByteArray()).toString(Charsets.UTF_8)
            val mutationInput = SetupFundingSourceRequest.builder()
                .type(input.type.toFundingSourceTypeInput(input.type))
                .currency(input.currency)
                .supportedProviders(input.supportedProviders)
                .setupData(encodedSetupData)
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
                return FundingSourceTransformer.toEntity(result.fragments().provisionalFundingSource())
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
            val provider = input.completionData.provider
            val type = input.completionData.type

            this.logger.info("Completing funding source: provider=$provider, type=$type")

            val encodedCompletionData: String
            if (input.completionData is StripeCardProviderCompletionData ||
                input.completionData is CheckoutCardProviderCompletionData
            ) {
                val encodedCompletionDataString = Gson().toJson(input.completionData)
                encodedCompletionData = Base64.encode(encodedCompletionDataString.toByteArray()).toString(Charsets.UTF_8)
            } else if (input.completionData is CheckoutBankAccountProviderCompletionData) {
                val publicKey = this.publicKeyService.getCurrentKey()
                    ?: throw SudoVirtualCardsClient.FundingSourceException.PublicKeyException(KEY_RETRIEVAL_ERROR_MSG)
                val signingService = DefaultSigningService(deviceKeyManager)
                TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
                val signedAt = Calendar.getInstance(TimeZone.getTimeZone("UTC")).time
                val authorizationTextSignatureData = SignatureData(
                    hash = input.completionData.authorizationText.hash,
                    hashAlgorithm = input.completionData.authorizationText.hashAlgorithm,
                    account = input.completionData.accountId,
                    signedAt = signedAt
                )
                val data = Gson().toJson(authorizationTextSignatureData)
                val signature = signingService.signString(data, publicKey.keyId, KeyType.PRIVATE_KEY)
                val authorizationTextSignature = Signature(
                    data,
                    algorithm = "RSASignatureSSAPKCS15SHA256",
                    keyId = publicKey.keyId,
                    signature = signature
                )
                val completionData = SerializedCheckoutBankAccountCompletionData(
                    provider = provider,
                    version = 1,
                    type = FundingSourceType.BANK_ACCOUNT,
                    keyId = publicKey.keyId,
                    publicToken = input.completionData.publicToken,
                    accountId = input.completionData.accountId,
                    institutionId = input.completionData.institutionId,
                    authorizationTextSignature = authorizationTextSignature
                )

                val completionDataString = Gson().toJson(completionData)
                encodedCompletionData = Base64.encode(completionDataString.toByteArray()).toString(Charsets.UTF_8)
            } else {
                throw SudoVirtualCardsClient.FundingSourceException.UnexpectedProviderException("Unexpected provider: $provider:$type")
            }

            val mutationInput = CompleteFundingSourceRequest.builder()
                .id(input.id)
                .completionData(encodedCompletionData)
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
                return FundingSourceTransformer.toEntityFromCompleteFundingSourceMutationResult(deviceKeyManager, result)
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

    override suspend fun refreshFundingSource(input: RefreshFundingSourceInput): FundingSource {
        try {
            val provider = input.refreshData.provider
            val type = input.refreshData.type
            val encodedRefreshData: String
            if (input.refreshData is CheckoutBankAccountProviderRefreshData) {
                val applicationName = input.applicationData.applicationName
                var authorizationTextSignature: Signature? = null
                val publicKey = this.publicKeyService.getCurrentKey()
                    ?: throw SudoVirtualCardsClient.FundingSourceException.PublicKeyException(KEY_RETRIEVAL_ERROR_MSG)
                if (input.refreshData.authorizationText != null && input.refreshData.accountId != null) {
                    val signingService = DefaultSigningService(deviceKeyManager)
                    val authorizationTextSignatureData = SignatureData(
                        hash = input.refreshData.authorizationText.hash,
                        hashAlgorithm = input.refreshData.authorizationText.hashAlgorithm,
                        account = input.refreshData.accountId,
                    )
                    val data = Gson().toJson(authorizationTextSignatureData)
                    val signature = signingService.signString(data, publicKey.keyId, KeyType.PRIVATE_KEY)
                    authorizationTextSignature = Signature(
                        data,
                        algorithm = "RSASignatureSSAPKCS15SHA256",
                        keyId = publicKey.keyId,
                        signature = signature
                    )
                }
                val refreshData = SerializedCheckoutBankAccountRefreshData(
                    provider = provider,
                    version = 1,
                    type = FundingSourceType.BANK_ACCOUNT,
                    applicationName = applicationName,
                    keyId = publicKey.keyId,
                    authorizationTextSignature = authorizationTextSignature
                )
                val refreshDataString = Gson().toJson(refreshData)
                encodedRefreshData = Base64.encode(refreshDataString.toByteArray()).toString(Charsets.UTF_8)
            } else {
                throw SudoVirtualCardsClient.FundingSourceException.UnexpectedProviderException("Unexpected provider: $provider:$type")
            }

            val mutationInput = RefreshFundingSourceRequest.builder()
                .id(input.id)
                .refreshData(encodedRefreshData)
                .language(input.language)
                .build()

            val mutation = RefreshFundingSourceMutation.builder()
                .input(mutationInput)
                .build()

            val mutationResponse = appSyncClient.mutate(mutation)
                .enqueue()

            if (mutationResponse.hasErrors()) {
                logger.error("errors = ${mutationResponse.errors()}")
                throw interpretFundingSourceError(mutationResponse.errors().first())
            }

            val result = mutationResponse.data()?.refreshFundingSource()
            result?.let {
                return FundingSourceTransformer.toEntityFromRefreshFundingSourceMutationResult(deviceKeyManager, result)
            }
            throw SudoVirtualCardsClient.FundingSourceException.RefreshFailedException(FUNDING_SOURCE_NOT_REFRESHED_MSG)
        } catch (e: Throwable) {
            logger.error("unexpected error $e")
            when (e) {
                is ApolloException -> throw SudoVirtualCardsClient.FundingSourceException.RefreshFailedException(cause = e)
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
            return FundingSourceTransformer.toEntityFromGetFundingSourceQueryResult(deviceKeyManager, result)
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
            val fundingSources = FundingSourceTransformer.toEntity(deviceKeyManager, result.items())
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
                return FundingSourceTransformer.toEntityFromCancelFundingSourceMutationResult(deviceKeyManager, result)
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
            val key = publicKeyService.getCurrentRegisteredKey()

            val mutationInput = CardProvisionRequest.builder()
                .clientRefId(input.clientRefId)
                .ownerProofs(input.ownershipProofs)
                .keyRingId(key.keyRingId)
                .fundingSourceId(input.fundingSourceId)
                .cardHolder(input.cardHolder)
                .alias(input.alias)
                .metadata(input.metadata.toMetadataInput(deviceKeyManager))
                .billingAddress(input.billingAddress.toAddressInput())
                .currency(input.currency)
                .build()
            val mutation = ProvisionVirtualCardMutation.builder()
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
                return VirtualCardTransformer.toEntity(deviceKeyManager, result.fragments().provisionalCard())
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
            return VirtualCardTransformer.toEntity(deviceKeyManager, result.fragments().provisionalCard())
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
            val key = publicKeyService.getCurrentKey()
                ?: throw SudoVirtualCardsClient.VirtualCardException.PublicKeyException(KEY_RETRIEVAL_ERROR_MSG)

            val query = GetCardQuery.builder()
                .id(id)
                .keyId(key.keyId)
                .build()

            val queryResponse = appSyncClient.query(query)
                .responseFetcher(cachePolicy.toResponseFetcher(cachePolicy))
                .enqueueFirst()

            if (queryResponse.hasErrors()) {
                logger.error("errors = ${queryResponse.errors()}")
                throw interpretVirtualCardError(queryResponse.errors().first())
            }

            val result = queryResponse.data()?.card ?: return null
            return VirtualCardTransformer.toEntity(deviceKeyManager, result.fragments().sealedCardWithLastTransaction())
        } catch (e: Throwable) {
            logger.error("unexpected error $e")
            when (e) {
                is ApolloException -> throw SudoVirtualCardsClient.VirtualCardException.FailedException(cause = e)
                else -> throw interpretVirtualCardException(e)
            }
        }
    }

    @Throws(SudoVirtualCardsClient.VirtualCardException::class)
    override suspend fun getVirtualCardsConfig(cachePolicy: CachePolicy): VirtualCardsConfig? {
        try {
            val query = GetVirtualCardsConfigQuery.builder().build()

            val queryResponse = appSyncClient.query(query)
                .responseFetcher(cachePolicy.toResponseFetcher(cachePolicy))
                .enqueueFirst()

            if (queryResponse.hasErrors()) {
                logger.error("errors = ${queryResponse.errors()}")
                throw interpretVirtualCardError(queryResponse.errors().first())
            }

            val result = queryResponse.data()?.virtualCardsConfig ?: return null
            return VirtualCardsConfigTransformer.toEntityFromGetVirtualCardsConfigQueryResult(result.fragments().virtualCardsConfig())
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
                    val unsealedCard = VirtualCardTransformer.toEntity(
                        deviceKeyManager,
                        sealedCard.fragments().sealedCardWithLastTransaction()
                    )
                    success.add(unsealedCard)
                } catch (e: Exception) {
                    val partialCard = VirtualCardTransformer.toPartialEntity(sealedCard.fragments().sealedCardWithLastTransaction())
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
            val keyPairResult = publicKeyService.getCurrentKey()
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

            val mutation = UpdateVirtualCardMutation.builder()
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
                    val unsealedUpdatedCard = VirtualCardTransformer.toEntity(
                        deviceKeyManager,
                        updatedCard.fragments().sealedCardWithLastTransaction()
                    )
                    return SingleAPIResult.Success(unsealedUpdatedCard)
                } catch (e: Exception) {
                    val partialUpdatedCard = VirtualCardTransformer.toPartialEntity(updatedCard.fragments().sealedCardWithLastTransaction())
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
            val key = publicKeyService.getCurrentKey()
                ?: throw SudoVirtualCardsClient.VirtualCardException.PublicKeyException(KEY_RETRIEVAL_ERROR_MSG)
            val keyId = key.keyId

            val mutationInput = CardCancelRequest.builder()
                .id(id)
                .keyId(keyId)
                .build()
            val mutation = CancelVirtualCardMutation.builder()
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
                    val unsealedCancelledCard = VirtualCardTransformer.toEntity(
                        deviceKeyManager,
                        cancelledCard.fragments().sealedCardWithLastTransaction()
                    )
                    return SingleAPIResult.Success(unsealedCancelledCard)
                } catch (e: Exception) {
                    val partialCancelledCard = VirtualCardTransformer.toPartialEntity(
                        cancelledCard.fragments().sealedCardWithLastTransaction()
                    )
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
            val keyPairResult = publicKeyService.getCurrentKey()
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
            return TransactionTransformer.toEntity(deviceKeyManager, result.fragments().sealedTransaction())
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

            val queryResult = queryResponse.data()?.listTransactionsByCardId2()
            val sealedTransactions = queryResult?.items() ?: emptyList()
            val newNextToken = queryResult?.nextToken()

            val success: MutableList<Transaction> = mutableListOf()
            val partials: MutableList<PartialResult<PartialTransaction>> = mutableListOf()
            for (sealedTransaction in sealedTransactions) {
                try {
                    val unsealerTransaction =
                        TransactionTransformer.toEntity(deviceKeyManager, sealedTransaction.fragments().sealedTransaction())
                    success.add(unsealerTransaction)
                } catch (e: Exception) {
                    val partialTransaction =
                        TransactionTransformer.toPartialEntity(sealedTransaction.fragments().sealedTransaction())
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

    @Throws(SudoVirtualCardsClient.TransactionException::class)
    override suspend fun listTransactions(
        limit: Int,
        nextToken: String?,
        cachePolicy: CachePolicy,
        dateRange: DateRange?,
        sortOrder: SortOrder
    ): ListAPIResult<Transaction, PartialTransaction> {
        try {
            val query = ListTransactionsQuery.builder()
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

            val queryResult = queryResponse.data()?.listTransactions2()
            val sealedTransactions = queryResult?.items() ?: emptyList()
            val newNextToken = queryResult?.nextToken()

            val success: MutableList<Transaction> = mutableListOf()
            val partials: MutableList<PartialResult<PartialTransaction>> = mutableListOf()
            for (sealedTransaction in sealedTransactions) {
                try {
                    val unsealerTransaction =
                        TransactionTransformer.toEntity(deviceKeyManager, sealedTransaction.fragments().sealedTransaction())
                    success.add(unsealerTransaction)
                } catch (e: Exception) {
                    val partialTransaction =
                        TransactionTransformer.toPartialEntity(sealedTransaction.fragments().sealedTransaction())
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
        subscriptions.subscribeTransactions(id, subscriber)
    }

    override suspend fun unsubscribeFromTransactions(id: String) {
        subscriptions.unsubscribeTransactions(id)
    }

    override suspend fun unsubscribeAllFromTransactions() {
        subscriptions.unsubscribeAllTransactions()
    }

    @Deprecated("Use unsubscribeAllFromTransactions instead", ReplaceWith("unsubscribeAllFromTransactions"))
    override suspend fun unsubscribeAll() {
        this.unsubscribeAllFromTransactions()
    }

    override suspend fun subscribeToFundingSources(id: String, subscriber: FundingSourceSubscriber) {
        subscriptions.subscribeFundingSources(id, subscriber)
    }

    override suspend fun unsubscribeFromFundingSources(id: String) {
        subscriptions.unsubscribeFundingSources(id)
    }

    override suspend fun unsubscribeAllFromFundingSources() {
        subscriptions.unsubscribeAllFundingSources()
    }

    override fun close() {
        subscriptions.close()
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
            val keyWithKeyRingId = publicKeyService.getCurrentRegisteredKey()
            return KeyResult(keyWithKeyRingId.created, keyWithKeyRingId.publicKey.keyId)
        } catch (e: Throwable) {
            logger.error("unexpected error $e")
            throw e
        }
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
        if (error.contains(ERROR_FUNDING_SOURCE_REQUIRES_USER_INTERACTION)) {
            val errorInfo = e.customAttributes()["errorInfo"]
            return try {
                val interactionData = SudoVirtualCardsClient.FundingSourceInteractionData.decode(errorInfo)
                SudoVirtualCardsClient.FundingSourceException.FundingSourceRequiresUserInteractionException(
                    FUNDING_SOURCE_REQUIRES_USER_INTERACTION_MSG,
                    ProviderDataTransformer.toUserInteractionData(interactionData.provisioningData)
                )
            } catch (e: Throwable) {
                SudoVirtualCardsClient.FundingSourceException.FailedException(
                    message = "Invalid user interaction data during funding source setup",
                    cause = e
                )
            }
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
