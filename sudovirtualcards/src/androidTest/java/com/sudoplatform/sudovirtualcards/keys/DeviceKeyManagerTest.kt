/*
 * Copyright © 2020 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards.keys

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sudoplatform.sudokeymanager.KeyManagerInterface
import com.sudoplatform.sudovirtualcards.BaseIntegrationTest
import io.kotlintest.matchers.numerics.shouldBeGreaterThan
import io.kotlintest.matchers.string.shouldStartWith
import io.kotlintest.shouldBe
import io.kotlintest.shouldNotBe
import io.kotlintest.shouldThrow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import timber.log.Timber
import java.util.logging.Logger

/**
 * Test the operation of [DefaultDeviceKeyManager] on Android.
 *
 * @since 2020-06-16
 */
@RunWith(AndroidJUnit4::class)
class DeviceKeyManagerTest : BaseIntegrationTest() {

    private val keyRingServiceName = "sudo-virtual-cards"

    private val deviceKeyManager by lazy {
        DefaultDeviceKeyManager(
            context = context,
            userClient = userClient,
            keyRingServiceName = keyRingServiceName,
            keyManager = keyManager
        )
    }

    @Before
    fun init() {
        Timber.plant(Timber.DebugTree())

        Logger.getLogger("com.amazonaws").level = java.util.logging.Level.FINEST
        Logger.getLogger("org.apache.http").level = java.util.logging.Level.FINEST

        keyManager.removeAllKeys()
    }

    @After
    fun fini() = runBlocking {
        if (clientConfigFilesPresent()) {
            if (userClient.isRegistered()) {
                deregister()
            }
            userClient.reset()
            sudoClient.reset()
        }

        Timber.uprootAll()
    }

    @Test
    fun shouldThrowIfNotRegistered() {
        shouldThrow<DeviceKeyManager.DeviceKeyManagerException.UserIdNotFoundException> {
            deviceKeyManager.getKeyRingId()
        }
    }

    @Test
    fun shouldBeAbleToPerformOperationsAfterSignIn() = runBlocking {

        signInAndRegister()

        deviceKeyManager.getCurrentKeyPair() shouldBe null
        deviceKeyManager.getKeyPairWithId("bogusValue") shouldBe null

        val keyPair = deviceKeyManager.generateNewCurrentKeyPair()
        with(keyPair) {
            this shouldNotBe null
            keyRingId shouldStartWith keyRingServiceName
            keyId.isBlank() shouldBe false
            publicKey shouldNotBe null
            publicKey.size shouldBeGreaterThan 0
            privateKey shouldNotBe null
            privateKey.size shouldBeGreaterThan 0
        }

        val currentKeyPair = deviceKeyManager.getCurrentKeyPair()
        currentKeyPair shouldNotBe null
        currentKeyPair shouldBe keyPair

        val fetchedKeyPair = deviceKeyManager.getKeyPairWithId(currentKeyPair!!.keyId)
        fetchedKeyPair shouldNotBe null
        fetchedKeyPair shouldBe keyPair
        fetchedKeyPair shouldBe currentKeyPair

        deviceKeyManager.getKeyRingId() shouldStartWith keyRingServiceName

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
}
