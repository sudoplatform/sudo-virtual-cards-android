/*
 * Copyright © 2022 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards

import android.content.Context
import com.amazonaws.mobileconnectors.appsync.AWSAppSyncClient
import com.sudoplatform.sudoprofiles.SudoProfilesClient
import com.sudoplatform.sudouser.PublicKey
import com.sudoplatform.sudouser.SudoUserClient
import com.sudoplatform.sudovirtualcards.keys.DeviceKeyManager
import com.sudoplatform.sudovirtualcards.keys.KeyPair
import com.sudoplatform.sudovirtualcards.keys.KeyRing
import com.sudoplatform.sudovirtualcards.keys.PublicKeyService
import com.sudoplatform.sudovirtualcards.types.CachePolicy
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
import java.util.concurrent.CancellationException

/**
 * Test the correct operation of [SudoVirtualCardsClient.createKeysIfAbsent]
 * using mocks and spies.
 */
class SudoVirtualCardsCreateKeysIfAbsentTest : BaseTests() {

    private val symmetricKeyId = "symmetric-key-id"
    private val keyId = "key-pair-id"
    private val keyRingId = "key-ring-id"
    private val keyPairResult by before {
        KeyPair(
            keyId = keyId,
            keyRingId = "key-ring-id",
            publicKey = ByteArray(42),
            privateKey = ByteArray(42)
        )
    }
    private val publicKey by before {
        PublicKey(keyId, ByteArray(42), "algorithm")
    }
    private val keyRing by before {
        KeyRing(keyRingId, listOf(publicKey), null)
    }

    private val mockContext by before {
        mock<Context>()
    }

    private val mockUserClient by before {
        mock<SudoUserClient>()
    }

    private val mockSudoClient by before {
        mock<SudoProfilesClient>()
    }

    private val mockAppSyncClient by before {
        mock<AWSAppSyncClient>()
    }

    private val mockDeviceKeyManager by before {
        mock<DeviceKeyManager>().stub {
            on { getCurrentSymmetricKeyId() } doReturn symmetricKeyId
            on { getCurrentKeyPair() } doReturn keyPairResult
            on { generateNewCurrentSymmetricKey() } doReturn symmetricKeyId
            on { generateNewCurrentKeyPair() } doReturn keyPairResult
        }
    }

    private val mockPublicKeyService by before {
        mock<PublicKeyService>().stub {
            onBlocking { getKeyRing(anyString(), any()) } doReturn keyRing
        }
    }

    private val client by before {
        DefaultSudoVirtualCardsClient(
            mockContext,
            mockAppSyncClient,
            mockUserClient,
            mockSudoClient,
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
            mockSudoClient,
            mockAppSyncClient,
            mockDeviceKeyManager,
            mockPublicKeyService
        )
    }

    @Test
    fun `createKeysIfAbsent() should not create new keys if current keys are present`() = runBlocking<Unit> {

        val deferredResult = async(Dispatchers.IO) {
            client.createKeysIfAbsent()
        }
        deferredResult.start()
        delay(100L)

        val result = deferredResult.await()
        result shouldNotBe null

        with(result) {
            symmetricKey.created shouldBe false
            symmetricKey.keyId shouldBe symmetricKeyId
            keyPair.created shouldBe false
            keyPair.keyId shouldBe keyId
        }

        verify(mockDeviceKeyManager).getCurrentSymmetricKeyId()
        verify(mockDeviceKeyManager).getCurrentKeyPair()
        verify(mockPublicKeyService).getKeyRing(keyRingId, CachePolicy.REMOTE_ONLY)
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
        verify(mockDeviceKeyManager).getCurrentKeyPair()
        verify(mockPublicKeyService).getKeyRing(keyRingId, CachePolicy.REMOTE_ONLY)
    }

    @Test
    fun `createKeysIfAbsent() should register key pair if current key pair is present but not registered`() = runBlocking<Unit> {

        mockPublicKeyService.stub {
            onBlocking { getKeyRing(anyString(), any()) } doReturn KeyRing("", emptyList(), null)
        }

        val deferredResult = async(Dispatchers.IO) {
            client.createKeysIfAbsent()
        }
        deferredResult.start()
        delay(100L)

        val result = deferredResult.await()
        result shouldNotBe null

        with(result) {
            symmetricKey.created shouldBe false
            symmetricKey.keyId shouldBe symmetricKeyId
            keyPair.created shouldBe false
            keyPair.keyId shouldBe keyId
        }

        verify(mockDeviceKeyManager).getCurrentSymmetricKeyId()
        verify(mockDeviceKeyManager).getCurrentKeyPair()
        verify(mockPublicKeyService).create(keyPairResult.keyId, keyPairResult.keyRingId, keyPairResult.publicKey)
        verify(mockPublicKeyService).getKeyRing(keyRingId, CachePolicy.REMOTE_ONLY)
    }

    @Test
    fun `createKeysIfAbsent() should create and register key pair if current key pair is not present`() = runBlocking<Unit> {

        mockDeviceKeyManager.stub {
            on { getCurrentKeyPair() } doReturn null
        }

        val deferredResult = async(Dispatchers.IO) {
            client.createKeysIfAbsent()
        }
        deferredResult.start()
        delay(100L)

        val result = deferredResult.await()
        result shouldNotBe null

        with(result) {
            symmetricKey.created shouldBe false
            symmetricKey.keyId shouldBe symmetricKeyId
            keyPair.created shouldBe true
            keyPair.keyId shouldBe keyId
        }

        verify(mockDeviceKeyManager).getCurrentSymmetricKeyId()
        verify(mockDeviceKeyManager).getCurrentKeyPair()
        verify(mockDeviceKeyManager).generateNewCurrentKeyPair()
        verify(mockPublicKeyService).create(keyPairResult.keyId, keyPairResult.keyRingId, keyPairResult.publicKey)
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

    @Test
    fun `createKeysIfAbsent() should not block coroutine cancellation exception`() = runBlocking<Unit> {

        mockPublicKeyService.stub {
            onBlocking { getKeyRing(anyString(), any()) } doThrow CancellationException("Mock Cancellation Exception")
        }

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.VirtualCardException.PublicKeyException> {
                client.createKeysIfAbsent()
            }
        }
        deferredResult.start()
        delay(100L)

        deferredResult.await()

        verify(mockDeviceKeyManager).getCurrentSymmetricKeyId()
        verify(mockDeviceKeyManager).getCurrentKeyPair()
        verify(mockPublicKeyService).getKeyRing(keyRingId, CachePolicy.REMOTE_ONLY)
    }
}
