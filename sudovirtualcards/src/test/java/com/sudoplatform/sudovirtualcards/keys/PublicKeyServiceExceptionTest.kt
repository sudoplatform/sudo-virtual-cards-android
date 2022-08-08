/*
 * Copyright © 2022 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards.keys

import com.amazonaws.mobileconnectors.appsync.AWSAppSyncClient
import org.mockito.kotlin.any
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.stub
import com.sudoplatform.sudologging.Logger
import com.sudoplatform.sudouser.SudoUserClient
import com.sudoplatform.sudovirtualcards.BaseTests
import com.sudoplatform.sudovirtualcards.graphql.CreatePublicKeyMutation
import com.sudoplatform.sudovirtualcards.graphql.GetPublicKeyQuery
import com.sudoplatform.sudovirtualcards.types.CachePolicy
import io.kotlintest.shouldThrow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.doReturn
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import timber.log.Timber
import java.lang.RuntimeException

/**
 * Test the operation of [DefaultPublicKeyService] under exceptional conditions using mocks.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class PublicKeyServiceExceptionTest : BaseTests() {

    private val keyRingServiceName = "sudo-virtual-cards"

    private val mockUserClient by before {
        mock<SudoUserClient>().stub {
            on { getSubject() } doReturn "mockSubject"
        }
    }

    private val mockDeviceKeyManager by before {
        mock<DeviceKeyManager>()
    }

    private val mockAppSyncClient by before {
        mock<AWSAppSyncClient>()
    }

    private val publicKeyService by before {
        DefaultPublicKeyService(
            keyRingServiceName = keyRingServiceName,
            userClient = mockUserClient,
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
    fun shouldThrowIfNotRegistered() = runBlocking<Unit> {
        // given
        mockUserClient.stub {
            on { getSubject() } doReturn null
        }

        shouldThrow<PublicKeyService.PublicKeyServiceException.UserIdNotFoundException> {
            publicKeyService.getCurrentRegisteredKey()
        }
    }

    @Test
    fun shouldThrowIfDeviceKeyManagerThrows1() = runBlocking<Unit> {
        mockDeviceKeyManager.stub {
            on { getCurrentKey() } doThrow DeviceKeyManager.DeviceKeyManagerException.KeyGenerationException("mock")
        }
        shouldThrow<PublicKeyService.PublicKeyServiceException.KeyCreateException> {
            publicKeyService.getCurrentKey()
        }
    }

    @Test
    fun shouldThrowIfDeviceKeyManagerThrows2() = runBlocking<Unit> {
        mockDeviceKeyManager.stub {
            on { generateNewCurrentKeyPair() } doThrow DeviceKeyManager.DeviceKeyManagerException.KeyGenerationException("mock")
        }
        shouldThrow<PublicKeyService.PublicKeyServiceException.KeyCreateException> {
            publicKeyService.getCurrentRegisteredKey()
        }
    }

    @Test
    fun shouldThrowIfAppSyncThrows1() = runBlocking<Unit> {
        mockAppSyncClient.stub {
            on { query(any<GetPublicKeyQuery>()) } doThrow RuntimeException("mock")
        }
        shouldThrow<PublicKeyService.PublicKeyServiceException.UnknownException> {
            publicKeyService.get("id", CachePolicy.REMOTE_ONLY)
        }
    }

    @Test
    fun shouldThrowIfAppSyncThrows2() = runBlocking<Unit> {
        mockAppSyncClient.stub {
            on { mutate(any<CreatePublicKeyMutation>()) } doThrow RuntimeException("mock")
        }
        shouldThrow<PublicKeyService.PublicKeyServiceException.UnknownException> {
            publicKeyService.create("id", "ringId", ByteArray(42))
        }
    }
}
