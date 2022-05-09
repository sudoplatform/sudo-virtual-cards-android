/*
 * Copyright © 2022 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards.keys

import com.sudoplatform.sudouser.PublicKey

/**
 * A container of public keys.
 *
 * @property id [String] Identifier of the key ring
 * @property keys [List<PublicKeys>] The public keys contained in the key ring
 * @property nextToken [String] The next token to call for the next page of paginated public keys.
 */
internal data class KeyRing(
    val id: String,
    val keys: List<PublicKey> = emptyList(),
    val nextToken: String?
)
