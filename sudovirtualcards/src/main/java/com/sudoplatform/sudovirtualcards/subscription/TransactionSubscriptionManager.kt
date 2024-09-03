/*
 * Copyright © 2024 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards.subscription

import com.sudoplatform.sudovirtualcards.types.Transaction

/**
 * Manages subscriptions for a specific GraphQL subscription.
 */
internal class TransactionSubscriptionManager<T> : SubscriptionManager<T, TransactionSubscriber>() {
    /**
     * Notifies subscribers of a new, updated or deleted [Transaction].
     *
     * @param transaction new, updated or deleted [Transaction].
     */
    internal fun transactionChanged(transaction: Transaction) {
        var subscribersToNotify: ArrayList<TransactionSubscriber>
        synchronized(this) {
            // Take a copy of the subscribers to notify in synchronized block
            // but notify outside the block to avoid deadlock.
            subscribersToNotify = ArrayList(this.getSubscribers().values)
        }

        // Notify subscribers.
        for (subscriber in subscribersToNotify) {
            subscriber.transactionChanged(transaction)
        }
    }
}
