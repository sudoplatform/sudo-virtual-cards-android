/*
 * Copyright © 2022 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards.keys

import com.sudoplatform.sudouser.PublicKey
import com.sudoplatform.sudovirtualcards.types.CachePolicy
import java.lang.RuntimeException

/**
 * Responsible for managing the storage and lifecycle of key pairs locally and remotely in the virtual cards service.
 */
internal interface PublicKeyService {

    enum class MissingKeyPolicy {
        GENERATE_IF_MISSING,
        DO_NOT_GENERATE
    }

    /**
     * Defines the exceptions for the [PublicKeyService] methods.
     *
     * @property message Accompanying message for the exception.
     * @property cause The cause for the exception.
     */
    sealed class PublicKeyServiceException(message: String? = null, cause: Throwable? = null) : RuntimeException(message, cause) {
        class KeyCreateException(message: String? = null, cause: Throwable? = null) :
            PublicKeyServiceException(message = message, cause = cause)
        class FailedException(message: String? = null, cause: Throwable? = null) :
            PublicKeyServiceException(message = message, cause = cause)
        class UnknownException(message: String? = null, cause: Throwable? = null) :
            PublicKeyServiceException(message = message, cause = cause)
    }

    /**
     * Get the current key pair. Optionally generate a new key pair if one does not exist.
     *
     * @param missingKeyPolicy Controls if the key pair is generated if it is absent.
     * @return The current key pair or null if they are missing and [missingKeyPolicy] is set to [MissingKeyPolicy.DO_NOT_GENERATE]
     */
    @Throws(PublicKeyServiceException::class)
    suspend fun getCurrentKeyPair(missingKeyPolicy: MissingKeyPolicy = MissingKeyPolicy.DO_NOT_GENERATE): KeyPair?

    /**
     * Get the [KeyRing] by ID
     *
     * @param id The key ring identifier
     * @param cachePolicy Controls if the results come from cache or server
     */
    @Throws(PublicKeyServiceException::class)
    suspend fun getKeyRing(id: String, cachePolicy: CachePolicy): KeyRing?

    /**
     * Create/Register a new public key. Although a key pair is passed in, only the public key is
     * sent external to the device. **Private keys remain on the device only**.
     *
     * @param keyId [String] The identifier of the key.
     * @param keyRingId [String] The identifier of the key ring that contains the keys.
     * @param publicKey [ByteArray] Bytes of the public key (PEM format) to register/create.
     */
    @Throws(PublicKeyServiceException::class)
    suspend fun create(keyId: String, keyRingId: String, publicKey: ByteArray): PublicKey
}
