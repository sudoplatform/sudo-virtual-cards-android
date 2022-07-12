/*
 * Copyright © 2022 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards.types.transformers

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.amazonaws.util.Base64
import com.sudoplatform.sudokeymanager.KeyManagerInterface
import com.sudoplatform.sudovirtualcards.BaseIntegrationTest
import com.sudoplatform.sudovirtualcards.keys.DefaultDeviceKeyManager
import com.sudoplatform.sudovirtualcards.keys.DefaultPublicKeyService
import io.kotlintest.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import timber.log.Timber
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Test the operation of the [Unsealer] on a real device with real crypto.
 */
@RunWith(AndroidJUnit4::class)
class AndroidUnsealerTest : BaseIntegrationTest() {

    companion object {
        private const val clearText = "The owl and the pussy cat went to sea in a beautiful pea green boat."
    }

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
    fun shouldBeAbleToUnseal() = runBlocking {
        registerSignInAndEntitle()

        val symmetricKeyId = UUID.randomUUID().toString()

        val keyPair = deviceKeyManager.generateNewCurrentKeyPair()

        keyManager.generateSymmetricKey(symmetricKeyId, true)
        val symmetricKeyData = keyManager.getSymmetricKeyData(symmetricKeyId)

        val encryptedKeyData = keyManager.encryptWithPublicKey(
            keyPair.keyId,
            symmetricKeyData,
            KeyManagerInterface.PublicKeyEncryptionAlgorithm.RSA_ECB_OAEPSHA1
        )

        val encryptedData = keyManager.encryptWithSymmetricKey(symmetricKeyId, clearText.toByteArray())
        encryptedKeyData.size shouldBe Unsealer.KEY_SIZE_AES

        val sealedData = encryptedKeyData + encryptedData
        val sealedBase64 = Base64.encodeAsString(*sealedData)

        val keyInfo = KeyInfo(keyPair.keyId, KeyType.PRIVATE_KEY, DefaultPublicKeyService.DEFAULT_ALGORITHM)
        val unsealer = Unsealer(deviceKeyManager, keyInfo)
        val unsealedText = unsealer.unseal(sealedBase64)
        unsealedText shouldBe clearText
    }

    @Test
    @Ignore // Enable when you want to examine peformance
    fun bulkUnsealingShouldBeFast() = runBlocking {
        registerSignInAndEntitle()

        val keyPair = deviceKeyManager.generateNewCurrentKeyPair()

        val sealedBase64 = mutableListOf<String>()

        val iterations = 1_000
        for (i in 1..iterations) {

            val symmetricKeyId = UUID.randomUUID().toString()
            keyManager.generateSymmetricKey(symmetricKeyId, true)
            val symmetricKeyData = keyManager.getSymmetricKeyData(symmetricKeyId)

            val encryptedKeyData = keyManager.encryptWithPublicKey(
                keyPair.keyId,
                symmetricKeyData,
                KeyManagerInterface.PublicKeyEncryptionAlgorithm.RSA_ECB_OAEPSHA1
            )

            val encryptedData = keyManager.encryptWithSymmetricKey(symmetricKeyId, clearText.toByteArray())
            encryptedKeyData.size shouldBe Unsealer.KEY_SIZE_AES

            val sealedData = encryptedKeyData + encryptedData
            sealedBase64.add(Base64.encodeAsString(*sealedData))
        }

        val keyInfo = KeyInfo(keyPair.keyId, KeyType.PRIVATE_KEY, DefaultPublicKeyService.DEFAULT_ALGORITHM)
        val unsealer = Unsealer(deviceKeyManager, keyInfo)

        val start = Instant.now()
        sealedBase64.forEach { sealedValue ->
            val unsealedText = unsealer.unseal(sealedValue)
            unsealedText shouldBe clearText
        }
        val end = Instant.now()
        val durationMillis = Duration.between(start, end).toMillis()

        println("Unsealing of $iterations took ${durationMillis}ms on ${Build.MANUFACTURER} ${Build.MODEL}")
    }
}
