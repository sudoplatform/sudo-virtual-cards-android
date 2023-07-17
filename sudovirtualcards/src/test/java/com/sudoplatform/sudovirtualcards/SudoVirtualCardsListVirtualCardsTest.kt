/*
 * Copyright © 2023 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards

import android.content.Context
import com.amazonaws.mobileconnectors.appsync.AWSAppSyncClient
import com.apollographql.apollo.api.Response
import com.apollographql.apollo.exception.ApolloHttpException
import com.sudoplatform.sudokeymanager.KeyManagerException
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
import com.sudoplatform.sudouser.SudoUserClient
import com.sudoplatform.sudovirtualcards.graphql.CallbackHolder
import com.sudoplatform.sudovirtualcards.graphql.ListCardsQuery
import com.sudoplatform.sudovirtualcards.graphql.fragment.SealedAddressAttribute
import com.sudoplatform.sudovirtualcards.graphql.fragment.SealedCard
import com.sudoplatform.sudovirtualcards.graphql.fragment.SealedCardWithLastTransaction
import com.sudoplatform.sudovirtualcards.graphql.fragment.SealedExpiryAttribute
import com.sudoplatform.sudovirtualcards.graphql.type.CardState
import com.sudoplatform.sudovirtualcards.types.CachePolicy
import com.sudoplatform.sudovirtualcards.types.ListAPIResult
import com.sudoplatform.sudovirtualcards.types.CardState as CardStateEntity
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
import org.junit.After
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.anyString
import java.net.HttpURLConnection
import java.util.Date
import java.util.concurrent.CancellationException

/**
 * Test the correct operation of [SudoVirtualCardsClient.listVirtualCards]
 * using mocks and spies.
 */
class SudoVirtualCardsListVirtualCardsTest : BaseTests() {

    private val billingAddress by before {
        SealedCard.BillingAddress(
            "BillingAddress",
            SealedCard.BillingAddress.Fragments(
                SealedAddressAttribute(
                    "SealedAddressAttribute",
                    mockSeal("addressLine1"),
                    mockSeal("addressLine2"),
                    mockSeal("city"),
                    mockSeal("state"),
                    mockSeal("postalCode"),
                    mockSeal("country")
                )
            )
        )
    }

    private val expiry by before {
        SealedCard.Expiry(
            "Expiry",
            SealedCard.Expiry.Fragments(
                SealedExpiryAttribute(
                    "SealedExpiryAttribute",
                    mockSeal("01"),
                    mockSeal("2020")
                )
            )
        )
    }

    private val queryResult by before {
        ListCardsQuery.ListCards(
            "ListCards",
            listOf(
                ListCardsQuery.Item(
                    "Item",
                    ListCardsQuery.Item.Fragments(
                        SealedCardWithLastTransaction(
                            "SealedCardWithLastTransaction",
                            null,
                            SealedCardWithLastTransaction.Fragments(
                                SealedCard(
                                    "SealedCard",
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
                                    null
                                )
                            )
                        )
                    )
                )
            ),
            null
        )
    }

    private val queryResponse by before {
        Response.builder<ListCardsQuery.Data>(ListCardsQuery(null, null))
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
        verifyNoMoreInteractions(mockContext, mockUserClient, mockKeyManager, mockAppSyncClient)
    }

    @Test
    fun `listVirtualCards() should return success result when no error present`() = runBlocking<Unit> {
        queryHolder.callback shouldBe null

        val deferredResult = async(Dispatchers.IO) {
            client.listVirtualCards()
        }
        deferredResult.start()

        delay(100L)
        queryHolder.callback shouldNotBe null
        queryHolder.callback?.onResponse(queryResponse)

        val listCard = deferredResult.await()
        listCard shouldNotBe null

        when (listCard) {
            is ListAPIResult.Success -> {
                listCard.result.items.isEmpty() shouldBe false
                listCard.result.items.size shouldBe 1
                listCard.result.nextToken shouldBe null

                with(listCard.result.items[0]) {
                    id shouldBe "id"
                    owners shouldNotBe null
                    owner shouldBe "owner"
                    version shouldBe 1
                    fundingSourceId shouldBe "fundingSourceId"
                    state shouldBe CardStateEntity.ISSUED
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
            }
            else -> { fail("Unexpected ListAPIResult") }
        }

        verify(mockAppSyncClient).query(any<ListCardsQuery>())
        verify(mockKeyManager, times(12)).decryptWithPrivateKey(anyString(), any(), any())
        verify(mockKeyManager, times(12)).decryptWithSymmetricKey(any<ByteArray>(), any<ByteArray>())
    }

    @Test
    fun `listVirtualCards() should return success result when populating nextToken`() = runBlocking<Unit> {
        queryHolder.callback shouldBe null

        val queryResultWithNextToken by before {
            ListCardsQuery.ListCards(
                "ListCards",
                listOf(
                    ListCardsQuery.Item(
                        "Item",
                        ListCardsQuery.Item.Fragments(
                            SealedCardWithLastTransaction(
                                "SealedCardWithLastTransaction",
                                null,
                                SealedCardWithLastTransaction.Fragments(
                                    SealedCard(
                                        "SealedCard",
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
                                        null
                                    )
                                )
                            )
                        )
                    )
                ),
                "dummyNextToken"
            )
        }

        val responseWithNextToken by before {
            Response.builder<ListCardsQuery.Data>(ListCardsQuery(1, "dummyNextToken"))
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

        val listCard = deferredResult.await()
        listCard shouldNotBe null

        when (listCard) {
            is ListAPIResult.Success -> {
                listCard.result.items.isEmpty() shouldBe false
                listCard.result.items.size shouldBe 1
                listCard.result.nextToken shouldBe "dummyNextToken"

                with(listCard.result.items[0]) {
                    id shouldBe "id"
                    owners shouldNotBe null
                    owner shouldBe "owner"
                    version shouldBe 1
                    fundingSourceId shouldBe "fundingSourceId"
                    state shouldBe CardStateEntity.ISSUED
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
            }
            else -> { fail("Unexpected ListAPIResult") }
        }

        verify(mockAppSyncClient).query(any<ListCardsQuery>())
        verify(mockKeyManager, times(12)).decryptWithPrivateKey(anyString(), any(), any())
        verify(mockKeyManager, times(12)).decryptWithSymmetricKey(any<ByteArray>(), any<ByteArray>())
    }

    @Test
    fun `listVirtualCards() should return success empty list result when query result data is empty`() = runBlocking<Unit> {
        queryHolder.callback shouldBe null

        val queryResultWithEmptyList by before {
            ListCardsQuery.ListCards(
                "typename",
                emptyList(),
                null
            )
        }

        val responseWithEmptyList by before {
            Response.builder<ListCardsQuery.Data>(ListCardsQuery(null, null))
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

        when (result) {
            is ListAPIResult.Success -> {
                result.result.items.isEmpty() shouldBe true
                result.result.items.size shouldBe 0
                result.result.nextToken shouldBe null
            }
            else -> { fail("Unexpected ListAPIResult") }
        }

        verify(mockAppSyncClient).query(any<ListCardsQuery>())
    }

    @Test
    fun `listVirtualCards() should return success empty list result when query response is null`() = runBlocking<Unit> {
        queryHolder.callback shouldBe null

        val nullQueryResponse by before {
            Response.builder<ListCardsQuery.Data>(ListCardsQuery(null, null))
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

        when (result) {
            is ListAPIResult.Success -> {
                result.result.items.isEmpty() shouldBe true
                result.result.items.size shouldBe 0
                result.result.nextToken shouldBe null
            }
            else -> { fail("Unexpected ListAPIResult") }
        }

        verify(mockAppSyncClient).query(any<ListCardsQuery>())
    }

    @Test
    fun `listVirtualCards() should return partial results when unsealing fails`() = runBlocking<Unit> {
        mockKeyManager.stub {
            on { decryptWithPrivateKey(anyString(), any(), any()) } doThrow KeyManagerException("KeyManagerException")
        }

        val deferredResult = async(Dispatchers.IO) {
            client.listVirtualCards()
        }
        deferredResult.start()

        delay(100L)
        queryHolder.callback shouldNotBe null
        queryHolder.callback?.onResponse(queryResponse)

        val listCard = deferredResult.await()
        listCard shouldNotBe null

        when (listCard) {
            is ListAPIResult.Partial -> {
                listCard.result.items.isEmpty() shouldBe true
                listCard.result.items.size shouldBe 0
                listCard.result.failed.isEmpty() shouldBe false
                listCard.result.failed.size shouldBe 1
                listCard.result.nextToken shouldBe null

                with(listCard.result.failed[0].partial) {
                    id shouldBe "id"
                    owners shouldNotBe null
                    owner shouldBe "owner"
                    version shouldBe 1
                    fundingSourceId shouldBe "fundingSourceId"
                    state shouldBe CardStateEntity.ISSUED
                    last4 shouldBe "last4"
                    currency shouldBe "currency"
                    activeTo shouldNotBe null
                    cancelledAt shouldBe null
                    createdAt shouldBe Date(1L)
                    updatedAt shouldBe Date(1L)
                }
            }
            else -> { fail("Unexpected ListAPIResult") }
        }

        verify(mockAppSyncClient).query(any<ListCardsQuery>())
        verify(mockKeyManager).decryptWithPrivateKey(anyString(), any(), any())
    }

    @Test
    fun `listVirtualCards() should not return duplicate cards with matching identifiers`() = runBlocking<Unit> {
        val queryResultItem1 = createMockVirtualCard("id1")
        val queryResultItem2 = createMockVirtualCard("id2")
        val queryResultItem3 = createMockVirtualCard("id1")

        val queryResult by before {
            ListCardsQuery.ListCards(
                "ListCards",
                listOf(queryResultItem1, queryResultItem2, queryResultItem3),
                null
            )
        }

        val queryResponse by before {
            Response.builder<ListCardsQuery.Data>(ListCardsQuery(null, null))
                .data(ListCardsQuery.Data(queryResult))
                .build()
        }

        queryHolder.callback shouldBe null

        val deferredResult = async(Dispatchers.IO) {
            client.listVirtualCards()
        }
        deferredResult.start()

        delay(100L)
        queryHolder.callback shouldNotBe null
        queryHolder.callback?.onResponse(queryResponse)

        val listCard = deferredResult.await()
        listCard shouldNotBe null

        when (listCard) {
            is ListAPIResult.Success -> {
                listCard.result.items.isEmpty() shouldBe false
                listCard.result.items.size shouldBe 2
                listCard.result.nextToken shouldBe null
            }
            else -> {
                fail("Unexpected ListAPIResult")
            }
        }

        verify(mockAppSyncClient).query(any<ListCardsQuery>())
        verify(mockKeyManager, times(36)).decryptWithPrivateKey(anyString(), any(), any())
        verify(mockKeyManager, times(36)).decryptWithSymmetricKey(any<ByteArray>(), any<ByteArray>())
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

    private fun createMockVirtualCard(id: String): ListCardsQuery.Item {
        return ListCardsQuery.Item(
            "Item",
            ListCardsQuery.Item.Fragments(
                SealedCardWithLastTransaction(
                    "SealedCardWithLastTransaction",
                    null,
                    SealedCardWithLastTransaction.Fragments(
                        SealedCard(
                            "SealedCard",
                            id,
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
                            null
                        )
                    )
                )
            )
        )
    }
}
