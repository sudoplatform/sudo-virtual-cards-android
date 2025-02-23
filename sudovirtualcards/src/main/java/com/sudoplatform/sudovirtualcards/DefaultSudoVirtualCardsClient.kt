/*
 * Copyright © 2024 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards

import android.content.Context
import com.amazonaws.util.Base64
import com.amplifyframework.api.graphql.GraphQLResponse
import com.apollographql.apollo3.api.Optional
import com.google.gson.Gson
import com.sudoplatform.sudologging.AndroidUtilsLogDriver
import com.sudoplatform.sudologging.LogLevel
import com.sudoplatform.sudologging.Logger
import com.sudoplatform.sudonotification.types.NotificationMetaData
import com.sudoplatform.sudonotification.types.NotificationSchemaEntry
import com.sudoplatform.sudouser.SudoUserClient
import com.sudoplatform.sudouser.amplify.GraphQLClient
import com.sudoplatform.sudouser.exceptions.GRAPHQL_ERROR_TYPE
import com.sudoplatform.sudouser.exceptions.HTTP_STATUS_CODE_KEY
import com.sudoplatform.sudovirtualcards.SudoVirtualCardsClient.FundingSourceException
import com.sudoplatform.sudovirtualcards.SudoVirtualCardsClient.VirtualCardException
import com.sudoplatform.sudovirtualcards.graphql.CancelFundingSourceMutation
import com.sudoplatform.sudovirtualcards.graphql.CancelProvisionalFundingSourceMutation
import com.sudoplatform.sudovirtualcards.graphql.CancelVirtualCardMutation
import com.sudoplatform.sudovirtualcards.graphql.CompleteFundingSourceMutation
import com.sudoplatform.sudovirtualcards.graphql.GetCardQuery
import com.sudoplatform.sudovirtualcards.graphql.GetFundingSourceQuery
import com.sudoplatform.sudovirtualcards.graphql.GetProvisionalCardQuery
import com.sudoplatform.sudovirtualcards.graphql.GetTransactionQuery
import com.sudoplatform.sudovirtualcards.graphql.GetVirtualCardsConfigQuery
import com.sudoplatform.sudovirtualcards.graphql.ListCardsQuery
import com.sudoplatform.sudovirtualcards.graphql.ListFundingSourcesQuery
import com.sudoplatform.sudovirtualcards.graphql.ListProvisionalFundingSourcesQuery
import com.sudoplatform.sudovirtualcards.graphql.ListTransactionsByCardIdAndTypeQuery
import com.sudoplatform.sudovirtualcards.graphql.ListTransactionsByCardIdQuery
import com.sudoplatform.sudovirtualcards.graphql.ListTransactionsQuery
import com.sudoplatform.sudovirtualcards.graphql.ProvisionVirtualCardMutation
import com.sudoplatform.sudovirtualcards.graphql.RefreshFundingSourceMutation
import com.sudoplatform.sudovirtualcards.graphql.ReviewUnfundedFundingSourceMutation
import com.sudoplatform.sudovirtualcards.graphql.SandboxGetPlaidDataQuery
import com.sudoplatform.sudovirtualcards.graphql.SandboxSetFundingSourceToRequireRefreshMutation
import com.sudoplatform.sudovirtualcards.graphql.SetupFundingSourceMutation
import com.sudoplatform.sudovirtualcards.graphql.UpdateVirtualCardMutation
import com.sudoplatform.sudovirtualcards.graphql.type.CardCancelRequest
import com.sudoplatform.sudovirtualcards.graphql.type.CardProvisionRequest
import com.sudoplatform.sudovirtualcards.graphql.type.CardUpdateRequest
import com.sudoplatform.sudovirtualcards.graphql.type.CompleteFundingSourceRequest
import com.sudoplatform.sudovirtualcards.graphql.type.IdInput
import com.sudoplatform.sudovirtualcards.graphql.type.RefreshFundingSourceRequest
import com.sudoplatform.sudovirtualcards.graphql.type.SandboxGetPlaidDataRequest
import com.sudoplatform.sudovirtualcards.graphql.type.SandboxSetFundingSourceToRequireRefreshRequest
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
import com.sudoplatform.sudovirtualcards.types.BankAccountFundingSource
import com.sudoplatform.sudovirtualcards.types.CheckoutBankAccountProviderCompletionData
import com.sudoplatform.sudovirtualcards.types.CheckoutBankAccountProviderRefreshData
import com.sudoplatform.sudovirtualcards.types.CreateKeysIfAbsentResult
import com.sudoplatform.sudovirtualcards.types.DateRange
import com.sudoplatform.sudovirtualcards.types.FundingSource
import com.sudoplatform.sudovirtualcards.types.FundingSourceType
import com.sudoplatform.sudovirtualcards.types.KeyResult
import com.sudoplatform.sudovirtualcards.types.ListAPIResult
import com.sudoplatform.sudovirtualcards.types.ListOutput
import com.sudoplatform.sudovirtualcards.types.PartialResult
import com.sudoplatform.sudovirtualcards.types.PartialTransaction
import com.sudoplatform.sudovirtualcards.types.PartialVirtualCard
import com.sudoplatform.sudovirtualcards.types.PlaidAccountMetadata
import com.sudoplatform.sudovirtualcards.types.ProviderSetupData
import com.sudoplatform.sudovirtualcards.types.ProvisionalFundingSource
import com.sudoplatform.sudovirtualcards.types.ProvisionalVirtualCard
import com.sudoplatform.sudovirtualcards.types.SandboxPlaidData
import com.sudoplatform.sudovirtualcards.types.SerializedCheckoutBankAccountCompletionData
import com.sudoplatform.sudovirtualcards.types.SerializedCheckoutBankAccountRefreshData
import com.sudoplatform.sudovirtualcards.types.SingleAPIResult
import com.sudoplatform.sudovirtualcards.types.SortOrder
import com.sudoplatform.sudovirtualcards.types.StripeCardProviderCompletionData
import com.sudoplatform.sudovirtualcards.types.Transaction
import com.sudoplatform.sudovirtualcards.types.TransactionType
import com.sudoplatform.sudovirtualcards.types.VirtualCard
import com.sudoplatform.sudovirtualcards.types.VirtualCardsConfig
import com.sudoplatform.sudovirtualcards.types.inputs.CompleteFundingSourceInput
import com.sudoplatform.sudovirtualcards.types.inputs.FundingSourceFilterInput
import com.sudoplatform.sudovirtualcards.types.inputs.ProvisionVirtualCardInput
import com.sudoplatform.sudovirtualcards.types.inputs.ProvisionalFundingSourceFilterInput
import com.sudoplatform.sudovirtualcards.types.inputs.RefreshFundingSourceInput
import com.sudoplatform.sudovirtualcards.types.inputs.SetupFundingSourceInput
import com.sudoplatform.sudovirtualcards.types.inputs.UpdateVirtualCardInput
import com.sudoplatform.sudovirtualcards.types.inputs.VirtualCardFilterInput
import com.sudoplatform.sudovirtualcards.types.transformers.DateRangeTransformer.toDateRangeInput
import com.sudoplatform.sudovirtualcards.types.transformers.FundingSourceTransformer
import com.sudoplatform.sudovirtualcards.types.transformers.FundingSourceTransformer.toFundingSourceFilterInput
import com.sudoplatform.sudovirtualcards.types.transformers.FundingSourceTransformer.toProvisionalFundingSourceFilterInput
import com.sudoplatform.sudovirtualcards.types.transformers.KeyType
import com.sudoplatform.sudovirtualcards.types.transformers.ProviderDataTransformer
import com.sudoplatform.sudovirtualcards.types.transformers.TransactionTransformer
import com.sudoplatform.sudovirtualcards.types.transformers.TransactionTypeTransformer.toTransactionType
import com.sudoplatform.sudovirtualcards.types.transformers.Unsealer
import com.sudoplatform.sudovirtualcards.types.transformers.VirtualCardTransformer
import com.sudoplatform.sudovirtualcards.types.transformers.VirtualCardTransformer.toAddressInput
import com.sudoplatform.sudovirtualcards.types.transformers.VirtualCardTransformer.toMetadataInput
import com.sudoplatform.sudovirtualcards.types.transformers.VirtualCardTransformer.toVirtualCardFilterInput
import com.sudoplatform.sudovirtualcards.types.transformers.VirtualCardsConfigTransformer
import java.net.HttpURLConnection
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.CancellationException

/**
 * Default implementation of the [SudoVirtualCardsClient] interface.
 *
 * @property context [Context] Application context.
 * @property graphQLClient [GraphQLClient] GraphQL client used to make requests to AWS and call sudo virtual cards service API.
 * @property sudoUserClient [SudoUserClient] Used to determine if a user is signed in and gain access to the user owner ID.
 * @property logger [Logger] Errors and warnings will be logged here.
 * @property deviceKeyManager [DeviceKeyManager] Used for device management of key storage.
 * @property publicKeyService [PublicKeyService] Service that handles registering public keys with the backend.
 */
internal class DefaultSudoVirtualCardsClient(
    private val context: Context,
    private val graphQLClient: GraphQLClient,
    private val sudoUserClient: SudoUserClient,
    private val logger: Logger = Logger(LogConstants.SUDOLOG_TAG, AndroidUtilsLogDriver(LogLevel.INFO)),
    private val deviceKeyManager: DeviceKeyManager,
    private val publicKeyService: PublicKeyService,
    private val notificationHandler: SudoVirtualCardsNotificationHandler? = null,
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
        private const val UNSUPPORTED_TRANSACTION_TYPE_MSG = "Transaction type is not supported"
        private const val INVALID_ARGUMENT_ERROR_MSG = "Invalid input"
        private const val KEY_ARCHIVE_ERROR_MSG = "Unable to perform key archive operation"

        /** Errors returned from the service */
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
     * Checksums for each file are generated and are used to create a checksum that is used when
     * publishing to maven central. In order to retry a failed publish without needing to change any
     * functionality, we need a way to generate a different checksum for the source code. We can
     * change the value of this property which will generate a different checksum for publishing
     * and allow us to retry. The value of `version` doesn't need to be kept up-to-date with the
     * version of the code.
     */
    private val version: String = "16.0.0"

    /** This manages the subscriptions to transaction updates and deletes */
    private val subscriptions = SubscriptionService(graphQLClient, deviceKeyManager, sudoUserClient, logger)

    @Throws(VirtualCardException::class)
    override suspend fun createKeysIfAbsent(): CreateKeysIfAbsentResult {
        try {
            val symmetricKeyResult = createSymmetricKeysIfAbsent()
            val keyPairResult = createAndRegisterKeyPairIfAbsent()
            return CreateKeysIfAbsentResult(symmetricKey = symmetricKeyResult, keyPair = keyPairResult)
        } catch (e: Throwable) {
            logger.error("unexpected error $e")
            when (e) {
                is CancellationException,
                is PublicKeyService.PublicKeyServiceException,
                ->
                    throw VirtualCardException.PublicKeyException(KEY_RETRIEVAL_ERROR_MSG, e)
                is VirtualCardException -> throw e
                else -> throw VirtualCardException.UnknownException(e)
            }
        }
    }

    @Throws(FundingSourceException::class)
    override suspend fun setupFundingSource(input: SetupFundingSourceInput): ProvisionalFundingSource {
        try {
            val setupData = ProviderSetupData(
                applicationName = input.applicationData.applicationName,
            )
            val setupDataString = Gson().toJson(setupData)
            val encodedSetupData = Base64.encode(setupDataString.toByteArray()).toString(Charsets.UTF_8)
            val mutationInput = SetupFundingSourceRequest(
                currency = input.currency,
                language = Optional.Absent,
                setupData = Optional.presentIfNotNull(encodedSetupData),
                supportedProviders = Optional.presentIfNotNull(input.supportedProviders),
                type = input.type.toFundingSourceTypeInput(input.type),
            )

            val mutationResponse = graphQLClient.mutate<SetupFundingSourceMutation, SetupFundingSourceMutation.Data>(
                SetupFundingSourceMutation.OPERATION_DOCUMENT,
                mapOf("input" to mutationInput),
            )

            if (mutationResponse.hasErrors()) {
                logger.error("errors = ${mutationResponse.errors}")
                throw interpretFundingSourceError(
                    mutationResponse.errors.first(),
                    FundingSourceException.SetupFailedException(mutationResponse.errors.first().toString()),
                )
            }

            val result = mutationResponse.data?.setupFundingSource
            result?.let {
                return FundingSourceTransformer.toEntity(result.provisionalFundingSource)
            }
            throw FundingSourceException.SetupFailedException(FUNDING_SOURCE_NOT_SETUP_MSG)
        } catch (e: Throwable) {
            logger.error("unexpected error $e")
            throw interpretFundingSourceException(e)
        }
    }

    @Throws(FundingSourceException::class)
    override suspend fun listProvisionalFundingSources(
        filter: ProvisionalFundingSourceFilterInput?,
        sortOrder: SortOrder?,
        limit: Int,
        nextToken: String?,
    ): ListOutput<ProvisionalFundingSource> {
        try {
            val queryResponse = graphQLClient.query<ListProvisionalFundingSourcesQuery, ListProvisionalFundingSourcesQuery.Data>(
                ListProvisionalFundingSourcesQuery.OPERATION_DOCUMENT,
                mapOf(
                    "filter" to Optional.presentIfNotNull(filter.toProvisionalFundingSourceFilterInput()),
                    "sortOrder" to Optional.presentIfNotNull(sortOrder?.toSortOrderInput(sortOrder)),
                    "limit" to Optional.presentIfNotNull(limit),
                    "nextToken" to Optional.presentIfNotNull(nextToken),
                ),
            )

            if (queryResponse.hasErrors()) {
                logger.error("errors = ${queryResponse.errors}")
                throw interpretFundingSourceError(queryResponse.errors.first())
            }

            val result = queryResponse.data?.listProvisionalFundingSources ?: return ListOutput(emptyList(), null)
            val fundingSources = FundingSourceTransformer.toEntity(result.items)
            return ListOutput(fundingSources, result.nextToken)
        } catch (e: Throwable) {
            logger.error("unexpected error $e")
            throw interpretFundingSourceException(e)
        }
    }

    @Throws(FundingSourceException::class)
    override suspend fun completeFundingSource(input: CompleteFundingSourceInput): FundingSource {
        try {
            val provider = input.completionData.provider
            val type = input.completionData.type

            this.logger.info("Completing funding source: provider=$provider, type=$type")

            val encodedCompletionData: String
            when (input.completionData) {
                is StripeCardProviderCompletionData -> {
                    val encodedCompletionDataString = Gson().toJson(input.completionData)
                    encodedCompletionData = Base64.encode(encodedCompletionDataString.toByteArray()).toString(Charsets.UTF_8)
                }

                is CheckoutBankAccountProviderCompletionData -> {
                    val publicKey = this.publicKeyService.getCurrentKey()
                        ?: throw FundingSourceException.PublicKeyException(KEY_RETRIEVAL_ERROR_MSG)
                    val signingService = DefaultSigningService(deviceKeyManager)
                    TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
                    val signedAt = Calendar.getInstance(TimeZone.getTimeZone("UTC")).time
                    val authorizationTextSignatureData = SignatureData(
                        hash = input.completionData.authorizationText.hash,
                        hashAlgorithm = input.completionData.authorizationText.hashAlgorithm,
                        account = input.completionData.accountId,
                        signedAt = signedAt,
                    )
                    val data = Gson().toJson(authorizationTextSignatureData)
                    val signature = signingService.signString(data, publicKey.keyId, KeyType.PRIVATE_KEY)
                    val authorizationTextSignature = Signature(
                        data,
                        algorithm = "RSASignatureSSAPKCS15SHA256",
                        keyId = publicKey.keyId,
                        signature = signature,
                    )
                    val completionData = SerializedCheckoutBankAccountCompletionData(
                        provider = provider,
                        version = 1,
                        type = FundingSourceType.BANK_ACCOUNT,
                        keyId = publicKey.keyId,
                        publicToken = input.completionData.publicToken,
                        accountId = input.completionData.accountId,
                        institutionId = input.completionData.institutionId,
                        authorizationTextSignature = authorizationTextSignature,
                    )

                    val completionDataString = Gson().toJson(completionData)
                    encodedCompletionData = Base64.encode(completionDataString.toByteArray()).toString(Charsets.UTF_8)
                }
            }

            val mutationInput = CompleteFundingSourceRequest(
                id = input.id,
                completionData = encodedCompletionData,
                updateCardFundingSource = Optional.presentIfNotNull(input.updateCardFundingSource),
            )

            val mutationResponse = graphQLClient.mutate<CompleteFundingSourceMutation, CompleteFundingSourceMutation.Data>(
                CompleteFundingSourceMutation.OPERATION_DOCUMENT,
                mapOf("input" to mutationInput),
            )

            if (mutationResponse.hasErrors()) {
                logger.error("errors = ${mutationResponse.errors}")
                throw interpretFundingSourceError(
                    mutationResponse.errors.first(),
                    FundingSourceException.CompletionFailedException(mutationResponse.errors.first().toString()),
                )
            }

            val result = mutationResponse.data?.completeFundingSource
            result?.let {
                return FundingSourceTransformer.toEntityFromCompleteFundingSourceMutationResult(deviceKeyManager, result)
            }
            throw FundingSourceException.CompletionFailedException(FUNDING_SOURCE_NOT_COMPLETE_MSG)
        } catch (e: Throwable) {
            logger.error("unexpected error $e")
            throw interpretFundingSourceException(e)
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
                    ?: throw FundingSourceException.PublicKeyException(KEY_RETRIEVAL_ERROR_MSG)
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
                        signature = signature,
                    )
                }
                val refreshData = SerializedCheckoutBankAccountRefreshData(
                    provider = provider,
                    version = 1,
                    type = FundingSourceType.BANK_ACCOUNT,
                    applicationName = applicationName,
                    keyId = publicKey.keyId,
                    authorizationTextSignature = authorizationTextSignature,
                )
                val refreshDataString = Gson().toJson(refreshData)
                encodedRefreshData = Base64.encode(refreshDataString.toByteArray()).toString(Charsets.UTF_8)
            } else {
                throw FundingSourceException.UnexpectedProviderException("Unexpected provider: $provider:$type")
            }

            val mutationInput = RefreshFundingSourceRequest(
                id = input.id,
                language = Optional.presentIfNotNull(input.language),
                refreshData = encodedRefreshData,
            )

            val mutationResponse = graphQLClient.mutate<RefreshFundingSourceMutation, RefreshFundingSourceMutation.Data>(
                RefreshFundingSourceMutation.OPERATION_DOCUMENT,
                mapOf("input" to mutationInput),
            )

            if (mutationResponse.hasErrors()) {
                logger.error("errors = ${mutationResponse.errors}")
                throw interpretFundingSourceError(
                    mutationResponse.errors.first(),
                    FundingSourceException.RefreshFailedException(mutationResponse.errors.first().toString()),
                )
            }
            val result = mutationResponse.data?.refreshFundingSource
            result?.let {
                return FundingSourceTransformer.toEntityFromRefreshFundingSourceMutationResult(deviceKeyManager, result)
            }
            throw FundingSourceException.RefreshFailedException(FUNDING_SOURCE_NOT_REFRESHED_MSG)
        } catch (e: Throwable) {
            logger.error("unexpected error $e")
            throw interpretFundingSourceException(e)
        }
    }

    @Throws(FundingSourceException::class)
    override suspend fun getFundingSource(id: String): FundingSource? {
        try {
            val queryResponse = graphQLClient.query<GetFundingSourceQuery, GetFundingSourceQuery.Data>(
                GetFundingSourceQuery.OPERATION_DOCUMENT,
                mapOf("id" to id),
            )

            if (queryResponse.hasErrors()) {
                logger.error("errors = ${queryResponse.errors}")
                throw interpretFundingSourceError(queryResponse.errors.first())
            }

            val result = queryResponse.data?.getFundingSource ?: return null
            return FundingSourceTransformer.toEntityFromGetFundingSourceQueryResult(deviceKeyManager, result)
        } catch (e: Throwable) {
            logger.error("unexpected error $e")
            throw interpretFundingSourceException(e)
        }
    }

    @Throws(FundingSourceException::class)
    override suspend fun listFundingSources(
        filter: FundingSourceFilterInput?,
        sortOrder: SortOrder?,
        limit: Int,
        nextToken: String?,
    ): ListOutput<FundingSource> {
        try {
            val queryResponse = graphQLClient.query<ListFundingSourcesQuery, ListFundingSourcesQuery.Data>(
                ListFundingSourcesQuery.OPERATION_DOCUMENT,
                mapOf(
                    "filter" to Optional.presentIfNotNull(filter.toFundingSourceFilterInput()),
                    "sortOrder" to Optional.presentIfNotNull(sortOrder?.toSortOrderInput(sortOrder)),
                    "limit" to Optional.presentIfNotNull(limit),
                    "nextToken" to Optional.presentIfNotNull(nextToken),
                ),
            )

            if (queryResponse.hasErrors()) {
                logger.error("errors = ${queryResponse.errors}")
                throw interpretFundingSourceError(queryResponse.errors.first())
            }

            val result = queryResponse.data?.listFundingSources ?: return ListOutput(emptyList(), null)
            val fundingSources = FundingSourceTransformer.toEntity(deviceKeyManager, result.items)
            return ListOutput(fundingSources, result.nextToken)
        } catch (e: Throwable) {
            logger.error("unexpected error $e")
            throw interpretFundingSourceException(e)
        }
    }

    @Throws(FundingSourceException::class)
    override suspend fun cancelFundingSource(id: String): FundingSource {
        try {
            val mutationInput = IdInput(id = id)

            val mutationResponse = graphQLClient.mutate<CancelFundingSourceMutation, CancelFundingSourceMutation.Data>(
                CancelFundingSourceMutation.OPERATION_DOCUMENT,
                mapOf("input" to mutationInput),
            )

            if (mutationResponse.hasErrors()) {
                logger.error("errors = ${mutationResponse.errors}")
                throw interpretFundingSourceError(
                    mutationResponse.errors.first(),
                    FundingSourceException.CancelFailedException(mutationResponse.errors.first().toString()),
                )
            }

            val result = mutationResponse.data?.cancelFundingSource
            result?.let {
                return FundingSourceTransformer.toEntityFromCancelFundingSourceMutationResult(deviceKeyManager, result)
            }
            throw FundingSourceException.CancelFailedException(NO_RESULT_ERROR_MSG)
        } catch (e: Throwable) {
            logger.error("unexpected error $e")
            throw interpretFundingSourceException(e)
        }
    }

    @Throws(FundingSourceException::class)
    override suspend fun cancelProvisionalFundingSource(id: String): ProvisionalFundingSource {
        try {
            val mutationInput = IdInput(id = id)

            val mutationResponse = graphQLClient.mutate<
                CancelProvisionalFundingSourceMutation,
                CancelProvisionalFundingSourceMutation.Data,
                >(
                CancelProvisionalFundingSourceMutation.OPERATION_DOCUMENT,
                mapOf("input" to mutationInput),
            )

            if (mutationResponse.hasErrors()) {
                logger.error("errors = ${mutationResponse.errors}")
                throw interpretFundingSourceError(
                    mutationResponse.errors.first(),
                    FundingSourceException.CancelFailedException(mutationResponse.errors.first().toString()),
                )
            }

            val result = mutationResponse.data?.cancelProvisionalFundingSource
            result?.let {
                return FundingSourceTransformer.toEntityFromCancelProvisionalFundingSourceMutationResult(result)
            }
            throw FundingSourceException.CancelFailedException(NO_RESULT_ERROR_MSG)
        } catch (e: Throwable) {
            logger.error("unexpected error $e")
            throw interpretFundingSourceException(e)
        }
    }

    @Throws(FundingSourceException::class)
    override suspend fun reviewUnfundedFundingSource(id: String): FundingSource {
        try {
            val mutationInput = IdInput(id = id)

            val mutationResponse = graphQLClient.mutate<ReviewUnfundedFundingSourceMutation, ReviewUnfundedFundingSourceMutation.Data>(
                ReviewUnfundedFundingSourceMutation.OPERATION_DOCUMENT,
                mapOf("input" to mutationInput),
            )

            if (mutationResponse.hasErrors()) {
                logger.error("errors = ${mutationResponse.errors}")
                throw interpretFundingSourceError(
                    mutationResponse.errors.first(),
                    FundingSourceException.ReviewFailedException(mutationResponse.errors.first().toString()),
                )
            }

            val result = mutationResponse.data?.reviewUnfundedFundingSource
            result?.let {
                return FundingSourceTransformer.toEntityFromReviewUnfundedFundingSourceMutationResult(deviceKeyManager, result)
            }
            throw FundingSourceException.ReviewFailedException(NO_RESULT_ERROR_MSG)
        } catch (e: Throwable) {
            logger.error("unexpected error $e")
            throw interpretFundingSourceException(e)
        }
    }

    @Throws(VirtualCardException::class)
    override suspend fun provisionVirtualCard(input: ProvisionVirtualCardInput): ProvisionalVirtualCard {
        try {
            val key = publicKeyService.getCurrentRegisteredKey()

            val mutationInput = CardProvisionRequest(
                alias = Optional.presentIfNotNull(input.alias),
                billingAddress = Optional.presentIfNotNull(input.billingAddress.toAddressInput()),
                cardHolder = input.cardHolder,
                clientRefId = input.clientRefId,
                currency = input.currency,
                fundingSourceId = input.fundingSourceId,
                keyRingId = key.keyRingId,
                metadata = Optional.presentIfNotNull(input.metadata.toMetadataInput(deviceKeyManager)),
                ownerProofs = input.ownershipProofs,
            )

            val mutationResponse = graphQLClient.mutate<ProvisionVirtualCardMutation, ProvisionVirtualCardMutation.Data>(
                ProvisionVirtualCardMutation.OPERATION_DOCUMENT,
                mapOf("input" to mutationInput),
            )

            if (mutationResponse.hasErrors()) {
                logger.error("errors = ${mutationResponse.errors}")
                throw interpretVirtualCardError(
                    mutationResponse.errors.first(),
                    VirtualCardException.ProvisionFailedException(mutationResponse.errors.first().toString()),
                )
            }

            val result = mutationResponse.data?.cardProvision
            result?.let {
                return VirtualCardTransformer.toEntity(deviceKeyManager, result.provisionalCard)
            }
            throw VirtualCardException.ProvisionFailedException(NO_RESULT_ERROR_MSG)
        } catch (e: Throwable) {
            logger.error("unexpected error $e")
            throw interpretVirtualCardException(e)
        }
    }

    @Throws(VirtualCardException::class)
    override suspend fun getProvisionalCard(id: String): ProvisionalVirtualCard? {
        try {
            val queryResponse = graphQLClient.query<GetProvisionalCardQuery, GetProvisionalCardQuery.Data>(
                GetProvisionalCardQuery.OPERATION_DOCUMENT,
                mapOf("id" to id),
            )

            if (queryResponse.hasErrors()) {
                logger.error("errors = ${queryResponse.errors}")
                throw interpretVirtualCardError(queryResponse.errors.first())
            }

            val result = queryResponse.data?.getProvisionalCard ?: return null
            return VirtualCardTransformer.toEntity(deviceKeyManager, result.provisionalCard)
        } catch (e: Throwable) {
            logger.error("unexpected error $e")
            throw interpretVirtualCardException(e)
        }
    }

    @Throws(VirtualCardException::class)
    override suspend fun getVirtualCard(id: String): VirtualCard? {
        try {
            val key = publicKeyService.getCurrentKey()
                ?: throw VirtualCardException.PublicKeyException(KEY_RETRIEVAL_ERROR_MSG)

            val queryResponse = graphQLClient.query<GetCardQuery, GetCardQuery.Data>(
                GetCardQuery.OPERATION_DOCUMENT,
                mapOf(
                    "id" to id,
                    "keyId" to Optional.presentIfNotNull(key.keyId),
                ),
            )

            if (queryResponse.hasErrors()) {
                logger.error("errors = ${queryResponse.errors}")
                throw interpretVirtualCardError(queryResponse.errors.first())
            }

            val result = queryResponse.data?.getCard ?: return null
            return VirtualCardTransformer.toEntity(deviceKeyManager, result.sealedCardWithLastTransaction)
        } catch (e: Throwable) {
            logger.error("unexpected error $e")
            throw interpretVirtualCardException(e)
        }
    }

    @Throws(VirtualCardException::class)
    override suspend fun getVirtualCardsConfig(): VirtualCardsConfig? {
        try {
            val queryResponse = graphQLClient.query<GetVirtualCardsConfigQuery, GetVirtualCardsConfigQuery.Data>(
                GetVirtualCardsConfigQuery.OPERATION_DOCUMENT,
                emptyMap(),
            )

            if (queryResponse.hasErrors()) {
                logger.error("errors = ${queryResponse.errors}")
                throw interpretVirtualCardError(queryResponse.errors.first())
            }

            val result = queryResponse.data?.getVirtualCardsConfig ?: return null
            return VirtualCardsConfigTransformer.toEntityFromGetVirtualCardsConfigQueryResult(result.virtualCardsConfig)
        } catch (e: Throwable) {
            logger.error("unexpected error $e")
            throw interpretVirtualCardException(e)
        }
    }

    @Throws(VirtualCardException::class)
    override suspend fun listVirtualCards(
        filter: VirtualCardFilterInput?,
        sortOrder: SortOrder?,
        limit: Int,
        nextToken: String?,
    ): ListAPIResult<VirtualCard, PartialVirtualCard> {
        try {
            val queryResponse = graphQLClient.query<ListCardsQuery, ListCardsQuery.Data>(
                ListCardsQuery.OPERATION_DOCUMENT,
                mapOf(
                    "filter" to Optional.presentIfNotNull(filter.toVirtualCardFilterInput()),
                    "sortOrder" to Optional.presentIfNotNull(sortOrder?.toSortOrderInput(sortOrder)),
                    "limit" to Optional.presentIfNotNull(limit),
                    "nextToken" to Optional.presentIfNotNull(nextToken),
                ),
            )

            if (queryResponse.hasErrors()) {
                logger.error("errors = ${queryResponse.errors}")
                throw interpretVirtualCardError(queryResponse.errors.first())
            }

            val queryResult = queryResponse.data?.listCards
            val sealedCards = queryResult?.items ?: emptyList()
            val newNextToken = queryResult?.nextToken

            val success: MutableList<VirtualCard> = mutableListOf()
            val partials: MutableList<PartialResult<PartialVirtualCard>> = mutableListOf()
            for (sealedCard in sealedCards) {
                try {
                    val unsealedCard = VirtualCardTransformer.toEntity(
                        deviceKeyManager,
                        sealedCard.sealedCardWithLastTransaction,
                    )
                    success.add(unsealedCard)
                } catch (e: Exception) {
                    val partialCard = VirtualCardTransformer.toPartialEntity(sealedCard.sealedCardWithLastTransaction)
                    val partialResult = PartialResult(partialCard, e)
                    partials.add(partialResult)
                }
            }
            return deduplicateListVirtualCardResult(success, partials, newNextToken)
        } catch (e: Throwable) {
            logger.error("unexpected error $e")
            throw interpretVirtualCardException(e)
        }
    }

    @Throws(VirtualCardException::class)
    override suspend fun updateVirtualCard(input: UpdateVirtualCardInput): SingleAPIResult<VirtualCard, PartialVirtualCard> {
        try {
            val keyPairResult = publicKeyService.getCurrentKey()
                ?: throw VirtualCardException.PublicKeyException(KEY_RETRIEVAL_ERROR_MSG)
            val keyId = keyPairResult.keyId

            val mutationInput = CardUpdateRequest(
                alias = Optional.presentIfNotNull(input.alias),
                billingAddress = Optional.presentIfNotNull(input.billingAddress?.toAddressInput()),
                cardHolder = Optional.presentIfNotNull(input.cardHolder),
                expectedVersion = Optional.presentIfNotNull(input.expectedCardVersion),
                id = input.id,
                keyId = Optional.presentIfNotNull(keyId),
                metadata = Optional.presentIfNotNull(input.metadata.toMetadataInput(deviceKeyManager)),
            )

            val mutationResponse = graphQLClient.mutate<UpdateVirtualCardMutation, UpdateVirtualCardMutation.Data>(
                UpdateVirtualCardMutation.OPERATION_DOCUMENT,
                mapOf("input" to mutationInput),
            )

            if (mutationResponse.hasErrors()) {
                logger.error("errors = ${mutationResponse.errors}")
                throw interpretVirtualCardError(
                    mutationResponse.errors.first(),
                    VirtualCardException.UpdateFailedException(mutationResponse.errors.first().toString()),
                )
            }

            val updatedCard = mutationResponse.data?.updateCard
            updatedCard?.let {
                try {
                    val unsealedUpdatedCard = VirtualCardTransformer.toEntity(
                        deviceKeyManager,
                        updatedCard.sealedCardWithLastTransaction,
                    )
                    return SingleAPIResult.Success(unsealedUpdatedCard)
                } catch (e: Exception) {
                    val partialUpdatedCard = VirtualCardTransformer.toPartialEntity(updatedCard.sealedCardWithLastTransaction)
                    val partialResult = PartialResult(partialUpdatedCard, e)
                    return SingleAPIResult.Partial(partialResult)
                }
            }
            throw VirtualCardException.FailedException(NO_RESULT_ERROR_MSG)
        } catch (e: Throwable) {
            logger.error("unexpected error $e")
            throw interpretVirtualCardException(e)
        }
    }

    override suspend fun cancelVirtualCard(id: String): SingleAPIResult<VirtualCard, PartialVirtualCard> {
        try {
            val key = publicKeyService.getCurrentKey()
                ?: throw VirtualCardException.PublicKeyException(KEY_RETRIEVAL_ERROR_MSG)
            val keyId = key.keyId

            val mutationInput = CardCancelRequest(
                id = id,
                keyId = Optional.presentIfNotNull(keyId),
            )
            val mutationResponse = graphQLClient.mutate<CancelVirtualCardMutation, CancelVirtualCardMutation.Data>(
                CancelVirtualCardMutation.OPERATION_DOCUMENT,
                mapOf("input" to mutationInput),
            )

            if (mutationResponse.hasErrors()) {
                logger.error("errors = ${mutationResponse.errors}")
                throw interpretVirtualCardError(
                    mutationResponse.errors.first(),
                    VirtualCardException.CancelFailedException(mutationResponse.errors.first().toString()),
                )
            }

            val cancelledCard = mutationResponse.data?.cancelCard
            cancelledCard?.let {
                try {
                    val unsealedCancelledCard = VirtualCardTransformer.toEntity(
                        deviceKeyManager,
                        cancelledCard.sealedCardWithLastTransaction,
                    )
                    return SingleAPIResult.Success(unsealedCancelledCard)
                } catch (e: Exception) {
                    val partialCancelledCard = VirtualCardTransformer.toPartialEntity(
                        cancelledCard.sealedCardWithLastTransaction,
                    )
                    val partialResult = PartialResult(partialCancelledCard, e)
                    return SingleAPIResult.Partial(partialResult)
                }
            }
            throw VirtualCardException.FailedException(NO_RESULT_ERROR_MSG)
        } catch (e: Throwable) {
            logger.error("unexpected error $e")
            throw interpretVirtualCardException(e)
        }
    }

    @Throws(SudoVirtualCardsClient.TransactionException::class)
    override suspend fun getTransaction(id: String): Transaction? {
        try {
            val keyPairResult = publicKeyService.getCurrentKey()
                ?: throw SudoVirtualCardsClient.TransactionException.PublicKeyException(KEY_RETRIEVAL_ERROR_MSG)
            val keyId = keyPairResult.keyId

            val queryResponse = graphQLClient.query<GetTransactionQuery, GetTransactionQuery.Data>(
                GetTransactionQuery.OPERATION_DOCUMENT,
                mapOf("id" to id, "keyId" to Optional.presentIfNotNull(keyId)),
            )

            if (queryResponse.hasErrors()) {
                logger.error("errors = ${queryResponse.errors}")
                throw interpretTransactionError(queryResponse.errors.first())
            }

            val result = queryResponse.data?.getTransaction ?: return null
            return TransactionTransformer.toEntity(deviceKeyManager, result.sealedTransaction)
        } catch (e: Throwable) {
            logger.error("unexpected error $e")
            throw interpretTransactionException(e)
        }
    }

    @Throws(SudoVirtualCardsClient.TransactionException::class)
    override suspend fun listTransactionsByCardId(
        cardId: String,
        limit: Int,
        nextToken: String?,
        dateRange: DateRange?,
        sortOrder: SortOrder,
    ): ListAPIResult<Transaction, PartialTransaction> {
        try {
            val queryResponse = graphQLClient.query<ListTransactionsByCardIdQuery, ListTransactionsByCardIdQuery.Data>(
                ListTransactionsByCardIdQuery.OPERATION_DOCUMENT,
                mapOf(
                    "cardId" to cardId,
                    "limit" to Optional.presentIfNotNull(limit),
                    "nextToken" to Optional.presentIfNotNull(nextToken),
                    "dateRange" to Optional.presentIfNotNull(dateRange?.toDateRangeInput()),
                    "sortOrder" to Optional.presentIfNotNull(sortOrder.toSortOrderInput(sortOrder)),
                ),
            )

            if (queryResponse.hasErrors()) {
                logger.error("errors = ${queryResponse.errors}")
                throw interpretTransactionError(queryResponse.errors.first())
            }

            val queryResult = queryResponse.data?.listTransactionsByCardId2
            val sealedTransactions = queryResult?.items ?: emptyList()
            val newNextToken = queryResult?.nextToken

            val success: MutableList<Transaction> = mutableListOf()
            val partials: MutableList<PartialResult<PartialTransaction>> = mutableListOf()
            for (sealedTransaction in sealedTransactions) {
                try {
                    val unsealerTransaction =
                        TransactionTransformer.toEntity(deviceKeyManager, sealedTransaction.sealedTransaction)
                    success.add(unsealerTransaction)
                } catch (e: Exception) {
                    val partialTransaction =
                        TransactionTransformer.toPartialEntity(sealedTransaction.sealedTransaction)
                    val partialResult = PartialResult(partialTransaction, e)
                    partials.add(partialResult)
                }
            }
            return deduplicateListTransactionResult(success, partials, newNextToken)
        } catch (e: Throwable) {
            logger.error("unexpected error $e")
            throw interpretTransactionException(e)
        }
    }

    @Throws(SudoVirtualCardsClient.TransactionException::class)
    override suspend fun listTransactionsByCardIdAndType(
        cardId: String,
        transactionType: TransactionType,
        limit: Int,
        nextToken: String?,
    ): ListAPIResult<Transaction, PartialTransaction> {
        try {
            val queryResponse = graphQLClient.query<ListTransactionsByCardIdAndTypeQuery, ListTransactionsByCardIdAndTypeQuery.Data>(
                ListTransactionsByCardIdAndTypeQuery.OPERATION_DOCUMENT,
                mapOf(
                    "cardId" to cardId,
                    "transactionType" to (
                        transactionType.toTransactionType()
                            ?: throw SudoVirtualCardsClient.TransactionException.UnsupportedTransactionTypeException(
                                UNSUPPORTED_TRANSACTION_TYPE_MSG,
                            )
                        ),
                    "limit" to Optional.presentIfNotNull(limit),
                    "nextToken" to Optional.presentIfNotNull(nextToken),
                ),
            )

            if (queryResponse.hasErrors()) {
                logger.error("errors = ${queryResponse.errors}")
                throw interpretTransactionError(queryResponse.errors.first())
            }

            val queryResult = queryResponse.data?.listTransactionsByCardIdAndType
            val sealedTransactions = queryResult?.items ?: emptyList()
            val newNextToken = queryResult?.nextToken

            val success: MutableList<Transaction> = mutableListOf()
            val partials: MutableList<PartialResult<PartialTransaction>> = mutableListOf()
            for (sealedTransaction in sealedTransactions) {
                try {
                    val unsealerTransaction =
                        TransactionTransformer.toEntity(deviceKeyManager, sealedTransaction.sealedTransaction)
                    success.add(unsealerTransaction)
                } catch (e: Exception) {
                    val partialTransaction =
                        TransactionTransformer.toPartialEntity(sealedTransaction.sealedTransaction)
                    val partialResult = PartialResult(partialTransaction, e)
                    partials.add(partialResult)
                }
            }
            return deduplicateListTransactionResult(success, partials, newNextToken)
        } catch (e: Throwable) {
            logger.error("unexpected error $e")
            throw interpretTransactionException(e)
        }
    }

    @Throws(SudoVirtualCardsClient.TransactionException::class)
    override suspend fun listTransactions(
        limit: Int,
        nextToken: String?,
        dateRange: DateRange?,
        sortOrder: SortOrder,
    ): ListAPIResult<Transaction, PartialTransaction> {
        try {
            val queryResponse = graphQLClient.query<ListTransactionsQuery, ListTransactionsQuery.Data>(
                ListTransactionsQuery.OPERATION_DOCUMENT,
                mapOf(
                    "limit" to Optional.presentIfNotNull(limit),
                    "nextToken" to Optional.presentIfNotNull(nextToken),
                    "dateRange" to Optional.presentIfNotNull(dateRange?.toDateRangeInput()),
                    "sortOrder" to Optional.presentIfNotNull(sortOrder.toSortOrderInput(sortOrder)),
                ),
            )

            if (queryResponse.hasErrors()) {
                logger.error("errors = ${queryResponse.errors}")
                throw interpretTransactionError(queryResponse.errors.first())
            }

            val queryResult = queryResponse.data?.listTransactions2
            val sealedTransactions = queryResult?.items ?: emptyList()
            val newNextToken = queryResult?.nextToken

            val success: MutableList<Transaction> = mutableListOf()
            val partials: MutableList<PartialResult<PartialTransaction>> = mutableListOf()
            for (sealedTransaction in sealedTransactions) {
                try {
                    val unsealerTransaction =
                        TransactionTransformer.toEntity(deviceKeyManager, sealedTransaction.sealedTransaction)
                    success.add(unsealerTransaction)
                } catch (e: Exception) {
                    val partialTransaction =
                        TransactionTransformer.toPartialEntity(sealedTransaction.sealedTransaction)
                    val partialResult = PartialResult(partialTransaction, e)
                    partials.add(partialResult)
                }
            }
            return deduplicateListTransactionResult(success, partials, newNextToken)
        } catch (e: Throwable) {
            logger.error("unexpected error $e")
            throw interpretTransactionException(e)
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

    override suspend fun subscribeToFundingSources(id: String, subscriber: FundingSourceSubscriber) {
        subscriptions.subscribeFundingSources(id, subscriber)
    }

    override suspend fun unsubscribeFromFundingSources(id: String) {
        subscriptions.unsubscribeFundingSources(id)
    }

    override suspend fun unsubscribeAllFromFundingSources() {
        subscriptions.unsubscribeAllFundingSources()
    }

    override suspend fun sandboxGetPlaidData(institutionId: String, plaidUsername: String): SandboxPlaidData {
        try {
            val queryInput = SandboxGetPlaidDataRequest(

                institutionId = institutionId,
                username = plaidUsername,
            )

            val queryResponse = graphQLClient.query<SandboxGetPlaidDataQuery, SandboxGetPlaidDataQuery.Data>(
                SandboxGetPlaidDataQuery.OPERATION_DOCUMENT,
                mapOf("input" to queryInput),
            )

            if (queryResponse.hasErrors()) {
                logger.error("errors = ${queryResponse.errors}")
                throw interpretFundingSourceError(queryResponse.errors.first())
            }

            val queryResult = queryResponse.data?.sandboxGetPlaidData
                ?: throw FundingSourceException.FailedException("No sandbox Plaid data returned")

            val result = SandboxPlaidData(
                queryResult.accountMetadata.map {
                    PlaidAccountMetadata(it.accountId, interpretPlaidAccountSubtype(it.subtype))
                },
                queryResult.publicToken,
            )

            return result
        } catch (e: Throwable) {
            logger.error("unexpected error $e")
            throw interpretFundingSourceException(e)
        }
    }

    override suspend fun sandboxSetFundingSourceToRequireRefresh(fundingSourceId: String): FundingSource {
        try {
            val mutationInput =
                SandboxSetFundingSourceToRequireRefreshRequest(
                    fundingSourceId = fundingSourceId,
                )

            val mutationResponse = graphQLClient.mutate<
                SandboxSetFundingSourceToRequireRefreshMutation,
                SandboxSetFundingSourceToRequireRefreshMutation.Data,
                >(
                SandboxSetFundingSourceToRequireRefreshMutation.OPERATION_DOCUMENT,
                mapOf("input" to mutationInput),
            )

            if (mutationResponse.hasErrors()) {
                logger.error("errors = ${mutationResponse.errors}")
                throw interpretFundingSourceError(mutationResponse.errors.first())
            }

            val mutationResult = mutationResponse.data?.sandboxSetFundingSourceToRequireRefresh
                ?: throw FundingSourceException.FailedException(
                    "No data returned setting funding source to require refresh",
                )

            return FundingSourceTransformer.toEntityFromSandboxSetFundingSourceToRequireRefreshResult(deviceKeyManager, mutationResult)
        } catch (e: Throwable) {
            logger.error("unexpected error $e")
            throw interpretFundingSourceException(e)
        }
    }

    override suspend fun importKeys(archiveData: ByteArray) {
        if (archiveData.isEmpty()) {
            throw SudoVirtualCardsClient.VirtualCardCryptographicKeysException.SecureKeyArchiveException(INVALID_ARGUMENT_ERROR_MSG)
        }
        try {
            deviceKeyManager.importKeys(archiveData)
        } catch (e: Throwable) {
            logger.error("unexpected error $e")
            throw interpretVirtualCardException(e)
        }
    }

    override suspend fun exportKeys(): ByteArray {
        try {
            return deviceKeyManager.exportKeys()
        } catch (e: Throwable) {
            logger.error("unexpected error $e")
            throw interpretVirtualCardException(e)
        }
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

    /**
     * Removes duplicate unsealed virtual cards from success and partial results based on identifier,
     * favouring unsealed virtual cards in the success list.
     *
     * @param success [List<VirtualCard>] A list of successfully unsealed virtual cards.
     * @param partials [List<PartialResult<PartialVirtualCard>>] A list of partial unsealed virtual cards.
     * @param nextToken [String] A token generated from previous calls to allow for pagination.
     * @return A [ListAPIResult.Success] or [ListAPIResult.Partial] result.
     */
    private fun deduplicateListVirtualCardResult(
        success: List<VirtualCard>,
        partials: List<PartialResult<PartialVirtualCard>>,
        nextToken: String?,
    ): ListAPIResult<VirtualCard, PartialVirtualCard> {
        // Remove duplicate success and partial virtual cards based on id
        val distinctSuccess = success.distinctBy { it.id }.toMutableList()
        val distinctPartials = partials.distinctBy { it.partial.id }.toMutableList()

        // Remove virtual cards from partial list that have been successfully unsealed
        distinctPartials.removeAll { partial -> distinctSuccess.any { it.id == partial.partial.id } }

        // Build up and return the ListAPIResult
        if (distinctPartials.isNotEmpty()) {
            val listPartialResult = ListAPIResult.ListPartialResult(distinctSuccess, distinctPartials, nextToken)
            return ListAPIResult.Partial(listPartialResult)
        }
        val listSuccessResult = ListAPIResult.ListSuccessResult(distinctSuccess, nextToken)
        return ListAPIResult.Success(listSuccessResult)
    }

    /**
     * Removes duplicate unsealed transactions from success and partial results based on identifier,
     * favouring unsealed transactions in the success list.
     *
     * @param success [List<Transaction>] A list of successfully unsealed transactions.
     * @param partials [List<PartialResult<PartialTransaction>>] A list of partial unsealed transactions.
     * @param nextToken [String] A token generated from previous calls to allow for pagination.
     * @return A [ListAPIResult.Success] or [ListAPIResult.Partial] result.
     */
    private fun deduplicateListTransactionResult(
        success: List<Transaction>,
        partials: List<PartialResult<PartialTransaction>>,
        nextToken: String?,
    ): ListAPIResult<Transaction, PartialTransaction> {
        // Remove duplicate success and partial transactions based on id
        val distinctSuccess = success.distinctBy { it.id }.toMutableList()
        val distinctPartials = partials.distinctBy { it.partial.id }.toMutableList()

        // Remove transactions from partial list that have been successfully unsealed
        distinctPartials.removeAll { partial -> distinctSuccess.any { it.id == partial.partial.id } }

        // Build up and return the ListAPIResult
        if (distinctPartials.isNotEmpty()) {
            val listPartialResult = ListAPIResult.ListPartialResult(distinctSuccess, distinctPartials, nextToken)
            return ListAPIResult.Partial(listPartialResult)
        }
        val listSuccessResult = ListAPIResult.ListSuccessResult(distinctSuccess, nextToken)
        return ListAPIResult.Success(listSuccessResult)
    }

    private fun interpretFundingSourceException(e: Throwable): Throwable {
        return when (e) {
            is CancellationException,
            is FundingSourceException,
            -> e
            else -> FundingSourceException.UnknownException(e)
        }
    }

    private fun interpretVirtualCardException(e: Throwable): Throwable {
        return when (e) {
            is CancellationException,
            is VirtualCardException,
            -> e
            is PublicKeyService.PublicKeyServiceException ->
                VirtualCardException.PublicKeyException(KEY_RETRIEVAL_ERROR_MSG, e)
            is Unsealer.UnsealerException ->
                VirtualCardException.UnsealingException(UNSEAL_CARD_ERROR_MSG, e)
            is DeviceKeyManager.DeviceKeyManagerException.SecureKeyArchiveException ->
                SudoVirtualCardsClient.VirtualCardCryptographicKeysException.SecureKeyArchiveException(KEY_ARCHIVE_ERROR_MSG, e)
            else -> VirtualCardException.UnknownException(e)
        }
    }

    private fun interpretTransactionException(e: Throwable): Throwable {
        return when (e) {
            is CancellationException,
            is SudoVirtualCardsClient.TransactionException,
            -> e
            is PublicKeyService.PublicKeyServiceException ->
                SudoVirtualCardsClient.TransactionException.PublicKeyException(KEY_RETRIEVAL_ERROR_MSG, e)
            is Unsealer.UnsealerException ->
                SudoVirtualCardsClient.TransactionException.UnsealingException(UNSEAL_CARD_ERROR_MSG, e)
            else -> SudoVirtualCardsClient.TransactionException.UnknownException(e)
        }
    }

    /**
     * @param e [GraphQLResponse.Error]: Error returned from Amplify library
     * @param networkError [FundingSourceException]? If provided, the error which should be thrown in the event of a network error.
     */
    private fun interpretFundingSourceError(
        e: GraphQLResponse.Error,
        networkError: FundingSourceException? = null,
    ): FundingSourceException {
        val httpStatusCode = e.extensions?.get(HTTP_STATUS_CODE_KEY) as Int?
        val error = e.extensions?.get(GRAPHQL_ERROR_TYPE)?.toString() ?: ""
        if (httpStatusCode == HttpURLConnection.HTTP_UNAUTHORIZED) {
            return FundingSourceException.AuthenticationException("$e")
        }
        if (httpStatusCode != null) {
            return networkError ?: FundingSourceException.FailedException("$e")
        }
        if (error.contains(ERROR_PROVISIONAL_FUNDING_SOURCE_NOT_FOUND)) {
            return FundingSourceException.ProvisionalFundingSourceNotFoundException(
                PROVISIONAL_FUNDING_SOURCE_NOT_FOUND_MSG,
            )
        }
        if (error.contains(ERROR_FUNDING_SOURCE_NOT_FOUND)) {
            return FundingSourceException.FundingSourceNotFoundException(FUNDING_SOURCE_NOT_FOUND_MSG)
        }
        if (error.contains(ERROR_DUPLICATE_FUNDING_SOURCE)) {
            return FundingSourceException.DuplicateFundingSourceException(DUPLICATE_FUNDING_SOURCE_MSG)
        }
        if (error.contains(ERROR_FUNDING_SOURCE_STATE)) {
            return FundingSourceException.FundingSourceStateException(FUNDING_SOURCE_STATE_MSG)
        }
        if (error.contains(ERROR_FUNDING_SOURCE_NOT_SETUP)) {
            return FundingSourceException.SetupFailedException(FUNDING_SOURCE_NOT_SETUP_MSG)
        }
        if (error.contains(ERROR_FUNDING_SOURCE_REQUIRES_USER_INTERACTION)) {
            val errorInfo = e.extensions?.get("errorInfo")
            return try {
                val interactionData = SudoVirtualCardsClient.FundingSourceInteractionData.decode(errorInfo)
                FundingSourceException.FundingSourceRequiresUserInteractionException(
                    FUNDING_SOURCE_REQUIRES_USER_INTERACTION_MSG,
                    ProviderDataTransformer.toUserInteractionData(interactionData.provisioningData),
                )
            } catch (e: Throwable) {
                FundingSourceException.FailedException(
                    message = "Invalid user interaction data during funding source setup",
                    cause = e,
                )
            }
        }
        if (error.contains(ERROR_FUNDING_SOURCE_COMPLETION_DATA_INVALID)) {
            return FundingSourceException.CompletionDataInvalidException(FUNDING_SOURCE_COMPLETION_DATA_INVALID_MSG)
        }
        if (error.contains(ERROR_UNACCEPTABLE_FUNDING_SOURCE)) {
            return FundingSourceException.UnacceptableFundingSourceException(UNACCEPTABLE_FUNDING_SOURCE_MSG)
        }
        if (error.contains(ERROR_UNSUPPORTED_CURRENCY)) {
            return FundingSourceException.UnsupportedCurrencyException(UNSUPPORTED_CURRENCY_MSG)
        }
        if (error.contains(ERROR_IDENTITY_NOT_VERIFIED) || error.contains(SERVICE_ERROR)) {
            return FundingSourceException.IdentityVerificationException(IDENTITY_NOT_VERIFIED_MSG)
        }
        if (error.contains(ERROR_ACCOUNT_LOCKED)) {
            return FundingSourceException.AccountLockedException(ACCOUNT_LOCKED_MSG)
        }
        if (error.contains(ERROR_VELOCITY_EXCEEDED)) {
            return FundingSourceException.VelocityExceededException(VELOCITY_EXCEEDED_MSG)
        }
        if (error.contains(ERROR_ENTITLEMENT_EXCEEDED)) {
            return FundingSourceException.EntitlementExceededException(ENTITLEMENT_EXCEEDED_MSG)
        }
        return FundingSourceException.FailedException(e.toString())
    }

    private fun interpretVirtualCardError(
        e: GraphQLResponse.Error,
        networkError: VirtualCardException? = null,
    ): VirtualCardException {
        val httpStatusCode = e.extensions?.get(HTTP_STATUS_CODE_KEY) as Int?
        val error = e.extensions?.get(GRAPHQL_ERROR_TYPE)?.toString() ?: ""
        if (httpStatusCode != null) {
            return networkError ?: VirtualCardException.FailedException("$e")
        }
        if (error.contains(ERROR_INVALID_TOKEN)) {
            return VirtualCardException.ProvisionFailedException(INVALID_TOKEN_MSG)
        }
        if (error.contains(ERROR_CARD_NOT_FOUND)) {
            return VirtualCardException.CardNotFoundException(CARD_NOT_FOUND_MSG)
        }
        if (error.contains(ERROR_INVALID_CARD_STATE)) {
            return VirtualCardException.CardStateException(INVALID_CARD_STATE_MSG)
        }
        if (error.contains(ERROR_FUNDING_SOURCE_NOT_FOUND)) {
            return VirtualCardException.FundingSourceNotFoundException(FUNDING_SOURCE_NOT_FOUND_MSG)
        }
        if (error.contains(ERROR_FUNDING_SOURCE_NOT_ACTIVE)) {
            return VirtualCardException.FundingSourceNotActiveException(FUNDING_SOURCE_NOT_ACTIVE_MSG)
        }
        if (error.contains(ERROR_VELOCITY_EXCEEDED)) {
            return VirtualCardException.VelocityExceededException(VELOCITY_EXCEEDED_MSG)
        }
        if (error.contains(ERROR_ENTITLEMENT_EXCEEDED)) {
            return VirtualCardException.EntitlementExceededException(ENTITLEMENT_EXCEEDED_MSG)
        }
        if (error.contains(ERROR_UNSUPPORTED_CURRENCY)) {
            return VirtualCardException.UnsupportedCurrencyException(UNSUPPORTED_CURRENCY_MSG)
        }
        if (error.contains(ERROR_IDENTITY_NOT_VERIFIED)) {
            return VirtualCardException.IdentityVerificationException(IDENTITY_NOT_VERIFIED_MSG)
        }
        if (error.contains(ERROR_IDENTITY_INSUFFICIENT)) {
            return VirtualCardException.IdentityVerificationInsufficientException(IDENTITY_INSUFFICIENT_MSG)
        }
        if (error.contains(ERROR_ACCOUNT_LOCKED)) {
            return VirtualCardException.AccountLockedException(ACCOUNT_LOCKED_MSG)
        }
        return VirtualCardException.FailedException(e.toString())
    }

    private fun interpretTransactionError(e: GraphQLResponse.Error): SudoVirtualCardsClient.TransactionException {
        return SudoVirtualCardsClient.TransactionException.FailedException(e.toString())
    }

    private fun interpretPlaidAccountSubtype(plaidSubtype: String?): BankAccountFundingSource.BankAccountType {
        return when (plaidSubtype) {
            "checking" -> BankAccountFundingSource.BankAccountType.CHECKING
            "savings" -> BankAccountFundingSource.BankAccountType.SAVING
            else -> BankAccountFundingSource.BankAccountType.UNKNOWN
        }
    }
}

data class SudoVirtualCardsNotificationSchemaEntry(
    override val description: String,
    override val fieldName: String,
    override val type: String,
) : NotificationSchemaEntry

data class SudoVirtualCardsNotificationMetaData(
    override val serviceName: String,
    override val schema: List<SudoVirtualCardsNotificationSchemaEntry>,
) : NotificationMetaData
