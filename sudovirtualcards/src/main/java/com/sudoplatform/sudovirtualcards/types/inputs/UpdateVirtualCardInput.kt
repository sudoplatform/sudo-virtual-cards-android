/*
 * Copyright © 2023 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards.types.inputs

import com.sudoplatform.sudovirtualcards.types.BillingAddress
import com.sudoplatform.sudovirtualcards.types.JsonValue

/**
 * Input object containing the information required to update a virtual card.
 *
 * @property id [String] Identifier of the virtual card to update.
 * @property expectedCardVersion [Int] Version of virtual card to update. If specified, version must
 *  match existing version of virtual card.
 * @property cardHolder [String] The name of the virtual card holder. Leave as existing to remain unchanged.
 * @property alias [String] *deprecated* User defined name associated with the card.
 * @property metadata [JsonValue] Custom metadata to associated with the virtual card. Can be used for values
 *  such as card aliases, card colors, image references, etc. There is a limit of 3k characters when data is serialized.
 * @property billingAddress [BillingAddress] associated with the card. To remove, set to null.
 */
data class UpdateVirtualCardInput(
    val id: String,
    val expectedCardVersion: Int? = null,
    val cardHolder: String,
    @Deprecated("Store alias as a property of metadata instead")
    val alias: String? = null,
    val metadata: JsonValue<Any>? = null,
    val billingAddress: BillingAddress? = null
) {
    constructor(
        id: String,
        expectedCardVersion: Int? = null,
        cardHolder: String,
        alias: String? = null,
        metadata: JsonValue<Any>? = null,
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
        metadata = metadata,
        billingAddress = BillingAddress(addressLine1, addressLine2, city, state, postalCode, country)
    )
}
