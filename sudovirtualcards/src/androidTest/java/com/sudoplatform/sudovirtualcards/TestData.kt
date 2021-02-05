/*
 * Copyright © 2020 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards

/**
 * Data used in tests.
 *
 * @since 2020-06-17
 */
object TestData {

    /** Test user that is pre-verified */
    object VerifiedUser {
        const val firstName = "John"
        const val lastName = "Smith"
        const val fullName = "$firstName $lastName"
        const val addressLine1 = "222333 Peachtree Place"
        val addressLine2 = null
        const val city = "Atlanta"
        const val state = "GA"
        const val postalCode = "30318"
        const val country = "US"
        const val dateOfBirth = "1975-02-28"
    }

    object IdentityVerification {
        const val virtualCardsAudience = "sudoplatform.virtual-cards.virtual-card"
    }

    /** Funding source test data.
     *
     * Note: All test data taken from https://stripe.com/docs/testing
     */
    object Visa {
        const val securityCode = "123"
        const val creditCardNumber = "4242424242424242"
    }
    object Mastercard {
        const val securityCode = "123"
        const val creditCardNumber = "5555555555554444"
    }
}
