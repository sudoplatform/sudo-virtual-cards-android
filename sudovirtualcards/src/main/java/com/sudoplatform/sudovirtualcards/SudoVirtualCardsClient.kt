/*
 * Copyright © 2020 Anonyome Labs, Inc. All rights reserved.
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
import com.sudoplatform.sudovirtualcards.types.Card
import com.sudoplatform.sudovirtualcards.types.FundingSource
import com.sudoplatform.sudovirtualcards.types.ListOutput
import com.sudoplatform.sudovirtualcards.types.ProvisionalCard
import com.sudoplatform.sudovirtualcards.types.Transaction
import com.sudoplatform.sudovirtualcards.types.inputs.CreditCardFundingSourceInput
import com.sudoplatform.sudovirtualcards.types.inputs.ProvisionCardInput
import com.sudoplatform.sudovirtualcards.types.inputs.filters.TransactionFilter
import com.sudoplatform.sudovirtualcards.types.inputs.UpdateCardInput
import com.sudoplatform.sudovirtualcards.types.inputs.filters.CardFilter
import java.util.Objects

/**
 * Interface encapsulating a library for interacting with the Sudo Platform Virtual Cards service.
 *
 * @sample com.sudoplatform.sudovirtualcards.samples.Samples.sudoVirtualCardsClient
 * @since 2020-05-21
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
        private var paymentProcessInteractions: PaymentProcessInteractions? = null
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
         * Provide the implementation of the [PaymentProcessInteractions] used to perform
         * Payment setup confirmation. This is primarily only supplied in order to mock this
         * functionality when testing (optional input).
         */
        fun setPaymentProcessInteractions(paymentProcessInteractions: PaymentProcessInteractions) = also {
            this.paymentProcessInteractions = paymentProcessInteractions
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

            paymentProcessInteractions?.let {
                return DefaultSudoVirtualCardsClient(
                    context = context!!,
                    appSyncClient = appSyncClient,
                    sudoUserClient = sudoUserClient!!,
                    sudoProfilesClient = sudoProfilesClient!!,
                    paymentProcessInteractions = it,
                    logger = logger,
                    deviceKeyManager = deviceKeyManager,
                    publicKeyService = publicKeyService
                )
            }
                ?: return DefaultSudoVirtualCardsClient(
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
     * @property message Accompanying message for the exception.
     * @property cause The cause for the exception.
     */
    sealed class FundingSourceException(message: String? = null, cause: Throwable? = null) : RuntimeException(message, cause) {
        class FailedException(message: String? = null, cause: Throwable? = null) :
            FundingSourceException(message = message, cause = cause)
        class SetupFailedException(message: String? = null, cause: Throwable? = null) :
            FundingSourceException(message = message, cause = cause)
        class CompletionFailedException(message: String? = null, cause: Throwable? = null) :
            FundingSourceException(message = message, cause = cause)
        class CancelFailedException(message: String? = null, cause: Throwable? = null) :
            FundingSourceException(message = message, cause = cause)
        class DuplicateFundingSourceException(message: String? = null, cause: Throwable? = null) :
            FundingSourceException(message = message, cause = cause)
        class FundingSourceNotFoundException(message: String? = null, cause: Throwable? = null) :
            FundingSourceException(message = message, cause = cause)
        class UnacceptableFundingSourceException(message: String? = null, cause: Throwable? = null) :
            FundingSourceException(message = message, cause = cause)
        class UnsupportedCurrencyException(message: String? = null, cause: Throwable? = null) :
            FundingSourceException(message = message, cause = cause)
        class IdentityVerificationException(message: String? = null, cause: Throwable? = null) :
            FundingSourceException(message = message, cause = cause)
        class UnknownException(cause: Throwable) :
            FundingSourceException(cause = cause)
    }

    /**
     * Defines the exceptions for the virtual payment card based methods.
     *
     * @property message Accompanying message for the exception.
     * @property cause The cause of the exception.
     */
    sealed class CardException(message: String? = null, cause: Throwable? = null) : RuntimeException(message, cause) {
        class FailedException(message: String? = null, cause: Throwable? = null) :
            CardException(message = message, cause = cause)
        class ProvisionFailedException(message: String? = null, cause: Throwable? = null) :
            CardException(message = message, cause = cause)
        class UpdateFailedException(message: String? = null, cause: Throwable? = null) :
            CardException(message = message, cause = cause)
        class CancelFailedException(message: String? = null, cause: Throwable? = null) :
            CardException(message = message, cause = cause)
        class CardNotFoundException(message: String? = null, cause: Throwable? = null) :
            CardException(message = message, cause = cause)
        class CardStateException(message: String? = null, cause: Throwable? = null) :
            CardException(message = message, cause = cause)
        class FundingSourceNotFoundException(message: String? = null, cause: Throwable? = null) :
            CardException(message = message, cause = cause)
        class FundingSourceNotActiveException(message: String? = null, cause: Throwable? = null) :
            CardException(message = message, cause = cause)
        class VelocityExceededException(message: String? = null, cause: Throwable? = null) :
            CardException(message = message, cause = cause)
        class EntitlementExceededException(message: String? = null, cause: Throwable? = null) :
            CardException(message = message, cause = cause)
        class UnsupportedCurrencyException(message: String? = null, cause: Throwable? = null) :
            CardException(message = message, cause = cause)
        class IdentityVerificationException(message: String? = null, cause: Throwable? = null) :
            CardException(message = message, cause = cause)
        class PublicKeyException(message: String? = null, cause: Throwable? = null) :
            CardException(message = message, cause = cause)
        class UnsealingException(message: String? = null, cause: Throwable? = null) :
            CardException(message = message, cause = cause)
        class UnknownException(cause: Throwable) :
            CardException(cause = cause)
    }

    /**
     * Defines the exceptions for the virtual payment transaction based methods.
     *
     * @property message Accompanying message for the exception.
     * @property cause The cause of the exception.
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
     * Creates a [FundingSource] using a credit card input.
     *
     * @param input Information needed to create a credit card funding source.
     * @return The created [FundingSource].
     */
    @Throws(FundingSourceException::class)
    suspend fun createFundingSource(input: CreditCardFundingSourceInput): FundingSource

    /**
     * Get a [FundingSource] using the [id] parameter.
     *
     * @param id Identifier of the [FundingSource] to be retrieved.
     * @param cachePolicy Determines how the data will be fetched. When using [CachePolicy.CACHE_ONLY],
     * be aware that this will only return cached results of similar exact API calls.
     * @return The [FundingSource] associated with the [id] or null if the funding source cannot be found.
     */
    @Throws(FundingSourceException::class)
    suspend fun getFundingSource(id: String, cachePolicy: CachePolicy = CachePolicy.REMOTE_ONLY): FundingSource?

    /**
     * Get a [ListOutput] of [FundingSource]s. If no [FundingSource]s can be found, the [ListOutput]
     * will contain null for the [ListOutput.nextToken] field and contain an empty list.
     *
     * @param limit Number of [FundingSource]s to return. If omitted the limit defaults to 10.
     * @param nextToken A token generated from previous calls to [listFundingSources].
     * This is to allow for pagination. This value should be generated from a
     * previous pagination call, otherwise it will throw an exception. The same
     * arguments should be supplied to this method if using a previously generated [nextToken].
     * @param cachePolicy Determines how the data will be fetched. When using [CachePolicy.CACHE_ONLY],
     * be aware that this will only return cached results of similar exact API calls.
     * @return A list of [FundingSource]s or an empty list if no funding sources can be found.
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
     * @param id Identifier of the [FundingSource] to cancel.
     * @return The [FundingSource] that has been cancelled and is in an inactive state.
     */
    @Throws(FundingSourceException::class)
    suspend fun cancelFundingSource(id: String): FundingSource

    /**
     * Provision a virtual payment card. Initially a [ProvisionalCard] is returned with the details of
     * the card being created. You should query the state of this card periodically by calling
     * [getProvisionalCard] to monitor the card's progress from a state of [ProvisionalCard.State.PROVISIONING]
     * to [ProvisionalCard.State.COMPLETED] or [ProvisionalCard.State.FAILED].
     *
     * @param input Information needed to create a new card.
     * @return A [ProvisionalCard] in the [ProvisionalCard.State.PROVISIONING] state.
     */
    @Throws(CardException::class)
    suspend fun provisionCard(input: ProvisionCardInput): ProvisionalCard

    /**
     * Get a provisional virtual payment card. A provisional card is one that is in the process
     * of being provisioned.
     *
     * @param id Identifier of the [ProvisionalCard] to be retrieved.
     * @param cachePolicy Determines how the data will be fetched. When using [CachePolicy.CACHE_ONLY],
     * be aware that this will only return cached results of similar exact API calls.
     * @return The provisional card or null if a provisional card with that [id] could not be found
     */
    @Throws(CardException::class)
    suspend fun getProvisionalCard(id: String, cachePolicy: CachePolicy = CachePolicy.REMOTE_ONLY): ProvisionalCard?

    /**
     * Get a virtual payment [Card] using the [id] parameter.
     *
     * @param id Identifier of the [Card] to be retrieved.
     * @param cachePolicy Determines how the data will be fetched. When using [CachePolicy.CACHE_ONLY],
     * be aware that this will only return cached results of similar exact API calls.
     * @returns The [Card] associated with the [id] or null if the virtual payment card cannot be found.
     */
    @Throws(CardException::class)
    suspend fun getCard(id: String, cachePolicy: CachePolicy = CachePolicy.REMOTE_ONLY): Card?

    /**
     * Get a [ListOutput] of virtual payment [Card]s. If no [Card]s can be found, the [ListOutput]
     * will contain null for the [ListOutput.nextToken] field and contain an empty list.
     *
     * The results of this operation are filtered locally as well as on the server. To ensure you obtain all the
     * cards that match, you should continue to call this method whenever the [ListOutput.nextToken]
     * field is not null, even if the [ListOutput.items] is empty.
     *
     * @param limit Number of [Card]s to return. If omitted the limit defaults to 10.
     * @param nextToken A token generated from previous calls to [listCards].
     * This is to allow for pagination. This value should be generated from a
     * previous pagination call, otherwise it will throw an exception. The same
     * arguments should be passed to this method if using a previously generated [nextToken].
     * @param cachePolicy Determines how the data will be fetched. When using [CachePolicy.CACHE_ONLY],
     * be aware that this will only return cached results of similar exact API calls.
     * @param filter Filter the cards so that only those that match all of the values of the
     * fields in the filter are returned.
     * @return A list of [Card]s or an empty list if no virtual payment cards can be found.
     * @sample com.sudoplatform.sudovirtualcards.samples.Samples.cardsFilter
     */
    @Throws(CardException::class)
    suspend fun listCards(
        limit: Int = DEFAULT_CARD_LIMIT,
        nextToken: String? = null,
        cachePolicy: CachePolicy = CachePolicy.REMOTE_ONLY,
        filter: () -> CardFilter? = { null }
    ): ListOutput<Card>

    /**
     * Update the details of a [Card].
     *
     * Be aware that when updating a card, all fields that should remain unchanged must be populated
     * as part of the input.
     *
     * @param input Information needed to update a card.
     * @return An updated [Card].
     */
    @Throws(CardException::class)
    suspend fun updateCard(input: UpdateCardInput): Card

    /**
     * Cancel a [Card] using the [id] parameter.
     *
     * @param id Identifier of the [Card] to cancel.
     * @return The [Card] that has been cancelled and is in a closed state.
     */
    @Throws(CardException::class)
    suspend fun cancelCard(id: String): Card

    /**
     * Get a virtual payment [Transaction] using the [id] parameter.
     *
     * @param id Identifier of the [Transaction] to be retrieved.
     * @param cachePolicy Determines how the data will be fetched. When using [CachePolicy.CACHE_ONLY],
     * be aware that this will only return cached results of similar exact API calls.
     * @returns The [Transaction] associated with the [id] or null if the virtual payment transaction cannot be found.
     */
    @Throws(TransactionException::class)
    suspend fun getTransaction(id: String, cachePolicy: CachePolicy = CachePolicy.REMOTE_ONLY): Transaction?

    /**
     * Get a [ListOutput] of virtual payment [Transaction]s. If no [Transaction]s can be found, the [ListOutput]
     * will contain null for the [ListOutput.nextToken] field and contain an empty list.
     *
     * The results of this operation are filtered locally as well as on the server. To ensure you obtain all the
     * transactions that match, you should continue to call this method whenever the [ListOutput.nextToken]
     * field is not null, even if the [ListOutput.items] is empty.
     *
     * @param limit Number of [Transaction]s to return. If omitted the limit defaults to 100.
     * @param nextToken A token generated from previous calls to [listTransactions].
     * This is to allow for pagination. This value should be generated from a
     * previous pagination call, otherwise it will throw an exception. You should call
     * this method with the same argument if you using a previously generated [ListOutput.nextToken].
     * @param cachePolicy Determines how the data will be fetched. When using [CachePolicy.CACHE_ONLY],
     * be aware that this will only return cached results of similar exact API calls.
     * @param filter Filter the transactions so that only those that match all of the values of the
     * fields in the filter are returned.
     * @return A [ListOutput] of [Transaction]s filtered by the [filter].
     * @sample com.sudoplatform.sudovirtualcards.samples.Samples.transactionFilter
     */
    @Throws(TransactionException::class)
    suspend fun listTransactions(
        limit: Int = DEFAULT_TRANSACTION_LIMIT,
        nextToken: String? = null,
        cachePolicy: CachePolicy = CachePolicy.REMOTE_ONLY,
        filter: () -> TransactionFilter? = { null }
    ): ListOutput<Transaction>

    /**
     * Subscribes to be notified of new, updated and deleted [Transaction]s.
     *
     * @param id unique ID for the subscriber.
     * @param subscriber subscriber to notify.
     */
    @Throws(TransactionException::class)
    suspend fun subscribeToTransactions(id: String, subscriber: TransactionSubscriber)

    /**
     * Unsubscribe the specified subscriber so that it no longer receives notifications about
     * new, updated or deleted [Transaction]s.
     *
     * @param id unique ID for the subscriber.
     */
    suspend fun unsubscribeFromTransactions(id: String)

    /**
     * Unsubscribe all subscribers from receiving notifications about new, updated or deleted [Transaction]s.
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
 * @param id unique ID for the subscriber.
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
