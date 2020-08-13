/*
 * Copyright © 2020 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards.types

import com.amazonaws.mobileconnectors.appsync.fetcher.AppSyncResponseFetchers
import com.apollographql.apollo.fetcher.ResponseFetcher

/**
 * Enumeration outlining options for how data will be fetched.
 *
 * @since 2020-06-08
 */
enum class CachePolicy {
    /**
     * Returns data from the local cache only.
     */
    CACHE_ONLY,

    /**
     * Returns data from the backend only and ignores any cached entries.
     */
    REMOTE_ONLY;

    fun toResponseFetcher(cachePolicy: CachePolicy): ResponseFetcher {
        return when (cachePolicy) {
            CACHE_ONLY -> {
                AppSyncResponseFetchers.CACHE_ONLY
            }
            REMOTE_ONLY -> {
                AppSyncResponseFetchers.NETWORK_ONLY
            }
        }
    }
}
