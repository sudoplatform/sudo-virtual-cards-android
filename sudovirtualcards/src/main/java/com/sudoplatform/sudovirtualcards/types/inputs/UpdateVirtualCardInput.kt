/*
 * Copyright © 2022 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards.types.inputs

import com.sudoplatform.sudovirtualcards.types.BillingAddress

/**
 * Input object containing the information required to update a virtual card.
 *
 * @property id [String] Identifier of the virtual card to update.
 * @property expectedCardVersion [Int] Version of virtual card to update. If specified, version must
 *  match existing version of virtual card.
 * @property cardHolder [String] The name of the virtual card holder. Leave as existing to remain unchanged.
 * @property alias [String] User defined name associated with the card.
 * @property billingAddress [BillingAddress] associated with the card. To remove, set to null.
 */
data class UpdateVirtualCardInput(
    val id: String,
    val expectedCardVersion: Int? = null,
    val cardHolder: String,
    val alias: String,
    val billingAddress: BillingAddress? = null
) {
    constructor(
        id: String,
        expectedCardVersion: Int? = null,
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
        expectedCardVersion = expectedCardVersion,
        cardHolder = cardHolder,
        alias = alias,
        billingAddress = BillingAddress(addressLine1, addressLine2, city, state, postalCode, country)
    )
}
