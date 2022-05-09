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
import org.mockito.kotlin.check
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
import com.sudoplatform.sudovirtualcards.graphql.ListTransactionsByCardIdQuery
import com.sudoplatform.sudovirtualcards.graphql.type.SortOrder as SortOrderEntity
import com.sudoplatform.sudovirtualcards.graphql.type.TransactionType
import com.sudoplatform.sudovirtualcards.types.CachePolicy
import com.sudoplatform.sudovirtualcards.types.DateRange
import com.sudoplatform.sudovirtualcards.types.SortOrder
import com.sudoplatform.sudovirtualcards.types.Transaction
import com.sudoplatform.sudovirtualcards.types.transformers.Unsealer
import io.kotlintest.matchers.doubles.shouldBeLessThan
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

/**
 * Test the correct operation of [SudoVirtualCardsClient.listTransactionsByCardId] using mocks
 * and spies.
 *
 * @since 2020-07-16
 */
class SudoVirtualCardsListTransactionsByCardIdTest : BaseTests() {

    private fun mockSeal(value: String): String {
        val valueBytes = value.toByteArray()
        val data = ByteArray(256)
        valueBytes.copyInto(data)
        return String(Base64.encode(data), Charsets.UTF_8)
    }

    private val queryResultItem by before {
        ListTransactionsByCardIdQuery.Item(
            "typename",
            "id",
            "owner",
            1,
            1.0,
            1.0,
            1.0,
            "algorithm",
            "keyId",
            mockSeal("cardId"),
            "sequenceId",
            TransactionType.COMPLETE,
            mockSeal("sealedTime"),
            ListTransactionsByCardIdQuery.BilledAmount("typename", mockSeal("USD"), mockSeal("billedAmount")),
            ListTransactionsByCardIdQuery.TransactedAmount("typename", mockSeal("USD"), mockSeal("transactedAmount")),
            mockSeal("description"),
            null,
            listOf(
                ListTransactionsByCardIdQuery.Detail(
                    "typename",
                    ListTransactionsByCardIdQuery.VirtualCardAmount("typename", mockSeal("USD"), mockSeal("cardAmount")),
                    ListTransactionsByCardIdQuery.Markup("typename", mockSeal("1"), mockSeal("2"), mockSeal("3")),
                    ListTransactionsByCardIdQuery.MarkupAmount("typename", mockSeal("USD"), mockSeal("markupAmount")),
                    ListTransactionsByCardIdQuery.FundingSourceAmount("typename", mockSeal("USD"), mockSeal("funds")),
                    "fundingSourceId",
                    mockSeal("description")
                )
            )
        )
    }

    private val queryResult by before {
        ListTransactionsByCardIdQuery.ListTransactionsByCardId("typename", listOf(queryResultItem), null)
    }

    private val queryResponse by before {
        Response.builder<ListTransactionsByCardIdQuery.Data>(ListTransactionsByCardIdQuery("cardId", null, null, null, null, null))
            .data(ListTransactionsByCardIdQuery.Data(queryResult))
            .build()
    }

    private val queryHolder = CallbackHolder<ListTransactionsByCardIdQuery.Data>()

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
            on { query(any<ListTransactionsByCardIdQuery>()) } doReturn queryHolder.queryOperation
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
    fun `listTransactionsByCardId() should return results when no error present`() = runBlocking<Unit> {

        queryHolder.callback shouldBe null

        val deferredResult = async(Dispatchers.IO) {
            client.listTransactionsByCardId(
                cardId = "cardId",
                limit = 1,
                nextToken = null,
                dateRange = DateRange(Date(), Date()),
                sortOrder = SortOrder.DESC
            )
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

        checkTransaction(result.items[0])

        verify(mockAppSyncClient)
            .query<ListTransactionsByCardIdQuery.Data, ListTransactionsByCardIdQuery, ListTransactionsByCardIdQuery.Variables>(
                check {
                    it.variables().cardId() shouldBe "cardId"
                    it.variables().limit() shouldBe 1
                    it.variables().nextToken() shouldBe null
                    it.variables().dateRange()?.startDateEpochMs()?.shouldBeLessThan(Date().time.toDouble())
                    it.variables().dateRange()?.endDateEpochMs()?.shouldBeLessThan(Date().time.toDouble())
                    it.variables().sortOrder() shouldBe SortOrderEntity.DESC
                }
            )
        verify(mockKeyManager, times(16)).decryptWithPrivateKey(anyString(), any(), any())
        verify(mockKeyManager, times(16)).decryptWithSymmetricKey(any<ByteArray>(), any<ByteArray>())
    }

    @Test
    fun `listTransactionsByCardId() should return results using default inputs when no error present`() = runBlocking<Unit> {

        queryHolder.callback shouldBe null

        val deferredResult = async(Dispatchers.IO) {
            client.listTransactionsByCardId("cardId")
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

        checkTransaction(result.items[0])

        verify(mockAppSyncClient)
            .query<ListTransactionsByCardIdQuery.Data, ListTransactionsByCardIdQuery, ListTransactionsByCardIdQuery.Variables>(
                check {
                    it.variables().cardId() shouldBe "cardId"
                    it.variables().limit() shouldBe 100
                    it.variables().nextToken() shouldBe null
                    it.variables().dateRange() shouldBe null
                    it.variables().sortOrder() shouldBe SortOrderEntity.DESC
                }
            )
        verify(mockKeyManager, times(16)).decryptWithPrivateKey(anyString(), any(), any())
        verify(mockKeyManager, times(16)).decryptWithSymmetricKey(any<ByteArray>(), any<ByteArray>())
    }

    private fun checkTransaction(transaction: Transaction) {
        with(transaction) {
            id shouldBe "id"
            owner shouldBe "owner"
            version shouldBe 1
            cardId shouldNotBe null
            type shouldBe Transaction.TransactionType.COMPLETE
            description.isBlank() shouldBe false
            sequenceId shouldBe "sequenceId"
            transactedAt shouldNotBe null
            billedAmount.currency.isBlank() shouldBe false
            transactedAmount.currency.isBlank() shouldBe false
            declineReason shouldBe null
            createdAt shouldBe Date(1L)
            updatedAt shouldBe Date(1L)
            details.isEmpty() shouldBe false
            details[0].fundingSourceId shouldBe "fundingSourceId"
            details[0].description.isBlank() shouldBe false
        }
    }

    @Test
    fun `listTransactionsByCardId() should return results when populating nextToken`() = runBlocking<Unit> {

        queryHolder.callback shouldBe null

        val queryResultWithNextToken by before {
            ListTransactionsByCardIdQuery.ListTransactionsByCardId("typename", listOf(queryResultItem), "dummyNextToken")
        }

        val responseWithNextToken by before {
            Response.builder<ListTransactionsByCardIdQuery.Data>(
                ListTransactionsByCardIdQuery(
                    "cardId",
                    null,
                    1,
                    "dummyNextToken",
                    null,
                    null,
                )
            )
                .data(ListTransactionsByCardIdQuery.Data(queryResultWithNextToken))
                .build()
        }

        val deferredResult = async(Dispatchers.IO) {
            client.listTransactionsByCardId("cardId", 1, "dummyNextToken", CachePolicy.REMOTE_ONLY)
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

        checkTransaction(result.items[0])

        verify(mockAppSyncClient)
            .query<ListTransactionsByCardIdQuery.Data, ListTransactionsByCardIdQuery, ListTransactionsByCardIdQuery.Variables>(
                check {
                    it.variables().cardId() shouldBe "cardId"
                    it.variables().limit() shouldBe 1
                    it.variables().nextToken() shouldBe "dummyNextToken"
                    it.variables().dateRange() shouldBe null
                    it.variables().sortOrder() shouldBe SortOrderEntity.DESC
                }
            )
        verify(mockKeyManager, times(16)).decryptWithPrivateKey(anyString(), any(), any())
        verify(mockKeyManager, times(16)).decryptWithSymmetricKey(any<ByteArray>(), any<ByteArray>())
    }

    @Test
    fun `listTransactionsByCardId() should return empty list output when query result data is empty`() = runBlocking<Unit> {

        queryHolder.callback shouldBe null

        val queryResultWithEmptyList by before {
            ListTransactionsByCardIdQuery.ListTransactionsByCardId(
                "typename",
                emptyList(),
                null
            )
        }

        val responseWithEmptyList by before {
            Response.builder<ListTransactionsByCardIdQuery.Data>(ListTransactionsByCardIdQuery("cardId", null, null, null, null, null))
                .data(ListTransactionsByCardIdQuery.Data(queryResultWithEmptyList))
                .build()
        }

        val deferredResult = async(Dispatchers.IO) {
            client.listTransactionsByCardId("cardId")
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

        verify(mockAppSyncClient)
            .query<ListTransactionsByCardIdQuery.Data, ListTransactionsByCardIdQuery, ListTransactionsByCardIdQuery.Variables>(
                check {
                    it.variables().cardId() shouldBe "cardId"
                    it.variables().limit() shouldBe 100
                    it.variables().nextToken() shouldBe null
                    it.variables().dateRange() shouldBe null
                    it.variables().sortOrder() shouldBe SortOrderEntity.DESC
                }
            )
    }

    @Test
    fun `listTransactionsByCardId() should return empty list output when query result data is null`() = runBlocking<Unit> {

        queryHolder.callback shouldBe null

        val responseWithNullData by before {
            Response.builder<ListTransactionsByCardIdQuery.Data>(ListTransactionsByCardIdQuery("cardId", null, null, null, null, null))
                .data(ListTransactionsByCardIdQuery.Data(null))
                .build()
        }

        val deferredResult = async(Dispatchers.IO) {
            client.listTransactionsByCardId("cardId")
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

        verify(mockAppSyncClient)
            .query<ListTransactionsByCardIdQuery.Data, ListTransactionsByCardIdQuery, ListTransactionsByCardIdQuery.Variables>(
                check {
                    it.variables().cardId() shouldBe "cardId"
                    it.variables().limit() shouldBe 100
                    it.variables().nextToken() shouldBe null
                    it.variables().dateRange() shouldBe null
                    it.variables().sortOrder() shouldBe SortOrderEntity.DESC
                }
            )
    }

    @Test
    fun `listTransactionsByCardId() should return empty list output when query response is null`() = runBlocking<Unit> {

        queryHolder.callback shouldBe null

        val nullQueryResponse by before {
            Response.builder<ListTransactionsByCardIdQuery.Data>(ListTransactionsByCardIdQuery("cardId", null, null, null, null, null))
                .data(null)
                .build()
        }

        val deferredResult = async(Dispatchers.IO) {
            client.listTransactionsByCardId("cardId")
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

        verify(mockAppSyncClient)
            .query<ListTransactionsByCardIdQuery.Data, ListTransactionsByCardIdQuery, ListTransactionsByCardIdQuery.Variables>(
                check {
                    it.variables().cardId() shouldBe "cardId"
                    it.variables().limit() shouldBe 100
                    it.variables().nextToken() shouldBe null
                    it.variables().dateRange() shouldBe null
                    it.variables().sortOrder() shouldBe SortOrderEntity.DESC
                }
            )
    }

    @Test
    fun `listTransactionsByCardId() should throw when unsealing fails`() = runBlocking<Unit> {

        mockAppSyncClient.stub {
            on { query(any<ListTransactionsByCardIdQuery>()) } doThrow
                Unsealer.UnsealerException.SealedDataTooShortException("Mock Unsealer Exception")
        }

        shouldThrow<SudoVirtualCardsClient.TransactionException.UnsealingException> {
            client.listTransactionsByCardId("cardId")
        }

        verify(mockAppSyncClient)
            .query<ListTransactionsByCardIdQuery.Data, ListTransactionsByCardIdQuery, ListTransactionsByCardIdQuery.Variables>(
                check {
                    it.variables().cardId() shouldBe "cardId"
                    it.variables().limit() shouldBe 100
                    it.variables().nextToken() shouldBe null
                    it.variables().dateRange() shouldBe null
                    it.variables().sortOrder() shouldBe SortOrderEntity.DESC
                }
            )
    }

    @Test
    fun `listTransactionsByCardId() should throw when http error occurs`() = runBlocking<Unit> {

        queryHolder.callback shouldBe null

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.TransactionException.FailedException> {
                client.listTransactionsByCardId("cardId")
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

        verify(mockAppSyncClient)
            .query<ListTransactionsByCardIdQuery.Data, ListTransactionsByCardIdQuery, ListTransactionsByCardIdQuery.Variables>(
                check {
                    it.variables().cardId() shouldBe "cardId"
                    it.variables().limit() shouldBe 100
                    it.variables().nextToken() shouldBe null
                    it.variables().dateRange() shouldBe null
                    it.variables().sortOrder() shouldBe SortOrderEntity.DESC
                }
            )
    }

    @Test
    fun `listTransactionsByCardId() should throw when unknown error occurs()`() = runBlocking<Unit> {

        queryHolder.callback shouldBe null

        mockAppSyncClient.stub {
            on { query(any<ListTransactionsByCardIdQuery>()) } doThrow RuntimeException("Mock Runtime Exception")
        }

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.TransactionException.UnknownException> {
                client.listTransactionsByCardId("cardId")
            }
        }
        deferredResult.start()

        delay(100L)
        deferredResult.await()

        verify(mockAppSyncClient)
            .query<ListTransactionsByCardIdQuery.Data, ListTransactionsByCardIdQuery, ListTransactionsByCardIdQuery.Variables>(
                check {
                    it.variables().cardId() shouldBe "cardId"
                    it.variables().limit() shouldBe 100
                    it.variables().nextToken() shouldBe null
                    it.variables().dateRange() shouldBe null
                    it.variables().sortOrder() shouldBe SortOrderEntity.DESC
                }
            )
    }

    @Test
    fun `listTransactionsByCardId() should not block coroutine cancellation exception`() = runBlocking<Unit> {

        mockAppSyncClient.stub {
            on { query(any<ListTransactionsByCardIdQuery>()) } doThrow CancellationException("Mock Cancellation Exception")
        }

        shouldThrow<CancellationException> {
            client.listTransactionsByCardId("cardId")
        }

        verify(mockAppSyncClient)
            .query<ListTransactionsByCardIdQuery.Data, ListTransactionsByCardIdQuery, ListTransactionsByCardIdQuery.Variables>(
                check {
                    it.variables().cardId() shouldBe "cardId"
                    it.variables().limit() shouldBe 100
                    it.variables().nextToken() shouldBe null
                    it.variables().dateRange() shouldBe null
                    it.variables().sortOrder() shouldBe SortOrderEntity.DESC
                }
            )
    }
}
