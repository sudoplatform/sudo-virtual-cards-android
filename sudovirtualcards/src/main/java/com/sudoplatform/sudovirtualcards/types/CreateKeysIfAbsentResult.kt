/*
 * Copyright © 2022 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards.types

/**
 * The result containing information about whether the key was created and its identifier.
 *
 * @param created [Boolean] Whether or not key needed to be created.
 * @param keyId [String] Identifier of the key.
 */
data class KeyResult(
    val created: Boolean,
    val keyId: String
)

/**
 * The result of the create keys if absent API.
 *
 * @param symmetricKey [KeyResult] Result of the create keys if absent operation for
 *  the symmetric key.
 * @param keyPair [KeyResult] Result of the create keys if absent operation for the
 *  key pair.
 */
data class CreateKeysIfAbsentResult(
    val symmetricKey: KeyResult,
    val keyPair: KeyResult
)
