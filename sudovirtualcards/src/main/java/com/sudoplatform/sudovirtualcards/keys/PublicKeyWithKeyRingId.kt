/*
 * Copyright © 2022 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards.keys

import com.sudoplatform.sudouser.PublicKey

/**
 * A tuple of public key and key ring ID
 *
 * @property publicKey [PublicKey] Publc key
 * @property keyRingId [String] ID of key ring to which this public key belongs
 */
internal data class PublicKeyWithKeyRingId(
    val publicKey: PublicKey,
    val keyRingId: String,
)
