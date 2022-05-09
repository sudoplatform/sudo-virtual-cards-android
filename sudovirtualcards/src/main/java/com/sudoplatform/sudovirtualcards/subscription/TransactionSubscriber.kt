/*
 * Copyright © 2022 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards.subscription

import com.sudoplatform.sudovirtualcards.types.Transaction

/**
 * Subscriber for receiving notifications about new, updated or deleted [Transaction]s.
 */
interface TransactionSubscriber {

    /**
     * Connection state of the subscription.
     */
    enum class ConnectionState {

        /**
         * Connected and receiving updates.
         */
        CONNECTED,

        /**
         * Disconnected and won't receive any updates. When disconnected all subscribers will be
         * unsubscribed so the consumer must re-subscribe.
         */
        DISCONNECTED
    }

    /**
     * Notifies the subscriber of a new, updated or deleted [Transaction].
     *
     * @param transaction new, updated or deleted [Transaction].
     */
    fun transactionChanged(transaction: Transaction)

    /**
     * Notifies the subscriber that the subscription connection state has changed. The subscriber won't be
     * notified of [Transaction] changes until the connection status changes to [ConnectionState.CONNECTED]. The subscriber will
     * stop receiving [Transaction] change notifications when the connection state changes to [ConnectionState.DISCONNECTED].
     *
     * @param state connection state.
     */
    fun connectionStatusChanged(state: ConnectionState)
}
