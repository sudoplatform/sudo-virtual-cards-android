/*
 * Copyright © 2023 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards.keys

import android.content.Context
import android.util.Base64
import com.amazonaws.mobileconnectors.appsync.AWSAppSyncClient
import com.google.gson.Gson
import com.sudoplatform.sudokeymanager.KeyManager
import com.sudoplatform.sudokeymanager.KeyManagerInterface
import com.sudoplatform.sudouser.PublicKey
import com.sudoplatform.sudouser.SudoUserClient
import com.sudoplatform.sudovirtualcards.BaseTests
import com.sudoplatform.sudovirtualcards.DefaultSudoVirtualCardsClient
import com.sudoplatform.sudovirtualcards.SudoVirtualCardsClient
import com.sudoplatform.sudovirtualcards.types.transformers.KeyInfo
import com.sudoplatform.sudovirtualcards.types.transformers.KeyType
import com.sudoplatform.sudovirtualcards.types.transformers.Unsealer
import io.kotlintest.shouldBe
import io.kotlintest.shouldThrow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.stub
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoMoreInteractions
import org.robolectric.RobolectricTestRunner

/**
 * Test the correct operation of [SudoEmailClient.exportKeys, SudoEmailClient.importKeys]
 * using mocks and spies.
 */

internal data class VCMetadata(
    val alias: String,
    val color: String
)

@RunWith(RobolectricTestRunner::class)
class SudoVirtualCardsImportExportKeysTest : BaseTests() {

    private val mockContext by before {
        mock<Context>()
    }

    private val mockUserClient by before {
        mock<SudoUserClient>()
    }

    private val mockAppSyncClient by before {
        mock<AWSAppSyncClient>().stub {
        }
    }

    private val mockKeyManager by before {
        mock<KeyManagerInterface>().stub {
        }
    }

    private val dummyKeyString = "dummy key archive"

    private val mockDeviceKeyManager by before {
        mock<DeviceKeyManager>().stub {
            on { exportKeys() } doReturn dummyKeyString.toByteArray(Charsets.UTF_8)
        }
    }

    private val currentKey = PublicKey(
        keyId = "keyId",
        publicKey = "publicKey".toByteArray()
    )

    private val mockPublicKeyService by before {
        mock<PublicKeyService>().stub {
            onBlocking { getCurrentKey() } doReturn currentKey
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
        verifyNoMoreInteractions(mockContext, mockUserClient, mockKeyManager, mockAppSyncClient)
    }

    @Test
    fun `importKeys(archiveData) should succeed when no error present`() = runBlocking<Unit> {
        val archiveData = dummyKeyString.toByteArray()
        client.importKeys(archiveData)

        verify(mockDeviceKeyManager).importKeys(archiveData)
    }

    @Test
    fun `importKeys(archiveData) with empty archive data throws`() = runBlocking<Unit> {
        val archiveData = "".toByteArray()
        shouldThrow<SudoVirtualCardsClient.VirtualCardCryptographicKeysException.SecureKeyArchiveException> {
            client.importKeys(archiveData)
        }
    }

    @Test
    fun `importKeys(archiveData) should throw when deviceKeyManager throws`() = runBlocking<Unit> {
        val mockDeviceKeyManager by before {
            mock<DeviceKeyManager>().stub {
                on { importKeys(any<ByteArray>()) } doThrow
                    DeviceKeyManager.DeviceKeyManagerException.SecureKeyArchiveException("Mock exception")
            }
        }

        val errorClient by before {
            DefaultSudoVirtualCardsClient(
                mockContext,
                mockAppSyncClient,
                mockUserClient,
                mockLogger,
                mockDeviceKeyManager,
                mockPublicKeyService
            )
        }

        shouldThrow<SudoVirtualCardsClient.VirtualCardCryptographicKeysException.SecureKeyArchiveException> {
            errorClient.importKeys(dummyKeyString.toByteArray(Charsets.UTF_8))
        }
    }

    @Test
    fun `exportKeys() should return key archive as exported from deviceKeyManager`() = runBlocking<Unit> {
        val keyArchiveData = client.exportKeys()

        verify(mockDeviceKeyManager).exportKeys()
        keyArchiveData.toString(Charsets.UTF_8) shouldBe dummyKeyString
    }

    @Test
    fun `exportKeys() should throw when deviceKeyManager throws`() = runBlocking<Unit> {
        val mockDeviceKeyManager by before {
            mock<DeviceKeyManager>().stub {
                on { exportKeys() } doThrow
                    DeviceKeyManager.DeviceKeyManagerException.SecureKeyArchiveException("Mock exception")
            }
        }

        val errorClient by before {
            DefaultSudoVirtualCardsClient(
                mockContext,
                mockAppSyncClient,
                mockUserClient,
                mockLogger,
                mockDeviceKeyManager,
                mockPublicKeyService
            )
        }

        shouldThrow<SudoVirtualCardsClient.VirtualCardCryptographicKeysException.SecureKeyArchiveException> {
            errorClient.exportKeys()
        }
    }

    @Test
    fun `importKeys() successfully imports keys exported from the JS SDK`() = runBlocking<Unit> {
        // Obtained from JS exportKeys API. Don't update these constants since the point of this is to
        // check that we have not broken backward compatibility (with existing key
        // backups) and interoperability with all clients.
        @Suppress("ktlint:max-line-length")
        val exportedKeys = "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"

        @Suppress("ktlint:max-line-length")
        val sealedCardHolder = "OCceHvS8PYMWfF2kGS1QeQK9kLYvCvAC2O+4aVj2aJu2nlrRgPCE4eXvqYOD2Myrar01dcCNzFjqxFp5N/OGPL0hbzkl4a2h7tllYzg+32ZUmkpEEcKOXpxSXWA1IDRkWAZ9SRwgAz25MJvm4hy0aFY+Sf6lEZXjK2MNvFjCGQ8Chfb7Byl+Krn+Z36lKvRN5+FCknukVqfOG6wfaLBJyWihFRPw/GTpC5z6SjmwKcPqzk2tzuOj4NdcDk9HElYkZFm5isVDxMjEed5AE4bVAKviL3qAcIiqpB7h7fjMdktqLPIkjjGi5M9bQ87ofDA7VUkUpG3AW0/dCLhENwkZk2Cpm6qbokNAS9ELtsCDQqrpSgMyzBAJrUjNsOS8Bz07"

        @Suppress("ktlint:max-line-length")
        val sealedAlias = "QxkXAyPgg6WYaeAqxy3Ta6tnLXOF9UEzv5z7QFLALPewQoqO6TapMyG+dGRNAAPPo5k8o3cuq7AbjlJMpuFnUvs2TX7mX2eNN3mYdt/fg5n4Z+arkhcJ3eE06NUdEFBtrWP09f5WeLi7FPOJ8WUYCzMUthPaCK9eMjNTyedsQRXiGA28ZQKw18mfkFlO6VaGtCY1iyt4LuqI/K4dXDvy8TsLqqqD550/He65RKPCGYPOdJohAxM//GjQ5HxV/zohE035hts0VMbNlAZyYBPjmGjT+Z3rcGgXVA9EAaZSqFn0a1hLwK5RpxNc1KCGZ3xHnrXCJB5hN+hS/3tevgDlXDONKzQMS5g/ITkbBUC5ytrJlZPosXsCGkmoVO6jg+Cu3Nh0ClC9uusPNcvIh7AK8g=="

        val sealedMetadata = "A01y/eR6pUYg/u3ArJncW+g5vZQTBJkpvR+oJSmacs4amNrhwcQWykuVZIAwX8HS"
        val metadataKeyId = "c75fcc22-295a-4512-b92f-933f2c50b53f"
        val metadataAlgorithm = "AES/CBC/PKCS7Padding"
        val keyId = "b09420e6-8e19-4ca9-a276-b0c3beaf1a1b"
        val algorithm = "RSAEncryptionOAEPAESCBC" // "RSA/OAEPWithSHA-1"
        val alias = "65d1fa70-922d-4520-9aa6-8b4f085297a6"
        val cardHolder = "exportImportKeysCardHolder"
        val metadataColor = "red"
        val metadataAlias = "metadata-alias"
        // End of obtained from JS exportKeys API.

        val currentKey by before {
            PublicKey(
                keyId = "keyId",
                publicKey = "publicKey".toByteArray()
            )
        }
        val mockPublicKeyService by before {
            mock<PublicKeyService>().stub {
                onBlocking { getCurrentKey() } doReturn currentKey
            }
        }

        val keyManager by before {
            KeyManager(InMemoryStore())
        }

        val deviceKeyManager by before {
            DefaultDeviceKeyManager(keyManager)
        }

        val importExportClient by before {
            DefaultSudoVirtualCardsClient(
                mockContext,
                mockAppSyncClient,
                mockUserClient,
                mockLogger,
                deviceKeyManager,
                mockPublicKeyService
            )
        }

        var keyInfo = KeyInfo(keyId, KeyType.PRIVATE_KEY, algorithm)
        var unsealer = Unsealer(deviceKeyManager, keyInfo)

        val archiveData = Base64.decode(exportedKeys, Base64.NO_WRAP)
        importExportClient.importKeys(archiveData)

        val unsealedCardHolder = unsealer.unseal(sealedCardHolder)
        unsealedCardHolder shouldBe cardHolder
        val unsealedAlias = unsealer.unseal(sealedAlias)
        unsealedAlias shouldBe alias

        keyInfo = KeyInfo(metadataKeyId, KeyType.SYMMETRIC_KEY, metadataAlgorithm)
        unsealer = Unsealer(deviceKeyManager, keyInfo)
        val unsealedMetadataString = unsealer.unseal(sealedMetadata)
        val unsealedMetadata = Gson().fromJson(unsealedMetadataString, VCMetadata::class.java)
        unsealedMetadata.alias shouldBe metadataAlias
        unsealedMetadata.color shouldBe metadataColor
    }
}
