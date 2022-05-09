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
import com.sudoplatform.sudovirtualcards.extensions.enqueue
import com.sudoplatform.sudovirtualcards.extensions.enqueueFirst
import com.sudoplatform.sudovirtualcards.graphql.CreatePublicKeyForVirtualCardsMutation
import com.sudoplatform.sudovirtualcards.graphql.GetKeyRingForVirtualCardsQuery
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
    private val deviceKeyManager: DeviceKeyManager,
    private val appSyncClient: AWSAppSyncClient,
    private val logger: Logger = Logger(LogConstants.SUDOLOG_TAG, AndroidUtilsLogDriver(LogLevel.INFO))
) : PublicKeyService {

    companion object {
        /** Algorithm used when creating/registering public keys. */
        const val DEFAULT_ALGORITHM = "RSAEncryptionOAEPAESCBC"
    }

    override suspend fun getCurrentKeyPair(missingKeyPolicy: PublicKeyService.MissingKeyPolicy): KeyPair? {
        try {
            val currentKeyPair = deviceKeyManager.getCurrentKeyPair()
            if (currentKeyPair == null && missingKeyPolicy == PublicKeyService.MissingKeyPolicy.GENERATE_IF_MISSING) {
                return deviceKeyManager.generateNewCurrentKeyPair()
            }
            return currentKeyPair
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

    override suspend fun getKeyRing(id: String, cachePolicy: CachePolicy): KeyRing? {
        try {
            val query = GetKeyRingForVirtualCardsQuery.builder()
                .keyRingId(id)
                .build()

            val queryResponse = appSyncClient.query(query)
                .responseFetcher(cachePolicy.toResponseFetcher(cachePolicy))
                .enqueueFirst()

            if (queryResponse.hasErrors()) {
                logger.warning("errors = ${queryResponse.errors()}")
                return null
            }

            val result = queryResponse.data()?.keyRingForVirtualCards
                ?: return null
            return KeyTransformer.toKeyRing(result)
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

    override suspend fun create(keyId: String, keyRingId: String, publicKey: ByteArray): PublicKey {

        try {
            val mutationInput = CreatePublicKeyInput.builder()
                .publicKey(String(Base64.encode(publicKey), Charsets.UTF_8))
                .algorithm(DEFAULT_ALGORITHM)
                .keyId(keyId)
                .keyRingId(keyRingId)
                .build()
            val mutation = CreatePublicKeyForVirtualCardsMutation.builder()
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
            return KeyTransformer.toPublicKey(createResult)
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
