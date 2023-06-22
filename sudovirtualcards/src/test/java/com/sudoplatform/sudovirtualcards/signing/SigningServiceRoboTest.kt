/*
 * Copyright © 2023 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards.signing

import com.amazonaws.util.Base64
import com.sudoplatform.sudovirtualcards.BaseTests
import com.sudoplatform.sudovirtualcards.keys.DeviceKeyManager
import com.sudoplatform.sudovirtualcards.types.transformers.KeyType
import io.kotlintest.shouldBe
import io.kotlintest.shouldThrow
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyString
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.stub
import org.mockito.kotlin.verify
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Test the operation of [DefaultSigningService] under exceptional conditions using mocks.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class SigningServiceRoboTest : BaseTests() {

    private val mockDeviceKeyManager by before {
        mock<DeviceKeyManager>()
    }

    private val signingService by before {
        DefaultSigningService(
            deviceKeyManager = mockDeviceKeyManager
        )
    }

    private val keyId = "key-id"
    private val stringToSign = "StringToSign"
    private val signatureString = "signature"
    private val signature = signatureString.toByteArray()
    private val signatureBase64 = Base64.encode(signature)

    @Test
    fun `signString() should successfully sign string data using device key manager`() = runBlocking<Unit> {
        mockDeviceKeyManager.stub {
            on { signWithPrivateKeyId(anyString(), any()) } doReturn signature
        }

        signingService.signString(stringToSign, keyId, KeyType.PRIVATE_KEY) shouldBe String(signatureBase64)

        verify(mockDeviceKeyManager).signWithPrivateKeyId(anyString(), any())
    }

    @Test
    fun `signString() should throw an IllegalArgumentException for invalid key type`() = runBlocking<Unit> {
        shouldThrow<IllegalArgumentException> {
            signingService.signString(stringToSign, keyId, KeyType.SYMMETRIC_KEY) shouldBe String(signatureBase64)
        }
    }
}
