/*
 * Copyright © 2020 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards.types

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * This represents the relationship of a unique identifier [id] with the [issuer] to a [Card]
 *
 * @property id Unique Identifier of the owner.
 * @property issuer Issuer of the owner identifier.
 *
 * @since 2020-06-11
 */
@Parcelize
data class Owner(
    val id: String,
    val issuer: String
) : Parcelable
