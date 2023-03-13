/*
 * Copyright © 2023 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards.types.transformers

import com.sudoplatform.sudovirtualcards.graphql.type.DateRangeInput
import com.sudoplatform.sudovirtualcards.types.DateRange

/**
 * Transformer responsible for transforming the [DateRange] GraphQL data types to
 * the entity type that is exposed to users.
 */
internal object DateRangeTransformer {

    /**
     * Transform the input type [DateRange] into the corresponding GraphQL type [DateRangeInput].
     */
    fun DateRange?.toDateRangeInput(): DateRangeInput? {
        if (this == null) {
            return null
        }
        return DateRangeInput
            .builder()
            .endDateEpochMs(endDate.time.toDouble())
            .startDateEpochMs(startDate.time.toDouble())
            .build()
    }
}
