/*
 * Copyright © 2023 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards.types

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * The representation of the legal residence of a cardholder for purposes of billing
 * in the Sudo Platform Virtual Cards SDK.
 *
 * @property addressLine1 [String] Street address for the cardholder's legal residence.
 * @property addressLine2 [String] Optional secondary address information for the cardholder's legal residence.
 * @property city [String] City of the cardholder's legal residence.
 * @property state [String] State or province of the cardholder's legal residence.
 * @property postalCode [String] Postal code for the cardholder's legal residence.
 * @property country [String] ISO-3166 Alpha-2 country code of the cardholder's legal residence.
 */
@Parcelize
data class BillingAddress(
    val addressLine1: String,
    val addressLine2: String? = null,
    val city: String,
    val state: String,
    val postalCode: String,
    val country: String,
) : Parcelable
