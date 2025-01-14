/*
 * Copyright © 2024 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards.subscription

import com.sudoplatform.sudovirtualcards.subscription.TransactionSubscriber.ChangeType
import com.sudoplatform.sudovirtualcards.types.Transaction

/**
 * Manages subscriptions for a specific GraphQL subscription.
 */
internal class TransactionSubscriptionManager<T> : SubscriptionManager<T, TransactionSubscriber>() {

    /**
     * Notifies subscribers of a new, updated or deleted [Transaction].
     *
     * @param transaction The [Transaction] that was changed.
     * @param changeType The type of change that occurred, represented by [ChangeType].
     */
    internal fun transactionChanged(transaction: Transaction, changeType: TransactionSubscriber.ChangeType) {
        var subscribersToNotify: ArrayList<TransactionSubscriber>
        synchronized(this) {
            // Take a copy of the subscribers to notify in synchronized block
            // but notify outside the block to avoid deadlock.
            subscribersToNotify = ArrayList(this.getSubscribers().values)
        }

        // Notify subscribers.
        for (subscriber in subscribersToNotify) {
            subscriber.transactionChanged(transaction, changeType)
        }
    }
}
