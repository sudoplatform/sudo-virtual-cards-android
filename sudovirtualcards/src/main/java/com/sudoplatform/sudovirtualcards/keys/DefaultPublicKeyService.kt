/*
 * Copyright © 2024 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards.keys

import com.amazonaws.util.Base64
import com.amplifyframework.api.graphql.GraphQLResponse
import com.apollographql.apollo.api.Optional
import com.sudoplatform.sudologging.AndroidUtilsLogDriver
import com.sudoplatform.sudologging.LogLevel
import com.sudoplatform.sudologging.Logger
import com.sudoplatform.sudouser.PublicKey
import com.sudoplatform.sudouser.SudoUserClient
import com.sudoplatform.sudouser.amplify.GraphQLClient
import com.sudoplatform.sudouser.exceptions.HTTP_STATUS_CODE_KEY
import com.sudoplatform.sudovirtualcards.graphql.CreatePublicKeyMutation
import com.sudoplatform.sudovirtualcards.graphql.GetPublicKeyQuery
import com.sudoplatform.sudovirtualcards.graphql.type.CreatePublicKeyInput
import com.sudoplatform.sudovirtualcards.logging.LogConstants
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
    private val graphQLClient: GraphQLClient,
    private val logger: Logger = Logger(LogConstants.SUDOLOG_TAG, AndroidUtilsLogDriver(LogLevel.INFO)),
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
                is PublicKeyService.PublicKeyServiceException,
                -> throw e
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
        val userId =
            userClient.getSubject()
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
                    publicKey = currentDeviceKeyPair.publicKey,
                )
            } else {
                val registeredKey = get(currentDeviceKeyPair.keyId)
                if (registeredKey != null) {
                    keyRingId = registeredKey.keyRingId
                } else {
                    create(
                        keyId = currentDeviceKeyPair.keyId,
                        keyRingId = keyRingId,
                        publicKey = currentDeviceKeyPair.publicKey,
                    )
                }
            }

            return PublicKeyWithKeyRingId(
                publicKey =
                    PublicKey(
                        keyId = currentDeviceKeyPair.keyId,
                        publicKey = currentDeviceKeyPair.publicKey,
                    ),
                keyRingId = keyRingId,
                created = created,
            )
        } catch (e: Throwable) {
            logger.debug("unexpected error $e")
            when (e) {
                is CancellationException,
                is PublicKeyService.PublicKeyServiceException,
                -> throw e
                is DeviceKeyManager.DeviceKeyManagerException.KeyGenerationException ->
                    throw PublicKeyService.PublicKeyServiceException.KeyCreateException("Failed to generate key", e)
                else -> throw PublicKeyService.PublicKeyServiceException.UnknownException(UNEXPECTED_EXCEPTION, e)
            }
        }
    }

    override suspend fun get(id: String): PublicKeyWithKeyRingId? {
        try {
            val queryResponse =
                graphQLClient.query<GetPublicKeyQuery, GetPublicKeyQuery.Data>(
                    GetPublicKeyQuery.OPERATION_DOCUMENT,
                    mapOf("keyId" to id),
                )

            if (queryResponse.hasErrors()) {
                logger.warning("errors = ${queryResponse.errors}")
                return null
            }

            val result =
                queryResponse.data?.getPublicKeyForVirtualCards
                    ?: return null
            return KeyTransformer.toPublicKeyWithKeyRingId(result)
        } catch (e: Throwable) {
            logger.debug("unexpected error $e")
            when (e) {
                is CancellationException,
                is PublicKeyService.PublicKeyServiceException,
                -> throw e
                else -> throw PublicKeyService.PublicKeyServiceException.UnknownException(UNEXPECTED_EXCEPTION, e)
            }
        }
    }

    override suspend fun create(
        keyId: String,
        keyRingId: String,
        publicKey: ByteArray,
    ): PublicKeyWithKeyRingId {
        try {
            val mutationInput =
                CreatePublicKeyInput(
                    algorithm = DEFAULT_ALGORITHM,
                    keyFormat = Optional.Absent,
                    keyId = keyId,
                    keyRingId = keyRingId,
                    publicKey = String(Base64.encode(publicKey), Charsets.UTF_8),
                )

            val createResponse =
                graphQLClient.mutate<CreatePublicKeyMutation, CreatePublicKeyMutation.Data>(
                    CreatePublicKeyMutation.OPERATION_DOCUMENT,
                    mapOf("input" to mutationInput),
                )

            if (createResponse.hasErrors()) {
                logger.debug("errors = ${createResponse.errors}")
                throw createResponse.errors.first().toCreateFailed()
            }

            logger.debug("succeeded")
            val createResult =
                createResponse.data?.createPublicKeyForVirtualCards
                    ?: throw PublicKeyService.PublicKeyServiceException.FailedException("create key failed - no response")
            return KeyTransformer.toPublicKeyWithKeyRingId(createResult)
        } catch (e: Throwable) {
            logger.debug("unexpected error $e")
            when (e) {
                is CancellationException,
                is PublicKeyService.PublicKeyServiceException,
                -> throw e
                else -> throw PublicKeyService.PublicKeyServiceException.UnknownException(UNEXPECTED_EXCEPTION, e)
            }
        }
    }

    private fun GraphQLResponse.Error.toCreateFailed(): PublicKeyService.PublicKeyServiceException {
        val httpStatusCode = this.extensions?.get(HTTP_STATUS_CODE_KEY) as Int?
        if (httpStatusCode != null) {
            return PublicKeyService.PublicKeyServiceException.FailedException(this.message)
        }
        return PublicKeyService.PublicKeyServiceException.KeyCreateException(this.message)
    }
}
