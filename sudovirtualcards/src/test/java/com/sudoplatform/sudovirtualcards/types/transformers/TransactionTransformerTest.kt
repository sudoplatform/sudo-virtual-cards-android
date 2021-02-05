/*
 * Copyright © 2020 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards.types.transformers

import com.sudoplatform.sudovirtualcards.BaseTests
import com.sudoplatform.sudovirtualcards.graphql.ListTransactionsQuery
import com.sudoplatform.sudovirtualcards.graphql.type.TransactionType
import com.sudoplatform.sudovirtualcards.types.DeclineReason
import com.sudoplatform.sudovirtualcards.types.inputs.filters.filterTransactionsBy
import io.kotlintest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotlintest.shouldBe
import org.junit.Test

/**
 * Test the local filtering of transactions by sequenceId is correct.
 *
 * @since 2020-07-21
 */
class TransactionTransformerTest : BaseTests() {

    private fun txn(sequenceId: String): ListTransactionsQuery.Item {
        return ListTransactionsQuery.Item(
            "typename",
            "id",
            "owner",
            1,
            1.0,
            1.0,
            "algorithm",
            "keyId",
            "cardId",
            sequenceId,
            TransactionType.COMPLETE,
            "sealedTime",
            ListTransactionsQuery.BilledAmount("typename", "USD", "billedAmount"),
            ListTransactionsQuery.TransactedAmount("typename", "USD", "transactedAmount"),
            "description",
            null,
            emptyList()
        )
    }

    private val resultsWithOne42 by before {
        listOf(
            txn("4242"),
            txn("42"),
            txn(" 42")
        )
    }

    @Test
    fun `sequenceId filter equalTo`() {
        val filter = filterTransactionsBy {
            sequenceId equalTo "42"
            cardId equalTo "this is irrelevant"
        }

        val filtered = TransactionTransformer.filter(resultsWithOne42, filter)
        filtered.map { it.sequenceId() }.shouldContainExactlyInAnyOrder("42")
    }

    @Test
    fun `sequenceId filter notEqualTo`() {
        val filter = filterTransactionsBy {
            sequenceId notEqualTo "42"
            cardId equalTo "this is irrelevant"
        }

        val filtered = TransactionTransformer.filter(resultsWithOne42, filter)
        filtered.map { it.sequenceId() }.shouldContainExactlyInAnyOrder("4242", " 42")
    }

    @Test
    fun `sequenceId filter lessThanOrEqualTo`() {
        val filter = filterTransactionsBy {
            sequenceId lessThanOrEqualTo "42"
            cardId equalTo "this is irrelevant"
        }

        val filtered = TransactionTransformer.filter(resultsWithOne42, filter)
        filtered.map { it.sequenceId() }.shouldContainExactlyInAnyOrder("42", " 42")
    }

    @Test
    fun `sequenceId filter lessThan`() {
        val filter = filterTransactionsBy {
            sequenceId lessThan "42"
            cardId equalTo "this is irrelevant"
        }

        val filtered = TransactionTransformer.filter(resultsWithOne42, filter)
        filtered.map { it.sequenceId() }.shouldContainExactlyInAnyOrder(" 42")
    }

    @Test
    fun `sequenceId filter greaterThanOrEqualTo`() {
        val filter = filterTransactionsBy {
            sequenceId greaterThanOrEqualTo "42"
            cardId equalTo "this is irrelevant"
        }

        val filtered = TransactionTransformer.filter(resultsWithOne42, filter)
        filtered.map { it.sequenceId() }.shouldContainExactlyInAnyOrder("4242", "42")
    }

    @Test
    fun `sequenceId filter greaterThan`() {
        val filter = filterTransactionsBy {
            sequenceId greaterThan "42"
            cardId equalTo "this is irrelevant"
        }

        val filtered = TransactionTransformer.filter(resultsWithOne42, filter)
        filtered.map { it.sequenceId() }.shouldContainExactlyInAnyOrder("4242")
    }

    @Test
    fun `sequenceId filter contains`() {
        val filter = filterTransactionsBy {
            sequenceId contains "24"
            cardId equalTo "this is irrelevant"
        }

        val filtered = TransactionTransformer.filter(resultsWithOne42, filter)
        filtered.map { it.sequenceId() }.shouldContainExactlyInAnyOrder("4242")
    }

    @Test
    fun `sequenceId filter notContains`() {
        val filter = filterTransactionsBy {
            sequenceId notContains "24"
            cardId equalTo "this is irrelevant"
        }

        val filtered = TransactionTransformer.filter(resultsWithOne42, filter)
        filtered.map { it.sequenceId() }.shouldContainExactlyInAnyOrder("42", " 42")
    }

    @Test
    fun `sequenceId filter beginsWith`() {
        val filter = filterTransactionsBy {
            sequenceId beginsWith " "
            cardId equalTo "this is irrelevant"
        }

        val filtered = TransactionTransformer.filter(resultsWithOne42, filter)
        filtered.map { it.sequenceId() }.shouldContainExactlyInAnyOrder(" 42")
    }

    @Test
    fun `sequenceId filter between`() {
        val filter = filterTransactionsBy {
            sequenceId between (" " to "42")
            cardId equalTo "this is irrelevant"
        }

        val filtered = TransactionTransformer.filter(resultsWithOne42, filter)
        filtered.map { it.sequenceId() }.shouldContainExactlyInAnyOrder(" 42", "42")
    }

    @Test
    fun `decline reasons should decode`() {
        "INSUFFICIENT_FUNDS".toDeclineReason() shouldBe DeclineReason.INSUFFICIENT_FUNDS
        "SUSPICIOUS".toDeclineReason() shouldBe DeclineReason.SUSPICIOUS
        "EXPIRY_CHECK_FAILED".toDeclineReason() shouldBe DeclineReason.EXPIRY_CHECK_FAILED
        "foobar".toDeclineReason() shouldBe DeclineReason.UNKNOWN
    }
}
