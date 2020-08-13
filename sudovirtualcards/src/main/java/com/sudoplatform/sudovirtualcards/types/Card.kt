/*
 * Copyright © 2020 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards.types

import android.os.Parcelable
import kotlinx.android.parcel.Parcelize
import java.util.Date

/**
 * Representation of a Virtual Card used in the Sudo Platform SDK.
 *
 * @property id Identifier generated by the Virtual Cards Service.
 * @property owners List of identifiers of user/accounts associated with this card. Typically, this will
 * consist of at least the user id and sudo id of the account.
 * @property owner Identifier of the user that owns the card.
 * @property version Card version supplied by the Virtual cards service.
 * @property fundingSourceId Identifier of the associated funding source used to provision the card.
 * @property state Current state of the card.
 * @property cardHolder The name on the front of the card.
 * @property alias User defined name associated with the card.
 * @property last4 Last 4 digits on the card.
 * @property cardNumber Card number (Primary Account Number) of the card
 * @property securityCode Security code for the back of the card, 3 or 4 digits.
 * @property billingAddress [BillingAddress] associated with the card. If not supplied, the default billing address will be used.
 * @property expirationMonth Card expiry month, 2 digits.
 * @property expirationYear Card expiry year, 4 digits.
 * @property currency The ISO 4217 currency code
 * @property activeTo The last [Date] the card will be active.
 * @property cancelledAt The [Date] that the card was cancelled, null if the card has not been cancelled.
 * @property createdAt [Date] when the card was created
 * @property updatedAt [Date] when the card was last updated
 *
 * @since 2020-06-11
 */
@Parcelize
data class Card(
    val id: String,
    val owners: List<Owner>,
    val owner: String,
    val version: Int,
    val fundingSourceId: String,
    val state: State,
    val cardHolder: String,
    val alias: String,
    val last4: String,
    val cardNumber: String,
    val securityCode: String,
    val billingAddress: BillingAddress? = null,
    val expirationMonth: Int,
    val expirationYear: Int,
    val currency: String,
    val activeTo: Date,
    val cancelledAt: Date? = null,
    val createdAt: Date,
    val updatedAt: Date
) : Parcelable {
    /** State of the card. */
    enum class State {
        /** Card is in an issued state and ready to be used. */
        ISSUED,
        /** Card is in a failed state and must be rectified.*/
        FAILED,
        /** Card has been closed. */
        CLOSED,
        /** Card has been suspended. */
        SUSPENDED,
        /** API Evolution - if this occurs, it may mean you need to update the library. */
        UNKNOWN
    }
}
