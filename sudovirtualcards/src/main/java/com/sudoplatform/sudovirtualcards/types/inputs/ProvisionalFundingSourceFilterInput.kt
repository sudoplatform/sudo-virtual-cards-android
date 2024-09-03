/*
 * Copyright © 2024 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards.types.inputs

import com.sudoplatform.sudovirtualcards.types.ProvisionalFundingSource

/**
 * Input object containing the information required to filter by provisional funding source state.
 */
data class ProvisionalFundingSourceStateFilterInput(
    val eq: ProvisionalFundingSource.ProvisioningState? = null,
    val ne: ProvisionalFundingSource.ProvisioningState? = null,
)

/**
 * Input object containing the information required to filter a provisional funding source list.
 */
data class ProvisionalFundingSourceFilterInput(
    val id: IdFilterInput? = null,
    val state: ProvisionalFundingSourceStateFilterInput? = null,
    val and: ArrayList<ProvisionalFundingSourceFilterInput>? = null,
    val or: ArrayList<ProvisionalFundingSourceFilterInput>? = null,
    val not: ProvisionalFundingSourceFilterInput? = null,
)
