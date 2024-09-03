/*
 * Copyright © 2024 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards.subscription

import com.sudoplatform.sudovirtualcards.types.FundingSource

/**
 * Manages subscriptions for a specific GraphQL FundingSource subscription.
 */
internal class FundingSourceSubscriptionManager<T> : SubscriptionManager<T, FundingSourceSubscriber>() {
    /**
     * Notifies subscribers of a modified FundingSource
     *
     * @param fundingSource: modified [FundingSource].
     */
    internal fun fundingSourceChanged(fundingSource: FundingSource) {
        var subscribersToNotify: ArrayList<FundingSourceSubscriber>
        synchronized(this) {
            // Take a copy of the subscribers to notify in synchronized block
            // but notify outside the block to avoid deadlock.
            subscribersToNotify = ArrayList(this.getSubscribers().values)
        }

        // Notify subscribers.
        for (subscriber in subscribersToNotify) {
            subscriber.fundingSourceChanged(fundingSource)
        }
    }
}
