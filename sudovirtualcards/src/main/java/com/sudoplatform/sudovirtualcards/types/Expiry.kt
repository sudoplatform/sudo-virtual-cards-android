/*
 * Copyright © 2024 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards.types

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * The representation of expiry information for a virtual card in the
 * Sudo Platform Virtual Cards SDK.
 *
 * @property mm [String] Month in the format MM - e.g. 12.
 * @property yyyy [String] Year in the format YYYY - e.g. 2020.
 */
@Parcelize
data class Expiry(
    val mm: String,
    val yyyy: String,
) : Parcelable
