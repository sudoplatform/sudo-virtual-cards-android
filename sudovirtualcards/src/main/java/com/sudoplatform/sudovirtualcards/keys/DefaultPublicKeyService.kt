/*
 * Copyright © 2022 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards.keys

import com.amazonaws.mobileconnectors.appsync.AWSAppSyncClient
import com.amazonaws.util.Base64
import com.apollographql.apollo.exception.ApolloException
import com.sudoplatform.sudologging.AndroidUtilsLogDriver
import com.sudoplatform.sudologging.LogLevel
import com.sudoplatform.sudologging.Logger
import com.sudoplatform.sudouser.PublicKey
import com.sudoplatform.sudouser.SudoUserClient
import com.sudoplatform.sudovirtualcards.extensions.enqueue
import com.sudoplatform.sudovirtualcards.extensions.enqueueFirst
import com.sudoplatform.sudovirtualcards.graphql.CreatePublicKeyMutation
import com.sudoplatform.sudovirtualcards.graphql.GetPublicKeyQuery
import com.sudoplatform.sudovirtualcards.graphql.type.CreatePublicKeyInput
import com.sudoplatform.sudovirtualcards.logging.LogConstants
import com.sudoplatform.sudovirtualcards.types.CachePolicy
import com.sudoplatform.sudovirtualcards.types.transformers.KeyTransformer
import java.util.concurrent.CancellationException

private const val UNEXPECTED_EXCEPTION = "Unexpected exception"

/**
 * The default implementation of the [PublicKeyService].
 */
internal class DefaultPublicKeyService(
    private val keyRingServiceName: String,
    private val deviceKeyManager: DeviceKeyManager,
    private val userClient: SudoUserClient,
    private val appSyncClient: AWSAppSyncClient,
    private val logger: Logger = Logger(LogConstants.SUDOLOG_TAG, AndroidUtilsLogDriver(LogLevel.INFO))
) : PublicKeyService {

    companion object {
        /** Algorithm used when creating/registering public keys. */
        const val DEFAULT_ALGORITHM = "RSAEncryptionOAEPAESCBC"
    }

    /**
     * Get the current public key of the current key pair, if any.
     *
     * @return The current key pair's public key or null if there is no
     * current key pair.
     */
    override fun getCurrentKey(): PublicKey? {
        try {
            val currentDeviceKeyPair = deviceKeyManager.getCurrentKey() ?: return null

            return PublicKey(
                keyId = currentDeviceKeyPair.keyId,
                publicKey = currentDeviceKeyPair.publicKey,
            )
        } catch (e: Throwable) {
            logger.error("unexpected error $e")
            when (e) {
                is CancellationException,
                is PublicKeyService.PublicKeyServiceException -> throw e
                is DeviceKeyManager.DeviceKeyManagerException.KeyGenerationException ->
                    throw PublicKeyService.PublicKeyServiceException.KeyCreateException("Failed to generate key", e)
                else -> throw PublicKeyService.PublicKeyServiceException.UnknownException(UNEXPECTED_EXCEPTION, e)
            }
        }
    }

    /**
     * Return the current key pair's public key with key ring ID.
     *
     * A new key pair will be created if one does not already exist.
     *
     * An existing but unregistered current key pair will have its public
     * key registered with the service using the default key ring ID.
     *
     * The key ring ID of the key is cached.
     *
     * @returns Current public key with key ring ID
     */
    override suspend fun getCurrentRegisteredKey(): PublicKeyWithKeyRingId {
        val userId = userClient.getSubject()
            ?: throw PublicKeyService.PublicKeyServiceException.UserIdNotFoundException("UserId not found")
        var keyRingId = "$keyRingServiceName.$userId"
        var created = false
        try {
            var currentDeviceKeyPair = deviceKeyManager.getCurrentKey()
            if (currentDeviceKeyPair == null) {
                currentDeviceKeyPair = deviceKeyManager.generateNewCurrentKeyPair()
                created = true
                create(
                    keyId = currentDeviceKeyPair.keyId,
                    keyRingId = keyRingId,
                    publicKey = currentDeviceKeyPair.publicKey
                )
            } else {
                val registeredKey = get(currentDeviceKeyPair.keyId, CachePolicy.REMOTE_ONLY)
                if (registeredKey != null) {
                    keyRingId = registeredKey.keyRingId
                } else {
                    create(
                        keyId = currentDeviceKeyPair.keyId,
                        keyRingId = keyRingId,
                        publicKey = currentDeviceKeyPair.publicKey
                    )
                }
            }

            return PublicKeyWithKeyRingId(
                publicKey = PublicKey(
                    keyId = currentDeviceKeyPair.keyId,
                    publicKey = currentDeviceKeyPair.publicKey,
                ),
                keyRingId = keyRingId,
                created = created
            )
        } catch (e: Throwable) {
            logger.debug("unexpected error $e")
            when (e) {
                is CancellationException,
                is PublicKeyService.PublicKeyServiceException -> throw e
                is DeviceKeyManager.DeviceKeyManagerException.KeyGenerationException ->
                    throw PublicKeyService.PublicKeyServiceException.KeyCreateException("Failed to generate key", e)
                else -> throw PublicKeyService.PublicKeyServiceException.UnknownException(UNEXPECTED_EXCEPTION, e)
            }
        }
    }

    override suspend fun get(id: String, cachePolicy: CachePolicy): PublicKeyWithKeyRingId? {
        try {
            val query = GetPublicKeyQuery.builder()
                .keyId(id)
                .build()

            val queryResponse = appSyncClient.query(query)
                .responseFetcher(cachePolicy.toResponseFetcher(cachePolicy))
                .enqueueFirst()

            if (queryResponse.hasErrors()) {
                logger.warning("errors = ${queryResponse.errors()}")
                return null
            }

            val result = queryResponse.data()?.publicKeyForVirtualCards
                ?: return null
            return KeyTransformer.toPublicKeyWithKeyRingId(result)
        } catch (e: Throwable) {
            logger.debug("unexpected error $e")
            when (e) {
                is CancellationException,
                is PublicKeyService.PublicKeyServiceException -> throw e
                is ApolloException -> throw PublicKeyService.PublicKeyServiceException.FailedException(cause = e)
                else -> throw PublicKeyService.PublicKeyServiceException.UnknownException(UNEXPECTED_EXCEPTION, e)
            }
        }
    }

    override suspend fun create(keyId: String, keyRingId: String, publicKey: ByteArray): PublicKeyWithKeyRingId {

        try {
            val mutationInput = CreatePublicKeyInput.builder()
                .publicKey(String(Base64.encode(publicKey), Charsets.UTF_8))
                .algorithm(DEFAULT_ALGORITHM)
                .keyId(keyId)
                .keyRingId(keyRingId)
                .build()
            val mutation = CreatePublicKeyMutation.builder()
                .input(mutationInput)
                .build()

            val createResponse = appSyncClient.mutate(mutation)
                .enqueue()

            if (createResponse.hasErrors()) {
                logger.debug("errors = ${createResponse.errors()}")
                throw createResponse.errors().first().toCreateFailed()
            }

            logger.debug("succeeded")
            val createResult = createResponse.data()?.createPublicKeyForVirtualCards()
                ?: throw PublicKeyService.PublicKeyServiceException.FailedException("create key failed - no response")
            return KeyTransformer.toPublicKeyWithKeyRingId(createResult)
        } catch (e: Throwable) {
            logger.debug("unexpected error $e")
            when (e) {
                is CancellationException,
                is PublicKeyService.PublicKeyServiceException -> throw e
                is ApolloException -> throw PublicKeyService.PublicKeyServiceException.FailedException(cause = e)
                else -> throw PublicKeyService.PublicKeyServiceException.UnknownException(UNEXPECTED_EXCEPTION, e)
            }
        }
    }

    private fun com.apollographql.apollo.api.Error.toCreateFailed(): PublicKeyService.PublicKeyServiceException {
        return PublicKeyService.PublicKeyServiceException.KeyCreateException(this.message())
    }
}
