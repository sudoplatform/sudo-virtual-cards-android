/*
 * Copyright © 2022 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards.keys

import org.mockito.kotlin.any
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.stub
import com.sudoplatform.sudokeymanager.KeyManagerException
import com.sudoplatform.sudokeymanager.KeyManagerInterface
import com.sudoplatform.sudovirtualcards.BaseTests
import io.kotlintest.shouldThrow
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyString
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Test the operation of [DefaultDeviceKeyManager] under exceptional conditions using mocks.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class DeviceKeyManagerExceptionTest : BaseTests() {

    private val mockKeyManager by before {
        mock<KeyManagerInterface>()
    }

    private val deviceKeyManager by before {
        DefaultDeviceKeyManager(
            keyManager = mockKeyManager,
            logger = mockLogger
        )
    }

    @Test
    fun getCurrentKeyPairShouldThrowIfKeyManagerThrows() {
        mockKeyManager.stub {
            on { getPassword(anyString()) } doThrow KeyManagerException("mock")
        }
        shouldThrow<DeviceKeyManager.DeviceKeyManagerException.KeyOperationFailedException> {
            deviceKeyManager.getCurrentKey()
        }
    }

    @Test
    fun getKeyPairWithIdShouldThrowIfKeyManagerThrows() {
        mockKeyManager.stub {
            on { getPublicKeyData(anyString()) } doThrow KeyManagerException("mock")
        }
        shouldThrow<DeviceKeyManager.DeviceKeyManagerException.KeyOperationFailedException> {
            deviceKeyManager.getKeyWithId("42")
        }
    }

    @Test
    fun generateNewCurrentKeyPairShouldThrowIfKeyManagerThrows() {
        mockKeyManager.stub {
            on { getPassword(anyString()) } doThrow KeyManagerException("mock")
        }
        shouldThrow<DeviceKeyManager.DeviceKeyManagerException.KeyGenerationException> {
            deviceKeyManager.generateNewCurrentKeyPair()
        }
    }

    @Test
    fun getCurrentSymmetricKeyIdShouldThrowIfKeyManagerThrows() {
        mockKeyManager.stub {
            on { getPassword(anyString()) } doThrow KeyManagerException("mock")
        }
        shouldThrow<DeviceKeyManager.DeviceKeyManagerException.KeyOperationFailedException> {
            deviceKeyManager.getCurrentSymmetricKeyId()
        }
    }

    @Test
    fun generateNewCurrentSymmetricKeyShouldThrowIfKeyManagerThrows() {
        mockKeyManager.stub {
            on { deletePassword(anyString()) } doThrow KeyManagerException("mock")
        }
        shouldThrow<DeviceKeyManager.DeviceKeyManagerException.KeyGenerationException> {
            deviceKeyManager.generateNewCurrentSymmetricKey()
        }
    }

    @Test
    fun decryptWithPrivateKeyShouldThrowIfKeyManagerThrows() {
        mockKeyManager.stub {
            on { decryptWithPrivateKey(anyString(), any(), any()) } doThrow KeyManagerException("mock")
        }
        shouldThrow<DeviceKeyManager.DeviceKeyManagerException.DecryptionException> {
            deviceKeyManager.decryptWithPrivateKey(
                ByteArray(42),
                "42",
                KeyManagerInterface.PublicKeyEncryptionAlgorithm.RSA_ECB_OAEPSHA1
            )
        }
    }

    @Test
    fun decryptWithSymmetricKeyShouldThrowIfKeyManagerThrows() {
        mockKeyManager.stub {
            on { decryptWithSymmetricKey(any<ByteArray>(), any<ByteArray>()) } doThrow KeyManagerException("mock")
        }
        shouldThrow<DeviceKeyManager.DeviceKeyManagerException.DecryptionException> {
            deviceKeyManager.decryptWithSymmetricKey(
                ByteArray(42),
                ByteArray(42)
            )
        }
    }

    @Test
    fun decryptWithSymmetricKeyIdShouldThrowIfKeyManagerThrows() {
        mockKeyManager.stub {
            on { decryptWithSymmetricKey(anyString(), any<ByteArray>()) } doThrow KeyManagerException("mock")
        }
        shouldThrow<DeviceKeyManager.DeviceKeyManagerException.DecryptionException> {
            deviceKeyManager.decryptWithSymmetricKeyId(
                "42",
                ByteArray(42)
            )
        }
    }

    @Test
    fun encryptWithSymmetricKeyIdShouldThrowIfKeyManagerThrows() {
        mockKeyManager.stub {
            on { encryptWithSymmetricKey(anyString(), any()) } doThrow KeyManagerException("mock")
        }
        shouldThrow<DeviceKeyManager.DeviceKeyManagerException.EncryptionException> {
            deviceKeyManager.encryptWithSymmetricKeyId(
                "42",
                ByteArray(42)
            )
        }
    }

    @Test
    fun removeAllKeysShouldThrowIfKeyManagerThrows() {
        mockKeyManager.stub {
            on { removeAllKeys() } doThrow RuntimeException("mock")
        }
        shouldThrow<DeviceKeyManager.DeviceKeyManagerException.UnknownException> {
            deviceKeyManager.removeAllKeys()
        }
    }
}
