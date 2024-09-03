/*
 * Copyright © 2024 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards.keys

import com.sudoplatform.sudokeymanager.KeyManagerInterface

/**
 * Responsible for managing the local storage and lifecycle of key pairs associated with the virtual cards service.
 */
internal interface DeviceKeyManager {

    /**
     * Defines the exceptions for the [DeviceKeyManager] methods.
     *
     * @property message Accompanying message for the exception.
     * @property cause The cause for the exception.
     */
    sealed class DeviceKeyManagerException(message: String? = null, cause: Throwable? = null) : RuntimeException(message, cause) {
        class KeyGenerationException(message: String? = null, cause: Throwable? = null) :
            DeviceKeyManagerException(message = message, cause = cause)
        class KeyOperationFailedException(message: String? = null, cause: Throwable? = null) :
            DeviceKeyManagerException(message = message, cause = cause)
        class DecryptionException(message: String? = null, cause: Throwable? = null) :
            DeviceKeyManagerException(message = message, cause = cause)
        class EncryptionException(message: String? = null, cause: Throwable? = null) :
            DeviceKeyManagerException(message = message, cause = cause)
        class KeyRingIdUnknownException(message: String? = null, cause: Throwable? = null) :
            DeviceKeyManagerException(message = message, cause = cause)
        class SigningException(message: String? = null, cause: Throwable? = null) :
            DeviceKeyManagerException(message = message, cause = cause)
        class SecureKeyArchiveException(message: String? = null, cause: Throwable? = null) :
            DeviceKeyManagerException(message = message, cause = cause)
        class UnknownException(message: String? = null, cause: Throwable? = null) :
            DeviceKeyManagerException(message = message, cause = cause)
    }

    /**
     * Returns the public key of the key pair that is currently being used by this service.
     * If no key pair has been previously generated, will return null and require the caller
     * to call [generateNewCurrentKeyPair] if a current key pair is required.
     *
     * @return The public key of the current key pair in use or null.
     */
    @Throws(DeviceKeyManagerException::class)
    fun getCurrentKey(): DeviceKey?

    /**
     * Returns the [DeviceKey] of the key pair with the identifier [id] if it exists.
     *
     * @return The [DeviceKey] of the key pair with the identifier [id] if it exists, null if it does not.
     */
    @Throws(DeviceKeyManagerException::class)
    fun getKeyWithId(id: String): DeviceKey?

    /**
     * Generate a new key pair and make it the current. Return the public key for the new
     * key pair.
     *
     * @return The generated key pair's [DeviceKey]
     * @throws [DeviceKeyManager.DeviceKeyManagerException.KeyGenerationException] if unable to generate the [DeviceKey]
     */
    @Throws(DeviceKeyManagerException::class)
    fun generateNewCurrentKeyPair(): DeviceKey

    /**
     * Generate a new symmetric key.
     *
     * @return The generated symmetric key identifier.
     * @throws [DeviceKeyManager.DeviceKeyManagerException.KeyGenerationException] if unable to generate the symmetric key.
     */
    @Throws(DeviceKeyManagerException::class)
    fun generateNewCurrentSymmetricKey(): String

    /**
     * Returns the symmetric key identifier that is currently being used by this service.
     * If no symmetric key has been previously generated, will return null and require the caller
     * to call [generateNewCurrentSymmetricKey] if a current symmetric key is required.
     *
     * @return The current symmetric key identifier in use or null.
     * @throws [DeviceKeyManager.DeviceKeyManagerException.KeyOperationFailedException] if key operation fails.
     */
    @Throws(DeviceKeyManagerException::class)
    fun getCurrentSymmetricKeyId(): String?

    /**
     * Decrypt the [data] with the private key [keyId] and [algorithm]
     *
     * @param data Data to be decrypted
     * @param keyId Key to use to decrypt the [data]
     * @param algorithm Algorithm to use to decrypt the [data]
     * @return the decrypted data
     * @throws [DeviceKeyManager.DeviceKeyManagerException.DecryptionException] if the data cannot be decrypted
     */
    @Throws(DeviceKeyManagerException::class)
    fun decryptWithPrivateKey(data: ByteArray, keyId: String, algorithm: KeyManagerInterface.PublicKeyEncryptionAlgorithm): ByteArray

    /**
     * Decrypt the [data] with the symmetric key [key]
     *
     * @param key Key to use to decrypt the [data]
     * @param data Data to be decrypted
     * @return the decrypted data
     * @throws [DeviceKeyManager.DeviceKeyManagerException.DecryptionException] if the data cannot be decrypted
     */
    @Throws(DeviceKeyManagerException::class)
    fun decryptWithSymmetricKey(key: ByteArray, data: ByteArray): ByteArray

    /**
     * Decrypt the [data] with the symmetric key [keyId].
     *
     * @param keyId [String] Key identifier belonging to the symmetric key used to decrypt the [data].
     * @param data [ByteArray] Data to be decrypted.
     * @return the decrypted data.
     * @throws [DeviceKeyManager.DeviceKeyManagerException.DecryptionException] if the data cannot be decrypted.
     */
    @Throws(DeviceKeyManagerException::class)
    fun decryptWithSymmetricKeyId(keyId: String, data: ByteArray): ByteArray

    /**
     * Encrypt the [data] with the symmetric key [keyId].
     *
     * @param keyId [String] Key identifier belonging to the symmetric key used to encrypt the [data].
     * @param data [ByteArray] Data to be encrypted.
     * @return the encrypted data.
     * @throws [DeviceKeyManager.DeviceKeyManagerException.EncryptionException] if the data cannot be encrypted.
     */
    @Throws(DeviceKeyManagerException::class)
    fun encryptWithSymmetricKeyId(keyId: String, data: ByteArray): ByteArray

    /**
     * Sign the [data] with the private key [keyId].
     *
     * @param keyId [String] Key identifier belonging to the private key.
     * @param data [ByteArray] Data to be signed.
     * @return the signed data.
     * @throws [DeviceKeyManager.DeviceKeyManagerException.SigningException] if the data cannot be signed.
     */
    @Throws(DeviceKeyManagerException::class)
    fun signWithPrivateKeyId(keyId: String, data: ByteArray): ByteArray

    /**
     * Import keys from a key archive.
     *
     * @param archiveData [ByteArray] Key archive data to import the keys from.
     * @throws [DeviceKeyManager.DeviceKeyManagerException.SecureKeyArchiveException]
     */
    @Throws(DeviceKeyManagerException::class)
    fun importKeys(archiveData: ByteArray)

    /**
     * Export keys to a key archive.
     *
     * @return The key archive data.
     * @throws [DeviceKeyManager.DeviceKeyManagerException.SecureKeyArchiveException]
     */
    @Throws(DeviceKeyManagerException::class)
    fun exportKeys(): ByteArray

    /**
     * Remove all the keys from the [DeviceKeyManager]
     */
    @Throws(DeviceKeyManagerException::class)
    fun removeAllKeys()
}
