/*
 * Copyright © 2020 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards.types.inputs

import com.sudoplatform.sudovirtualcards.types.BillingAddress

/**
 * Input object containing the information required to update a virtual payment card.
 *
 * @property id Identifier of the card.
 * @property cardHolder The name on the front of the card.
 * @property alias User defined name associated with the card.
 * @property billingAddress [BillingAddress] associated with the card.
 */
data class UpdateCardInput(
    val id: String,
    val cardHolder: String,
    val alias: String,
    val billingAddress: BillingAddress? = null
) {
    constructor(
        id: String,
        cardHolder: String,
        alias: String,
        addressLine1: String,
        addressLine2: String? = null,
        city: String,
        state: String,
        postalCode: String,
        country: String
    ) : this(
        id = id,
        cardHolder = cardHolder,
        alias = alias,
        billingAddress = BillingAddress(addressLine1, addressLine2, city, state, postalCode, country)
    )
}
