/*
 * Copyright © 2020 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards.types

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * A currency used in the virtual cards system.
 *
 * @property currency The ISO 4217 currency code
 * @property amount The amount of a currency expressed in the currencies minor units, e.g. cents for USD
 *
 * @since 2020-06-16
 */
@Parcelize
data class CurrencyAmount(
    val currency: String,
    val amount: Int
) : Parcelable
