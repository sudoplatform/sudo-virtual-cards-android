/*
 * Copyright © 2024 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards.types

/**
 * Representation of an enumeration depicting a list of supported key encryption
 * algorithms.
 */
enum class SymmetricKeyEncryptionAlgorithm(private val algorithmName: String) {
    AES_CBC_PKCS7PADDING("AES/CBC/PKCS7Padding"),
    ;

    companion object {
        fun isAlgorithmSupported(algorithm: String): Boolean {
            return values().any { it.algorithmName == algorithm }
        }
    }

    override fun toString(): String {
        when (this) {
            AES_CBC_PKCS7PADDING -> return this.algorithmName
        }
    }
}
