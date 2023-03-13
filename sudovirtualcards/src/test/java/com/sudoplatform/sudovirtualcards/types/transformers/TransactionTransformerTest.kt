/*
 * Copyright © 2023 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards.types.transformers

import com.sudoplatform.sudovirtualcards.BaseTests
import com.sudoplatform.sudovirtualcards.types.ChargeDetailState
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

    @Test
    fun `charge detail state should decode`() {
        "PENDING".toChargeDetailState() shouldBe ChargeDetailState.PENDING
        "CLEARED".toChargeDetailState() shouldBe ChargeDetailState.CLEARED
        "INSUFFICIENT_FUNDS".toChargeDetailState() shouldBe ChargeDetailState.INSUFFICIENT_FUNDS
        "FAILED".toChargeDetailState() shouldBe ChargeDetailState.FAILED
        "foobar".toChargeDetailState() shouldBe ChargeDetailState.UNKNOWN
    }
}
