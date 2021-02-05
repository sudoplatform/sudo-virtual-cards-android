/*
 * Copyright © 2020 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards.types.inputs

import com.sudoplatform.sudovirtualcards.types.inputs.filters.CardFilter
import com.sudoplatform.sudovirtualcards.types.inputs.filters.filterCardsBy
import io.kotlintest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotlintest.shouldBe
import org.junit.Test

/**
 * Check that the definition of the [CardFilter] matches what is specified.
 *
 * @since 2020-07-22
 */
class CardFilterTest {

    @Test
    fun `filter builder should combine all the elements specified`() {
        val filter = filterCardsBy {
            state equalTo "ISSUED"
        }
        filter.propertyFilters.size shouldBe 1
        filter.propertyFilters.shouldContainExactlyInAnyOrder(
            CardFilter.PropertyFilter(
                CardFilter.Property.STATE,
                CardFilter.ComparisonOperator.EQUAL,
                Pair("ISSUED", "")
            )
        )
    }
}
