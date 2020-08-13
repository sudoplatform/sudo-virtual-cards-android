/*
 * Copyright © 2020 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards.keys

import com.sudoplatform.sudouser.PublicKey

/**
 * A container of public keys.
 *
 * @property id Identifier of the key ring
 * @property keys The public keys contained in the key ring
 *
 * @since 2020-06-18
 */
internal data class KeyRing(
    val id: String,
    val keys: List<PublicKey> = emptyList()
)
