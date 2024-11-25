/*
 * Copyright © 2024 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards.types

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import java.util.Date

/**
 * Representation of a fundingSourceChanged Virtual Cards Service notification.
 * @property id [String] Identifier of funding source to which this notification corresponds.
 * @property owner [String] Subject identifier of user to whom the notification is addressed.
 * @property type [FundingSourceType] Type of funding source to which this notificaiton applies.
 * @property last4 [String] Last 4 digits of funding source to permit disambiguation.
 * @property state [FundingSourceState] Current state of the funding source
 * @property flags [List<FundingSourceFlags>] Set of flags currently associated with the funding source
 * @property updatedAt [Date] When the funding source was updated
 */
@Parcelize
data class VirtualCardsFundingSourceChangedNotification(
    val id: String,
    val owner: String,
    val type: FundingSourceType,
    val last4: String,
    val state: FundingSourceState,
    val flags: List<FundingSourceFlags>,
    val updatedAt: Date,
) : Parcelable
