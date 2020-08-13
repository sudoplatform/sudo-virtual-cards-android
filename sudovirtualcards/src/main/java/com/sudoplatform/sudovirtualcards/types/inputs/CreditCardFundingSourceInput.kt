/*
 * Copyright © 2020 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards.types.inputs

/**
 * Input object containing the information required to create a Credit Card Funding Source.
 *
 * @property cardNumber Required card account number.
 * @property expirationMonth Required expiration month field.
 * @property expirationYear Required expiration year field.
 * @property securityCode Required 3 or 4 digit security code from the back of the card.
 * @property addressLine1 Required street address for the cardholder's legal residence.
 * @property addressLine2 Optional secondary address information for the cardholder's legal residence.
 * @property city Required city that the address resides in.
 * @property state Required state that the address resides in.
 * @property postalCode Required postal code for the cardholder's legal residence.
 * @property country Required ISO-3166 Alpha-2 country code that the address resides in.
 *
 * @since 2020-05-21
 */
data class CreditCardFundingSourceInput(
    val cardNumber: String,
    val expirationMonth: Int,
    val expirationYear: Int,
    val securityCode: String,
    val addressLine1: String,
    val addressLine2: String? = null,
    val city: String,
    val state: String,
    val postalCode: String,
    val country: String
)
