/*
 * Copyright © 2024 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards.subscription

import com.amplifyframework.api.graphql.GraphQLOperation

/**
 * Manages subscriptions for a specific GraphQL subscription.
 */
internal open class SubscriptionManager<T, S : Subscriber> {

    /**
     * Subscribers.
     */
    private val subscribers: MutableMap<String, S> = mutableMapOf()

    /**
     * AppSync subscription watcher.
     */
    internal var watcher: GraphQLOperation<T>? = null

    protected fun getSubscribers(): MutableMap<String, S> {
        return this.subscribers
    }

    /**
     * Adds or replaces a subscriber with the specified ID.
     *
     * @param id subscriber ID.
     * @param subscriber subscriber to subscribe.
     */
    internal fun replaceSubscriber(id: String, subscriber: S) {
        synchronized(this) {
            subscribers[id] = subscriber
        }
    }

    /**
     * Removes the subscriber with the specified ID.
     *
     * @param id subscriber ID.
     */
    internal fun removeSubscriber(id: String) {
        synchronized(this) {
            subscribers.remove(id)

            if (subscribers.isEmpty()) {
                watcher?.cancel()
                watcher = null
            }
        }
    }

    /**
     * Removes all subscribers.
     */
    internal fun removeAllSubscribers() {
        synchronized(this) {
            subscribers.clear()
            watcher?.cancel()
            watcher = null
        }
    }

    /**
     * Processes AppSync subscription connection status change.
     *
     * @param state connection state.
     */
    internal fun connectionStatusChanged(state: Subscriber.ConnectionState) {
        var subscribersToNotify: ArrayList<S>
        synchronized(this) {
            // Take a copy of the subscribers to notify in synchronized block
            // but notify outside the block to avoid deadlock.
            subscribersToNotify = ArrayList(subscribers.values)

            // If the subscription was disconnected then remove all subscribers.
            if (state == Subscriber.ConnectionState.DISCONNECTED) {
                subscribers.clear()
                if (watcher != null) {
                    watcher?.cancel()
                }
                watcher = null
            }
        }

        // Notify subscribers.
        for (subscriber in subscribersToNotify) {
            subscriber.connectionStatusChanged(state)
        }
    }
}
