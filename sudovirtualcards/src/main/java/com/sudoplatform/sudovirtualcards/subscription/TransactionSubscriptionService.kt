/*
 * Copyright © 2020 Anonyome Labs, Inc. All rights reserved.
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
import com.sudoplatform.sudovirtualcards.graphql.OnTransactionDeleteSubscription
import com.sudoplatform.sudovirtualcards.graphql.OnTransactionUpdateSubscription
import com.sudoplatform.sudovirtualcards.keys.DeviceKeyManager
import com.sudoplatform.sudovirtualcards.logging.LogConstants
import com.sudoplatform.sudovirtualcards.types.transformers.TransactionTransformer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Manage the subscriptions of transaction updates.
 *
 * @since 2020-07-22
 */
internal class TransactionSubscriptionService(
    private val appSyncClient: AWSAppSyncClient,
    private val deviceKeyManager: DeviceKeyManager,
    private val userClient: SudoUserClient,
    private val logger: Logger = Logger(LogConstants.SUDOLOG_TAG, AndroidUtilsLogDriver(LogLevel.INFO))
) : AutoCloseable {

    companion object {
        private const val ERROR_UNAUTHENTICATED_MSG = "User client does not have subject. Is the user authenticated?"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val updateSubscriptionManager = SubscriptionManager<OnTransactionUpdateSubscription.Data>()
    private val deleteSubscriptionManager = SubscriptionManager<OnTransactionDeleteSubscription.Data>()

    suspend fun subscribe(id: String, subscriber: TransactionSubscriber) {

        val userSubject = userClient.getSubject()
            ?: throw SudoVirtualCardsClient.TransactionException.AuthenticationException(ERROR_UNAUTHENTICATED_MSG)

        updateSubscriptionManager.replaceSubscriber(id, subscriber)
        deleteSubscriptionManager.replaceSubscriber(id, subscriber)

        scope.launch {
            if (updateSubscriptionManager.watcher == null) {
                val watcher = appSyncClient.subscribe(OnTransactionUpdateSubscription.builder()
                    .owner(userSubject)
                    .build())
                updateSubscriptionManager.watcher = watcher
                watcher.execute(updateCallback)
            }

            if (deleteSubscriptionManager.watcher == null) {
                val watcher = appSyncClient.subscribe(OnTransactionDeleteSubscription.builder()
                    .owner(userSubject)
                    .build())
                deleteSubscriptionManager.watcher = watcher
                watcher.execute(deleteCallback)
            }
        }.join()
    }

    fun unsubscribe(id: String) {
        updateSubscriptionManager.removeSubscriber(id)
        deleteSubscriptionManager.removeSubscriber(id)
    }

    fun unsubscribeAll() {
        updateSubscriptionManager.removeAllSubscribers()
        deleteSubscriptionManager.removeAllSubscribers()
    }

    override fun close() {
        unsubscribeAll()
        scope.cancel()
    }

    private val updateCallback = object : AppSyncSubscriptionCall.Callback<OnTransactionUpdateSubscription.Data> {
        override fun onFailure(e: ApolloException) {
            logger.error("Transaction update subscription error $e")
            updateSubscriptionManager.connectionStatusChanged(TransactionSubscriber.ConnectionState.DISCONNECTED)
        }

        override fun onResponse(response: Response<OnTransactionUpdateSubscription.Data>) {
            scope.launch {
                val transactionUpdate = response.data()?.onTransactionUpdate()
                    ?: return@launch
                updateSubscriptionManager.transactionChanged(
                    TransactionTransformer.toEntityFromUpdateSubscription(deviceKeyManager, transactionUpdate)
                )
            }
        }

        override fun onCompleted() {
            updateSubscriptionManager.connectionStatusChanged(TransactionSubscriber.ConnectionState.DISCONNECTED)
        }
    }

    private val deleteCallback = object : AppSyncSubscriptionCall.Callback<OnTransactionDeleteSubscription.Data> {
        override fun onFailure(e: ApolloException) {
            logger.error("Transaction delete subscription error $e")
            deleteSubscriptionManager.connectionStatusChanged(TransactionSubscriber.ConnectionState.DISCONNECTED)
        }

        override fun onResponse(response: Response<OnTransactionDeleteSubscription.Data>) {
            scope.launch {
                val transactionDelete = response.data()?.onTransactionDelete()
                    ?: return@launch
                deleteSubscriptionManager.transactionChanged(
                    TransactionTransformer.toEntityFromDeleteSubscription(deviceKeyManager, transactionDelete)
                )
            }
        }

        override fun onCompleted() {
            updateSubscriptionManager.connectionStatusChanged(TransactionSubscriber.ConnectionState.DISCONNECTED)
        }
    }
}
