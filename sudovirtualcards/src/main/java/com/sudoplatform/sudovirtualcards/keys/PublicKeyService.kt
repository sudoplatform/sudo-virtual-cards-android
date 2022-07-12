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
        class UserIdNotFoundException(message: String? = null, cause: Throwable? = null) :
            PublicKeyServiceException(message = message, cause = cause)
        class UnknownException(message: String? = null, cause: Throwable? = null) :
            PublicKeyServiceException(message = message, cause = cause)
    }

    /**
     * Get the current public key of the current key pair, if any.
     *
     * @return The current key pair's public key or null if there is no
     * current key pair.
     */
    @Throws(PublicKeyServiceException::class)
    fun getCurrentKey(): PublicKey?

    /**
     * Return the current key pair's public key with key ring ID.
     *
     * A new key pair will be created if one does not already exist.
     *
     * An existing but unregistered current key pair will have its public
     * key registered with the service using the default key ring ID.
     *
     * @returns Current key pair's public key with key ring ID
     */
    @Throws(PublicKeyServiceException::class)
    suspend fun getCurrentRegisteredKey(): PublicKeyWithKeyRingId

    /**
     * Get the [PublicKey] by ID
     *
     * @param id The key identifier
     * @param cachePolicy Controls if the results come from cache or server
     */
    @Throws(PublicKeyServiceException::class)
    suspend fun get(id: String, cachePolicy: CachePolicy): PublicKeyWithKeyRingId?

    /**
     * Create/Register a new public key. Although a key pair is passed in, only the public key is
     * sent external to the device. **Private keys remain on the device only**.
     *
     * @param keyId [String] The identifier of the key.
     * @param keyRingId [String] The identifier of the key ring that contains the keys.
     * @param publicKey [ByteArray] Bytes of the public key (PEM format) to register/create.
     */
    @Throws(PublicKeyServiceException::class)
    suspend fun create(keyId: String, keyRingId: String, publicKey: ByteArray): PublicKeyWithKeyRingId
}
