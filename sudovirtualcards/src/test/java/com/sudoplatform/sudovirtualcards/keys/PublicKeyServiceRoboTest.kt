/*
 * Copyright © 2022 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards.keys

import com.amazonaws.mobileconnectors.appsync.AWSAppSyncClient
import com.apollographql.apollo.api.Response
import org.mockito.kotlin.mock
import org.mockito.kotlin.stub
import com.sudoplatform.sudologging.Logger
import com.sudoplatform.sudouser.PublicKey
import com.sudoplatform.sudouser.SudoUserClient
import com.sudoplatform.sudovirtualcards.BaseTests
import com.sudoplatform.sudovirtualcards.graphql.CallbackHolder
import com.sudoplatform.sudovirtualcards.graphql.CreatePublicKeyForVirtualCardsMutation
import com.sudoplatform.sudovirtualcards.graphql.GetPublicKeyForVirtualCardsQuery
import com.sudoplatform.sudovirtualcards.graphql.type.CreatePublicKeyInput
import com.sudoplatform.sudovirtualcards.graphql.type.KeyFormat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.doReturn
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import timber.log.Timber
import io.kotlintest.shouldBe
import io.kotlintest.shouldNotBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import org.bouncycastle.util.encoders.Base64
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoMoreInteractions

/**
 * Test the operation of [DefaultPublicKeyService] under exceptional conditions using mocks.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class PublicKeyServiceRoboTest : BaseTests() {

    private val keyRingServiceName = "sudo-virtual-cards"
    private val owner = "mockSubject"

    private val mockUserClient by before {
        mock<SudoUserClient>().stub {
            on { getSubject() } doReturn owner
        }
    }

    private val mockDeviceKeyManager by before {
        mock<DeviceKeyManager>()
    }

    private val mockAppSyncClient by before {
        mock<AWSAppSyncClient>()
    }

    private val publicKeyService by before {
        DefaultPublicKeyService(
            keyRingServiceName = keyRingServiceName,
            userClient = mockUserClient,
            deviceKeyManager = mockDeviceKeyManager,
            appSyncClient = mockAppSyncClient,
            logger = mock<Logger>()
        )
    }

    private val publicKey = "publicKey".toByteArray()
    private val deviceKeyPair = DeviceKey(
        keyId = "keyId",
        publicKey = publicKey,
    )

    private val queryResult by before {
        GetPublicKeyForVirtualCardsQuery.GetPublicKeyForVirtualCards(
            "typename",
            "owner-keyId",
            "keyId",
            "keyRingId",
            "algoirithm",
            KeyFormat.RSA_PUBLIC_KEY,
            Base64.toBase64String(publicKey),
            "owner",
            1,
            1.0,
            1.0
        )
    }

    private val queryResponse by before {
        Response.builder<GetPublicKeyForVirtualCardsQuery.Data>(GetPublicKeyForVirtualCardsQuery("id", null))
            .data(GetPublicKeyForVirtualCardsQuery.Data(queryResult))
            .build()
    }

    private val nullQueryResponse by before {
        Response.builder<GetPublicKeyForVirtualCardsQuery.Data>(GetPublicKeyForVirtualCardsQuery("id", null))
            .data(GetPublicKeyForVirtualCardsQuery.Data(null))
            .build()
    }

    private val queryHolder = CallbackHolder<GetPublicKeyForVirtualCardsQuery.Data>()

    private val mutationResult by before {
        CreatePublicKeyForVirtualCardsMutation.CreatePublicKeyForVirtualCards(
            "typename",
            "owner-keyId",
            "keyId",
            "$keyRingServiceName.$owner",
            "algoirithm",
            KeyFormat.RSA_PUBLIC_KEY,
            Base64.toBase64String(publicKey),
            "owner",
            1,
            1.0,
            1.0
        )
    }

    private val mutationResponse by before {
        Response.builder<CreatePublicKeyForVirtualCardsMutation.Data>(
            CreatePublicKeyForVirtualCardsMutation(
                CreatePublicKeyInput.builder()
                    .keyId("keyId")
                    .keyRingId("$keyRingServiceName.$owner")
                    .algorithm("algorithm")
                    .publicKey(Base64.toBase64String(publicKey))
                    .build()

            )
        )
            .data(CreatePublicKeyForVirtualCardsMutation.Data(mutationResult))
            .build()
    }

    private val mutationHolder = CallbackHolder<CreatePublicKeyForVirtualCardsMutation.Data>()

    @Before
    fun init() {
        Timber.plant(Timber.DebugTree())
        queryHolder.callback = null
        mutationHolder.callback = null
    }

    @After
    fun fini() = runBlocking {
        Timber.uprootAll()

        verifyNoMoreInteractions(mockAppSyncClient, mockDeviceKeyManager, mockUserClient)
    }

    @Test
    fun `getCurrentKey() should return key if present in device key manager`() = runBlocking<Unit> {
        mockDeviceKeyManager.stub {
            on { getCurrentKey() } doReturn deviceKeyPair
        }

        publicKeyService.getCurrentKey() shouldBe PublicKey(
            keyId = deviceKeyPair.keyId,
            publicKey = deviceKeyPair.publicKey
        )

        verify(mockDeviceKeyManager).getCurrentKey()
    }

    @Test
    fun `getCurrentKey() should return null if no key present in device key manager`() = runBlocking<Unit> {
        mockDeviceKeyManager.stub {
            on { getCurrentKey() } doReturn null
        }

        publicKeyService.getCurrentKey() shouldBe null

        verify(mockDeviceKeyManager).getCurrentKey()
    }

    @Test
    fun `getCurrentRegisteredKey() should return key if present in key manager with key ring id from service`() = runBlocking<Unit> {
        queryHolder.callback shouldBe null

        mockDeviceKeyManager.stub {
            on { getCurrentKey() } doReturn deviceKeyPair
        }
        mockAppSyncClient.stub {
            on { query(any<GetPublicKeyForVirtualCardsQuery>()) } doReturn queryHolder.queryOperation
        }

        val deferredResult = async(Dispatchers.IO) {
            publicKeyService.getCurrentRegisteredKey()
        }

        deferredResult.start()

        delay(100)

        queryHolder.callback shouldNotBe null
        queryHolder.callback?.onResponse(queryResponse)

        val result = deferredResult.await()
        with(result) {
            publicKey shouldBe publicKey
            keyRingId shouldBe "keyRingId"
        }

        verify(mockDeviceKeyManager).getCurrentKey()
        verify(mockAppSyncClient).query(any<GetPublicKeyForVirtualCardsQuery>())
    }

    @Test
    fun `getCurrentRegisteredKey() should return key if present in key manager with cached key ring id`() = runBlocking<Unit> {
        queryHolder.callback shouldBe null

        mockDeviceKeyManager.stub {
            on { getCurrentKey() } doReturn deviceKeyPair
        }
        mockAppSyncClient.stub {
            on { query(any<GetPublicKeyForVirtualCardsQuery>()) } doReturn queryHolder.queryOperation
        }

        val deferredResult = async(Dispatchers.IO) {
            publicKeyService.getCurrentRegisteredKey()
            publicKeyService.getCurrentRegisteredKey()
        }

        deferredResult.start()

        delay(100)

        queryHolder.callback shouldNotBe null
        queryHolder.callback?.onResponse(queryResponse)

        val result = deferredResult.await()
        with(result) {
            publicKey shouldBe publicKey
            keyRingId shouldBe "keyRingId"
        }

        verify(mockDeviceKeyManager, times(2)).getCurrentKey()
        verify(mockAppSyncClient, times(1)).query(any<GetPublicKeyForVirtualCardsQuery>())
    }

    @Test
    fun `getCurrentRegisteredKey() should create key if not present in key manager`() = runBlocking<Unit> {
        queryHolder.callback shouldBe null
        mutationHolder.callback shouldBe null

        mockDeviceKeyManager.stub {
            on { getCurrentKey() } doReturn null
            on { generateNewCurrentKeyPair() } doReturn deviceKeyPair
        }
        mockAppSyncClient.stub {
            on { mutate(any<CreatePublicKeyForVirtualCardsMutation>()) } doReturn mutationHolder.mutationOperation
        }

        val deferredResult = async(Dispatchers.IO) {
            publicKeyService.getCurrentRegisteredKey()
        }

        deferredResult.start()

        delay(100)

        mutationHolder.callback shouldNotBe null
        mutationHolder.callback?.onResponse(mutationResponse)

        val result = deferredResult.await()
        with(result) {
            publicKey shouldBe publicKey
            keyRingId shouldBe "$keyRingServiceName.$owner"
        }

        verify(mockDeviceKeyManager).getCurrentKey()
        verify(mockDeviceKeyManager).generateNewCurrentKeyPair()

        val mutationCaptor = argumentCaptor<CreatePublicKeyForVirtualCardsMutation>()
        verify(mockAppSyncClient).mutate(mutationCaptor.capture())
        mutationCaptor.firstValue.variables().input().keyRingId() shouldBe "$keyRingServiceName.$owner"

        verify(mockUserClient).getSubject()
    }

    @Test
    fun `getCurrentRegisteredKey() should register key if present in key manager and not registered`() = runBlocking<Unit> {
        queryHolder.callback shouldBe null
        mutationHolder.callback shouldBe null

        mockDeviceKeyManager.stub {
            on { getCurrentKey() } doReturn deviceKeyPair
        }
        mockAppSyncClient.stub {
            on { query(any<GetPublicKeyForVirtualCardsQuery>()) } doReturn queryHolder.queryOperation
            on { mutate(any<CreatePublicKeyForVirtualCardsMutation>()) } doReturn mutationHolder.mutationOperation
        }

        val deferredResult = async(Dispatchers.IO) {
            publicKeyService.getCurrentRegisteredKey()
        }

        deferredResult.start()

        delay(100)
        queryHolder.callback shouldNotBe null
        queryHolder.callback?.onResponse(nullQueryResponse)

        delay(100)
        mutationHolder.callback shouldNotBe null
        mutationHolder.callback?.onResponse(mutationResponse)

        val result = deferredResult.await()
        with(result) {
            publicKey shouldBe publicKey
            keyRingId shouldBe "$keyRingServiceName.$owner"
        }

        verify(mockDeviceKeyManager).getCurrentKey()
        verify(mockAppSyncClient).query(any<GetPublicKeyForVirtualCardsQuery>())

        val mutationCaptor = argumentCaptor<CreatePublicKeyForVirtualCardsMutation>()
        verify(mockAppSyncClient).mutate(mutationCaptor.capture())
        mutationCaptor.firstValue.variables().input().keyRingId() shouldBe "$keyRingServiceName.$owner"

        verify(mockUserClient).getSubject()
    }
}
