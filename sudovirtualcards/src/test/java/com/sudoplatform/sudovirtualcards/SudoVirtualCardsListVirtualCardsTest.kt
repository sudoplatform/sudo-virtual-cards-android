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
import com.sudoplatform.sudologging.Logger
import com.sudoplatform.sudouser.SudoUserClient
import com.sudoplatform.sudouser.amplify.GraphQLClient
import com.sudoplatform.sudovirtualcards.graphql.ListCardsQuery
import com.sudoplatform.sudovirtualcards.graphql.type.CardState
import com.sudoplatform.sudovirtualcards.types.ListAPIResult
import com.sudoplatform.sudovirtualcards.types.SortOrder
import com.sudoplatform.sudovirtualcards.types.inputs.IdFilterInput
import com.sudoplatform.sudovirtualcards.types.inputs.VirtualCardFilterInput
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
import com.sudoplatform.sudovirtualcards.types.CardState as CardStateEntity

/**
 * Test the correct operation of [SudoVirtualCardsClient.listVirtualCards]
 * using mocks and spies.
 */
class SudoVirtualCardsListVirtualCardsTest : BaseTests() {

    private val queryResponse by before {
        JSONObject(
            """
                {
                    'listCards': {
                        'items': [${createMockVirtualCard("id")}],
                        'nextToken': null
                    }
                }
            """.trimIndent(),
        )
    }
    private val queryResponseWithNextToken by before {
        JSONObject(
            """
                {
                    'listCards': {
                        'items': [${createMockVirtualCard("id")}],
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
                    'listCards': {
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
                    'listCards': {
                        'items': [
                            ${createMockVirtualCard("id1")},
                            ${createMockVirtualCard("id2")},
                            ${createMockVirtualCard("id1")},
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
                    argThat { this.query.equals(ListCardsQuery.OPERATION_DOCUMENT) },
                    any(), any(),
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
        SudoVirtualCardsClient.builder()
            .setContext(mockContext)
            .setSudoUserClient(mockUserClient)
            .setGraphQLClient(GraphQLClient(mockApiCategory))
            .setKeyManager(mockKeyManager)
            .setLogger(mock<Logger>())
            .build()
    }

    @After
    fun fini() {
        verifyNoMoreInteractions(mockContext, mockUserClient, mockKeyManager, mockApiCategory)
    }

    @Test
    fun `listVirtualCards() should return success result when no error present`() = runBlocking<Unit> {
        val deferredResult = async(Dispatchers.IO) {
            client.listVirtualCards()
        }
        deferredResult.start()

        delay(100L)

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

        verify(mockApiCategory).query<String>(
            org.mockito.kotlin.check {
                assertEquals(ListCardsQuery.OPERATION_DOCUMENT, it.query)
            },
            any(),
            any(),
        )
        verify(mockKeyManager, times(12)).decryptWithPrivateKey(anyString(), any(), any())
        verify(mockKeyManager, times(12)).decryptWithSymmetricKey(any<ByteArray>(), any<ByteArray>())
    }

    @Test
    fun `listVirtualCards() should return success result when populating nextToken`() = runBlocking<Unit> {
        val mockOperation: GraphQLOperation<String> = mock()
        whenever(
            mockApiCategory.query<String>(
                argThat { this.query.equals(ListCardsQuery.OPERATION_DOCUMENT) },
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
        val filterInput = VirtualCardFilterInput(IdFilterInput(eq = "dummyId"))
        val deferredResult = async(Dispatchers.IO) {
            client.listVirtualCards(filterInput, SortOrder.ASC, 1, "dummyNextToken")
        }
        deferredResult.start()

        delay(100L)

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

        verify(mockApiCategory).query<String>(
            org.mockito.kotlin.check {
                assertEquals(ListCardsQuery.OPERATION_DOCUMENT, it.query)
            },
            any(),
            any(),
        )
        verify(mockKeyManager, times(12)).decryptWithPrivateKey(anyString(), any(), any())
        verify(mockKeyManager, times(12)).decryptWithSymmetricKey(any<ByteArray>(), any<ByteArray>())
    }

    @Test
    fun `listVirtualCards() should return success empty list result when query result data is empty`() = runBlocking<Unit> {
        val mockOperation: GraphQLOperation<String> = mock()
        whenever(
            mockApiCategory.query<String>(
                argThat { this.query.equals(ListCardsQuery.OPERATION_DOCUMENT) },
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

        val deferredResult = async(Dispatchers.IO) {
            client.listVirtualCards()
        }
        deferredResult.start()

        delay(100L)
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

        verify(mockApiCategory).query<String>(
            org.mockito.kotlin.check {
                assertEquals(ListCardsQuery.OPERATION_DOCUMENT, it.query)
            },
            any(),
            any(),
        )
    }

    @Test
    fun `listVirtualCards() should return success empty list result when query response is null`() = runBlocking<Unit> {
        val mockOperation: GraphQLOperation<String> = mock()
        whenever(
            mockApiCategory.query<String>(
                argThat { this.query.equals(ListCardsQuery.OPERATION_DOCUMENT) },
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

        val deferredResult = async(Dispatchers.IO) {
            client.listVirtualCards()
        }
        deferredResult.start()

        delay(100L)

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

        verify(mockApiCategory).query<String>(
            org.mockito.kotlin.check {
                assertEquals(ListCardsQuery.OPERATION_DOCUMENT, it.query)
            },
            any(),
            any(),
        )
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

        verify(mockApiCategory).query<String>(
            org.mockito.kotlin.check {
                assertEquals(ListCardsQuery.OPERATION_DOCUMENT, it.query)
            },
            any(),
            any(),
        )
        verify(mockKeyManager).decryptWithPrivateKey(anyString(), any(), any())
    }

    @Test
    fun `listVirtualCards() should not return duplicate cards with matching identifiers`() = runBlocking<Unit> {
        val mockOperation: GraphQLOperation<String> = mock()
        whenever(
            mockApiCategory.query<String>(
                argThat { this.query.equals(ListCardsQuery.OPERATION_DOCUMENT) },
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
        val deferredResult = async(Dispatchers.IO) {
            client.listVirtualCards()
        }
        deferredResult.start()

        delay(100L)
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

        verify(mockApiCategory).query<String>(
            org.mockito.kotlin.check {
                assertEquals(ListCardsQuery.OPERATION_DOCUMENT, it.query)
            },
            any(),
            any(),
        )
        verify(mockKeyManager, times(36)).decryptWithPrivateKey(anyString(), any(), any())
        verify(mockKeyManager, times(36)).decryptWithSymmetricKey(any<ByteArray>(), any<ByteArray>())
    }

    @Test
    fun `listVirtualCards() should throw when unsealing fails`() = runBlocking<Unit> {
        mockApiCategory.stub {
            on {
                query<String>(
                    argThat { this.query.equals(ListCardsQuery.OPERATION_DOCUMENT) },
                    any(),
                    any(),
                )
            } doThrow Unsealer.UnsealerException.SealedDataTooShortException("Mock Unsealer Exception")
        }

        shouldThrow<SudoVirtualCardsClient.VirtualCardException.UnsealingException> {
            client.listVirtualCards()
        }

        verify(mockApiCategory).query<String>(
            org.mockito.kotlin.check {
                assertEquals(ListCardsQuery.OPERATION_DOCUMENT, it.query)
            },
            any(),
            any(),
        )
    }

    @Test
    fun `listVirtualCards() should throw when http error occurs`() = runBlocking<Unit> {
        val errors = listOf(
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
                argThat { this.query.equals(ListCardsQuery.OPERATION_DOCUMENT) },
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
        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.VirtualCardException.FailedException> {
                client.listVirtualCards()
            }
        }
        deferredResult.start()
        delay(100L)
        deferredResult.await()

        verify(mockApiCategory).query<String>(
            org.mockito.kotlin.check {
                assertEquals(ListCardsQuery.OPERATION_DOCUMENT, it.query)
            },
            any(),
            any(),
        )
    }

    @Test
    fun `listVirtualCards() should throw when unknown error occurs()`() = runBlocking<Unit> {
        mockApiCategory.stub {
            on {
                query<String>(
                    argThat { this.query.equals(ListCardsQuery.OPERATION_DOCUMENT) },
                    any(),
                    any(),
                )
            } doThrow RuntimeException("Mock Runtime Exception")
        }

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.VirtualCardException.UnknownException> {
                client.listVirtualCards()
            }
        }
        deferredResult.start()

        delay(100L)
        deferredResult.await()

        verify(mockApiCategory).query<String>(
            org.mockito.kotlin.check {
                assertEquals(ListCardsQuery.OPERATION_DOCUMENT, it.query)
            },
            any(),
            any(),
        )
    }

    @Test
    fun `listVirtualCards() should not block coroutine cancellation exception`() = runBlocking<Unit> {
        mockApiCategory.stub {
            on {
                query<String>(
                    argThat { this.query.equals(ListCardsQuery.OPERATION_DOCUMENT) },
                    any(),
                    any(),
                )
            } doThrow CancellationException("Mock Cancellation Exception")
        }

        shouldThrow<CancellationException> {
            client.listVirtualCards()
        }

        verify(mockApiCategory).query<String>(
            org.mockito.kotlin.check {
                assertEquals(ListCardsQuery.OPERATION_DOCUMENT, it.query)
            },
            any(),
            any(),
        )
    }

    private fun createMockVirtualCard(id: String): String {
        return """
            {
                '__typename': 'SealedCard',
                'id':'$id',
                'owner': 'owner',
                'version': 1,
                'createdAtEpochMs': 1.0,
                'updatedAtEpochMs': 1.0,
                'algorithm': 'algorithm',
                'keyId': 'keyId',
                'keyRingId': 'keyRingId',
                'owners': [],
                'fundingSourceId': 'fundingSourceId',
                'currency': 'currency',
                'state': '${CardState.ISSUED}',
                'activeToEpochMs': 1.0,
                'cancelledAtEpochMs': null,
                'last4': 'last4',
                'cardHolder': '${mockSeal("cardHolder")}',
                'alias': '${mockSeal("alias")}',
                'pan': '${mockSeal("pan")}',
                'csc': '${mockSeal("csc")}',
                'billingAddress':  {
                    '__typename': 'BillingAddress',
                    'addressLine1': '${mockSeal("addressLine1")}',
                    'addressLine2': '${mockSeal("addressLine2")}',
                    'city': '${mockSeal("city")}',
                    'state': '${mockSeal("state")}',
                    'postalCode': '${mockSeal("postalCode")}',
                    'country': '${mockSeal("country")}'
                },
                'expiry': {
                    '__typename': 'Expiry',
                    'mm': '${mockSeal("01")}',
                    'yyyy': '${mockSeal("2020")}'
                },
            }
        """.trimIndent()
    }
}
