/*
 * Copyright © 2020 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards.types.inputs

import com.sudoplatform.sudovirtualcards.types.inputs.filters.TransactionFilter
import com.sudoplatform.sudovirtualcards.types.inputs.filters.filterTransactionsBy
import io.kotlintest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotlintest.shouldBe
import org.junit.Test

/**
 * Check that the definition of the [TransactionFilter] matches what is specified.
 *
 * @since 2020-07-17
 */
class TransactionFilterTest {

    @Test
    fun `filter builder should combine all the elements specified`() {
        val filter = filterTransactionsBy {
            cardId equalTo "1"
            cardId between ("a" to "b")
        }
        filter.propertyFilters.size shouldBe 2
        filter.propertyFilters.shouldContainExactlyInAnyOrder(
            TransactionFilter.PropertyFilter(
                TransactionFilter.Property.CARD_ID,
                TransactionFilter.ComparisonOperator.EQUAL,
                Pair("1", "")
            ),
            TransactionFilter.PropertyFilter(
                TransactionFilter.Property.CARD_ID,
                TransactionFilter.ComparisonOperator.BETWEEN,
                Pair("a", "b")
            )
        )
    }
}
