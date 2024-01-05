/*
 * Copyright © 2023 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards.keys

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sudoplatform.sudoapiclient.ApiClientManager
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
 */
@RunWith(AndroidJUnit4::class)
class PublicKeyServiceTest : BaseIntegrationTest() {

    private val keyRingServiceName = "sudo-virtual-cards"

    private val deviceKeyManager by lazy {
        DefaultDeviceKeyManager(keyManager)
    }

    private val publicKeyService by lazy {
        DefaultPublicKeyService(
            keyRingServiceName = keyRingServiceName,
            userClient = userClient,
            deviceKeyManager = deviceKeyManager,
            appSyncClient = ApiClientManager.getClient(context, userClient),
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
        if (userClient.isRegistered()) {
            deregister()
        }
        userClient.reset()

        Timber.uprootAll()
    }

    @Test
    fun getCurrentRegisteredKey_shouldThrowIfNotRegistered() = runBlocking<Unit> {
        shouldThrow<PublicKeyService.PublicKeyServiceException.UserIdNotFoundException> {
            publicKeyService.getCurrentRegisteredKey()
        }
    }

    @Test
    fun getCurrentRegisteredKey_shouldSucceedAfterSignIn() = runBlocking {
        registerSignInAndEntitle()

        deviceKeyManager.getCurrentKey() shouldBe null
        deviceKeyManager.getKeyWithId("bogusValue") shouldBe null

        val key = publicKeyService.getCurrentRegisteredKey()
        with(key) {
            this shouldNotBe null
            keyRingId shouldStartWith keyRingServiceName
            publicKey shouldNotBe null
            publicKey.keyId.isBlank() shouldBe false
            publicKey.publicKey.size shouldBeGreaterThan 0
        }

        val currentKeyPair = deviceKeyManager.getCurrentKey()
        currentKeyPair shouldNotBe null
        currentKeyPair!!.publicKey shouldBe key.publicKey.publicKey

        val fetchedKeyPair = deviceKeyManager.getKeyWithId(currentKeyPair.keyId)
        fetchedKeyPair shouldNotBe null
        fetchedKeyPair!!.publicKey shouldBe key.publicKey.publicKey
    }
}
