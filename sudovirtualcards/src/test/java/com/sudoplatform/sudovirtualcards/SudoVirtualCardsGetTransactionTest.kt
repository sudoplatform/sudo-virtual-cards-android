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
import com.sudoplatform.sudokeymanager.KeyManagerInterface
import com.sudoplatform.sudouser.PublicKey
import com.sudoplatform.sudouser.SudoUserClient
import com.sudoplatform.sudouser.amplify.GraphQLClient
import com.sudoplatform.sudovirtualcards.graphql.GetTransactionQuery
import com.sudoplatform.sudovirtualcards.graphql.type.TransactionType
import com.sudoplatform.sudovirtualcards.keys.PublicKeyService
import com.sudoplatform.sudovirtualcards.types.Transaction
import com.sudoplatform.sudovirtualcards.types.transformers.Unsealer
import io.kotlintest.shouldBe
import io.kotlintest.shouldNotBe
import io.kotlintest.shouldThrow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.never
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
import com.sudoplatform.sudovirtualcards.types.TransactionType as TransactionTypeEntity

/**
 * Test the correct operation of [SudoVirtualCardsClient.getTransaction]
 * using mocks and spies.
 */
class SudoVirtualCardsGetTransactionTest : BaseTests() {
    private val queryResponse by before {
        JSONObject(
            """
            {
                'getTransaction': {
                        '__typename': 'GetTransaction',
                        'id':'id',
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
            }
            """.trimIndent(),
        )
    }

    private val mockContext by before {
        mock<Context>()
    }

    private val mockUserClient by before {
        mock<SudoUserClient>().stub {
            on { getSubject() } doReturn "owner"
        }
    }

    private val currentKey =
        PublicKey(
            keyId = "keyId",
            publicKey = "publicKey".toByteArray(),
        )

    private val mockPublicKeyService by before {
        mock<PublicKeyService>().stub {
            onBlocking { getCurrentKey() } doReturn currentKey
        }
    }

    private val mockApiCategory by before {
        mock<ApiCategory>().stub {
            on {
                query<String>(
                    argThat { this.query.equals(GetTransactionQuery.OPERATION_DOCUMENT) },
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
            .setPublicKeyService(mockPublicKeyService)
            .build()
    }

    @After
    fun fini() {
        verifyNoMoreInteractions(mockContext, mockUserClient, mockKeyManager, mockPublicKeyService, mockApiCategory)
    }

    @Test
    fun `getTransaction() should return results when no error present`() =
        runBlocking<Unit> {
            val deferredResult =
                async(Dispatchers.IO) {
                    client.getTransaction("id")
                }
            deferredResult.start()

            delay(100L)

            val result = deferredResult.await()
            result shouldNotBe null

            checkTransaction(result!!)

            verify(mockApiCategory).query<String>(
                check {
                    assertEquals(GetTransactionQuery.OPERATION_DOCUMENT, it.query)
                },
                any(),
                any(),
            )
            verify(mockPublicKeyService).getCurrentKey()
            verify(mockKeyManager, times(18)).decryptWithPrivateKey(anyString(), any(), any())
            verify(mockKeyManager, times(18)).decryptWithSymmetricKey(any<ByteArray>(), any<ByteArray>())
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
            createdAt shouldBe java.util.Date(1L)
            updatedAt shouldBe java.util.Date(1L)
            details.isEmpty() shouldBe false
            details[0].fundingSourceId shouldBe "fundingSourceId"
            details[0].description.isBlank() shouldBe false
        }
    }

    @Test
    fun `getTransaction() should return null result when query response is null`() =
        runBlocking<Unit> {
            val mockOperation: GraphQLOperation<String> = mock()
            whenever(
                mockApiCategory.query<String>(
                    argThat { this.query.equals(GetTransactionQuery.OPERATION_DOCUMENT) },
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
                    client.getTransaction("id")
                }
            deferredResult.start()
            delay(100L)
            val result = deferredResult.await()
            result shouldBe null

            verify(mockApiCategory).query<String>(
                check {
                    assertEquals(GetTransactionQuery.OPERATION_DOCUMENT, it.query)
                },
                any(),
                any(),
            )
            verify(mockPublicKeyService).getCurrentKey()
        }

    @Test
    fun `getTransaction() should return null when query response has no transaction found`() =
        runBlocking<Unit> {
            val noTransactionResponse by before {
                JSONObject(
                    """
                    {
                        'getTransaction': null
                    }
                    """.trimIndent(),
                )
            }

            val mockOperation: GraphQLOperation<String> = mock()
            whenever(
                mockApiCategory.query<String>(
                    argThat { this.query.equals(GetTransactionQuery.OPERATION_DOCUMENT) },
                    any(),
                    any(),
                ),
            ).thenAnswer {
                @Suppress("UNCHECKED_CAST")
                (it.arguments[1] as Consumer<GraphQLResponse<String>>).accept(
                    GraphQLResponse(noTransactionResponse.toString(), null),
                )
                mockOperation
            }
            val deferredResult =
                async(Dispatchers.IO) {
                    client.getTransaction("id")
                }
            deferredResult.start()

            delay(100L)
            deferredResult.await() shouldBe null

            verify(mockApiCategory).query<String>(
                check {
                    assertEquals(GetTransactionQuery.OPERATION_DOCUMENT, it.query)
                },
                any(),
                any(),
            )
            verify(mockPublicKeyService).getCurrentKey()
        }

    @Test
    fun `getTransaction() should throw current key pair retrieval returns null`() =
        runBlocking<Unit> {
            mockPublicKeyService.stub {
                onBlocking { getCurrentKey() } doReturn null
            }

            shouldThrow<SudoVirtualCardsClient.TransactionException.PublicKeyException> {
                client.getTransaction("id")
            }

            verify(mockApiCategory, never()).query<String>(
                check {
                    assertEquals(GetTransactionQuery.OPERATION_DOCUMENT, it.query)
                },
                any(),
                any(),
            )

            verify(mockPublicKeyService).getCurrentKey()
        }

    @Test
    fun `getTransaction() should throw when unsealing fails`() =
        runBlocking<Unit> {
            mockApiCategory.stub {
                on {
                    mockApiCategory.query<String>(
                        argThat { this.query.equals(GetTransactionQuery.OPERATION_DOCUMENT) },
                        any(),
                        any(),
                    )
                } doThrow
                    Unsealer.UnsealerException.SealedDataTooShortException("Mock Unsealer Exception")
            }

            shouldThrow<SudoVirtualCardsClient.TransactionException.UnsealingException> {
                client.getTransaction("id")
            }

            verify(mockApiCategory).query<String>(
                check {
                    assertEquals(GetTransactionQuery.OPERATION_DOCUMENT, it.query)
                },
                any(),
                any(),
            )
            verify(mockPublicKeyService).getCurrentKey()
        }

    @Test
    fun `getTransaction() should throw when http error occurs`() =
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
                    argThat { this.query.equals(GetTransactionQuery.OPERATION_DOCUMENT) },
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
                        client.getTransaction("id")
                    }
                }
            deferredResult.start()

            delay(100L)
            deferredResult.await()

            verify(mockApiCategory).query<String>(
                check {
                    assertEquals(GetTransactionQuery.OPERATION_DOCUMENT, it.query)
                },
                any(),
                any(),
            )
            verify(mockPublicKeyService).getCurrentKey()
        }

    @Test
    fun `getTransaction() should throw when unknown error occurs`() =
        runBlocking<Unit> {
            mockApiCategory.stub {
                on {
                    query<String>(
                        argThat { this.query.equals(GetTransactionQuery.OPERATION_DOCUMENT) },
                        any(),
                        any(),
                    )
                } doThrow RuntimeException("Mock Runtime Exception")
            }

            shouldThrow<SudoVirtualCardsClient.TransactionException.UnknownException> {
                client.getTransaction("id")
            }

            verify(mockApiCategory).query<String>(
                check {
                    assertEquals(GetTransactionQuery.OPERATION_DOCUMENT, it.query)
                },
                any(),
                any(),
            )
            verify(mockPublicKeyService).getCurrentKey()
        }

    @Test
    fun `getTransaction() should not block coroutine cancellation exception`() =
        runBlocking<Unit> {
            mockApiCategory.stub {
                on {
                    query<String>(
                        argThat { this.query.equals(GetTransactionQuery.OPERATION_DOCUMENT) },
                        any(),
                        any(),
                    )
                } doThrow CancellationException("Mock Cancellation Exception")
            }

            shouldThrow<CancellationException> {
                client.getTransaction("id")
            }

            verify(mockApiCategory).query<String>(
                check {
                    assertEquals(GetTransactionQuery.OPERATION_DOCUMENT, it.query)
                },
                any(),
                any(),
            )
            verify(mockPublicKeyService).getCurrentKey()
        }
}
