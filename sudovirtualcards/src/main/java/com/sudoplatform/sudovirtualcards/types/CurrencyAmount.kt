/*
 * Copyright © 2023 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards.types

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * The representation of the currency and amount used in the Sudo Platform Virtual Cards SDK.
 *
 * @property currency [String] The ISO 4217 currency code.
 * @property amount [Int] The amount of a currency expressed in the currencies minor units, e.g. cents for USD.
 */
@Parcelize
data class CurrencyAmount(
    val currency: String,
    val amount: Int
) : Parcelable
