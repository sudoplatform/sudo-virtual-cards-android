/*
 * Copyright © 2022 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards.keys

import com.sudoplatform.sudokeymanager.KeyManagerException
import com.sudoplatform.sudokeymanager.KeyManagerInterface
import com.sudoplatform.sudologging.AndroidUtilsLogDriver
import com.sudoplatform.sudologging.LogLevel
import com.sudoplatform.sudologging.Logger
import com.sudoplatform.sudouser.SudoUserClient
import com.sudoplatform.sudovirtualcards.logging.LogConstants
import java.util.UUID

/**
 * Responsible for Managing the lifecycle of key pairs associated with the virtual cards service.
 */
internal class DefaultDeviceKeyManager(
    private val keyRingServiceName: String,
    private val userClient: SudoUserClient,
    private val keyManager: KeyManagerInterface,
    private val logger: Logger = Logger(LogConstants.SUDOLOG_TAG, AndroidUtilsLogDriver(LogLevel.INFO))
) : DeviceKeyManager {

    companion object {
        private const val CURRENT_KEY_ID_NAME = "current"
        private const val SECRET_KEY_ID_NAME = "vc-secret-key"
    }

    /**
     * Returns the key ring id associated with the owner's service.
     *
     * @return The identifier of the key ring associated with the owner's service
     * @throws [DeviceKeyManager.DeviceKeyManagerException.UserIdNotFoundException] if the user Id cannot be found.
     */
    @Throws(DeviceKeyManager.DeviceKeyManagerException.UserIdNotFoundException::class)
    override fun getKeyRingId(): String {
        try {
            val userId = userClient.getSubject()
                ?: throw DeviceKeyManager.DeviceKeyManagerException.UserIdNotFoundException("UserId not found")
            return "$keyRingServiceName.$userId"
        } catch (e: Exception) {
            throw DeviceKeyManager.DeviceKeyManagerException.UserIdNotFoundException("UserId could not be accessed", e)
        }
    }

    /**
     * Returns the key pair that is currently being used by this service.
     * If no key pair has been previously generated, will return null and require the caller
     * to call [generateNewCurrentKeyPair] if a current key pair is required.
     *
     * @return The current key pair in use or null.
     */
    override fun getCurrentKeyPair(): KeyPair? {
        try {
            val currentKeyData = keyManager.getPassword(CURRENT_KEY_ID_NAME)
                ?: return null
            val currentKeyId = currentKeyData.toString(Charsets.UTF_8)
            val publicKey = keyManager.getPublicKeyData(currentKeyId)
                ?: return null
            val privateKey = keyManager.getPrivateKeyData(currentKeyId)
                ?: return null
            return KeyPair(
                keyId = currentKeyId,
                keyRingId = getKeyRingId(),
                publicKey = publicKey,
                privateKey = privateKey
            )
        } catch (e: KeyManagerException) {
            throw DeviceKeyManager.DeviceKeyManagerException.KeyOperationFailedException("KeyManager exception", e)
        }
    }

    /**
     * Returns the [KeyPair] with the identifier [id] if it exists.
     *
     * @return The [KeyPair] with the identifier [id] if it exists, null if it does not.
     */
    override fun getKeyPairWithId(id: String): KeyPair? {
        try {
            val publicKey = keyManager.getPublicKeyData(id)
                ?: return null
            val privateKey = keyManager.getPrivateKeyData(id)
                ?: return null
            return KeyPair(
                keyId = id,
                keyRingId = getKeyRingId(),
                publicKey = publicKey,
                privateKey = privateKey
            )
        } catch (e: KeyManagerException) {
            throw DeviceKeyManager.DeviceKeyManagerException.KeyOperationFailedException("KeyManager exception", e)
        }
    }

    /**
     * Generate a new [KeyPair] and make it the current [KeyPair].
     *
     * @return The generated [KeyPair]
     * @throws [DeviceKeyManager.DeviceKeyManagerException.KeyGenerationException] if unable to generate the [KeyPair]
     */
    @Throws(DeviceKeyManager.DeviceKeyManagerException::class)
    override fun generateNewCurrentKeyPair(): KeyPair {

        val keyId = UUID.randomUUID().toString()
        try {
            // Replace the old current key identifier with a new one
            keyManager.deletePassword(CURRENT_KEY_ID_NAME)
            keyManager.addPassword(keyId.toByteArray(), CURRENT_KEY_ID_NAME, true)

            // Generate the key pair for the new current key
            keyManager.generateKeyPair(keyId, true)

            val publicKey = keyManager.getPublicKeyData(keyId)
            val privateKey = keyManager.getPrivateKeyData(keyId)
            return KeyPair(
                keyId = keyId,
                keyRingId = getKeyRingId(),
                publicKey = publicKey,
                privateKey = privateKey
            )
        } catch (e: Exception) {
            logger.error("error $e")
            try {
                keyManager.deleteKeyPair(keyId)
                keyManager.deletePassword(CURRENT_KEY_ID_NAME)
            } catch (e: Throwable) {
                // Suppress any other failures while cleaning up
            }
            throw DeviceKeyManager.DeviceKeyManagerException.KeyGenerationException("Failed to generate key pair", e)
        }
    }

    /**
     * Generate a new symmetric key.
     *
     * @return The generated symmetric key identifier.
     * @throws [DeviceKeyManager.DeviceKeyManagerException.KeyGenerationException] if unable to generate the symmetric key.
     */
    @Throws(DeviceKeyManager.DeviceKeyManagerException::class)
    override fun generateNewCurrentSymmetricKey(): String {
        val keyId = UUID.randomUUID().toString()
        try {
            // Replace the old current key identifier with a new one
            keyManager.deletePassword(SECRET_KEY_ID_NAME)
            keyManager.addPassword(keyId.toByteArray(), SECRET_KEY_ID_NAME)

            // Generate the key pair for the new symmetric key
            keyManager.generateSymmetricKey(keyId)
            return keyId
        } catch (e: Exception) {
            logger.error("error $e")
            throw DeviceKeyManager.DeviceKeyManagerException.KeyGenerationException("Failed to generate symmetric key", e)
        }
    }

    /**
     * Returns the symmetric key identifier that is currently being used by this service.
     * If no symmetric key has been previously generated, will return null and require the caller
     * to call [generateNewCurrentSymmetricKey] if a current symmetric key is required.
     *
     * @return The current symmetric key identifier in use or null.
     * @throws [DeviceKeyManager.DeviceKeyManagerException.KeyOperationFailedException] if key operation fails.
     */
    @Throws(DeviceKeyManager.DeviceKeyManagerException::class)
    override fun getCurrentSymmetricKeyId(): String? {
        try {
            val symmetricKeyIdBits = keyManager.getPassword(SECRET_KEY_ID_NAME) ?: return null
            val symmetricKeyId = symmetricKeyIdBits.toString(Charsets.UTF_8)
            keyManager.getSymmetricKeyData(symmetricKeyId) ?: return null
            return symmetricKeyId
        } catch (e: KeyManagerException) {
            logger.error("error $e")
            throw DeviceKeyManager.DeviceKeyManagerException.KeyOperationFailedException("KeyManager exception", e)
        }
    }

    /**
     * Decrypt the [data] with the private key [keyId] and [algorithm]
     *
     * @param data Data to be decrypted
     * @param keyId Key to use to decrypt the [data]
     * @param algorithm Algorithm to use to decrypt the [data]
     * @return The decrypted data
     * @throws [DeviceKeyManager.DeviceKeyManagerException.DecryptionException] if the data cannot be decrypted
     */
    @Throws(DeviceKeyManager.DeviceKeyManagerException::class)
    override fun decryptWithPrivateKey(
        data: ByteArray,
        keyId: String,
        algorithm: KeyManagerInterface.PublicKeyEncryptionAlgorithm
    ): ByteArray {
        try {
            return keyManager.decryptWithPrivateKey(keyId, data, algorithm)
        } catch (e: KeyManagerException) {
            logger.error("error $e")
            throw DeviceKeyManager.DeviceKeyManagerException.DecryptionException("Failed to decrypt", e)
        }
    }

    /**
     * Decrypt the [data] with the symmetric key [key]
     *
     * @param key Key to use to decrypt the [data]
     * @param data Data to be decrypted
     * @return The decrypted data
     * @throws [DeviceKeyManager.DeviceKeyManagerException.DecryptionException] if the data cannot be decrypted
     */
    @Throws(DeviceKeyManager.DeviceKeyManagerException::class)
    override fun decryptWithSymmetricKey(key: ByteArray, data: ByteArray): ByteArray {
        try {
            return keyManager.decryptWithSymmetricKey(key, data)
        } catch (e: KeyManagerException) {
            logger.error("error $e")
            throw DeviceKeyManager.DeviceKeyManagerException.DecryptionException("Failed to decrypt", e)
        }
    }

    /**
     * Decrypt the [data] with the symmetric key [keyId].
     *
     * @param keyId [String] Key identifier belonging to the symmetric key used to decrypt the [data].
     * @param data [ByteArray] Data to be decrypted.
     * @return the decrypted data.
     * @throws [DeviceKeyManager.DeviceKeyManagerException.DecryptionException] if the data cannot be decrypted.
     */
    @Throws(DeviceKeyManager.DeviceKeyManagerException::class)
    override fun decryptWithSymmetricKeyId(keyId: String, data: ByteArray): ByteArray {
        try {
            return keyManager.decryptWithSymmetricKey(keyId, data)
        } catch (e: KeyManagerException) {
            logger.error("error $e")
            throw DeviceKeyManager.DeviceKeyManagerException.DecryptionException("Failed to decrypt", e)
        }
    }

    /**
     * Encrypt the [data] with the symmetric key [keyId].
     *
     * @param keyId [String] Key identifier belonging to the symmetric key used to encrypt the [data].
     * @param data [ByteArray] Data to be encrypted.
     * @return the encrypted data.
     * @throws [DeviceKeyManager.DeviceKeyManagerException.EncryptionException] if the data cannot be encrypted.
     */
    @Throws(DeviceKeyManager.DeviceKeyManagerException::class)
    override fun encryptWithSymmetricKeyId(keyId: String, data: ByteArray): ByteArray {
        try {
            return keyManager.encryptWithSymmetricKey(keyId, data)
        } catch (e: KeyManagerException) {
            logger.error("error $e")
            throw DeviceKeyManager.DeviceKeyManagerException.EncryptionException("Failed to encrypt", e)
        }
    }

    /**
     * Remove all the keys from the [DefaultDeviceKeyManager]
     */
    @Throws(DeviceKeyManager.DeviceKeyManagerException::class)
    override fun removeAllKeys() {
        try {
            return keyManager.removeAllKeys()
        } catch (e: Exception) {
            logger.error("error $e")
            throw DeviceKeyManager.DeviceKeyManagerException.UnknownException("Failed to remove all keys", e)
        }
    }
}
