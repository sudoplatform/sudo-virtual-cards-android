/*
 * Copyright © 2023 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards.signing

import java.util.Date

/**
 * Representation of a signature and associated signed data.
 *
 * @property data [String] Data that is signed.
 * @property algorithm [String] Algorithm used to sign the data.
 * @property keyId [String] Identifier of the key used to sign the data.
 * @property signature [String] Signature of the data.
 */
data class Signature(
    val data: String,
    val algorithm: String,
    val keyId: String,
    val signature: String
)

/**
 * Representation of the data used to form the signature after signing.
 *
 * @property hash [String] Hash of the data.
 * @property hashAlgorithm [String] Hash algorithm used to hash the data.
 * @property signedAt [Date] The date in which the signing has occurred.
 * @property blob [String] Blob of data to be signed.
 */
internal data class SignatureData(
    val hash: String,
    val hashAlgorithm: String,
    val signedAt: Date = Date(),
    val blob: String,
)
