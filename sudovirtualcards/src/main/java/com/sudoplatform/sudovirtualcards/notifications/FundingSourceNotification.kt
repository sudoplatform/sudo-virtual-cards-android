/*
 * Copyright © 2024 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards.notifications

import com.sudoplatform.sudovirtualcards.types.FundingSourceType
import kotlinx.serialization.Serializable

/**
 * Base class of Virtual Cards Service notifications that pertain to a specific funding source.
 *
 * @property type [String] Type of notification.
 * @property owner [String] Subject identifier of user to whom the notification is addressed.
 * @property fundingSourceId [String] Identifier of funding source to which this notification pertains.
 * @property fundingSourceType [String] Type of funding source to which this notification pertains.
 * @property last4 [String] last4 digits of the funding source to enable users to distinguish.
 */
@Serializable
sealed class FundingSourceNotification : VirtualCardsServiceNotification() {
    abstract override val type: String
    abstract override val owner: String
    abstract val fundingSourceId: String
    abstract val fundingSourceType: FundingSourceType
    abstract val last4: String
}
