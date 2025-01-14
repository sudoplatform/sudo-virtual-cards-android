/*
 * Copyright © 2024 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards.subscription

import com.sudoplatform.sudovirtualcards.types.Transaction

/**
 * Subscriber for receiving notifications about new, updated or deleted [Transaction]s.
 */
interface TransactionSubscriber : Subscriber {

    /**
     * Represents the type of change that occurred to a [Transaction].
     *
     * Used to indicate whether a transaction was added/updated or deleted.
     */
    enum class ChangeType {

        /**
         * Indicates that a [Transaction] was added or updated.
         */
        UPSERTED,

        /**
         * Indicates that a [Transaction] was deleted.
         */
        DELETED,
    }

    /**
     * Connection state of the subscription. Redundant but retained for backwards compatibility.
     */
    @Deprecated("Prefer Subscriber.ConnectionState")
    enum class ConnectionState {

        /**
         * Connected and receiving updates.
         */
        CONNECTED,

        /**
         * Disconnected and won't receive any updates. When disconnected all subscribers will be
         * unsubscribed so the consumer must re-subscribe.
         */
        DISCONNECTED,
    }

    /**
     * Default implementation in this class to maintain backwards compatibility. Once the deprecated method/enum
     * are removed, this implementation should also be removed.
     * See [Subscriber.connectionStatusChanged]
     */
    override fun connectionStatusChanged(state: Subscriber.ConnectionState) {
        val localState = when (state) {
            Subscriber.ConnectionState.CONNECTED -> ConnectionState.CONNECTED
            Subscriber.ConnectionState.DISCONNECTED -> ConnectionState.DISCONNECTED
        }
        return this.connectionStatusChanged(localState)
    }

    @Deprecated("Prefer connectionStatusChanged(state: Subscriber.ConnectionState)")
    fun connectionStatusChanged(state: ConnectionState) {
    }

    /**
     * Notifies the subscriber of a new, updated or deleted [Transaction].
     *
     * @param transaction new, updated or deleted [Transaction].
     */
    @Deprecated("Prefer transactionChanged(transaction: Transaction, changeType: ChangeType)")
    fun transactionChanged(transaction: Transaction) {
        // Default implementation
    }

    /**
     * Notifies the subscriber of a change to a [Transaction].
     *
     * This method is called whenever a [Transaction] is added, updated, or deleted.
     *
     * @param transaction The [Transaction] that was changed.
     * @param changeType The type of change that occurred, represented by [ChangeType].
     */
    fun transactionChanged(transaction: Transaction, changeType: ChangeType) {
        transactionChanged(transaction)
    }
}
