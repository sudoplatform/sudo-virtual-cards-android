/*
 * Copyright © 2024 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards.subscription

import com.sudoplatform.sudovirtualcards.types.FundingSource

/**
 * Subscriber for receiving notifications about changes to [FundingSource]s.
 */
interface FundingSourceSubscriber : Subscriber {
    /**
     * Notifies the subscriber of a modified [FundingSource]
     *
     * @param fundingSource modified [FundingSource].
     */
    fun fundingSourceChanged(fundingSource: FundingSource)
}
