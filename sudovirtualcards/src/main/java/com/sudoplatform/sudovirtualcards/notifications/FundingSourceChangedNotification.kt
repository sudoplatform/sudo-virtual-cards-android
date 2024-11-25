/*
 * Copyright © 2024 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards.notifications

import com.sudoplatform.sudovirtualcards.types.FundingSourceFlags
import com.sudoplatform.sudovirtualcards.types.FundingSourceState
import com.sudoplatform.sudovirtualcards.types.FundingSourceType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A fundingSourceChanged Virtual Cards Service notification
 *
 * @property type [String] Type of notification. Always [FundingSourceChangedNotification.TYPE]
 * @property owner [String] Subject identifier of user to whom the notification is addressed.
 * @property fundingSourceId [String] Identifier of funding source to which this notification pertains.
 * @property fundingSourceType [FundingSourceType] Type of funding source to which this notification pertains.
 * @property last4 [String] last4 digits of the funding source to enable users to distinguish.
 * @property state [FundingSourceState] New state of the funding source.
 * @property flags [List<FundingSourceFlags>] List of flags associated with the funding source.
 * @property updatedAtEpochMs [Long] When the funding source update occurred.
 */
@Serializable
@SerialName(FundingSourceChangedNotification.TYPE) // Value of type property
class FundingSourceChangedNotification(
    override val type: String,
    override val owner: String,
    override val fundingSourceId: String,
    override val fundingSourceType: FundingSourceType,
    override val last4: String,
    val state: FundingSourceState,
    val flags: List<FundingSourceFlags>,
    val updatedAtEpochMs: Long,
) : FundingSourceNotification() {
    internal companion object {
        const val TYPE = "fundingSourceChanged"
    }
}
