/*
 * Copyright © 2023 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards.types

import java.util.Date

/**
 * Representation of a date range  used in the Sudo Platform Virtual Cards SDK.
 *
 * @property startDate [Date] The starting date of the range to query.
 * @property endDate [Date] The ending date of the range to query.
 */
data class DateRange(
    val startDate: Date,
    val endDate: Date,
)
