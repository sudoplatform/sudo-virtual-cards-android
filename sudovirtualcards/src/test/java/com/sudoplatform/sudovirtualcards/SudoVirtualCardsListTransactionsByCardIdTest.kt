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
import org.mockito.kotlin.check
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.stub
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoMoreInteractions
import com.sudoplatform.sudokeymanager.KeyManagerInterface
import com.sudoplatform.sudouser.SudoUserClient
import com.sudoplatform.sudovirtualcards.graphql.CallbackHolder
import com.sudoplatform.sudovirtualcards.graphql.ListTransactionsByCardIdQuery
import com.sudoplatform.sudovirtualcards.graphql.fragment.SealedCurrencyAmountAttribute
import com.sudoplatform.sudovirtualcards.graphql.fragment.SealedMarkupAttribute
import com.sudoplatform.sudovirtualcards.graphql.fragment.SealedTransaction
import com.sudoplatform.sudovirtualcards.graphql.fragment.SealedTransactionDetailChargeAttribute
import com.sudoplatform.sudovirtualcards.graphql.type.SortOrder as SortOrderEntity
import com.sudoplatform.sudovirtualcards.graphql.type.TransactionType
import com.sudoplatform.sudovirtualcards.types.CachePolicy
import com.sudoplatform.sudovirtualcards.types.DateRange
import com.sudoplatform.sudovirtualcards.types.ListAPIResult
import com.sudoplatform.sudovirtualcards.types.SortOrder
import com.sudoplatform.sudovirtualcards.types.Transaction
import com.sudoplatform.sudovirtualcards.types.TransactionType as TransactionTypeEntity
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
import org.junit.After
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.anyString
import java.net.HttpURLConnection
import java.util.Date
import java.util.concurrent.CancellationException

/**
 * Test the correct operation of [SudoVirtualCardsClient.listTransactionsByCardId] using mocks
 * and spies.
 */
class SudoVirtualCardsListTransactionsByCardIdTest : BaseTests() {

    private val billedAmount by before {
        SealedTransaction.BilledAmount(
            "BilledAmount",
            SealedTransaction.BilledAmount.Fragments(
                SealedCurrencyAmountAttribute(
                    "CurrencyAmount",
                    mockSeal("USD"),
                    mockSeal("billedAmount")
                )
            )
        )
    }

    private val transactedAmount by before {
        SealedTransaction.TransactedAmount(
            "TransactedAmount",
            SealedTransaction.TransactedAmount.Fragments(
                SealedCurrencyAmountAttribute(
                    "CurrencyAmount",
                    mockSeal("USD"),
                    mockSeal("transactedAmount")
                )
            )
        )
    }

    private val queryResultItem by before {
        ListTransactionsByCardIdQuery.Item(
            "typename",
            ListTransactionsByCardIdQuery.Item.Fragments(
                SealedTransaction(
                    "SealedTransaction",
                    "id",
                    "owner",
                    1,
                    1.0,
                    1.0,
                    1.0,
                    "algorithm",
                    "keyId",
                    "cardId",
                    "sequenceId",
                    TransactionType.COMPLETE,
                    mockSeal("transactedAt"),
                    mockSeal("settledAt"),
                    billedAmount,
                    transactedAmount,
                    mockSeal("description"),
                    null,
                    listOf(
                        SealedTransaction.Detail(
                            "Detail",
                            SealedTransaction.Detail.Fragments(
                                SealedTransactionDetailChargeAttribute(
                                    "SealedTransactionDetailChargeAttribute",
                                    SealedTransactionDetailChargeAttribute.VirtualCardAmount(
                                        "VirtualCardAmount",
                                        SealedTransactionDetailChargeAttribute.VirtualCardAmount.Fragments(
                                            SealedCurrencyAmountAttribute(
                                                "CurrencyAmount",
                                                mockSeal("USD"),
                                                mockSeal("virtualCardAmount")
                                            )
                                        )
                                    ),
                                    SealedTransactionDetailChargeAttribute.Markup(
                                        "Markup",
                                        SealedTransactionDetailChargeAttribute.Markup.Fragments(
                                            SealedMarkupAttribute(
                                                "SealedMarkupAttribute",
                                                mockSeal("1"),
                                                mockSeal("2"),
                                                mockSeal("3")
                                            )
                                        )
                                    ),
                                    SealedTransactionDetailChargeAttribute.MarkupAmount(
                                        "MarkupAmount",
                                        SealedTransactionDetailChargeAttribute.MarkupAmount.Fragments(
                                            SealedCurrencyAmountAttribute(
                                                "CurrencyAmount",
                                                mockSeal("USD"),
                                                mockSeal("markupAmount")
                                            )
                                        )
                                    ),
                                    SealedTransactionDetailChargeAttribute.FundingSourceAmount(
                                        "FundingSourceAmount",
                                        SealedTransactionDetailChargeAttribute.FundingSourceAmount.Fragments(
                                            SealedCurrencyAmountAttribute(
                                                "CurrencyAmount",
                                                mockSeal("USD"),
                                                mockSeal("fundingSourceAmount")
                                            )
                                        )
                                    ),
                                    "fundingSourceId",
                                    mockSeal("description"),
                                    mockSeal("CLEARED")
                                )
                            )
                        )
                    )
                )
            )
        )
    }

    private val queryResult by before {
        ListTransactionsByCardIdQuery.ListTransactionsByCardId2("ListTransactionsByCardId2", listOf(queryResultItem), null)
    }

    private val queryResponse by before {
        Response.builder<ListTransactionsByCardIdQuery.Data>(ListTransactionsByCardIdQuery("cardId", null, null, null, null))
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
            .setAppSyncClient(mockAppSyncClient)
            .setKeyManager(mockKeyManager)
            .setLogger(mock())
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
    fun `listTransactionsByCardId() should return success result when no error present`() = runBlocking<Unit> {
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

        val listTransactions = deferredResult.await()
        listTransactions shouldNotBe null

        when (listTransactions) {
            is ListAPIResult.Success -> {
                listTransactions.result.items.isEmpty() shouldBe false
                listTransactions.result.items.size shouldBe 1
                listTransactions.result.nextToken shouldBe null

                checkTransaction(listTransactions.result.items[0])
            }
            else -> {
                fail("Unexpected ListAPIResult")
            }
        }

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
        verify(mockKeyManager, times(17)).decryptWithPrivateKey(anyString(), any(), any())
        verify(mockKeyManager, times(17)).decryptWithSymmetricKey(any<ByteArray>(), any<ByteArray>())
    }

    @Test
    fun `listTransactionsByCardId() should return success result using default inputs when no error present`() = runBlocking<Unit> {
        queryHolder.callback shouldBe null

        val deferredResult = async(Dispatchers.IO) {
            client.listTransactionsByCardId("cardId")
        }
        deferredResult.start()

        delay(100L)
        queryHolder.callback shouldNotBe null
        queryHolder.callback?.onResponse(queryResponse)

        val listTransactions = deferredResult.await()
        listTransactions shouldNotBe null

        when (listTransactions) {
            is ListAPIResult.Success -> {
                listTransactions.result.items.isEmpty() shouldBe false
                listTransactions.result.items.size shouldBe 1
                listTransactions.result.nextToken shouldBe null

                checkTransaction(listTransactions.result.items[0])
            }
            else -> {
                fail("Unexpected ListAPIResult")
            }
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
        verify(mockKeyManager, times(17)).decryptWithPrivateKey(anyString(), any(), any())
        verify(mockKeyManager, times(17)).decryptWithSymmetricKey(any<ByteArray>(), any<ByteArray>())
    }

    @Test
    fun `listTransactionsByCardId() should return success result when populating nextToken`() = runBlocking<Unit> {
        queryHolder.callback shouldBe null

        val queryResultWithNextToken by before {
            ListTransactionsByCardIdQuery.ListTransactionsByCardId2("ListTransactionsByCardId2", listOf(queryResultItem), "dummyNextToken")
        }

        val responseWithNextToken by before {
            Response.builder<ListTransactionsByCardIdQuery.Data>(
                ListTransactionsByCardIdQuery(
                    "cardId",
                    1,
                    "dummyNextToken",
                    null,
                    null
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

        val listTransactions = deferredResult.await()
        listTransactions shouldNotBe null

        when (listTransactions) {
            is ListAPIResult.Success -> {
                listTransactions.result.items.isEmpty() shouldBe false
                listTransactions.result.items.size shouldBe 1
                listTransactions.result.nextToken shouldBe "dummyNextToken"

                checkTransaction(listTransactions.result.items[0])
            }
            else -> {
                fail("Unexpected ListAPIResult")
            }
        }

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
        verify(mockKeyManager, times(17)).decryptWithPrivateKey(anyString(), any(), any())
        verify(mockKeyManager, times(17)).decryptWithSymmetricKey(any<ByteArray>(), any<ByteArray>())
    }

    @Test
    fun `listTransactionsByCardId() should return success empty list result when query result data is empty`() = runBlocking<Unit> {
        queryHolder.callback shouldBe null

        val queryResultWithEmptyList by before {
            ListTransactionsByCardIdQuery.ListTransactionsByCardId2(
                "ListTransactionsByCardId2",
                emptyList(),
                null
            )
        }

        val responseWithEmptyList by before {
            Response.builder<ListTransactionsByCardIdQuery.Data>(ListTransactionsByCardIdQuery("cardId", null, null, null, null))
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

        val listTransactions = deferredResult.await()
        listTransactions shouldNotBe null

        when (listTransactions) {
            is ListAPIResult.Success -> {
                listTransactions.result.items.isEmpty() shouldBe true
                listTransactions.result.items.size shouldBe 0
                listTransactions.result.nextToken shouldBe null
            }
            else -> {
                fail("Unexpected ListAPIResult")
            }
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
    fun `listTransactionsByCardId() should return success empty list result when query response is null`() = runBlocking<Unit> {
        queryHolder.callback shouldBe null

        val nullQueryResponse by before {
            Response.builder<ListTransactionsByCardIdQuery.Data>(ListTransactionsByCardIdQuery("cardId", null, null, null, null))
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

        val listTransactions = deferredResult.await()
        listTransactions shouldNotBe null

        when (listTransactions) {
            is ListAPIResult.Success -> {
                listTransactions.result.items.isEmpty() shouldBe true
                listTransactions.result.items.size shouldBe 0
                listTransactions.result.nextToken shouldBe null
            }
            else -> {
                fail("Unexpected ListAPIResult")
            }
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
    fun `listTransactionsByCardId() should return partial results when unsealing fails`() = runBlocking<Unit> {
        mockKeyManager.stub {
            on { decryptWithPrivateKey(anyString(), any(), any()) } doThrow KeyManagerException("KeyManagerException")
        }

        val deferredResult = async(Dispatchers.IO) {
            client.listTransactionsByCardId("cardId")
        }
        deferredResult.start()

        delay(100L)
        queryHolder.callback shouldNotBe null
        queryHolder.callback?.onResponse(queryResponse)

        val listTransactions = deferredResult.await()
        listTransactions shouldNotBe null

        when (listTransactions) {
            is ListAPIResult.Partial -> {
                listTransactions.result.items.isEmpty() shouldBe true
                listTransactions.result.items.size shouldBe 0
                listTransactions.result.failed.isEmpty() shouldBe false
                listTransactions.result.failed.size shouldBe 1
                listTransactions.result.nextToken shouldBe null

                with(listTransactions.result.failed[0].partial) {
                    id shouldBe "id"
                    owner shouldBe "owner"
                    version shouldBe 1
                    cardId shouldNotBe null
                    type shouldBe TransactionTypeEntity.COMPLETE
                    sequenceId shouldBe "sequenceId"
                    createdAt shouldBe Date(1L)
                    updatedAt shouldBe Date(1L)
                }
            }
            else -> {
                fail("Unexpected ListAPIResult")
            }
        }

        verify(mockAppSyncClient).query(any<ListTransactionsByCardIdQuery>())
        verify(mockKeyManager).decryptWithPrivateKey(anyString(), any(), any())
    }

    @Test
    fun `listTransactionsByCardId() should not return duplicate transactions with matching identifiers`() = runBlocking<Unit> {
        val queryResultItem1 = createMockTransaction("id1", TransactionType.COMPLETE)
        val queryResultItem2 = createMockTransaction("id2", TransactionType.COMPLETE)
        val queryResultItem3 = createMockTransaction("id1", TransactionType.COMPLETE)

        val queryResult by before {
            ListTransactionsByCardIdQuery.ListTransactionsByCardId2(
                "ListTransactionsByCardId2",
                listOf(queryResultItem1, queryResultItem2, queryResultItem3),
                null
            )
        }

        val queryResponse by before {
            Response.builder<ListTransactionsByCardIdQuery.Data>(ListTransactionsByCardIdQuery("cardId", null, null, null, null))
                .data(ListTransactionsByCardIdQuery.Data(queryResult))
                .build()
        }

        queryHolder.callback shouldBe null

        val deferredResult = async(Dispatchers.IO) {
            client.listTransactionsByCardId("cardId")
        }
        deferredResult.start()

        delay(100L)
        queryHolder.callback shouldNotBe null
        queryHolder.callback?.onResponse(queryResponse)

        val listTransactions = deferredResult.await()
        listTransactions shouldNotBe null

        when (listTransactions) {
            is ListAPIResult.Success -> {
                listTransactions.result.items.isEmpty() shouldBe false
                listTransactions.result.items.size shouldBe 2
                listTransactions.result.nextToken shouldBe null
            }
            else -> {
                fail("Unexpected ListAPIResult")
            }
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
        verify(mockKeyManager, times(18)).decryptWithPrivateKey(anyString(), any(), any())
        verify(mockKeyManager, times(18)).decryptWithSymmetricKey(any<ByteArray>(), any<ByteArray>())
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

    private fun createMockTransaction(id: String, transactionType: TransactionType): ListTransactionsByCardIdQuery.Item {
        return ListTransactionsByCardIdQuery.Item(
            "typename",
            ListTransactionsByCardIdQuery.Item.Fragments(
                SealedTransaction(
                    "SealedTransaction",
                    id,
                    "owner",
                    1,
                    1.0,
                    1.0,
                    1.0,
                    "algorithm",
                    "keyId",
                    "cardId",
                    "sequenceId",
                    transactionType,
                    mockSeal("transactedAt"),
                    mockSeal("settledAt"),
                    billedAmount,
                    transactedAmount,
                    mockSeal("description"),
                    null,
                    emptyList()
                )
            )
        )
    }

    private fun checkTransaction(transaction: Transaction) {
        with(transaction) {
            id shouldBe "id"
            owner shouldBe "owner"
            version shouldBe 1
            cardId shouldNotBe null
            type shouldBe TransactionTypeEntity.COMPLETE
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
}
