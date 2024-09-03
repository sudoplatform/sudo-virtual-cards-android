/*
 * Copyright © 2024 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards.types.inputs

/**
 * Input object containing the information required to create a credit card funding source.
 *
 * @property cardNumber [String] Required card account number.
 * @property expirationMonth [Int] Required expiration month field.
 * @property expirationYear [Int] Required expiration year field.
 * @property securityCode [String] Required 3 or 4 digit security code from the back of the card.
 * @property addressLine1 [String] Required street address for the cardholder's legal residence.
 * @property addressLine2 [String] Optional secondary address information for the cardholder's legal residence.
 * @property city [String] Required city that the address resides in.
 * @property state [String] Required state that the address resides in.
 * @property postalCode [String] Required postal code for the cardholder's legal residence.
 * @property country [String] Required ISO-3166 Alpha-2 country code that the address resides in.
 * @property name [String] Name of the cardholder. Optional for backwards compatibility but required for checkout integrations
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
    val country: String,
    val name: String? = null,
)
