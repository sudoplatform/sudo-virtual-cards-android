/*
 * Copyright © 2024 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards.types

import com.sudoplatform.sudovirtualcards.graphql.type.SortOrder as SortOrderInput

/**
 * An enumeration depicting sort order in the Sudo Platform Virtual Cards SDK.
 *
 * @enum SortOrder
 */
enum class SortOrder {
    /**
     * Sort the list of results in ascending order.
     */
    ASC,

    /**
     * Sort the list of results in descending order.
     */
    DESC,

    ;

    fun toSortOrderInput(sortOrder: SortOrder): SortOrderInput {
        return when (sortOrder) {
            ASC -> {
                SortOrderInput.ASC
            }
            DESC -> {
                SortOrderInput.DESC
            }
        }
    }
}
