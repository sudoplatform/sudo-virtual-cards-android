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
 * A representation of a virtual card that is in the process of being provisioned in the
 * Sudo Platform Virtual Cards SDK.
 *
 * @property id [String] Identifier of the provisional virtual card.
 * @property owner [String] Identifier of the user that owns the provisional virtual card.
 * @property version [Int] Current version of the provisional virtual card.
 * @property createdAt [Date] Date when the provisional virtual card was created.
 * @property updatedAt [Date] Date when the provisional virtual card was last updated.
 * @property clientRefId [String] A reference identifier generated by the caller.
 * @property provisioningState [ProvisioningState] Current state of the provisional virtual card.
 * @property card [VirtualCard] Fully provisioned virtual card, will be null until provisioning completes.
 */
@Parcelize
data class ProvisionalVirtualCard(
    val id: String,
    val owner: String,
    val version: Int,
    val createdAt: Date,
    val updatedAt: Date,
    val clientRefId: String,
    val provisioningState: ProvisioningState,
    val card: VirtualCard? = null,
) : Parcelable {
    /** State of the provisional card. */
    enum class ProvisioningState {
        /** VirtualCard is in a provisioning state currently. */
        PROVISIONING,
        /**
         * [VirtualCard] has been provisioned which means the card should be accessible by either a getVirtualCard API
         * or via [card] property.
         */
        COMPLETED,
        /** [VirtualCard] is in a failed state and needs to be rectified. */
        FAILED,
        /** API Evolution - if this occurs, it may mean you need to update the library. */
        UNKNOWN
    }
}