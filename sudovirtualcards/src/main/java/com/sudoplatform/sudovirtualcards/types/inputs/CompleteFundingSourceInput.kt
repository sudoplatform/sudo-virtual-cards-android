/*
 * Copyright © 2023 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards.types.inputs

import com.sudoplatform.sudovirtualcards.types.ProviderCompletionData as CompletionDataInput

/**
 * Input object containing the information required to complete the creation of a funding source.
 *
 * @property id [String] Identifier of the provisional funding source to be completed and provisioned.
 * @property completionData [CompletionDataInput] The completion data used to complete
 *  the funding source creation.
 * @property updateCardFundingSource [Boolean] Optional flag to indicate whether to update inactive card
 *  funding sources with a new funding source when a funding source is created.
 */
data class CompleteFundingSourceInput(
    val id: String,
    val completionData: CompletionDataInput,
    val updateCardFundingSource: Boolean?
)
