/*
 * Copyright © 2024 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards.types

import com.sudoplatform.sudovirtualcards.graphql.type.FundingSourceType as FundingSourceTypeInput

/**
 * Representation of an enumeration depicting the funding source type in the
 * Sudo Platform Virtual Cards SDK.
 */
enum class FundingSourceType {
    CREDIT_CARD,
    BANK_ACCOUNT,
    ;

    fun toFundingSourceTypeInput(fundingSourceType: FundingSourceType): FundingSourceTypeInput =
        when (fundingSourceType) {
            CREDIT_CARD -> {
                FundingSourceTypeInput.CREDIT_CARD
            }
            BANK_ACCOUNT -> {
                FundingSourceTypeInput.BANK_ACCOUNT
            }
        }
}
