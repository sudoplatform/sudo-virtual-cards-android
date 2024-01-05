/*
 * Copyright © 2023 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards

import com.sudoplatform.sudoprofiles.Sudo

data class TestCardBillingAddress(
    val addressLine1: String = TestData.VerifiedUser.addressLine1,
    val addressLine2: String? = TestData.VerifiedUser.addressLine2,
    val city: String = TestData.VerifiedUser.city,
    val state: String = TestData.VerifiedUser.state,
    val postalCode: String = TestData.VerifiedUser.postalCode,
    val country: String = TestData.VerifiedUser.country,
)

data class TestCard(
    val creditCardNumber: String,
    val securityCode: String,
    val address: TestCardBillingAddress,
) {

    var last4 = creditCardNumber.takeLast(4)
}

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

    val DefaultTestCardBillingAddress = mapOf(
        "stripe" to TestCardBillingAddress(),
        "checkout" to TestCardBillingAddress(
            // See https://www.checkout.com/docs/testing/avs-check-testing
            // We need to set this to ensure success cases force AVS check
            // to pass. Otherwise, because the test cards are all non-US,
            // they return AVS check code 'G' which we don't accept as its
            // an international code indicating no check performed.
            addressLine1 = "Test_Y",
        ),
    )

    /** Stripe Funding source test data.
     *
     * Note: All test data taken from https://stripe.com/docs/testing
     */
    val TestCards = mapOf(
        "stripe" to mapOf(
            "Visa-3DS2-1" to TestCard("4000000000003220", "123", DefaultTestCardBillingAddress["stripe"]!!),
            "Visa-No3DS-1" to TestCard("4242424242424242", "123", DefaultTestCardBillingAddress["stripe"]!!),
            "MC-No3DS-1" to TestCard("5555555555554444", "123", DefaultTestCardBillingAddress["stripe"]!!),
        ),
        /** Checkout Funding source test data.
         *
         * Note: All test data taken from https://www.checkout.com/docs/testing/test-cards
         * Visa 4532432452900131 is flagged as non-3ds but does trigger a challenge.
         */
        "checkout" to mapOf(
            "Visa-3DS2-1" to TestCard("4242424242424242", "123", DefaultTestCardBillingAddress["checkout"]!!),
            "Visa-3DS2-2" to TestCard("4543474002249996", "956", DefaultTestCardBillingAddress["checkout"]!!),
            "Visa-No3DS-1" to TestCard("4484070000035519", "257", DefaultTestCardBillingAddress["checkout"]!!),
            "MC-No3DS-1" to TestCard("5183683001544411", "100", DefaultTestCardBillingAddress["checkout"]!!),
            "BadAddress" to TestCard("4484070000035519", "257", TestCardBillingAddress(addressLine1 = "Test_N")),
            "BadCVV" to TestCard("4484070000035519", "202", DefaultTestCardBillingAddress["checkout"]!!),
        ),
    )

    object TestBankAccountUsername {
        const val customChecking = "custom_checking_500"
        const val customIdentityMismatch = "custom_identity_mismatch"
    }
}
