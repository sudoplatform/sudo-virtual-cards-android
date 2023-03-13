/*
 * Copyright © 2023 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards

import android.content.Context
import com.amazonaws.mobileconnectors.appsync.AWSAppSyncClient
import com.sudoplatform.sudouser.PublicKey
import com.sudoplatform.sudouser.SudoUserClient
import com.sudoplatform.sudovirtualcards.keys.DeviceKeyManager
import com.sudoplatform.sudovirtualcards.keys.DeviceKey
import com.sudoplatform.sudovirtualcards.keys.PublicKeyService
import com.sudoplatform.sudovirtualcards.keys.PublicKeyWithKeyRingId
import io.kotlintest.shouldBe
import io.kotlintest.shouldNotBe
import io.kotlintest.shouldThrow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.stub
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoMoreInteractions

/**
 * Test the correct operation of [SudoVirtualCardsClient.createKeysIfAbsent]
 * using mocks and spies.
 */
class SudoVirtualCardsCreateKeysIfAbsentTest : BaseTests() {

    private val symmetricKeyId = "symmetric-key-id"
    private val keyId = "key-pair-id"
    private val keyRingId = "key-ring-id"
    private val deviceKeyResult by before {
        DeviceKey(
            keyId = keyId,
            publicKey = ByteArray(42),
        )
    }

    private val publicKeyWithKeyRingIdResult by before {
        PublicKeyWithKeyRingId(
            publicKey = PublicKey(
                keyId = keyId,
                publicKey = ByteArray(42)
            ),
            keyRingId = keyRingId
        )
    }

    private val publicKey by before {
        PublicKey(keyId, ByteArray(42), "algorithm")
    }

    private val mockContext by before {
        mock<Context>()
    }

    private val mockUserClient by before {
        mock<SudoUserClient>()
    }

    private val mockAppSyncClient by before {
        mock<AWSAppSyncClient>()
    }

    private val mockDeviceKeyManager by before {
        mock<DeviceKeyManager>().stub {
            on { getCurrentSymmetricKeyId() } doReturn symmetricKeyId
            on { getCurrentKey() } doReturn deviceKeyResult
            on { generateNewCurrentSymmetricKey() } doReturn symmetricKeyId
            on { generateNewCurrentKeyPair() } doReturn deviceKeyResult
        }
    }

    private val mockPublicKeyService by before {
        mock<PublicKeyService>().stub {
            onBlocking { get(anyString(), any()) } doReturn PublicKeyWithKeyRingId(
                publicKey = publicKey,
                keyRingId = keyRingId
            )
            onBlocking { getCurrentKey() } doReturn publicKey
            onBlocking { getCurrentRegisteredKey() } doReturn publicKeyWithKeyRingIdResult
        }
    }

    private val client by before {
        DefaultSudoVirtualCardsClient(
            mockContext,
            mockAppSyncClient,
            mockUserClient,
            mockLogger,
            mockDeviceKeyManager,
            mockPublicKeyService
        )
    }

    @After
    fun fini() {
        verifyNoMoreInteractions(
            mockContext,
            mockUserClient,
            mockAppSyncClient,
            mockDeviceKeyManager,
            mockPublicKeyService
        )
    }

    @Test
    fun `createKeysIfAbsent() should create new symmetric key if current symmetric key is not present`() = runBlocking<Unit> {

        mockDeviceKeyManager.stub {
            on { getCurrentSymmetricKeyId() } doReturn null
        }

        val deferredResult = async(Dispatchers.IO) {
            client.createKeysIfAbsent()
        }
        deferredResult.start()
        delay(100L)

        val result = deferredResult.await()
        result shouldNotBe null

        with(result) {
            symmetricKey.created shouldBe true
            symmetricKey.keyId shouldBe symmetricKeyId
            keyPair.created shouldBe false
            keyPair.keyId shouldBe keyId
        }

        verify(mockDeviceKeyManager).getCurrentSymmetricKeyId()
        verify(mockDeviceKeyManager).generateNewCurrentSymmetricKey()
        verify(mockPublicKeyService).getCurrentRegisteredKey()
    }

    @Test
    fun `createKeysIfAbsent() should throw when an unknown error occurs`() = runBlocking<Unit> {

        mockDeviceKeyManager.stub {
            on { getCurrentSymmetricKeyId() } doThrow RuntimeException("Mock Runtime Exception")
        }

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.VirtualCardException.UnknownException> {
                client.createKeysIfAbsent()
            }
        }
        deferredResult.start()
        delay(100L)

        deferredResult.await()

        verify(mockDeviceKeyManager).getCurrentSymmetricKeyId()
    }
}
