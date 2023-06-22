/*
 * Copyright © 2023 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards.keys

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sudoplatform.sudokeymanager.AndroidSQLiteStore
import com.sudoplatform.sudokeymanager.KeyManager
import com.sudoplatform.sudokeymanager.KeyManagerInterface
import com.sudoplatform.sudovirtualcards.BaseTests
import io.kotlintest.matchers.numerics.shouldBeGreaterThan
import io.kotlintest.shouldBe
import io.kotlintest.shouldNotBe
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import timber.log.Timber

/**
 * Test the operation of [DefaultDeviceKeyManager] using mocks under Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class DeviceKeyManagerRoboTest : BaseTests() {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private val keyManager by before {
        KeyManager(AndroidSQLiteStore(context))
    }

    private val deviceKeyManager by before {
        DefaultDeviceKeyManager(
            keyManager = keyManager,
            logger = mockLogger
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
    fun shouldBeAbleToPerformOperations() = runBlocking {
        deviceKeyManager.getCurrentKey() shouldBe null
        deviceKeyManager.getKeyWithId("bogusValue") shouldBe null

        val keyPair = deviceKeyManager.generateNewCurrentKeyPair()
        with(keyPair) {
            this shouldNotBe null
            keyId.isBlank() shouldBe false
            publicKey shouldNotBe null
            publicKey.size shouldBeGreaterThan 0
        }

        val currentKeyPair = deviceKeyManager.getCurrentKey()
        currentKeyPair shouldNotBe null
        currentKeyPair shouldBe keyPair

        val fetchedKeyPair = deviceKeyManager.getKeyWithId(currentKeyPair!!.keyId)
        fetchedKeyPair shouldNotBe null
        fetchedKeyPair shouldBe keyPair
        fetchedKeyPair shouldBe currentKeyPair

        val clearData = "hello world".toByteArray()
        var secretData = keyManager.encryptWithPublicKey(
            currentKeyPair.keyId,
            clearData,
            KeyManagerInterface.PublicKeyEncryptionAlgorithm.RSA_ECB_OAEPSHA1
        )
        var decryptedData = deviceKeyManager.decryptWithPrivateKey(
            secretData,
            currentKeyPair.keyId,
            KeyManagerInterface.PublicKeyEncryptionAlgorithm.RSA_ECB_OAEPSHA1
        )
        decryptedData shouldBe clearData

        keyManager.generateSymmetricKey("symmetricKey")
        val symmetricKey = keyManager.getSymmetricKeyData("symmetricKey")
        secretData = keyManager.encryptWithSymmetricKey("symmetricKey", clearData)

        decryptedData = deviceKeyManager.decryptWithSymmetricKey(symmetricKey, secretData)
        decryptedData shouldBe clearData
    }

    @Test
    fun shouldBeAbleToGenerateSymmetricKeyId() = runBlocking {
        deviceKeyManager.getCurrentSymmetricKeyId() shouldBe null

        val symmetricKey = deviceKeyManager.generateNewCurrentSymmetricKey()
        symmetricKey.isBlank() shouldBe false

        val symmetricKeyId = deviceKeyManager.getCurrentSymmetricKeyId()
        symmetricKeyId shouldNotBe null
        symmetricKeyId?.isBlank() shouldBe false
    }
}
