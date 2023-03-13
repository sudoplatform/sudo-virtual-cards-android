/*
 * Copyright © 2023 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards.keys

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sudoplatform.sudokeymanager.KeyManagerInterface
import com.sudoplatform.sudovirtualcards.BaseIntegrationTest
import io.kotlintest.matchers.numerics.shouldBeGreaterThan
import io.kotlintest.shouldBe
import io.kotlintest.shouldNotBe
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import timber.log.Timber

/**
 * Test the operation of [DefaultDeviceKeyManager] on Android.
 */
@RunWith(AndroidJUnit4::class)
class DeviceKeyManagerTest : BaseIntegrationTest() {

    private val deviceKeyManager by lazy {
        DefaultDeviceKeyManager(
            keyManager = keyManager
        )
    }

    @Before
    fun init() {
        Timber.plant(Timber.DebugTree())

        keyManager.removeAllKeys()
    }

    @After
    fun fini() = runBlocking {
        Timber.uprootAll()
    }

    @Test
    fun shouldReturnNullIfNoCurrentKey() {
        deviceKeyManager.getCurrentKey() shouldBe null
    }

    @Test
    fun shouldReturnCurrentKeyAfterGeneratingNewKey() = runBlocking {
        deviceKeyManager.getCurrentKey() shouldBe null
        val generated = deviceKeyManager.generateNewCurrentKeyPair()
        with(generated) {
            this shouldNotBe null
            keyId.isBlank() shouldBe false
            publicKey shouldNotBe null
            publicKey.size shouldBeGreaterThan 0
        }
        deviceKeyManager.getCurrentKey() shouldBe generated

        val fetchedKeyPair = deviceKeyManager.getKeyWithId(generated.keyId)
        fetchedKeyPair shouldNotBe null
        fetchedKeyPair shouldBe generated

        val clearData = "hello world".toByteArray()
        var secretData = keyManager.encryptWithPublicKey(
            generated.keyId,
            clearData,
            KeyManagerInterface.PublicKeyEncryptionAlgorithm.RSA_ECB_OAEPSHA1
        )
        var decryptedData = deviceKeyManager.decryptWithPrivateKey(
            secretData,
            generated.keyId,
            KeyManagerInterface.PublicKeyEncryptionAlgorithm.RSA_ECB_OAEPSHA1
        )
        decryptedData shouldBe clearData

        keyManager.generateSymmetricKey("symmetricKey")
        val symmetricKey = keyManager.getSymmetricKeyData("symmetricKey")
        secretData = keyManager.encryptWithSymmetricKey("symmetricKey", clearData)

        decryptedData = deviceKeyManager.decryptWithSymmetricKey(symmetricKey, secretData)
        decryptedData shouldBe clearData
    }
}
