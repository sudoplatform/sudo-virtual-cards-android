/*
 * Copyright © 2022 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards

import com.sudoplatform.sudoprofiles.Sudo

data class TestCard(val creditCardNumber: String, val securityCode: String, val last4: String)

/**
 * Data used in tests.
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

    /** Test information used to provision a virtual card */
    object ProvisionCardInput {
        const val cardHolder = "Unlimited Cards"
        const val addressLine1 = "123 Nowhere St"
        const val city = "Menlo Park"
        const val state = "CA"
        const val postalCode = "94025"
        const val country = "US"
        const val currency = "USD"
    }

    /** Test sudo to use for integration tests */
    val sudo = Sudo("Mr", "Theodore", "Bear", "Shopping", null, null)

    /** Stripe Funding source test data.
     *
     * Note: All test data taken from https://stripe.com/docs/testing
     */
    val TestCards = mapOf(
        "stripe" to mapOf(
            "Visa-3DS2-1" to TestCard("4000000000003220", "123", "3220"),
            "Visa-3DS2-2" to null,
            "Visa-No3DS-1" to TestCard("4242424242424242", "123", "4242"),
            "MC-No3DS-1" to TestCard("5555555555554444", "123", "4444")
        ),
        /** Checkout Funding source test data.
         *
         * Note: All test data taken from https://www.checkout.com/docs/testing/test-cards
         */
        "checkout" to mapOf(
            "Visa-3DS2-1" to TestCard("4242424242424242", "123", "4242"),
            "Visa-3DS2-2" to TestCard("4543474002249996", "956", "9996"),
            "Visa-No3DS-1" to TestCard("4532432452900131", "257", "0131"),
            "MC-No3DS-1" to TestCard("5183683001544411", "100", "4411")
        )
    )
    @Deprecated("use TestCards[\"providerName\"]")
    val StripeTestCards = (TestCards["stripe"] ?: throw AssertionError("Missing test data"))
}
