/*
 * Copyright © 2022 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards.types

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import java.util.Date

/**
 * A representation of a funding source that is in the process of being created in the
 * Sudo Platform Virtual Cards SDK.
 *
 * @property id [String] Identifier of the provisional funding source.
 * @property owner [String] Identifier of the user that owns the provisional funding source.
 * @property version [Int] Current version of the provisional funding source.
 * @property createdAt [Date] Date when the provisional funding source was created.
 * @property updatedAt [Date] Date when the provisional funding source was last updated.
 * @property state [ProvisioningState] Current state of the provisional funding source.
 * @property stateReason [StateReason] The provisional funding source state reason.
 * @property provisioningData [ProvisioningData] Provisioning data used to complete the provisioning
 *  of the funding source.
 */
@Parcelize
data class ProvisionalFundingSource(
    val id: String,
    val owner: String,
    val version: Int,
    val createdAt: Date,
    val updatedAt: Date,
    val state: ProvisioningState,
    val stateReason: StateReason,
    val provisioningData: ProvisioningData
) : Parcelable {
    enum class ProvisioningState {
        /** Provisional funding source has completed provisioning */
        COMPLETED,
        /** Provisional funding source has failed provisioning */
        FAILED,
        /** Provisional funding source is in a pending state */
        PENDING,
        /** Provisional funding source is in the middle of being provisioned */
        PROVISIONING,
        /** Unknown state. Please check you have the correct (latest) version of this SDK. */
        UNKNOWN
    }

    enum class StateReason {
        ADMIN,
        DELETION,
        ENTITLEMENT,
        LOCKED,
        PROCESSING,
        SUSPICIOUS,
        UNLOCKED,
        USER,
        /** Unknown state reason. Please check you have the correct (latest) version of this SDK. */
        UNKNOWN
    }
}
