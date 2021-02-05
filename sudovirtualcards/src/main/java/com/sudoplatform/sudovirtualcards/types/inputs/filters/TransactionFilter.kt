/*
 * Copyright © 2020 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards.types.inputs.filters

import com.sudoplatform.sudovirtualcards.types.Transaction

/**
 * A filter that can be applied when listing transactions so that only the subset
 * of transactions that match the items in the filter are returned.
 *
 * @sample com.sudoplatform.sudovirtualcards.samples.Samples.transactionFilter
 * @since 2020-07-17
 */
class TransactionFilter private constructor(val propertyFilters: Set<PropertyFilter>) {

    companion object {
        /** Return a [TransactionFilter.Builder] that is used to create a [TransactionFilter]. */
        @JvmStatic
        fun builder() = Builder()
    }

    /** These are the properties of a [Transaction] that can be used in a [TransactionFilter]. */
    enum class Property {
        CARD_ID,
        SEQUENCE_ID
    }

    /** Properties used in filters can be compared to the values in a [Transaction] with these operators. */
    enum class ComparisonOperator {
        EQUAL,
        NOT_EQUAL,
        LESS_THAN_EQUAL,
        LESS_THAN,
        GREATER_THAN_EQUAL,
        GREATER_THAN,
        CONTAINS,
        NOT_CONTAINS,
        BEGINS_WITH,
        BETWEEN
    }

    /**
     * The filter [property] and its [value] that must match when compared according to the [comparison]
     * for a [Transaction] to be included in the list of results.
     */
    data class PropertyFilter(
        val property: Property,
        val comparison: ComparisonOperator,
        val value: Pair<String, String>
    )

    /** A Builder that is used to create a [TransactionFilter] */
    class Builder internal constructor() {

        private val propertyFilters = mutableSetOf<PropertyFilter>()

        /**
         * These provide a nicer syntax than specifying the enums in the lambda,
         * e.g cardId eq "4242" rather than [Property.CARD_ID] eq "4242"
         */
        val cardId = Property.CARD_ID
        val sequenceId = Property.SEQUENCE_ID

        private fun add(property: Property, comparison: ComparisonOperator, value: String) {
            propertyFilters.add(PropertyFilter(property = property, comparison = comparison, value = Pair(value, "")))
        }

        infix fun Property.equalTo(value: String) {
            add(this, ComparisonOperator.EQUAL, value)
        }

        infix fun Property.notEqualTo(value: String) {
            add(this, ComparisonOperator.NOT_EQUAL, value)
        }

        infix fun Property.lessThanOrEqualTo(value: String) {
            add(this, ComparisonOperator.LESS_THAN_EQUAL, value)
        }

        infix fun Property.lessThan(value: String) {
            add(this, ComparisonOperator.LESS_THAN, value)
        }

        infix fun Property.greaterThanOrEqualTo(value: String) {
            add(this, ComparisonOperator.GREATER_THAN_EQUAL, value)
        }

        infix fun Property.greaterThan(value: String) {
            add(this, ComparisonOperator.GREATER_THAN, value)
        }

        infix fun Property.contains(value: String) {
            add(this, ComparisonOperator.CONTAINS, value)
        }

        infix fun Property.notContains(value: String) {
            add(this, ComparisonOperator.NOT_CONTAINS, value)
        }

        infix fun Property.beginsWith(value: String) {
            add(this, ComparisonOperator.BEGINS_WITH, value)
        }

        infix fun Property.between(values: Pair<String, String>) {
            propertyFilters.add(PropertyFilter(property = this, comparison = ComparisonOperator.BETWEEN, value = values))
        }

        fun build(): TransactionFilter {
            return TransactionFilter(propertyFilters)
        }
    }
}

/**
 * A helper function to make it easy to specify the filter of transactions.
 *
 * @sample com.sudoplatform.sudovirtualcards.samples.Samples.transactionFilter
 */
fun filterTransactionsBy(
    init: TransactionFilter.Builder.() -> Unit = {}
) = TransactionFilter.builder()
    .apply(init)
    .build()
