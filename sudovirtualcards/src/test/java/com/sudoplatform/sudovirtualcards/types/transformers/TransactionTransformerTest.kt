/*
 * Copyright © 2022 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards.types.transformers

import com.sudoplatform.sudovirtualcards.BaseTests
import com.sudoplatform.sudovirtualcards.types.DeclineReason
import io.kotlintest.shouldBe
import org.junit.Test

/**
 * Testing the transaction transformer.
 */
class TransactionTransformerTest : BaseTests() {

    @Test
    fun `decline reasons should decode`() {
        "INSUFFICIENT_FUNDS".toDeclineReason() shouldBe DeclineReason.INSUFFICIENT_FUNDS
        "SUSPICIOUS".toDeclineReason() shouldBe DeclineReason.SUSPICIOUS
        "EXPIRY_CHECK_FAILED".toDeclineReason() shouldBe DeclineReason.EXPIRY_CHECK_FAILED
        "foobar".toDeclineReason() shouldBe DeclineReason.UNKNOWN
    }
}
