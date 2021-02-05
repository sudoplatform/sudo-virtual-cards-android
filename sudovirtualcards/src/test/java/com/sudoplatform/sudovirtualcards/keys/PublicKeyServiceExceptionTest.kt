/*
 * Copyright © 2020 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards.keys

import com.amazonaws.mobileconnectors.appsync.AWSAppSyncClient
import com.nhaarman.mockitokotlin2.any
import com.nhaarman.mockitokotlin2.doThrow
import com.nhaarman.mockitokotlin2.mock
import com.nhaarman.mockitokotlin2.stub
import com.sudoplatform.sudologging.Logger
import com.sudoplatform.sudovirtualcards.BaseTests
import com.sudoplatform.sudovirtualcards.graphql.CreatePublicKeyForVirtualCardsMutation
import com.sudoplatform.sudovirtualcards.graphql.GetKeyRingForVirtualCardsQuery
import com.sudoplatform.sudovirtualcards.types.CachePolicy
import io.kotlintest.shouldThrow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import timber.log.Timber
import java.lang.RuntimeException

/**
 * Test the operation of [DefaultPublicKeyService] under exceptional conditions using mocks.
 *
 * @since 2020-06-23
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class PublicKeyServiceExceptionTest : BaseTests() {

    private val mockDeviceKeyManager by before {
        mock<DeviceKeyManager>()
    }

    private val mockAppSyncClient by before {
        mock<AWSAppSyncClient>()
    }

    private val publicKeyService by before {
        DefaultPublicKeyService(
            deviceKeyManager = mockDeviceKeyManager,
            appSyncClient = mockAppSyncClient,
            logger = mock<Logger>()
        )
    }

    @Before
    fun init() {
        Timber.plant(Timber.DebugTree())
    }

    @After
    fun fini() = runBlocking {
        Timber.uprootAll()
    }

    @Test
    fun shouldThrowIfDeviceKeyManagerThrows1() = runBlocking<Unit> {
        mockDeviceKeyManager.stub {
            on { getCurrentKeyPair() } doThrow DeviceKeyManager.DeviceKeyManagerException.KeyGenerationException("mock")
        }
        shouldThrow<PublicKeyService.PublicKeyServiceException.KeyCreateException> {
            publicKeyService.getCurrentKeyPair(PublicKeyService.MissingKeyPolicy.GENERATE_IF_MISSING)
        }
    }

    @Test
    fun shouldThrowIfDeviceKeyManagerThrows2() = runBlocking<Unit> {
        mockDeviceKeyManager.stub {
            on { generateNewCurrentKeyPair() } doThrow DeviceKeyManager.DeviceKeyManagerException.KeyGenerationException("mock")
        }
        shouldThrow<PublicKeyService.PublicKeyServiceException.KeyCreateException> {
            publicKeyService.getCurrentKeyPair(PublicKeyService.MissingKeyPolicy.GENERATE_IF_MISSING)
        }
    }

    @Test
    fun shouldThrowIfAppSyncThrows1() = runBlocking<Unit> {
        mockAppSyncClient.stub {
            on { query(any<GetKeyRingForVirtualCardsQuery>()) } doThrow RuntimeException("mock")
        }
        shouldThrow<PublicKeyService.PublicKeyServiceException.UnknownException> {
            publicKeyService.getKeyRing("id", CachePolicy.REMOTE_ONLY)
        }
    }

    @Test
    fun shouldThrowIfAppSyncThrows2() = runBlocking<Unit> {
        mockAppSyncClient.stub {
            on { mutate(any<CreatePublicKeyForVirtualCardsMutation>()) } doThrow RuntimeException("mock")
        }
        shouldThrow<PublicKeyService.PublicKeyServiceException.UnknownException> {
            publicKeyService.create(KeyPair("id", "ringId", ByteArray(42), ByteArray(42)))
        }
    }
}
