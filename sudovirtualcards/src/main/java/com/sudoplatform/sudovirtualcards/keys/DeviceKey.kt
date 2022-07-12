/*
 * Copyright © 2022 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards.keys

/**
 * Key Pair created and/or retrieved from a [DeviceKeyManager].
 *
 * @property keyId Unique identifier of the key pair.
 * @property publicKey Bytes of the public key (PEM format)
 */
internal data class DeviceKey(
    val keyId: String,
    val publicKey: ByteArray,
) {
    override fun toString(): String {
        val clz = this@DeviceKey.javaClass.simpleName
        return "$clz[keyId=$keyId publicKey.size=${publicKey.size}]"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as DeviceKey

        if (keyId != other.keyId) return false
        if (!publicKey.contentEquals(other.publicKey)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = keyId.hashCode()
        result = 31 * result + publicKey.contentHashCode()
        return result
    }
}
