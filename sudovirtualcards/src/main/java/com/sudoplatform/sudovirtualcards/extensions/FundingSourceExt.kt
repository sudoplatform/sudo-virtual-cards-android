/*
 * Copyright © 2024 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards.extensions

import com.sudoplatform.sudovirtualcards.types.FundingSource
import com.sudoplatform.sudovirtualcards.types.FundingSourceFlags

fun FundingSource.isUnfunded(): Boolean = this.flags.contains(FundingSourceFlags.UNFUNDED)

fun FundingSource.needsRefresh(): Boolean = this.flags.contains(FundingSourceFlags.REFRESH)
