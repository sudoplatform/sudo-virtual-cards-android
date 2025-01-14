/*
 * Copyright © 2024 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards.subscription

import com.amplifyframework.api.ApiException
import com.amplifyframework.api.graphql.GraphQLResponse
import com.sudoplatform.sudologging.AndroidUtilsLogDriver
import com.sudoplatform.sudologging.LogLevel
import com.sudoplatform.sudologging.Logger
import com.sudoplatform.sudouser.SudoUserClient
import com.sudoplatform.sudouser.amplify.GraphQLClient
import com.sudoplatform.sudovirtualcards.SudoVirtualCardsClient
import com.sudoplatform.sudovirtualcards.graphql.OnFundingSourceUpdateSubscription
import com.sudoplatform.sudovirtualcards.graphql.OnTransactionDeleteSubscription
import com.sudoplatform.sudovirtualcards.graphql.OnTransactionUpdateSubscription
import com.sudoplatform.sudovirtualcards.keys.DeviceKeyManager
import com.sudoplatform.sudovirtualcards.logging.LogConstants
import com.sudoplatform.sudovirtualcards.types.transformers.FundingSourceTransformer
import com.sudoplatform.sudovirtualcards.types.transformers.TransactionTransformer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Manage the subscriptions of transaction updates.
 */
internal class SubscriptionService(
    private val graphQLClient: GraphQLClient,
    private val deviceKeyManager: DeviceKeyManager,
    private val userClient: SudoUserClient,
    private val logger: Logger = Logger(LogConstants.SUDOLOG_TAG, AndroidUtilsLogDriver(LogLevel.INFO)),
) : AutoCloseable {

    companion object {
        private const val ERROR_UNAUTHENTICATED_MSG = "User client does not have subject. Is the user authenticated?"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val txnUpdateSubscriptionManager = TransactionSubscriptionManager<OnTransactionUpdateSubscription.Data>()
    private val txnDeleteSubscriptionManager = TransactionSubscriptionManager<OnTransactionDeleteSubscription.Data>()
    private val fsUpdateSubscriptionManager = FundingSourceSubscriptionManager<OnFundingSourceUpdateSubscription.Data>()

    suspend fun subscribeTransactions(id: String, subscriber: TransactionSubscriber) {
        val userSubject = userClient.getSubject()
            ?: throw SudoVirtualCardsClient.TransactionException.AuthenticationException(ERROR_UNAUTHENTICATED_MSG)

        txnUpdateSubscriptionManager.replaceSubscriber(id, subscriber)
        txnDeleteSubscriptionManager.replaceSubscriber(id, subscriber)

        scope.launch {
            if (txnUpdateSubscriptionManager.watcher == null) {
                val watcher = graphQLClient.subscribe<OnTransactionUpdateSubscription, OnTransactionUpdateSubscription.Data>(
                    OnTransactionUpdateSubscription.OPERATION_DOCUMENT,
                    mapOf("owner" to userSubject),
                    transactionUpdateCallbacks.onSubscriptionEstablished,
                    transactionUpdateCallbacks.onSubscription,
                    transactionUpdateCallbacks.onSubscriptionCompleted,
                    transactionUpdateCallbacks.onFailure,
                )
                txnUpdateSubscriptionManager.watcher = watcher
            }

            if (txnDeleteSubscriptionManager.watcher == null) {
                val watcher = graphQLClient.subscribe<OnTransactionDeleteSubscription, OnTransactionDeleteSubscription.Data>(
                    OnTransactionDeleteSubscription.OPERATION_DOCUMENT,
                    mapOf("owner" to userSubject),
                    transactionDeleteCallbacks.onSubscriptionEstablished,
                    transactionDeleteCallbacks.onSubscription,
                    transactionDeleteCallbacks.onSubscriptionCompleted,
                    transactionDeleteCallbacks.onFailure,
                )
                txnDeleteSubscriptionManager.watcher = watcher
            }
        }.join()
    }
    suspend fun subscribeFundingSources(id: String, subscriber: FundingSourceSubscriber) {
        val userSubject = userClient.getSubject()
            ?: throw SudoVirtualCardsClient.FundingSourceException.AuthenticationException(ERROR_UNAUTHENTICATED_MSG)

        fsUpdateSubscriptionManager.replaceSubscriber(id, subscriber)

        scope.launch {
            if (fsUpdateSubscriptionManager.watcher == null) {
                val watcher = graphQLClient.subscribe<OnFundingSourceUpdateSubscription, OnFundingSourceUpdateSubscription.Data>(
                    OnFundingSourceUpdateSubscription.OPERATION_DOCUMENT,
                    mapOf("owner" to userSubject),
                    fundingSourceUpdateCallbacks.onSubscriptionEstablished,
                    fundingSourceUpdateCallbacks.onSubscription,
                    fundingSourceUpdateCallbacks.onSubscriptionCompleted,
                    fundingSourceUpdateCallbacks.onFailure,
                )
                fsUpdateSubscriptionManager.watcher = watcher
            }
        }.join()
    }

    fun unsubscribeTransactions(id: String) {
        txnUpdateSubscriptionManager.removeSubscriber(id)
        txnDeleteSubscriptionManager.removeSubscriber(id)
    }

    fun unsubscribeFundingSources(id: String) {
        fsUpdateSubscriptionManager.removeSubscriber(id)
    }

    fun unsubscribeAllTransactions() {
        txnUpdateSubscriptionManager.removeAllSubscribers()
        txnDeleteSubscriptionManager.removeAllSubscribers()
    }

    fun unsubscribeAllFundingSources() {
        fsUpdateSubscriptionManager.removeAllSubscribers()
    }

    override fun close() {
        unsubscribeAllTransactions()
        unsubscribeAllFundingSources()
        scope.cancel()
    }

    private val transactionUpdateCallbacks = object {
        val onSubscriptionEstablished: (GraphQLResponse<OnTransactionUpdateSubscription.Data>) -> Unit = {
            txnUpdateSubscriptionManager.connectionStatusChanged(
                Subscriber.ConnectionState.CONNECTED,
            )
        }
        val onSubscription: (GraphQLResponse<OnTransactionUpdateSubscription.Data>) -> Unit = {
            scope.launch {
                val transactionUpdate = it.data?.onTransactionUpdate
                    ?: return@launch
                val transaction = TransactionTransformer.toEntity(deviceKeyManager, transactionUpdate.sealedTransaction)
                txnUpdateSubscriptionManager.transactionChanged(transaction, TransactionSubscriber.ChangeType.UPSERTED)
            }
        }
        val onSubscriptionCompleted = {
            txnUpdateSubscriptionManager.connectionStatusChanged(Subscriber.ConnectionState.DISCONNECTED)
        }
        val onFailure: (ApiException) -> Unit = {
            logger.error("Transaction update subscription error $it")
            txnUpdateSubscriptionManager.connectionStatusChanged(Subscriber.ConnectionState.DISCONNECTED)
        }
    }

    private val fundingSourceUpdateCallbacks = object {
        val onSubscriptionEstablished: (GraphQLResponse<OnFundingSourceUpdateSubscription.Data>) -> Unit = {
            fsUpdateSubscriptionManager.connectionStatusChanged(
                Subscriber.ConnectionState.CONNECTED,
            )
        }
        val onSubscription: (GraphQLResponse<OnFundingSourceUpdateSubscription.Data>) -> Unit = {
            scope.launch {
                val fundingSourceUpdate = it.data?.onFundingSourceUpdate
                    ?: return@launch
                fsUpdateSubscriptionManager.fundingSourceChanged(
                    FundingSourceTransformer.toEntityFromFundingSourceUpdateSubscriptionResult(deviceKeyManager, fundingSourceUpdate),
                )
            }
        }

        val onSubscriptionCompleted = {
            fsUpdateSubscriptionManager.connectionStatusChanged(Subscriber.ConnectionState.DISCONNECTED)
        }

        val onFailure: (ApiException) -> Unit = {
            logger.error("FundingSource update subscription error $it")
            fsUpdateSubscriptionManager.connectionStatusChanged(Subscriber.ConnectionState.DISCONNECTED)
        }
    }

    private val transactionDeleteCallbacks = object {
        val onSubscriptionEstablished: (GraphQLResponse<OnTransactionDeleteSubscription.Data>) -> Unit = {
            txnDeleteSubscriptionManager.connectionStatusChanged(
                Subscriber.ConnectionState.CONNECTED,
            )
        }
        val onSubscription: (GraphQLResponse<OnTransactionDeleteSubscription.Data>) -> Unit = {
            scope.launch {
                val transactionDelete = it.data?.onTransactionDelete
                    ?: return@launch
                val transaction = TransactionTransformer.toEntity(deviceKeyManager, transactionDelete.sealedTransaction)
                txnDeleteSubscriptionManager.transactionChanged(transaction, TransactionSubscriber.ChangeType.DELETED)
            }
        }
        val onSubscriptionCompleted = {
            txnDeleteSubscriptionManager.connectionStatusChanged(Subscriber.ConnectionState.DISCONNECTED)
        }
        val onFailure: (ApiException) -> Unit = {
            logger.error("Transaction delete subscription error $it")
            txnDeleteSubscriptionManager.connectionStatusChanged(Subscriber.ConnectionState.DISCONNECTED)
        }
    }
}
