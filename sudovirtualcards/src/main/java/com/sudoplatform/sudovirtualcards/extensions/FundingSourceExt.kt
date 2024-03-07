/*
 * Copyright © 2024 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards.extensions

import com.sudoplatform.sudovirtualcards.types.FundingSource
import com.sudoplatform.sudovirtualcards.types.FundingSourceFlags

fun FundingSource.isUnfunded(): Boolean {
    return this.flags.contains(FundingSourceFlags.UNFUNDED)
}
