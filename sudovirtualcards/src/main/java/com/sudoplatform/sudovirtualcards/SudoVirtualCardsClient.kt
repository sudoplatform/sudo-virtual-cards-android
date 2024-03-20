/*
 * Copyright © 2023 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards

import android.content.Context
import com.amazonaws.mobileconnectors.appsync.AWSAppSyncClient
import com.google.gson.Gson
import com.sudoplatform.sudoapiclient.ApiClientManager
import com.sudoplatform.sudokeymanager.AndroidSQLiteStore
import com.sudoplatform.sudokeymanager.KeyManagerFactory
import com.sudoplatform.sudokeymanager.KeyManagerInterface
import com.sudoplatform.sudologging.AndroidUtilsLogDriver
import com.sudoplatform.sudologging.LogLevel
import com.sudoplatform.sudologging.Logger
import com.sudoplatform.sudouser.SudoUserClient
import com.sudoplatform.sudovirtualcards.keys.DefaultDeviceKeyManager
import com.sudoplatform.sudovirtualcards.keys.DefaultPublicKeyService
import com.sudoplatform.sudovirtualcards.keys.PublicKeyService
import com.sudoplatform.sudovirtualcards.logging.LogConstants
import com.sudoplatform.sudovirtualcards.subscription.FundingSourceSubscriber
import com.sudoplatform.sudovirtualcards.subscription.Subscriber
import com.sudoplatform.sudovirtualcards.subscription.TransactionSubscriber
import com.sudoplatform.sudovirtualcards.types.CachePolicy
import com.sudoplatform.sudovirtualcards.types.CreateKeysIfAbsentResult
import com.sudoplatform.sudovirtualcards.types.DateRange
import com.sudoplatform.sudovirtualcards.types.FundingSource
import com.sudoplatform.sudovirtualcards.types.FundingSourceClientConfiguration
import com.sudoplatform.sudovirtualcards.types.ListAPIResult
import com.sudoplatform.sudovirtualcards.types.ListOutput
import com.sudoplatform.sudovirtualcards.types.PartialTransaction
import com.sudoplatform.sudovirtualcards.types.PartialVirtualCard
import com.sudoplatform.sudovirtualcards.types.ProviderUserInteractionData
import com.sudoplatform.sudovirtualcards.types.ProvisionalFundingSource
import com.sudoplatform.sudovirtualcards.types.ProvisionalVirtualCard
import com.sudoplatform.sudovirtualcards.types.SandboxPlaidData
import com.sudoplatform.sudovirtualcards.types.SingleAPIResult
import com.sudoplatform.sudovirtualcards.types.SortOrder
import com.sudoplatform.sudovirtualcards.types.Transaction
import com.sudoplatform.sudovirtualcards.types.TransactionType
import com.sudoplatform.sudovirtualcards.types.VirtualCard
import com.sudoplatform.sudovirtualcards.types.VirtualCardsConfig
import com.sudoplatform.sudovirtualcards.types.inputs.CompleteFundingSourceInput
import com.sudoplatform.sudovirtualcards.types.inputs.ProvisionVirtualCardInput
import com.sudoplatform.sudovirtualcards.types.inputs.RefreshFundingSourceInput
import com.sudoplatform.sudovirtualcards.types.inputs.SetupFundingSourceInput
import com.sudoplatform.sudovirtualcards.types.inputs.UpdateVirtualCardInput
import java.util.Objects

/**
 * Interface encapsulating a library for interacting with the Sudo Platform Virtual Cards service.
 *
 * @sample com.sudoplatform.sudovirtualcards.samples.Samples.sudoVirtualCardsClient
 */
interface SudoVirtualCardsClient : AutoCloseable {

    companion object {
        /** Create a [Builder] for [SudoVirtualCardsClient]. */
        @JvmStatic
        fun builder() = Builder()

        const val DEFAULT_CARD_LIMIT = 10
        const val DEFAULT_FUNDING_SOURCE_LIMIT = 10
        const val DEFAULT_TRANSACTION_LIMIT = 100

        const val DEFAULT_KEY_NAMESPACE = "vc"
    }

    /**
     * Builder used to construct the [SudoVirtualCardsClient].
     */
    class Builder internal constructor() {
        private var context: Context? = null
        private var sudoUserClient: SudoUserClient? = null
        private var appSyncClient: AWSAppSyncClient? = null
        private var keyManager: KeyManagerInterface? = null
        private var publicKeyService: PublicKeyService? = null
        private var logger: Logger = Logger(LogConstants.SUDOLOG_TAG, AndroidUtilsLogDriver(LogLevel.INFO))
        private var namespace: String = DEFAULT_KEY_NAMESPACE
        private var databaseName: String = AndroidSQLiteStore.DEFAULT_DATABASE_NAME

        /**
         * Provide the application context (required input).
         */
        fun setContext(context: Context) = also {
            this.context = context
        }

        /**
         * Provide the implementation of the [SudoUserClient] used to perform
         * sign in and ownership operations (required input).
         */
        fun setSudoUserClient(sudoUserClient: SudoUserClient) = also {
            this.sudoUserClient = sudoUserClient
        }

        /**
         * Provide an [AWSAppSyncClient] for the [SudoVirtualCardsClient] to use
         * (optional input). If this is not supplied, an [AWSAppSyncClient] will
         * be constructed and used.
         */
        fun setAppSyncClient(appSyncClient: AWSAppSyncClient) = also {
            this.appSyncClient = appSyncClient
        }

        /**
         * Provide the implementation of the [KeyManagerInterface] used for key management and
         * cryptographic operations (optional input). If a value is not supplied a default
         * implementation will be used.
         */
        fun setKeyManager(keyManager: KeyManagerInterface) = also {
            this.keyManager = keyManager
        }

        /**
         * Provide the implementation of the [Logger] used for logging errors (optional input).
         * If a value is not supplied a default implementation will be used.
         */
        fun setLogger(logger: Logger) = also {
            this.logger = logger
        }

        /**
         * Provide the namespace to use for internal data and cryptographic keys. This should be unique
         * per client per app to avoid name conflicts between multiple clients. If a value is not supplied
         * a default value will be used.
         */
        fun setNamespace(namespace: String) = also {
            this.namespace = namespace
        }

        /**
         * Provide the database name to use for exportable key store database.
         */
        fun setDatabaseName(databaseName: String) = also {
            this.databaseName = databaseName
        }

        /**
         * Provider the implementation of the [PublicKeyService] used for public
         * key operations (optional).
         * If a value is not supplied a default implementation will be used.
         */
        internal fun setPublicKeyService(publicKeyService: PublicKeyService) = also {
            this.publicKeyService = publicKeyService
        }

        /**
         * Construct the [SudoVirtualCardsClient]. Will throw a [NullPointerException] if
         * the [context] and [sudoUserClient] has not been provided.
         */
        fun build(): SudoVirtualCardsClient {
            Objects.requireNonNull(context, "Context must be provided.")
            Objects.requireNonNull(sudoUserClient, "SudoUserClient must be provided.")

            val appSyncClient = appSyncClient
                ?: ApiClientManager.getClient(this@Builder.context!!, this@Builder.sudoUserClient!!, "vcService")

            val deviceKeyManager = DefaultDeviceKeyManager(
                keyManager = keyManager ?: KeyManagerFactory(context!!).createAndroidKeyManager(this.namespace, this.databaseName),
            )

            val publicKeyService = publicKeyService ?: DefaultPublicKeyService(
                keyRingServiceName = "sudo-virtual-cards",
                userClient = sudoUserClient!!,
                deviceKeyManager = deviceKeyManager,
                appSyncClient = appSyncClient,
                logger = logger,
            )

            return DefaultSudoVirtualCardsClient(
                context = context!!,
                appSyncClient = appSyncClient,
                sudoUserClient = sudoUserClient!!,
                logger = logger,
                deviceKeyManager = deviceKeyManager,
                publicKeyService = publicKeyService,
            )
        }
    }

    /**
     * Returned with a FundingSourceRequiresUserInteraction error to provide feedback to the client.
     */
    data class FundingSourceInteractionData(val provisioningData: String) {
        companion object {
            fun decode(errorInfo: Any?): FundingSourceInteractionData {
                val jsonErrorInfo = Gson().toJson(errorInfo)
                return Gson().fromJson(jsonErrorInfo, FundingSourceInteractionData::class.java)
            }
        }
    }

    /**
     * Defines the exceptions for the funding source based methods.
     *
     * @property message [String] Accompanying message for the exception.
     * @property cause [Throwable] The cause of the exception.
     */
    sealed class FundingSourceException(message: String? = null, cause: Throwable? = null) : RuntimeException(message, cause) {
        class FailedException(message: String? = null, cause: Throwable? = null) :
            FundingSourceException(message = message, cause = cause)
        class SetupFailedException(message: String? = null, cause: Throwable? = null) :
            FundingSourceException(message = message, cause = cause)
        class FundingSourceStateException(message: String? = null, cause: Throwable? = null) :
            FundingSourceException(message = message, cause = cause)
        class CompletionFailedException(message: String? = null, cause: Throwable? = null) :
            FundingSourceException(message = message, cause = cause)
        class RefreshFailedException(message: String? = null, cause: Throwable? = null) :
            FundingSourceException(message = message, cause = cause)
        class CancelFailedException(message: String? = null, cause: Throwable? = null) :
            FundingSourceException(message = message, cause = cause)
        class ReviewFailedException(message: String? = null, cause: Throwable? = null) :
            FundingSourceException(message = message, cause = cause)
        class DuplicateFundingSourceException(message: String? = null, cause: Throwable? = null) :
            FundingSourceException(message = message, cause = cause)
        class FundingSourceNotFoundException(message: String? = null, cause: Throwable? = null) :
            FundingSourceException(message = message, cause = cause)
        class ProvisionalFundingSourceNotFoundException(message: String? = null, cause: Throwable? = null) :
            FundingSourceException(message = message, cause = cause)
        class UnacceptableFundingSourceException(message: String? = null, cause: Throwable? = null) :
            FundingSourceException(message = message, cause = cause)
        class UnsupportedCurrencyException(message: String? = null, cause: Throwable? = null) :
            FundingSourceException(message = message, cause = cause)
        class CompletionDataInvalidException(message: String? = null, cause: Throwable? = null) :
            FundingSourceException(message = message, cause = cause)
        class UnexpectedProviderException(message: String? = null, cause: Throwable? = null) :
            FundingSourceException(message = message, cause = cause)
        class IdentityVerificationException(message: String? = null, cause: Throwable? = null) :
            FundingSourceException(message = message, cause = cause)
        class AccountLockedException(message: String? = null, cause: Throwable? = null) :
            FundingSourceException(message = message, cause = cause)
        class EntitlementExceededException(message: String? = null, cause: Throwable? = null) :
            FundingSourceException(message = message, cause = cause)
        class VelocityExceededException(message: String? = null, cause: Throwable? = null) :
            FundingSourceException(message = message, cause = cause)
        class PublicKeyException(message: String? = null, cause: Throwable? = null) :
            FundingSourceException(message = message, cause = cause)
        class AuthenticationException(message: String? = null, cause: Throwable? = null) :
            FundingSourceException(message = message, cause = cause)
        class FundingSourceRequiresUserInteractionException(
            message: String? = null,
            val interactionData: ProviderUserInteractionData,
        ) : FundingSourceException(message = message)
        class UnknownException(cause: Throwable) :
            FundingSourceException(cause = cause)
    }

    /**
     * Defines the exceptions for the virtual payment card based methods.
     *
     * @property message [String] Accompanying message for the exception.
     * @property cause [Throwable] The cause of the exception.
     */
    sealed class VirtualCardException(message: String? = null, cause: Throwable? = null) : RuntimeException(message, cause) {
        class FailedException(message: String? = null, cause: Throwable? = null) :
            VirtualCardException(message = message, cause = cause)
        class ProvisionFailedException(message: String? = null, cause: Throwable? = null) :
            VirtualCardException(message = message, cause = cause)
        class UpdateFailedException(message: String? = null, cause: Throwable? = null) :
            VirtualCardException(message = message, cause = cause)
        class CancelFailedException(message: String? = null, cause: Throwable? = null) :
            VirtualCardException(message = message, cause = cause)
        class CardNotFoundException(message: String? = null, cause: Throwable? = null) :
            VirtualCardException(message = message, cause = cause)
        class CardStateException(message: String? = null, cause: Throwable? = null) :
            VirtualCardException(message = message, cause = cause)
        class FundingSourceNotFoundException(message: String? = null, cause: Throwable? = null) :
            VirtualCardException(message = message, cause = cause)
        class FundingSourceNotActiveException(message: String? = null, cause: Throwable? = null) :
            VirtualCardException(message = message, cause = cause)
        class VelocityExceededException(message: String? = null, cause: Throwable? = null) :
            VirtualCardException(message = message, cause = cause)
        class EntitlementExceededException(message: String? = null, cause: Throwable? = null) :
            VirtualCardException(message = message, cause = cause)
        class UnsupportedCurrencyException(message: String? = null, cause: Throwable? = null) :
            VirtualCardException(message = message, cause = cause)
        class IdentityVerificationException(message: String? = null, cause: Throwable? = null) :
            VirtualCardException(message = message, cause = cause)
        class IdentityVerificationInsufficientException(message: String? = null, cause: Throwable? = null) :
            VirtualCardException(message = message, cause = cause)
        class PublicKeyException(message: String? = null, cause: Throwable? = null) :
            VirtualCardException(message = message, cause = cause)
        class UnsealingException(message: String? = null, cause: Throwable? = null) :
            VirtualCardException(message = message, cause = cause)
        class AccountLockedException(message: String? = null, cause: Throwable? = null) :
            VirtualCardException(message = message, cause = cause)
        class UnknownException(cause: Throwable) :
            VirtualCardException(cause = cause)
    }

    /**
     * Defines the exceptions for the virtual payment transaction based methods.
     *
     * @property message [String] Accompanying message for the exception.
     * @property cause [Throwable] The cause of the exception.
     */
    sealed class TransactionException(message: String? = null, cause: Throwable? = null) : RuntimeException(message, cause) {
        class FailedException(message: String? = null, cause: Throwable? = null) :
            TransactionException(message = message, cause = cause)
        class PublicKeyException(message: String? = null, cause: Throwable? = null) :
            TransactionException(message = message, cause = cause)
        class UnsealingException(message: String? = null, cause: Throwable? = null) :
            TransactionException(message = message, cause = cause)
        class AuthenticationException(message: String? = null, cause: Throwable? = null) :
            TransactionException(message = message, cause = cause)
        class UnsupportedTransactionTypeException(message: String? = null, cause: Throwable? = null) :
            TransactionException(message = message, cause = cause)
        class UnknownException(cause: Throwable) :
            TransactionException(cause = cause)
    }

    /**
     * Defines the exceptions for the virtual card cryptographic keys based methods.
     *
     * @property message [String] Accompanying message for the exception.
     * @property cause [Throwable] The cause for the exception.
     */
    sealed class VirtualCardCryptographicKeysException(
        message: String? = null,
        cause: Throwable? = null,
    ) : RuntimeException(message, cause) {
        class SecureKeyArchiveException(message: String? = null, cause: Throwable? = null) :
            VirtualCardCryptographicKeysException(message = message, cause = cause)
    }

    /**
     * Create key pair and secret key for use by the virtual cards client if they have not
     * already been created.
     *
     * The key pair is used to register a public key with the service for the encryption of
     * virtual card and transaction details.
     *
     * The secret key is used for client side encryption of user specific card metadata.
     *
     * @return The [CreateKeysIfAbsentResult] containing the key pair and symmetric key.
     *
     * @throws [VirtualCardException].
     */
    @Throws(VirtualCardException::class)
    suspend fun createKeysIfAbsent(): CreateKeysIfAbsentResult

    /**
     * Begin the setup of a [ProvisionalFundingSource].
     *
     * @param input [SetupFundingSourceInput] Parameters used to setup the provisional funding source.
     * @return The created [ProvisionalFundingSource] in a provisioning state.
     *
     * @throws [FundingSourceException].
     */
    @Throws(FundingSourceException::class)
    suspend fun setupFundingSource(input: SetupFundingSourceInput): ProvisionalFundingSource

    /**
     * Complete the creation of a [FundingSource].
     *
     * @param input [CompleteFundingSourceInput] Parameters used to complete the creation of a funding source.
     * @return The created [FundingSource].
     *
     * @throws [FundingSourceException].
     */
    @Throws(FundingSourceException::class)
    suspend fun completeFundingSource(input: CompleteFundingSourceInput): FundingSource

    /**
     * Refresh a [FundingSource] currently in REFRESH state.
     *
     * @param input [RefreshFundingSourceInput] Parameters used to complete the creation of a funding source.
     * @return The refreshed [FundingSource].
     *
     * @throws [FundingSourceException].
     */
    @Throws(FundingSourceException::class)
    suspend fun refreshFundingSource(input: RefreshFundingSourceInput): FundingSource

    /**
     * Get the [FundingSourceClientConfiguration] from the service.
     *
     * @return The [FundingSourceClientConfiguration] of the client funding source data.
     */
    @Deprecated("Use getVirtualCardsConfig instead to retrieve the FundingSourceClientConfiguration")
    @Throws(FundingSourceException::class)
    suspend fun getFundingSourceClientConfiguration(): List<FundingSourceClientConfiguration>

    /**
     * Get a [FundingSource] using the [id] parameter.
     *
     * @param id [String] Identifier of the [FundingSource] to be retrieved.
     * @param cachePolicy [CachePolicy] Determines how the data will be fetched. When using [CachePolicy.CACHE_ONLY],
     *  be aware that this will only return cached results of identical API calls.
     * @return The [FundingSource] associated with the [id] or null if it could not be found.
     *
     * @throws [FundingSourceException].
     */
    @Throws(FundingSourceException::class)
    suspend fun getFundingSource(id: String, cachePolicy: CachePolicy = CachePolicy.REMOTE_ONLY): FundingSource?

    /**
     * Get a [ListOutput] of [FundingSource]s.
     *
     * If no [FundingSource]s can be found, the [ListOutput] will contain null for the [ListOutput.nextToken]
     * field and contain an empty [ListOutput.items] list.
     *
     * @param limit [Int] Maximum number of [FundingSource]s to return. If omitted the limit defaults to 10.
     * @param nextToken [String] A token generated from previous calls to [listFundingSources].
     *  This is to allow for pagination. This value should be generated from a
     *  previous pagination call, otherwise it will throw an exception. The same
     *  arguments should be supplied to this method if using a previously generated [nextToken].
     * @param cachePolicy [CachePolicy] Determines how the data will be fetched. When using [CachePolicy.CACHE_ONLY],
     *  be aware that this will only return cached results of identical API calls.
     * @return A list of [FundingSource]s or an empty list if no matching funding sources can be found.
     *
     * @throws [FundingSourceException].
     */
    @Throws(FundingSourceException::class)
    suspend fun listFundingSources(
        limit: Int = DEFAULT_FUNDING_SOURCE_LIMIT,
        nextToken: String? = null,
        cachePolicy: CachePolicy = CachePolicy.REMOTE_ONLY,
    ): ListOutput<FundingSource>

    /**
     * Cancel a [FundingSource] using the [id] parameter.
     *
     * @param id [String] Identifier of the [FundingSource] to cancel.
     * @return The [FundingSource] that has been cancelled and is in an inactive state.
     *
     * @throws [FundingSourceException].
     */
    @Throws(FundingSourceException::class)
    suspend fun cancelFundingSource(id: String): FundingSource

    /**
     * Review an unfunded [FundingSource] using the [id] parameter.
     *
     * @param id [String] Identifier of the [FundingSource] to review.
     * @return The reviewed [FundingSource].
     *
     * @throws [FundingSourceException].
     */
    @Throws(FundingSourceException::class)
    suspend fun reviewUnfundedFundingSource(id: String): FundingSource

    /**
     * Provision a [VirtualCard].
     *
     * Initially a [ProvisionalVirtualCard] is returned with the details of the virtual card being
     * created. The state of this virtual card can be queried periodically by calling
     * [getProvisionalCard] to monitor the card's progress from a provisioning state to a completed
     * or failed state.
     *
     * @param input [ProvisionVirtualCardInput] Parameters used to provision a virtual card.
     * @return A [ProvisionalVirtualCard] in a provisioning state.
     *
     * @throws [VirtualCardException].
     */
    @Throws(VirtualCardException::class)
    suspend fun provisionVirtualCard(input: ProvisionVirtualCardInput): ProvisionalVirtualCard

    /**
     * Get a [ProvisionalVirtualCard] using the [id] parameter.
     *
     * A provisional virtual card is one that is in the process of being provisioned.
     *
     * @param id [String] Identifier of the [ProvisionalVirtualCard] to be retrieved.
     * @param cachePolicy [CachePolicy] Determines how the data will be fetched. When using [CachePolicy.CACHE_ONLY],
     *  be aware that this will only return cached results of identical API calls.
     * @return The [ProvisionalVirtualCard] associated with the [id] or null if it could not be found.
     *
     * @throws [VirtualCardException].
     */
    @Throws(VirtualCardException::class)
    suspend fun getProvisionalCard(id: String, cachePolicy: CachePolicy = CachePolicy.REMOTE_ONLY): ProvisionalVirtualCard?

    /**
     * Get a [VirtualCard] using the [id] parameter.
     *
     * @param id [String] Identifier of the [VirtualCard] to be retrieved.
     * @param cachePolicy [CachePolicy] Determines how the data will be fetched. When using [CachePolicy.CACHE_ONLY],
     *  be aware that this will only return cached results of identical API calls.
     * @returns The [VirtualCard] associated with the [id] or null if it cannot be found.
     *
     * @throws [VirtualCardException].
     */
    @Throws(VirtualCardException::class)
    suspend fun getVirtualCard(id: String, cachePolicy: CachePolicy = CachePolicy.REMOTE_ONLY): VirtualCard?

    /**
     * Get the current [VirtualCardsConfig].
     *
     * @param cachePolicy [CachePolicy] Determines how the data will be fetched. When using [CachePolicy.CACHE_ONLY],
     *  be aware that this will only return cached results of identical API calls.
     * @returns The [VirtualCardsConfig] or null if it cannot be found.
     *
     * @throws [VirtualCardException].
     */
    @Throws(VirtualCardException::class)
    suspend fun getVirtualCardsConfig(cachePolicy: CachePolicy = CachePolicy.REMOTE_ONLY): VirtualCardsConfig?

    /**
     * Get a list of [VirtualCard]s.
     *
     * This API returns a [ListAPIResult]:
     * - On [ListAPIResult.Success] result, contains the list of requested [VirtualCard]s.
     * - On [ListAPIResult.Partial] result, contains the list of [PartialVirtualCard]s representing
     *    virtual cards that could not be unsealed successfully and the exception indicating why
     *    the unsealing failed. A virtual card may fail to unseal if the client version is not up to
     *    date or the required cryptographic key is missing from the client device.
     *
     * If no [VirtualCard]s can be found, the result will contain null for the [nextToken] field and
     * contain an empty item list.
     *
     * To ensure you obtain all the virtual cards that match, you should continue to call this method
     * whenever the [nextToken] field is not null, even if the items list is empty.
     *
     * @param limit [Int] Maximum number of [VirtualCard]s to return. If omitted the limit defaults to 10.
     * @param nextToken [String] A token generated from previous calls to [listVirtualCards].
     *  This is to allow for pagination. This value should be generated from a
     *  previous pagination call, otherwise it will throw an exception. The same
     *  arguments should be passed to this method if using a previously generated [nextToken].
     * @param cachePolicy [CachePolicy] Determines how the data will be fetched. When using [CachePolicy.CACHE_ONLY],
     *  be aware that this will only return cached results of identical API calls.
     * @return A [ListAPIResult.Success] or a [ListAPIResult.Partial] result containing either a list of
     *  [VirtualCard]s or [PartialVirtualCard]s respectively. Returns an empty list if no virtual cards can be found.
     *
     * @throws [VirtualCardException].
     */
    @Throws(VirtualCardException::class)
    suspend fun listVirtualCards(
        limit: Int = DEFAULT_CARD_LIMIT,
        nextToken: String? = null,
        cachePolicy: CachePolicy = CachePolicy.REMOTE_ONLY,
    ): ListAPIResult<VirtualCard, PartialVirtualCard>

    /**
     * Update the details of a [VirtualCard].
     *
     * Be aware that when updating a virtual card, all fields that should remain unchanged must be
     * populated as part of the input.
     *
     * This API returns a [SingleAPIResult]:
     * - On [SingleAPIResult.Success] result, contains the updated [VirtualCard].
     * - On [SingleAPIResult.Partial] result, contains the updated [PartialVirtualCard] representing
     *    a virtual card that could not be unsealed successfully and the exception indicating why
     *    the unsealing failed. A virtual card may fail to unseal if the client version is not up to
     *    date or the required cryptographic key is missing from the client device.
     *
     * @param input [UpdateVirtualCardInput] Parameters used to update a virtual card.
     * @return A [SingleAPIResult.Success] or a [SingleAPIResult.Partial] result containing either a
     *  [VirtualCard] or [PartialVirtualCard] respectively.
     *
     * @throws [VirtualCardException].
     */
    @Throws(VirtualCardException::class)
    suspend fun updateVirtualCard(input: UpdateVirtualCardInput): SingleAPIResult<VirtualCard, PartialVirtualCard>

    /**
     * Cancel a [VirtualCard] using the [id] parameter.
     *
     * This API returns a [SingleAPIResult]:
     * - On [SingleAPIResult.Success] result, contains the cancelled [VirtualCard].
     * - On [SingleAPIResult.Partial] result, contains the cancelled [PartialVirtualCard] representing
     *    a virtual card that could not be unsealed successfully and the exception indicating why
     *    the unsealing failed. A virtual card may fail to unseal if the client version is not up to
     *    date or the required cryptographic key is missing from the client device.
     *
     * @param id [String] Identifier of the [VirtualCard] to cancel.
     * @return A [SingleAPIResult.Success] or a [SingleAPIResult.Partial] result containing either a
     *  [VirtualCard] or [PartialVirtualCard] respectively that has been cancelled and is in a closed state.
     *
     * @throws [VirtualCardException].
     */
    @Throws(VirtualCardException::class)
    suspend fun cancelVirtualCard(id: String): SingleAPIResult<VirtualCard, PartialVirtualCard>

    /**
     * Get a [Transaction] using the [id] parameter.
     *
     * @param id [String] Identifier of the [Transaction] to be retrieved.
     * @param cachePolicy [CachePolicy] Determines how the data will be fetched. When using [CachePolicy.CACHE_ONLY],
     *  be aware that this will only return cached results of identical API calls.
     * @returns The [Transaction] associated with the [id] or null if it cannot be found.
     *
     * @throws [TransactionException].
     */
    @Throws(TransactionException::class)
    suspend fun getTransaction(id: String, cachePolicy: CachePolicy = CachePolicy.REMOTE_ONLY): Transaction?

    /**
     * Get a list of all [Transaction]s related to a virtual card.
     *
     * This API returns a [ListAPIResult]:
     * - On [ListAPIResult.Success] result, contains the list of requested [Transaction]s.
     * - On [ListAPIResult.Partial] result, contains the list of [PartialTransaction]s representing
     *    transactions that could not be unsealed successfully and the exception indicating why
     *    the unsealing failed. A transaction may fail to unseal if the client version is not up to
     *    date or the required cryptographic key is missing from the client device.
     *
     * If no [Transaction]s can be found, the result will contain null for the [nextToken] field and
     * contain an empty item list.
     *
     * To ensure you obtain all the transactions that match, you should continue to call this method
     * whenever the [nextToken] field is not null, even if the items list is empty.
     *
     * @param cardId [String] Identifier of the virtual card to list for related transactions.
     * @param limit [Int] Maximum number of [Transaction]s to return. If omitted the limit defaults to 100.
     * @param nextToken [String] A token generated from previous calls to [listTransactionsByCardId].
     *  This is to allow for pagination. This value should be generated from a
     *  previous pagination call, otherwise it will throw an exception. You should call
     *  this method with the same argument if you are using a previously generated [nextToken].
     * @param cachePolicy [CachePolicy] Determines how the data will be fetched. When using [CachePolicy.CACHE_ONLY],
     *  be aware that this will only return cached results of identical API calls.
     * @param dateRange [DateRange] The date range of transactions to return.
     * @param sortOrder [SortOrder] The order in which the transactions are returned. Defaults to descending.
     * @return A [ListAPIResult.Success] or a [ListAPIResult.Partial] result containing either a list of
     *  [Transaction]s or [PartialTransaction]s respectively. Returns an empty list if no transactions can be found.
     *
     * @throws [TransactionException].
     */
    @Throws(TransactionException::class)
    suspend fun listTransactionsByCardId(
        cardId: String,
        limit: Int = DEFAULT_TRANSACTION_LIMIT,
        nextToken: String? = null,
        cachePolicy: CachePolicy = CachePolicy.REMOTE_ONLY,
        dateRange: DateRange? = null,
        sortOrder: SortOrder = SortOrder.DESC,
    ): ListAPIResult<Transaction, PartialTransaction>

    /**
     * Get a list of all [Transaction]s for a certain type related to a virtual card.
     *
     * This API returns a [ListAPIResult]:
     * - On [ListAPIResult.Success] result, contains the list of requested [Transaction]s.
     * - On [ListAPIResult.Partial] result, contains the list of [PartialTransaction]s representing
     *    transactions that could not be unsealed successfully and the exception indicating why
     *    the unsealing failed. A transaction may fail to unseal if the client version is not up to
     *    date or the required cryptographic key is missing from the client device.
     *
     * If no [Transaction]s can be found, the result will contain null for the [nextToken] field and
     * contain an empty item list.
     *
     * To ensure you obtain all the transactions that match, you should continue to call this method
     * whenever the [nextToken] field is not null, even if the items list is empty.
     *
     * @param cardId [String] Identifier of the virtual card to list for related transactions.
     * @param transactionType [TransactionType] The type of transaction to retrieve.
     * @param limit [Int] Maximum number of [Transaction]s to return. If omitted the limit defaults to 100.
     * @param nextToken [String] A token generated from previous calls to [listTransactionsByCardId].
     *  This is to allow for pagination. This value should be generated from a
     *  previous pagination call, otherwise it will throw an exception. You should call
     *  this method with the same argument if you are using a previously generated [nextToken].
     * @param cachePolicy [CachePolicy] Determines how the data will be fetched. When using [CachePolicy.CACHE_ONLY],
     *  be aware that this will only return cached results of identical API calls.
     * @return A [ListAPIResult.Success] or a [ListAPIResult.Partial] result containing either a list of
     *  [Transaction]s or [PartialTransaction]s respectively. Returns an empty list if no transactions can be found.
     *
     * @throws [TransactionException].
     */
    @Throws(TransactionException::class)
    suspend fun listTransactionsByCardIdAndType(
        cardId: String,
        transactionType: TransactionType,
        limit: Int = DEFAULT_TRANSACTION_LIMIT,
        nextToken: String? = null,
        cachePolicy: CachePolicy = CachePolicy.REMOTE_ONLY,
    ): ListAPIResult<Transaction, PartialTransaction>

    /**
     * Get a list of all [Transaction]s across all virtual cards.
     *
     * This API returns a [ListAPIResult]:
     * - On [ListAPIResult.Success] result, contains the list of requested [Transaction]s.
     * - On [ListAPIResult.Partial] result, contains the list of [PartialTransaction]s representing
     *    transactions that could not be unsealed successfully and the exception indicating why
     *    the unsealing failed. A transaction may fail to unseal if the client version is not up to
     *    date or the required cryptographic key is missing from the client device.
     *
     * If no [Transaction]s can be found, the result will contain null for the [nextToken] field and
     * contain an empty item list.
     *
     * To ensure you obtain all the transactions that match, you should continue to call this method
     * whenever the [nextToken] field is not null, even if the items list is empty.
     *
     * @param limit [Int] Maximum number of [Transaction]s to return. If omitted the limit defaults to 100.
     * @param nextToken [String] A token generated from previous calls to [listTransactionsByCardId].
     *  This is to allow for pagination. This value should be generated from a
     *  previous pagination call, otherwise it will throw an exception. You should call
     *  this method with the same argument if you are using a previously generated [nextToken].
     * @param cachePolicy [CachePolicy] Determines how the data will be fetched. When using [CachePolicy.CACHE_ONLY],
     *  be aware that this will only return cached results of identical API calls.
     * @param dateRange [DateRange] The date range of transactions to return.
     * @param sortOrder [SortOrder] The order in which the transactions are returned. Defaults to descending.
     * @return A [ListAPIResult.Success] or a [ListAPIResult.Partial] result containing either a list of
     *  [Transaction]s or [PartialTransaction]s respectively. Returns an empty list if no transactions can be found.
     *
     * @throws [TransactionException].
     */
    @Throws(TransactionException::class)
    suspend fun listTransactions(
        limit: Int = DEFAULT_TRANSACTION_LIMIT,
        nextToken: String? = null,
        cachePolicy: CachePolicy = CachePolicy.REMOTE_ONLY,
        dateRange: DateRange? = null,
        sortOrder: SortOrder = SortOrder.DESC,
    ): ListAPIResult<Transaction, PartialTransaction>

    /**
     * Subscribes to be notified of new, updated and deleted [Transaction]s. Subscribing multiple
     * times with the same subscriber id will cause the previous subscriber to be unsubscribed.
     *
     * @param id [String] Unique identifier of the subscriber.
     * @param subscriber [TransactionSubscriber] Subscriber to notify.
     */
    @Throws(TransactionException::class)
    suspend fun subscribeToTransactions(id: String, subscriber: TransactionSubscriber)

    /**
     * Unsubscribe the specified subscriber so that it no longer receives notifications about
     * new, updated or deleted [Transaction]s.
     *
     * @param id [String] Unique identifier of the subscriber.
     */
    suspend fun unsubscribeFromTransactions(id: String)

    /**
     * Unsubscribe all subscribers from receiving notifications about new, updated or
     * deleted [Transaction]s.
     */
    suspend fun unsubscribeAllFromTransactions()

    @Deprecated("Use unsubscribeAllFromTransactions instead")
    suspend fun unsubscribeAll()

    /**
     * Subscribes to be notified of modified [FundingSource]s. Subscribing multiple times with the same
     * subscriber id will cause the previous subscriber to be unsubscribed.
     *
     * @param id [String] Unique identifier of the subscriber.
     * @param subscriber [FundingSourceSubscriber] Subscriber to notify.
     */
    @Throws(FundingSourceException::class)
    suspend fun subscribeToFundingSources(id: String, subscriber: FundingSourceSubscriber)

    /**
     * Unsubscribe the specified subscriber so that it no longer receives notifications about
     * modified [FundingSource]s.
     *
     * @param id [String] Unique identifier of the subscriber.
     */
    suspend fun unsubscribeFromFundingSources(id: String)

    /**
     * Unsubscribe all subscribers from receiving notifications about modifications to [FundingSource]s.
     */
    suspend fun unsubscribeAllFromFundingSources()

    /**
     * Sandbox API to obtain data normally returned by full Plaid Link flow. Useful for testing
     * ahead of full Plaid Link integration during application development.
     *
     * @param institutionId [String] ID of Plaid sandbox institution to use
     * @param plaidUsername [String] Username of Plaid sandbox user to obtain data for
     * @returns [SandboxPlaidData]
     *   Sandbox Plaid data for provisioning new funding
     *   source at requested institution and user
     * @throws(FundingSourceException::class)
     */
    @Throws(FundingSourceException::class)
    suspend fun sandboxGetPlaidData(institutionId: String, plaidUsername: String): SandboxPlaidData

    /**
     * Sandbox API to set a funding source to refresh state to facilitate testing.
     *
     * @param fundingSourceId [String] ID of funding source to set to require refresh.
     *
     * @returns [FundingSource] The funding source.
     */
    @Throws(FundingSourceException::class)
    suspend fun sandboxSetFundingSourceToRequireRefresh(fundingSourceId: String): FundingSource

    /**
     * Import cryptographic keys from a key archive.
     *
     * @param archiveData [Data] Key archive data to import the keys from.
     *
     * @throws [VirtualCardCryptographicKeysException]
     */
    @Throws(VirtualCardCryptographicKeysException::class)
    suspend fun importKeys(archiveData: ByteArray)

    /**
     * Export the cryptographic keys to a key archive.
     *
     * @returns Key archive data.
     *
     * @throws [VirtualCardCryptographicKeysException]
     */
    @Throws(VirtualCardCryptographicKeysException::class)
    suspend fun exportKeys(): ByteArray

    /**
     * Reset any internal state and cached content.
     */
    fun reset()
}

/**
 * Subscribes to be notified of modified [FundingSource]s.
 *
 * @param id [String] Unique identifier of the subscriber.
 * @param onConnectionChange lambda that is called when the subscription connection state changes.
 * @param onFundingSourceChanged lambda that receives updates as [FundingSource]s are modified.
 */
@Throws(SudoVirtualCardsClient.FundingSourceException::class)
suspend fun SudoVirtualCardsClient.subscribeToFundingSources(
    id: String,
    onConnectionChange: (status: Subscriber.ConnectionState) -> Unit = {},
    onFundingSourceChanged: (fundingSource: FundingSource) -> Unit,
) =
    subscribeToFundingSources(
        id,
        object : FundingSourceSubscriber {
            override fun connectionStatusChanged(state: Subscriber.ConnectionState) {
                onConnectionChange.invoke(state)
            }
            override fun fundingSourceChanged(fundingSource: FundingSource) {
                onFundingSourceChanged.invoke(fundingSource)
            }
        },
    )

/**
 * Subscribes to be notified of new, updated and deleted [Transaction]s.
 *
 * @param id [String] Unique identifier of the subscriber.
 * @param onConnectionChange lambda that is called when the subscription connection state changes.
 * @param onTransactionChange lambda that receives updates as [Transaction]s change.
 */
@Throws(SudoVirtualCardsClient.TransactionException::class)
suspend fun SudoVirtualCardsClient.subscribeToTransactions(
    id: String,
    onConnectionChange: (status: TransactionSubscriber.ConnectionState) -> Unit = {},
    onTransactionChange: (transaction: Transaction) -> Unit,
) =
    subscribeToTransactions(
        id,
        object : TransactionSubscriber {
            override fun connectionStatusChanged(state: TransactionSubscriber.ConnectionState) {
                onConnectionChange.invoke(state)
            }
            override fun transactionChanged(transaction: Transaction) {
                onTransactionChange.invoke(transaction)
            }
        },
    )
