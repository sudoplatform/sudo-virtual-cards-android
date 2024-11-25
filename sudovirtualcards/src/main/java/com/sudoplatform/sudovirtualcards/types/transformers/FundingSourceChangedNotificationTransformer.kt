/*
 * Copyright © 2024 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards.types.transformers

import com.sudoplatform.sudovirtualcards.notifications.FundingSourceChangedNotification
import java.util.Date
import com.sudoplatform.sudovirtualcards.types.VirtualCardsFundingSourceChangedNotification as VirtualCardsFundingSourceChangedNotification

/**
 * Transformer responsible for transforming the [FundingSourceChangedNotification] data
 * to the [VirtualCardsFundingSourceChangedNotification] entity type that is exposed to users.
 */
internal object FundingSourceChangedNotificationTransformer {

    /**
     * Transform the [FundingSourceChangedNotification] type to its [VirtualCardsFundingSourceChangedNotification]
     *  entity type.
     *
     * @param notification [FundingSourceChangedNotification] The message received notification type.
     * @return The [VirtualCardsFundingSourceChangedNotification] entity type.
     */
    fun toEntity(notification: FundingSourceChangedNotification): VirtualCardsFundingSourceChangedNotification {
        return VirtualCardsFundingSourceChangedNotification(
            id = notification.fundingSourceId,
            owner = notification.owner,
            type = notification.fundingSourceType,
            last4 = notification.last4,
            state = notification.state,
            flags = notification.flags,
            updatedAt = Date(notification.updatedAtEpochMs),
        )
    }
}
