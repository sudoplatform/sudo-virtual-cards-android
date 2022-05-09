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
 * Representation of a funding source used in the Sudo Platform Virtual Cards SDK.
 *
 * @property id [String] Identifier of the funding source.
 * @property owner [String] Identifier of the user that owns the funding source.
 * @property version [Int] Current version of the funding source.
 * @property createdAt [Date] Date when the funding source was created.
 * @property updatedAt [Date] Date when the funding source was last updated.
 * @property state [State] Current state of the funding source.
 * @property currency [String] Billing currency of the funding source as a 3 character ISO 4217 currency code.
 * @property last4 [String] Last 4 digits of the credit card used as the funding source.
 * @property network [CreditCardNetwork] Payments network of the funding source.
 */
@Parcelize
data class FundingSource(
    val id: String,
    val owner: String,
    val version: Int,
    val createdAt: Date,
    val updatedAt: Date,
    val state: State,
    val currency: String,
    val last4: String,
    val network: CreditCardNetwork
) : Parcelable {
    enum class State {
        /** Funding source is active and can be used */
        ACTIVE,
        /** Funding source is inactive and cannot be used */
        INACTIVE,
        /** Unknown state. Please check you have the correct (latest) version of this SDK. */
        UNKNOWN
    }

    enum class CreditCardNetwork {
        AMEX,
        DINERS,
        DISCOVER,
        JCB,
        MASTERCARD,
        UNIONPAY,
        VISA,
        OTHER,
        /** Unknown network. Please check you have the correct (latest) version of this SDK. */
        UNKNOWN
    }
}
