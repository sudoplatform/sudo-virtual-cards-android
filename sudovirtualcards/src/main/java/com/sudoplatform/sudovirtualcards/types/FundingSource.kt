/*
 * Copyright © 2020 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards.types

import android.os.Parcelable
import kotlinx.android.parcel.Parcelize

/**
 * Representation of a [FundingSource] used in the Sudo Platform Virtual Cards SDK.
 *
 * @property id Identifier generated from the Sudo Platform Virtual Cards service.
 * @property owner Owner identifier of the funding source. This is typically the user id.
 * @property version Version assigned by the service.
 * @property state Current state of the funding source.
 * @property currency Billing currency of the funding source as a 3 character ISO 4217 currency code.
 * @property last4 Last 4 digits of the credit card used as the funding source.
 * @property network Payments network of the funding source.
 *
 * @since 2020-05-21
 */
@Parcelize
data class FundingSource(
    val id: String,
    val owner: String,
    val version: Int,
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
