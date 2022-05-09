/*
 * Copyright © 2022 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards

import android.content.Context
import com.amazonaws.mobileconnectors.appsync.AWSAppSyncClient
import com.apollographql.apollo.api.Response
import com.apollographql.apollo.exception.ApolloHttpException
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.stub
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoMoreInteractions
import com.sudoplatform.sudokeymanager.KeyManagerInterface
import com.sudoplatform.sudologging.Logger
import com.sudoplatform.sudoprofiles.SudoProfilesClient
import com.sudoplatform.sudouser.SudoUserClient
import com.sudoplatform.sudovirtualcards.graphql.CallbackHolder
import com.sudoplatform.sudovirtualcards.graphql.ListCardsQuery
import com.sudoplatform.sudovirtualcards.graphql.type.CardState
import com.sudoplatform.sudovirtualcards.types.CachePolicy
import com.sudoplatform.sudovirtualcards.types.VirtualCard
import com.sudoplatform.sudovirtualcards.types.transformers.Unsealer
import io.kotlintest.shouldBe
import io.kotlintest.shouldNotBe
import io.kotlintest.shouldThrow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.ResponseBody.Companion.toResponseBody
import org.bouncycastle.util.encoders.Base64
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.anyString
import java.net.HttpURLConnection
import java.util.Date
import java.util.concurrent.CancellationException
import kotlin.text.Charsets.UTF_8

/**
 * Test the correct operation of [SudoVirtualCardsClient.listVirtualCards]
 * using mocks and spies.
 */
class SudoVirtualCardsListVirtualCardsTest : BaseTests() {

    private fun mockSeal(value: String): String {
        val valueBytes = value.toByteArray()
        val data = ByteArray(256)
        valueBytes.copyInto(data)
        return String(Base64.encode(data), UTF_8)
    }

    private val billingAddress by before {
        ListCardsQuery.BillingAddress(
            "typename",
            mockSeal("addressLine1"),
            mockSeal("addressLine2"),
            mockSeal("city"),
            mockSeal("state"),
            mockSeal("postalCode"),
            mockSeal("country")
        )
    }

    private val expiry by before {
        ListCardsQuery.Expiry(
            "typename",
            mockSeal("01"),
            mockSeal("2020")
        )
    }

    private val queryResult by before {
        ListCardsQuery.ListCards(
            "typename",
            listOf(
                ListCardsQuery.Item(
                    "typename",
                    "id",
                    "owner",
                    1,
                    1.0,
                    1.0,
                    "algorithm",
                    "keyId",
                    "keyRingId",
                    emptyList(),
                    "fundingSourceId",
                    "currency",
                    CardState.ISSUED,
                    1.0,
                    null,
                    "last4",
                    mockSeal("cardHolder"),
                    mockSeal("alias"),
                    mockSeal("pan"),
                    mockSeal("csc"),
                    billingAddress,
                    expiry,
                    null,
                    null
                ),
            ),
            null
        )
    }

    private val queryResponse by before {
        Response.builder<ListCardsQuery.Data>(ListCardsQuery(null, null, null))
            .data(ListCardsQuery.Data(queryResult))
            .build()
    }

    private val queryHolder = CallbackHolder<ListCardsQuery.Data>()

    private val mockContext by before {
        mock<Context>()
    }

    private val mockUserClient by before {
        mock<SudoUserClient>().stub {
            on { getSubject() } doReturn "subject"
        }
    }

    private val mockSudoClient by before {
        mock<SudoProfilesClient>()
    }

    private val mockAppSyncClient by before {
        mock<AWSAppSyncClient>().stub {
            on { query(any<ListCardsQuery>()) } doReturn queryHolder.queryOperation
        }
    }

    private val mockKeyManager by before {
        mock<KeyManagerInterface>().stub {
            on { getPassword(anyString()) } doReturn ByteArray(42)
            on { getPublicKeyData(anyString()) } doReturn ByteArray(42)
            on { getPrivateKeyData(anyString()) } doReturn ByteArray(42)
            on { decryptWithPrivateKey(anyString(), any(), any()) } doReturn ByteArray(42)
            on { decryptWithSymmetricKey(any<ByteArray>(), any<ByteArray>()) } doReturn "42".toByteArray()
        }
    }

    private val client by before {
        SudoVirtualCardsClient.builder()
            .setContext(mockContext)
            .setSudoUserClient(mockUserClient)
            .setSudoProfilesClient(mockSudoClient)
            .setAppSyncClient(mockAppSyncClient)
            .setKeyManager(mockKeyManager)
            .setLogger(mock<Logger>())
            .build()
    }

    @Before
    fun init() {
        queryHolder.callback = null
    }

    @After
    fun fini() {
        verifyNoMoreInteractions(mockContext, mockUserClient, mockSudoClient, mockKeyManager, mockAppSyncClient)
    }

    @Test
    fun `listVirtualCards() should return results when no error present`() = runBlocking<Unit> {

        queryHolder.callback shouldBe null

        val deferredResult = async(Dispatchers.IO) {
            client.listVirtualCards()
        }
        deferredResult.start()

        delay(100L)
        queryHolder.callback shouldNotBe null
        queryHolder.callback?.onResponse(queryResponse)

        val result = deferredResult.await()
        result shouldNotBe null
        result.items.isEmpty() shouldBe false
        result.items.size shouldBe 1
        result.nextToken shouldBe null

        with(result.items[0]) {
            id shouldBe "id"
            owners shouldNotBe null
            owner shouldBe "owner"
            version shouldBe 1
            fundingSourceId shouldBe "fundingSourceId"
            state shouldBe VirtualCard.State.ISSUED
            cardHolder shouldNotBe null
            alias shouldNotBe null
            last4 shouldBe "last4"
            cardNumber shouldNotBe null
            securityCode shouldNotBe null
            billingAddress shouldNotBe null
            expiry shouldNotBe null
            currency shouldBe "currency"
            activeTo shouldNotBe null
            cancelledAt shouldBe null
            createdAt shouldBe Date(1L)
            updatedAt shouldBe Date(1L)
        }

        verify(mockAppSyncClient).query(any<ListCardsQuery>())
        verify(mockKeyManager, times(12)).decryptWithPrivateKey(anyString(), any(), any())
        verify(mockKeyManager, times(12)).decryptWithSymmetricKey(any<ByteArray>(), any<ByteArray>())
    }

    @Test
    fun `listVirtualCards() should return results when populating nextToken`() = runBlocking<Unit> {

        queryHolder.callback shouldBe null

        val queryResultWithNextToken by before {
            ListCardsQuery.ListCards(
                "typename",
                listOf(
                    ListCardsQuery.Item(
                        "typename",
                        "id",
                        "owner",
                        1,
                        1.0,
                        1.0,
                        "algorithm",
                        "keyId",
                        "keyRingId",
                        emptyList(),
                        "fundingSourceId",
                        "currency",
                        CardState.ISSUED,
                        1.0,
                        null,
                        "last4",
                        mockSeal("cardHolder"),
                        mockSeal("alias"),
                        mockSeal("pan"),
                        mockSeal("csc"),
                        billingAddress,
                        expiry,
                        null,
                        null
                    )
                ),
                "dummyNextToken"
            )
        }

        val responseWithNextToken by before {
            Response.builder<ListCardsQuery.Data>(ListCardsQuery(null, 1, "dummyNextToken"))
                .data(ListCardsQuery.Data(queryResultWithNextToken))
                .build()
        }

        val deferredResult = async(Dispatchers.IO) {
            client.listVirtualCards(1, "dummyNextToken", CachePolicy.REMOTE_ONLY)
        }
        deferredResult.start()

        delay(100L)
        queryHolder.callback shouldNotBe null
        queryHolder.callback?.onResponse(responseWithNextToken)

        val result = deferredResult.await()
        result shouldNotBe null
        result.items.isEmpty() shouldBe false
        result.items.size shouldBe 1
        result.nextToken shouldBe "dummyNextToken"

        with(result.items[0]) {
            id shouldBe "id"
            owners shouldNotBe null
            owner shouldBe "owner"
            version shouldBe 1
            fundingSourceId shouldBe "fundingSourceId"
            state shouldBe VirtualCard.State.ISSUED
            cardHolder shouldNotBe null
            alias shouldNotBe null
            last4 shouldBe "last4"
            cardNumber shouldNotBe null
            securityCode shouldNotBe null
            billingAddress shouldNotBe null
            expiry shouldNotBe null
            currency shouldBe "currency"
            activeTo shouldNotBe null
            cancelledAt shouldBe null
            createdAt shouldBe Date(1L)
            updatedAt shouldBe Date(1L)
        }

        verify(mockAppSyncClient).query(any<ListCardsQuery>())
        verify(mockKeyManager, times(12)).decryptWithPrivateKey(anyString(), any(), any())
        verify(mockKeyManager, times(12)).decryptWithSymmetricKey(any<ByteArray>(), any<ByteArray>())
    }

    @Test
    fun `listVirtualCards() should return empty list output when query result data is empty`() = runBlocking<Unit> {

        queryHolder.callback shouldBe null

        val queryResultWithEmptyList by before {
            ListCardsQuery.ListCards(
                "typename",
                emptyList(),
                null
            )
        }

        val responseWithEmptyList by before {
            Response.builder<ListCardsQuery.Data>(ListCardsQuery(null, null, null))
                .data(ListCardsQuery.Data(queryResultWithEmptyList))
                .build()
        }

        val deferredResult = async(Dispatchers.IO) {
            client.listVirtualCards()
        }
        deferredResult.start()

        delay(100L)
        queryHolder.callback shouldNotBe null
        queryHolder.callback?.onResponse(responseWithEmptyList)

        val result = deferredResult.await()
        result shouldNotBe null
        result.items.isEmpty() shouldBe true
        result.items.size shouldBe 0
        result.nextToken shouldBe null

        verify(mockAppSyncClient).query(any<ListCardsQuery>())
    }

    @Test
    fun `listVirtualCards() should return empty list output when query result data is null`() = runBlocking<Unit> {

        queryHolder.callback shouldBe null

        val responseWithNullData by before {
            Response.builder<ListCardsQuery.Data>(ListCardsQuery(null, null, null))
                .data(ListCardsQuery.Data(null))
                .build()
        }

        val deferredResult = async(Dispatchers.IO) {
            client.listVirtualCards()
        }
        deferredResult.start()

        delay(100L)
        queryHolder.callback shouldNotBe null
        queryHolder.callback?.onResponse(responseWithNullData)

        val result = deferredResult.await()
        result shouldNotBe null
        result.items.isEmpty() shouldBe true
        result.items.size shouldBe 0
        result.nextToken shouldBe null

        verify(mockAppSyncClient).query(any<ListCardsQuery>())
    }

    @Test
    fun `listVirtualCards() should return empty list output when query response is null`() = runBlocking<Unit> {

        queryHolder.callback shouldBe null

        val nullQueryResponse by before {
            Response.builder<ListCardsQuery.Data>(ListCardsQuery(null, null, null))
                .data(null)
                .build()
        }

        val deferredResult = async(Dispatchers.IO) {
            client.listVirtualCards()
        }
        deferredResult.start()

        delay(100L)
        queryHolder.callback shouldNotBe null
        queryHolder.callback?.onResponse(nullQueryResponse)

        val result = deferredResult.await()
        result shouldNotBe null
        result.items.isEmpty() shouldBe true
        result.items.size shouldBe 0
        result.nextToken shouldBe null

        verify(mockAppSyncClient).query(any<ListCardsQuery>())
    }

    @Test
    fun `listVirtualCards() should throw when unsealing fails`() = runBlocking<Unit> {

        mockAppSyncClient.stub {
            on { query(any<ListCardsQuery>()) } doThrow Unsealer.UnsealerException.SealedDataTooShortException("Mock Unsealer Exception")
        }

        shouldThrow<SudoVirtualCardsClient.VirtualCardException.UnsealingException> {
            client.listVirtualCards()
        }

        verify(mockAppSyncClient).query(any<ListCardsQuery>())
    }

    @Test
    fun `listVirtualCards() should throw when http error occurs`() = runBlocking<Unit> {

        queryHolder.callback shouldBe null

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.VirtualCardException.FailedException> {
                client.listVirtualCards()
            }
        }
        deferredResult.start()
        delay(100L)

        val request = Request.Builder()
            .get()
            .url("http://www.smh.com.au")
            .build()
        val responseBody = "{}".toResponseBody("application/json; charset=utf-8".toMediaType())
        val forbidden = okhttp3.Response.Builder()
            .protocol(Protocol.HTTP_1_1)
            .code(HttpURLConnection.HTTP_FORBIDDEN)
            .request(request)
            .message("Forbidden")
            .body(responseBody)
            .build()

        queryHolder.callback shouldNotBe null
        queryHolder.callback?.onHttpError(ApolloHttpException(forbidden))

        deferredResult.await()

        verify(mockAppSyncClient).query(any<ListCardsQuery>())
    }

    @Test
    fun `listVirtualCards() should throw when unknown error occurs()`() = runBlocking<Unit> {

        queryHolder.callback shouldBe null

        mockAppSyncClient.stub {
            on { query(any<ListCardsQuery>()) } doThrow RuntimeException("Mock Runtime Exception")
        }

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.VirtualCardException.UnknownException> {
                client.listVirtualCards()
            }
        }
        deferredResult.start()

        delay(100L)
        deferredResult.await()

        verify(mockAppSyncClient).query(any<ListCardsQuery>())
    }

    @Test
    fun `listVirtualCards() should not block coroutine cancellation exception`() = runBlocking<Unit> {

        mockAppSyncClient.stub {
            on { query(any<ListCardsQuery>()) } doThrow CancellationException("Mock Cancellation Exception")
        }

        shouldThrow<CancellationException> {
            client.listVirtualCards()
        }

        verify(mockAppSyncClient).query(any<ListCardsQuery>())
    }
}
