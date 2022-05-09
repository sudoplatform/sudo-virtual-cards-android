/*
 * Copyright © 2022 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards

import android.content.Context
import com.amazonaws.mobileconnectors.appsync.AWSAppSyncClient
import com.sudoplatform.sudoapiclient.ApiClientManager
import com.sudoplatform.sudokeymanager.KeyManagerFactory
import com.sudoplatform.sudokeymanager.KeyManagerInterface
import com.sudoplatform.sudologging.AndroidUtilsLogDriver
import com.sudoplatform.sudologging.LogLevel
import com.sudoplatform.sudologging.Logger
import com.sudoplatform.sudoprofiles.SudoProfilesClient
import com.sudoplatform.sudouser.SudoUserClient
import com.sudoplatform.sudovirtualcards.keys.DefaultDeviceKeyManager
import com.sudoplatform.sudovirtualcards.keys.DefaultPublicKeyService
import com.sudoplatform.sudovirtualcards.logging.LogConstants
import com.sudoplatform.sudovirtualcards.subscription.TransactionSubscriber
import com.sudoplatform.sudovirtualcards.types.CachePolicy
import com.sudoplatform.sudovirtualcards.types.CreateKeysIfAbsentResult
import com.sudoplatform.sudovirtualcards.types.DateRange
import com.sudoplatform.sudovirtualcards.types.FundingSource
import com.sudoplatform.sudovirtualcards.types.FundingSourceClientConfiguration
import com.sudoplatform.sudovirtualcards.types.ListOutput
import com.sudoplatform.sudovirtualcards.types.ProvisionalFundingSource
import com.sudoplatform.sudovirtualcards.types.ProvisionalVirtualCard
import com.sudoplatform.sudovirtualcards.types.SortOrder
import com.sudoplatform.sudovirtualcards.types.Transaction
import com.sudoplatform.sudovirtualcards.types.VirtualCard
import com.sudoplatform.sudovirtualcards.types.inputs.CompleteFundingSourceInput
import com.sudoplatform.sudovirtualcards.types.inputs.ProvisionVirtualCardInput
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
        private var sudoProfilesClient: SudoProfilesClient? = null
        private var appSyncClient: AWSAppSyncClient? = null
        private var keyManager: KeyManagerInterface? = null
        private var logger: Logger = Logger(LogConstants.SUDOLOG_TAG, AndroidUtilsLogDriver(LogLevel.INFO))

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
         * Provide the implementation of the [SudoProfilesClient] used to perform
         * ownership proof lifecycle operations (required input).
         */
        fun setSudoProfilesClient(sudoProfilesClient: SudoProfilesClient) = also {
            this.sudoProfilesClient = sudoProfilesClient
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
         * Construct the [SudoVirtualCardsClient]. Will throw a [NullPointerException] if
         * the [context], [sudoUserClient] and [sudoProfilesClient] has not been provided.
         */
        fun build(): SudoVirtualCardsClient {
            Objects.requireNonNull(context, "Context must be provided.")
            Objects.requireNonNull(sudoUserClient, "SudoUserClient must be provided.")
            Objects.requireNonNull(sudoProfilesClient, "SudoProfilesClient must be provided.")

            val appSyncClient = appSyncClient ?: ApiClientManager.getClient(this@Builder.context!!, this@Builder.sudoUserClient!!)

            val deviceKeyManager = DefaultDeviceKeyManager(
                keyRingServiceName = "sudo-virtual-cards",
                userClient = sudoUserClient!!,
                keyManager = keyManager ?: KeyManagerFactory(context!!).createAndroidKeyManager(DEFAULT_KEY_NAMESPACE)
            )

            val publicKeyService = DefaultPublicKeyService(
                deviceKeyManager = deviceKeyManager,
                appSyncClient = appSyncClient,
                logger = logger
            )

            return DefaultSudoVirtualCardsClient(
                context = context!!,
                appSyncClient = appSyncClient,
                sudoUserClient = sudoUserClient!!,
                sudoProfilesClient = sudoProfilesClient!!,
                logger = logger,
                deviceKeyManager = deviceKeyManager,
                publicKeyService = publicKeyService
            )
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
        class CancelFailedException(message: String? = null, cause: Throwable? = null) :
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
        class IdentityVerificationException(message: String? = null, cause: Throwable? = null) :
            FundingSourceException(message = message, cause = cause)
        class AccountLockedException(message: String? = null, cause: Throwable? = null) :
            FundingSourceException(message = message, cause = cause)
        class EntitlementExceededException(message: String? = null, cause: Throwable? = null) :
            FundingSourceException(message = message, cause = cause)
        class VelocityExceededException(message: String? = null, cause: Throwable? = null) :
            FundingSourceException(message = message, cause = cause)
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
        class UnknownException(cause: Throwable) :
            TransactionException(cause = cause)
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
     * Get the [FundingSourceClientConfiguration] from the service.
     *
     * @return The [FundingSourceClientConfiguration] of the client funding source data.
     */
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
     * @param limit [Int] Number of [FundingSource]s to return. If omitted the limit defaults to 10.
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
        cachePolicy: CachePolicy = CachePolicy.REMOTE_ONLY
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
     * Get a [ListOutput] of [VirtualCard]s.
     *
     * If no [VirtualCard]s can be found, the [ListOutput] will contain null for the [ListOutput.nextToken]
     * field and contain an empty [ListOutput.items] list.
     *
     * To ensure you obtain all the virtual cards that match, you should continue to call this method
     * whenever the [ListOutput.nextToken] field is not null, even if the [ListOutput.items] is empty.
     *
     * @param limit [Int] Number of [VirtualCard]s to return. If omitted the limit defaults to 10.
     * @param nextToken [String] A token generated from previous calls to [listVirtualCards].
     *  This is to allow for pagination. This value should be generated from a
     *  previous pagination call, otherwise it will throw an exception. The same
     *  arguments should be passed to this method if using a previously generated [nextToken].
     * @param cachePolicy [CachePolicy] Determines how the data will be fetched. When using [CachePolicy.CACHE_ONLY],
     *  be aware that this will only return cached results of identical API calls.
     * @return A list of [VirtualCard]s or an empty list if no virtual cards can be found.
     *
     * @throws [VirtualCardException].
     */
    @Throws(VirtualCardException::class)
    suspend fun listVirtualCards(
        limit: Int = DEFAULT_CARD_LIMIT,
        nextToken: String? = null,
        cachePolicy: CachePolicy = CachePolicy.REMOTE_ONLY
    ): ListOutput<VirtualCard>

    /**
     * Update the details of a [VirtualCard].
     *
     * Be aware that when updating a virtual card, all fields that should remain unchanged must be
     * populated as part of the input.
     *
     * @param input [UpdateVirtualCardInput] Parameters used to update a virtual card.
     * @return The [VirtualCard] that was updated.
     *
     * @throws [VirtualCardException].
     */
    @Throws(VirtualCardException::class)
    suspend fun updateVirtualCard(input: UpdateVirtualCardInput): VirtualCard

    /**
     * Cancel a [VirtualCard] using the [id] parameter.
     *
     * @param id [String] Identifier of the [VirtualCard] to cancel.
     * @return The [VirtualCard] that has been cancelled and is in a closed state.
     *
     * @throws [VirtualCardException].
     */
    @Throws(VirtualCardException::class)
    suspend fun cancelVirtualCard(id: String): VirtualCard

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
     * Get a [ListOutput] of all [Transaction]s related to a virtual card.
     *
     * If no [Transaction]s can be found, the [ListOutput] will contain null for the [ListOutput.nextToken]
     * field and contain an empty [ListOutput.items] list.
     *
     * To ensure you obtain all the transactions that match, you should continue to call this method
     * whenever the [ListOutput.nextToken] field is not null, even if the [ListOutput.items] is empty.
     *
     * @param cardId [String] Identifier of the virtual card to list for related transactions.
     * @param limit [Int] Number of [Transaction]s to return. If omitted the limit defaults to 100.
     * @param nextToken [String] A token generated from previous calls to [listTransactions].
     *  This is to allow for pagination. This value should be generated from a
     *  previous pagination call, otherwise it will throw an exception. You should call
     *  this method with the same argument if you using a previously generated [ListOutput.nextToken].
     * @param cachePolicy [CachePolicy] Determines how the data will be fetched. When using [CachePolicy.CACHE_ONLY],
     *  be aware that this will only return cached results of identical API calls.
     * @param dateRange [DateRange] The date range of transactions to return.
     * @param sortOrder [SortOrder] The order in which the transactions are returned. Defaults to descending.
     * @return A list of [Transaction]s or an empty list if no transactions can be found.
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
        sortOrder: SortOrder = SortOrder.DESC
    ): ListOutput<Transaction>

    /**
     * Subscribes to be notified of new, updated and deleted [Transaction]s.
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
    suspend fun unsubscribeAll()

    /**
     * Reset any internal state and cached content.
     */
    fun reset()
}

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
    onTransactionChange: (transaction: Transaction) -> Unit
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
        }
    )
