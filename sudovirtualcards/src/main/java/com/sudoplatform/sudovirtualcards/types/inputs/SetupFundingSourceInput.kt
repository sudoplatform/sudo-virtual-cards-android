/*
 * Copyright © 2022 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards.types.inputs

import com.sudoplatform.sudovirtualcards.graphql.type.FundingSourceType as FundingSourceTypeInput

/**
 * Input object containing the information required to setup a funding source.
 *
 * @property currency [String] The ISO 4217 currency code that is being used for the setup.
 * @property type [FundingSourceType] The type of funding source being setup.
 */
data class SetupFundingSourceInput(
    val currency: String,
    val type: FundingSourceType
)

/**
 * Representation of an enumeration depicting the funding source type in the
 * Sudo Platform Virtual Cards SDK.
 */
enum class FundingSourceType {
    CREDIT_CARD;

    fun toFundingSourceTypeInput(fundingSourceType: FundingSourceType): FundingSourceTypeInput {
        return when (fundingSourceType) {
            CREDIT_CARD -> {
                FundingSourceTypeInput.CREDIT_CARD
            }
        }
    }
}
