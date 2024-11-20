/*
 * Copyright © 2024 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards.types.inputs

import com.sudoplatform.sudovirtualcards.types.FundingSourceState

/**
 * Input object containing the information required to filter by funding source state.
 */
data class FundingSourceStateFilterInput(
    val eq: FundingSourceState? = null,
    val ne: FundingSourceState? = null,
)

/**
 * Input object containing the information required to filter a funding source list.
 */
data class FundingSourceFilterInput(
    val id: IdFilterInput? = null,
    val state: FundingSourceStateFilterInput? = null,
    val and: ArrayList<FundingSourceFilterInput>? = null,
    val or: ArrayList<FundingSourceFilterInput>? = null,
    val not: FundingSourceFilterInput? = null,
)
