/*
 * Copyright © 2023 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards.subscription

import com.amazonaws.mobileconnectors.appsync.AWSAppSyncClient
import com.amazonaws.mobileconnectors.appsync.AppSyncSubscriptionCall
import com.apollographql.apollo.api.Response
import com.apollographql.apollo.exception.ApolloException
import com.sudoplatform.sudologging.AndroidUtilsLogDriver
import com.sudoplatform.sudologging.LogLevel
import com.sudoplatform.sudologging.Logger
import com.sudoplatform.sudouser.SudoUserClient
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
    private val appSyncClient: AWSAppSyncClient,
    private val deviceKeyManager: DeviceKeyManager,
    private val userClient: SudoUserClient,
    private val logger: Logger = Logger(LogConstants.SUDOLOG_TAG, AndroidUtilsLogDriver(LogLevel.INFO))
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
            if (txnUpdateSubscriptionManager.watcher == null && txnUpdateSubscriptionManager.pendingWatcher == null) {
                val watcher = appSyncClient.subscribe(
                    OnTransactionUpdateSubscription.builder()
                        .owner(userSubject)
                        .build()
                )
                txnUpdateSubscriptionManager.pendingWatcher = watcher
                watcher.execute(updateCallback)
            }

            if (txnDeleteSubscriptionManager.watcher == null && txnDeleteSubscriptionManager.pendingWatcher == null) {
                val watcher = appSyncClient.subscribe(
                    OnTransactionDeleteSubscription.builder()
                        .owner(userSubject)
                        .build()
                )
                txnDeleteSubscriptionManager.pendingWatcher = watcher
                watcher.execute(deleteCallback)
            }
        }.join()
    }
    suspend fun subscribeFundingSources(id: String, subscriber: FundingSourceSubscriber) {
        val userSubject = userClient.getSubject()
            ?: throw SudoVirtualCardsClient.FundingSourceException.AuthenticationException(ERROR_UNAUTHENTICATED_MSG)

        fsUpdateSubscriptionManager.replaceSubscriber(id, subscriber)

        scope.launch {
            if (fsUpdateSubscriptionManager.watcher == null && fsUpdateSubscriptionManager.pendingWatcher == null) {
                val watcher = appSyncClient.subscribe(
                    OnFundingSourceUpdateSubscription.builder()
                        .owner(userSubject)
                        .build()
                )
                fsUpdateSubscriptionManager.pendingWatcher = watcher
                watcher.execute(fundingSourceCallback)
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

    private val updateCallback = object : AppSyncSubscriptionCall.StartedCallback<OnTransactionUpdateSubscription.Data> {
        override fun onFailure(e: ApolloException) {
            logger.error("Transaction update subscription error $e")
            txnUpdateSubscriptionManager.connectionStatusChanged(Subscriber.ConnectionState.DISCONNECTED)
        }

        override fun onResponse(response: Response<OnTransactionUpdateSubscription.Data>) {
            scope.launch {
                val transactionUpdate = response.data()?.onTransactionUpdate()
                    ?: return@launch
                txnUpdateSubscriptionManager.transactionChanged(
                    TransactionTransformer.toEntity(deviceKeyManager, transactionUpdate.fragments().sealedTransaction())
                )
            }
        }

        override fun onCompleted() {
            txnUpdateSubscriptionManager.connectionStatusChanged(Subscriber.ConnectionState.DISCONNECTED)
        }

        override fun onStarted() {
            txnUpdateSubscriptionManager
                .watcher = txnUpdateSubscriptionManager.pendingWatcher
            txnUpdateSubscriptionManager.connectionStatusChanged(
                Subscriber.ConnectionState.CONNECTED
            )
        }
    }

    private val fundingSourceCallback = object : AppSyncSubscriptionCall.StartedCallback<OnFundingSourceUpdateSubscription.Data> {
        override fun onFailure(e: ApolloException) {
            logger.error("FundingSource update subscription error $e")
            fsUpdateSubscriptionManager.connectionStatusChanged(Subscriber.ConnectionState.DISCONNECTED)
        }

        override fun onResponse(response: Response<OnFundingSourceUpdateSubscription.Data>) {
            scope.launch {
                val fundingSourceUpdate = response.data()?.onFundingSourceUpdate()
                    ?: return@launch
                fsUpdateSubscriptionManager.fundingSourceChanged(
                    FundingSourceTransformer.toEntityFromFundingSourceUpdateSubscriptionResult(deviceKeyManager, fundingSourceUpdate)
                )
            }
        }

        override fun onCompleted() {
            fsUpdateSubscriptionManager.connectionStatusChanged(Subscriber.ConnectionState.DISCONNECTED)
        }

        override fun onStarted() {
            fsUpdateSubscriptionManager
                .watcher = fsUpdateSubscriptionManager.pendingWatcher
            fsUpdateSubscriptionManager.connectionStatusChanged(
                Subscriber.ConnectionState.CONNECTED
            )
        }
    }

    private val deleteCallback = object : AppSyncSubscriptionCall.StartedCallback<OnTransactionDeleteSubscription.Data> {
        override fun onFailure(e: ApolloException) {
            logger.error("Transaction delete subscription error $e")
            txnDeleteSubscriptionManager.connectionStatusChanged(Subscriber.ConnectionState.DISCONNECTED)
        }

        override fun onResponse(response: Response<OnTransactionDeleteSubscription.Data>) {
            scope.launch {
                val transactionDelete = response.data()?.onTransactionDelete()
                    ?: return@launch
                txnDeleteSubscriptionManager.transactionChanged(
                    TransactionTransformer.toEntity(deviceKeyManager, transactionDelete.fragments().sealedTransaction())
                )
            }
        }

        override fun onCompleted() {
            txnDeleteSubscriptionManager.connectionStatusChanged(Subscriber.ConnectionState.DISCONNECTED)
        }

        override fun onStarted() {
            txnDeleteSubscriptionManager
                .watcher = txnDeleteSubscriptionManager.pendingWatcher
            txnDeleteSubscriptionManager.connectionStatusChanged(
                Subscriber.ConnectionState.CONNECTED
            )
        }
    }
}
