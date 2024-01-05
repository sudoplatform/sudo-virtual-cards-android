/*
 * Copyright © 2023 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards.types

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.RawValue
import java.util.Date

/**
 * Representation of a Virtual Card used in the Sudo Platform Virtual Cards SDK.
 *
 * @property id [String] Identifier of the virtual card.
 * @property owner [String] Identifier of the user that owns the virtual card.
 * @property version [Int] Current version of the virtual card.
 * @property createdAt [Date] Date when the virtual card was created.
 * @property updatedAt [Date] Date when the virtual card was last updated.
 * @property owners [List<Owner>] List of identifiers of user/accounts associated with this virtual card. Typically, this will
 *  consist of at least the user id and sudo id of the account.
 * @property fundingSourceId [String] Identifier of the funding source associated with the virtual card.
 * @property currency [String] The ISO 4217 currency code.
 * @property state [CardState] Current state of the card.
 * @property activeTo [Date] The date of when the virtual card will be active to.
 * @property cancelledAt The date that the virtual card was cancelled, null if the card has not been cancelled.
 * @property cardHolder [String] The name of the virtual card holder.
 * @property last4 [String] Last 4 digits on the virtual card.
 * @property cardNumber [String] Card number (Permanent Account Number) of the card.
 * @property securityCode [String] Security code (csc) for the back of the card, 3 or 4 digits.
 * @property alias [String] *deprecated* User defined name associated with the virtual card.
 * @property billingAddress [BillingAddress] Billing address associated with the virtual card. If not supplied, the
 *  default billing address will be used.
 * @property expiry [Expiry] Expiry information of the virtual card.
 * @property lastTransaction [Transaction] Most recent transaction, if any, that has occurred on the virtual card.
 * @property metadata [JsonValue] Custom metadata to associate with the virtual card. Can be used for values such as
 *  card aliases, card colors, image references, etc.
 */
@Parcelize
data class VirtualCard(
    val id: String,
    val owner: String,
    val version: Int,
    val createdAt: Date,
    val updatedAt: Date,
    val owners: List<Owner>,
    val fundingSourceId: String,
    val currency: String,
    val state: CardState,
    val activeTo: Date,
    val cancelledAt: Date? = null,
    val cardHolder: String,
    val last4: String,
    val cardNumber: String,
    val securityCode: String,
    @Deprecated("Store alias as a property of metadata instead")
    val alias: String?,
    val metadata: @RawValue JsonValue<Any>? = null,
    val billingAddress: BillingAddress? = null,
    val expiry: Expiry,
    val lastTransaction: Transaction? = null,
) : Parcelable

/**
 * Representation of a Virtual Card without its unsealed attributes used in the Sudo
 * Platform Virtual Cards SDK.
 *
 * @property id [String] Identifier of the virtual card.
 * @property owner [String] Identifier of the user that owns the virtual card.
 * @property version [Int] Current version of the virtual card.
 * @property createdAt [Date] Date when the virtual card was created.
 * @property updatedAt [Date] Date when the virtual card was last updated.
 * @property owners [List<Owner>] List of identifiers of user/accounts associated with this virtual card. Typically, this will
 *  consist of at least the user id and sudo id of the account.
 * @property fundingSourceId [String] Identifier of the funding source associated with the virtual card.
 * @property currency [String] The ISO 4217 currency code.
 * @property state [CardState] Current state of the card.
 * @property activeTo [Date] The date of when the virtual card will be active to.
 * @property cancelledAt The date that the virtual card was cancelled, null if the card has not been cancelled.
 * @property last4 [String] Last 4 digits on the virtual card.
 */
@Parcelize
data class PartialVirtualCard(
    val id: String,
    val owner: String,
    val version: Int,
    val createdAt: Date,
    val updatedAt: Date,
    val owners: List<Owner>,
    val fundingSourceId: String,
    val currency: String,
    val state: CardState,
    val activeTo: Date,
    val cancelledAt: Date? = null,
    val last4: String,
) : Parcelable

/**
 * Representation of an enumeration depicting the state that the [VirtualCard] is in, in the
 * Sudo Platform Virtual Cards SDK.
 */
enum class CardState {
    /** Card is in an issued state and ready to be used. */
    ISSUED,

    /** Card is in a failed state and must be rectified.*/
    FAILED,

    /** Card has been closed. */
    CLOSED,

    /** Card has been suspended. */
    SUSPENDED,

    /** API Evolution - if this occurs, it may mean you need to update the library. */
    UNKNOWN,
}
