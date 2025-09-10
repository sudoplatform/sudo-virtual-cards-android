/*
 * Copyright © 2024 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards

import android.content.Context
import com.amplifyframework.api.ApiCategory
import com.amplifyframework.api.graphql.GraphQLOperation
import com.amplifyframework.api.graphql.GraphQLResponse
import com.amplifyframework.core.Consumer
import com.sudoplatform.sudokeymanager.KeyManagerException
import com.sudoplatform.sudokeymanager.KeyManagerInterface
import com.sudoplatform.sudouser.SudoUserClient
import com.sudoplatform.sudouser.amplify.GraphQLClient
import com.sudoplatform.sudovirtualcards.graphql.ListTransactionsByCardIdAndTypeQuery
import com.sudoplatform.sudovirtualcards.graphql.type.TransactionType
import com.sudoplatform.sudovirtualcards.types.ListAPIResult
import com.sudoplatform.sudovirtualcards.types.Transaction
import com.sudoplatform.sudovirtualcards.types.transformers.Unsealer
import io.kotlintest.shouldBe
import io.kotlintest.shouldNotBe
import io.kotlintest.shouldThrow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.check
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.stub
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoMoreInteractions
import org.mockito.kotlin.whenever
import java.net.HttpURLConnection
import java.util.Date
import java.util.concurrent.CancellationException
import com.sudoplatform.sudovirtualcards.types.TransactionType as TransactionTypeEntity

/**
 * Test the correct operation of [SudoVirtualCardsClient.listTransactionsByCardIdAndType] using mocks
 * and spies.
 */
class SudoVirtualCardsListTransactionsByCardIdAndTypeTest : BaseTests() {
    private val queryResponse by before {
        JSONObject(
            """
            {
                'listTransactionsByCardIdAndType': {
                    'items': [${createMockTransaction("id")}]
                }
            }
            """.trimIndent(),
        )
    }

    private val queryResponseWithNextToken by before {
        JSONObject(
            """
            {
                'listTransactionsByCardIdAndType': {
                    'items': [${createMockTransaction("id")}],
                    'nextToken': 'dummyNextToken'
                }
            }
            """.trimIndent(),
        )
    }
    private val queryResponseWithEmptyList by before {
        JSONObject(
            """
            {
                'listTransactionsByCardIdAndType': {
                    'items': []
                }
            }
            """.trimIndent(),
        )
    }

    private val queryResponseWithDuplicateIdentifiers by before {
        JSONObject(
            """
            {
                'listTransactionsByCardIdAndType': {
                    'items': [
                        ${createMockTransaction("id1")},
                        ${createMockTransaction("id2")},
                        ${createMockTransaction("id1")},
                    ]
                }
            }
            """.trimIndent(),
        )
    }

    private val mockContext by before {
        mock<Context>()
    }

    private val mockUserClient by before {
        mock<SudoUserClient>().stub {
            on { getSubject() } doReturn "subject"
        }
    }

    private val mockApiCategory by before {
        mock<ApiCategory>().stub {
            on {
                query<String>(
                    argThat { this.query.equals(ListTransactionsByCardIdAndTypeQuery.OPERATION_DOCUMENT) },
                    any(),
                    any(),
                )
            } doAnswer {
                val mockOperation: GraphQLOperation<String> = mock()
                @Suppress("UNCHECKED_CAST")
                (it.arguments[1] as Consumer<GraphQLResponse<String>>).accept(
                    GraphQLResponse(queryResponse.toString(), null),
                )
                mockOperation
            }
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
        SudoVirtualCardsClient
            .builder()
            .setContext(mockContext)
            .setSudoUserClient(mockUserClient)
            .setGraphQLClient(GraphQLClient(mockApiCategory))
            .setKeyManager(mockKeyManager)
            .setLogger(mock())
            .build()
    }

    @After
    fun fini() {
        verifyNoMoreInteractions(mockContext, mockUserClient, mockKeyManager, mockApiCategory)
    }

    @Test
    fun `listTransactionsByCardIdAndType() should return success result when no error present`() =
        runBlocking<Unit> {
            val deferredResult =
                async(Dispatchers.IO) {
                    client.listTransactionsByCardIdAndType(
                        cardId = "cardId",
                        transactionType = TransactionTypeEntity.PENDING,
                        limit = 1,
                        nextToken = null,
                    )
                }
            deferredResult.start()

            delay(100L)
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
            verifyListTransactionsByCardIdAndTypeQuery("cardId", TransactionType.PENDING, 1, null)
            verify(mockKeyManager, times(18)).decryptWithPrivateKey(anyString(), any(), any())
            verify(mockKeyManager, times(18)).decryptWithSymmetricKey(any<ByteArray>(), any<ByteArray>())
        }

    @Test
    fun `listTransactionsByCardIdAndType() should return success result using default inputs when no error present`() =
        runBlocking<Unit> {
            val deferredResult =
                async(Dispatchers.IO) {
                    client.listTransactionsByCardIdAndType("cardId", TransactionTypeEntity.PENDING)
                }
            deferredResult.start()

            delay(100L)

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

            verifyListTransactionsByCardIdAndTypeQuery("cardId", TransactionType.PENDING, 100, null)

            verify(mockKeyManager, times(18)).decryptWithPrivateKey(anyString(), any(), any())
            verify(mockKeyManager, times(18)).decryptWithSymmetricKey(any<ByteArray>(), any<ByteArray>())
        }

    @Test
    fun `listTransactionsByCardIdAndType() should return success result when populating nextToken`() =
        runBlocking<Unit> {
            val mockOperation: GraphQLOperation<String> = mock()
            whenever(
                mockApiCategory.query<String>(
                    argThat { this.query.equals(ListTransactionsByCardIdAndTypeQuery.OPERATION_DOCUMENT) },
                    any(),
                    any(),
                ),
            ).thenAnswer {
                @Suppress("UNCHECKED_CAST")
                (it.arguments[1] as Consumer<GraphQLResponse<String>>).accept(
                    GraphQLResponse(queryResponseWithNextToken.toString(), null),
                )
                mockOperation
            }

            val deferredResult =
                async(Dispatchers.IO) {
                    client.listTransactionsByCardIdAndType("cardId", TransactionTypeEntity.PENDING, 1, "dummyNextToken")
                }
            deferredResult.start()

            delay(100L)

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

            verifyListTransactionsByCardIdAndTypeQuery("cardId", TransactionType.PENDING, 1, "dummyNextToken")

            verify(mockKeyManager, times(18)).decryptWithPrivateKey(anyString(), any(), any())
            verify(mockKeyManager, times(18)).decryptWithSymmetricKey(any<ByteArray>(), any<ByteArray>())
        }

    @Test
    fun `listTransactionsByCardIdAndType() should return success empty list result when query result data is empty`() =
        runBlocking<Unit> {
            val mockOperation: GraphQLOperation<String> = mock()
            whenever(
                mockApiCategory.query<String>(
                    argThat { this.query.equals(ListTransactionsByCardIdAndTypeQuery.OPERATION_DOCUMENT) },
                    any(),
                    any(),
                ),
            ).thenAnswer {
                @Suppress("UNCHECKED_CAST")
                (it.arguments[1] as Consumer<GraphQLResponse<String>>).accept(
                    GraphQLResponse(queryResponseWithEmptyList.toString(), null),
                )
                mockOperation
            }

            val deferredResult =
                async(Dispatchers.IO) {
                    client.listTransactionsByCardIdAndType("cardId", TransactionTypeEntity.PENDING)
                }
            deferredResult.start()

            delay(100L)

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

            verifyListTransactionsByCardIdAndTypeQuery("cardId", TransactionType.PENDING, 100, null)
        }

    @Test
    fun `listTransactionsByCardIdAndType() should return success empty list result when query response is null`() =
        runBlocking<Unit> {
            val mockOperation: GraphQLOperation<String> = mock()
            whenever(
                mockApiCategory.query<String>(
                    argThat { this.query.equals(ListTransactionsByCardIdAndTypeQuery.OPERATION_DOCUMENT) },
                    any(),
                    any(),
                ),
            ).thenAnswer {
                @Suppress("UNCHECKED_CAST")
                (it.arguments[1] as Consumer<GraphQLResponse<String>>).accept(
                    GraphQLResponse(null, null),
                )
                mockOperation
            }

            val deferredResult =
                async(Dispatchers.IO) {
                    client.listTransactionsByCardIdAndType("cardId", TransactionTypeEntity.PENDING)
                }
            deferredResult.start()

            delay(100L)

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

            verifyListTransactionsByCardIdAndTypeQuery("cardId", TransactionType.PENDING, 100, null)
        }

    @Test
    fun `listTransactionsByCardIdAndType() should not return duplicate transactions with matching identifiers`() =
        runBlocking<Unit> {
            val mockOperation: GraphQLOperation<String> = mock()
            whenever(
                mockApiCategory.query<String>(
                    argThat { this.query.equals(ListTransactionsByCardIdAndTypeQuery.OPERATION_DOCUMENT) },
                    any(),
                    any(),
                ),
            ).thenAnswer {
                @Suppress("UNCHECKED_CAST")
                (it.arguments[1] as Consumer<GraphQLResponse<String>>).accept(
                    GraphQLResponse(queryResponseWithDuplicateIdentifiers.toString(), null),
                )
                mockOperation
            }

            val deferredResult =
                async(Dispatchers.IO) {
                    client.listTransactionsByCardIdAndType("cardId", TransactionTypeEntity.COMPLETE)
                }
            deferredResult.start()

            delay(100L)

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

            verifyListTransactionsByCardIdAndTypeQuery("cardId", TransactionType.COMPLETE, 100, null)

            verify(mockKeyManager, times(18 * 3)).decryptWithPrivateKey(anyString(), any(), any())
            verify(mockKeyManager, times(18 * 3)).decryptWithSymmetricKey(any<ByteArray>(), any<ByteArray>())
        }

    @Test
    fun `listTransactionsByCardIdAndType() should return partial results when unsealing fails`() =
        runBlocking<Unit> {
            mockKeyManager.stub {
                on { decryptWithPrivateKey(anyString(), any(), any()) } doThrow KeyManagerException("KeyManagerException")
            }

            val deferredResult =
                async(Dispatchers.IO) {
                    client.listTransactionsByCardIdAndType("cardId", TransactionTypeEntity.PENDING)
                }
            deferredResult.start()

            delay(100L)
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

            verifyListTransactionsByCardIdAndTypeQuery("cardId", TransactionType.PENDING, 100, null)

            verify(mockKeyManager).decryptWithPrivateKey(anyString(), any(), any())
        }

    @Test
    fun `listTransactionsByCardIdAndType() should throw when unsealing fails`() =
        runBlocking<Unit> {
            mockApiCategory.stub {
                on {
                    query<String>(
                        argThat { this.query.equals(ListTransactionsByCardIdAndTypeQuery.OPERATION_DOCUMENT) },
                        any(),
                        any(),
                    )
                } doThrow
                    Unsealer.UnsealerException.SealedDataTooShortException("Mock Unsealer Exception")
            }

            shouldThrow<SudoVirtualCardsClient.TransactionException.UnsealingException> {
                client.listTransactionsByCardIdAndType("cardId", TransactionTypeEntity.PENDING)
            }

            verifyListTransactionsByCardIdAndTypeQuery("cardId", TransactionType.PENDING, 100, null)
        }

    @Test
    fun `listTransactionsByCardIdAndType() should throw when http error occurs`() =
        runBlocking<Unit> {
            val errors =
                listOf(
                    GraphQLResponse.Error(
                        "mock",
                        null,
                        null,
                        mapOf("httpStatus" to HttpURLConnection.HTTP_FORBIDDEN),
                    ),
                )
            val mockOperation: GraphQLOperation<String> = mock()
            whenever(
                mockApiCategory.query<String>(
                    argThat { this.query.equals(ListTransactionsByCardIdAndTypeQuery.OPERATION_DOCUMENT) },
                    any(),
                    any(),
                ),
            ).thenAnswer {
                @Suppress("UNCHECKED_CAST")
                (it.arguments[1] as Consumer<GraphQLResponse<String>>).accept(
                    GraphQLResponse(null, errors),
                )
                mockOperation
            }
            val deferredResult =
                async(Dispatchers.IO) {
                    shouldThrow<SudoVirtualCardsClient.TransactionException.FailedException> {
                        client.listTransactionsByCardIdAndType("cardId", TransactionTypeEntity.PENDING)
                    }
                }
            deferredResult.start()
            delay(100L)

            deferredResult.await()

            verifyListTransactionsByCardIdAndTypeQuery("cardId", TransactionType.PENDING, 100, null)
        }

    @Test
    fun `listTransactionsByCardIdAndType() should throw when unknown error occurs()`() =
        runBlocking<Unit> {
            mockApiCategory.stub {
                on {
                    query<String>(
                        argThat { this.query.equals(ListTransactionsByCardIdAndTypeQuery.OPERATION_DOCUMENT) },
                        any(),
                        any(),
                    )
                } doThrow RuntimeException("Mock Runtime Exception")
            }

            val deferredResult =
                async(Dispatchers.IO) {
                    shouldThrow<SudoVirtualCardsClient.TransactionException.UnknownException> {
                        client.listTransactionsByCardIdAndType("cardId", TransactionTypeEntity.PENDING)
                    }
                }
            deferredResult.start()

            delay(100L)
            deferredResult.await()

            verifyListTransactionsByCardIdAndTypeQuery("cardId", TransactionType.PENDING, 100, null)
        }

    @Test
    fun `listTransactionsByCardIdAndType() should not block coroutine cancellation exception`() =
        runBlocking<Unit> {
            mockApiCategory.stub {
                on {
                    query<String>(
                        argThat { this.query.equals(ListTransactionsByCardIdAndTypeQuery.OPERATION_DOCUMENT) },
                        any(),
                        any(),
                    )
                } doThrow CancellationException("Mock Cancellation Exception")
            }

            shouldThrow<CancellationException> {
                client.listTransactionsByCardIdAndType("cardId", TransactionTypeEntity.PENDING)
            }

            verifyListTransactionsByCardIdAndTypeQuery("cardId", TransactionType.PENDING, 100, null)
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

    private fun createMockTransaction(id: String): String =
        """
        {
            '__typename': 'SealedTransaction',
            'id':'$id',
            'owner': 'owner',
            'version': 1,
            'createdAtEpochMs': 1.0,
            'updatedAtEpochMs': 1.0,
            'sortDateEpochMs': 1.0,
            'algorithm': 'algorithm',
            'keyId': 'keyId',
            'cardId': 'cardId',
            'sequenceId': 'sequenceId',
            'type': '${TransactionType.COMPLETE}',
            'transactedAtEpochMs': '${mockSeal("transactedAt")}',
            'settledAtEpochMs': '${mockSeal("settledAt")}',
            'billedAmount': {
                '__typename': 'BilledAmount',
                'currency': '${mockSeal("USD")}',
                'amount': '${mockSeal("billedAmount")}'
            },
            'transactedAmount': {
                '__typename': 'TransactedAmount',
                'currency': '${mockSeal("USD")}',
                'amount': '${mockSeal("transactedAmount")}'
            },
            'description': '${mockSeal("description")}',
            'detail': [{
                '__typename': 'SealedTransactionDetailChargeAttribute',
                'virtualCardAmount': {
                    '__typename': 'VirtualCardAmount',
                    'currency': '${mockSeal("USD")}',
                    'amount': '${mockSeal("virtualCardAmount")}'
                },
                'markup': {
                    '__typename': 'Markup',
                    'percent': '${mockSeal("1")}',
                    'flat': '${mockSeal("2")}',
                    'minCharge': '${mockSeal("3")}'
                },
                'markupAmount': {
                    '__typename': 'MarkupAmount',
                    'currency': '${mockSeal("USD")}',
                    'amount': '${mockSeal("markupAmount")}'
                },
                'fundingSourceAmount': {
                    '__typename': 'FundingSourceAmount',
                    'currency': '${mockSeal("USD")}',
                    'amount': '${mockSeal("fundingSourceAmount")}'
                },
                'fundingSourceId': 'fundingSourceId',
                'description': '${mockSeal("description")}',
                'state': '${mockSeal("CLEARED")}'
            }]
        }
        """.trimIndent()

    private fun verifyListTransactionsByCardIdAndTypeQuery(
        expectedCardId: String,
        expectedType: TransactionType,
        expectedLimit: Int?,
        expectedNextToken: String? = null,
    ) {
        verify(mockApiCategory).query<String>(
            check {
                assertEquals(ListTransactionsByCardIdAndTypeQuery.OPERATION_DOCUMENT, it.query)
                val cardId = it.variables["cardId"] as String?
                cardId shouldBe expectedCardId
                val type = it.variables["transactionType"] as TransactionType?
                type shouldBe expectedType
                val limit = it.variables["limit"] as Int?
                limit shouldBe expectedLimit
                val nextToken = it.variables["nextToken"]as String?
                nextToken shouldBe expectedNextToken
            },
            any(),
            any(),
        )
    }
}
