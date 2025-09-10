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
import com.sudoplatform.sudouser.PublicKey
import com.sudoplatform.sudouser.SudoUserClient
import com.sudoplatform.sudouser.amplify.GraphQLClient
import com.sudoplatform.sudovirtualcards.graphql.CancelVirtualCardMutation
import com.sudoplatform.sudovirtualcards.graphql.type.CardState
import com.sudoplatform.sudovirtualcards.keys.PublicKeyService
import com.sudoplatform.sudovirtualcards.types.SingleAPIResult
import com.sudoplatform.sudovirtualcards.types.transformers.Unsealer
import io.kotlintest.fail
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
import com.sudoplatform.sudovirtualcards.types.CardState as CardStateEntity

/**
 * Test the correct operation of [SudoVirtualCardsClient.cancelVirtualCard]
 * using mocks and spies.
 */
class SudoVirtualCardsCancelVirtualCardTest : BaseTests() {
    private val mutationResponse by before {
        JSONObject(
            """
            {
                'cancelCard': {
                    '__typename': 'CancelCard',
                    'lastTransaction': null,
                    'id':'id',
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
                    'state': '${CardState.CLOSED}',
                    'activeToEpochMs': 1.0,
                    'cancelledAtEpochMs': 1.0,
                    'last4': 'last4',
                    'cardHolder': '${mockSeal("newCardHolder")}',
                    'alias': '${mockSeal("newAlias")}',
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
                        'yyyy': '${mockSeal("2021")}'
                     },
                    'metadata': null
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
                mutate<String>(
                    argThat { this.query.equals(CancelVirtualCardMutation.OPERATION_DOCUMENT) },
                    any(),
                    any(),
                )
            } doAnswer {
                val mockOperation: GraphQLOperation<String> = mock()
                @Suppress("UNCHECKED_CAST")
                (it.arguments[1] as Consumer<GraphQLResponse<String>>).accept(
                    GraphQLResponse(mutationResponse.toString(), null),
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
        verifyNoMoreInteractions(mockContext, mockUserClient, mockKeyManager, mockApiCategory)
    }

    @Test
    fun `cancelVirtualCard() should return success result when no error present`() =
        runBlocking<Unit> {
            val deferredResult =
                async(Dispatchers.IO) {
                    client.cancelVirtualCard("id")
                }
            deferredResult.start()
            delay(100L)
            val cancelCard = deferredResult.await()
            cancelCard shouldNotBe null

            when (cancelCard) {
                is SingleAPIResult.Success -> {
                    cancelCard.result.id shouldBe "id"
                    cancelCard.result.owners shouldNotBe null
                    cancelCard.result.owner shouldBe "owner"
                    cancelCard.result.version shouldBe 1
                    cancelCard.result.fundingSourceId shouldBe "fundingSourceId"
                    cancelCard.result.state shouldBe CardStateEntity.CLOSED
                    cancelCard.result.cardHolder shouldNotBe null
                    cancelCard.result.alias shouldNotBe null
                    cancelCard.result.last4 shouldBe "last4"
                    cancelCard.result.cardNumber shouldNotBe null
                    cancelCard.result.securityCode shouldNotBe null
                    cancelCard.result.billingAddress shouldNotBe null
                    cancelCard.result.expiry shouldNotBe null
                    cancelCard.result.currency shouldBe "currency"
                    cancelCard.result.activeTo shouldNotBe null
                    cancelCard.result.cancelledAt shouldBe Date(1L)
                    cancelCard.result.createdAt shouldBe Date(1L)
                    cancelCard.result.updatedAt shouldBe Date(1L)
                }
                else -> {
                    fail("Unexpected SingleAPIResult")
                }
            }

            verify(mockPublicKeyService).getCurrentKey()
            verify(mockApiCategory).mutate<String>(
                org.mockito.kotlin.check {
                    assertEquals(CancelVirtualCardMutation.OPERATION_DOCUMENT, it.query)
                },
                any(),
                any(),
            )
            verify(mockKeyManager, times(12)).decryptWithPrivateKey(anyString(), any(), any())
            verify(mockKeyManager, times(12)).decryptWithSymmetricKey(any<ByteArray>(), any<ByteArray>())
        }

    @Test
    fun `cancelVirtualCard() should return partial result when unsealing fails`() =
        runBlocking<Unit> {
            mockKeyManager.stub {
                on { decryptWithPrivateKey(anyString(), any(), any()) } doThrow KeyManagerException("KeyManagerException")
            }

            val deferredResult =
                async(Dispatchers.IO) {
                    client.cancelVirtualCard("id")
                }
            deferredResult.start()

            delay(100L)
            val cancelCard = deferredResult.await()
            cancelCard shouldNotBe null

            when (cancelCard) {
                is SingleAPIResult.Partial -> {
                    cancelCard.result.partial.id shouldBe "id"
                    cancelCard.result.partial.owners shouldNotBe null
                    cancelCard.result.partial.owner shouldBe "owner"
                    cancelCard.result.partial.version shouldBe 1
                    cancelCard.result.partial.fundingSourceId shouldBe "fundingSourceId"
                    cancelCard.result.partial.state shouldBe CardStateEntity.CLOSED
                    cancelCard.result.partial.last4 shouldBe "last4"
                    cancelCard.result.partial.currency shouldBe "currency"
                    cancelCard.result.partial.activeTo shouldNotBe null
                    cancelCard.result.partial.cancelledAt shouldBe Date(1L)
                    cancelCard.result.partial.createdAt shouldBe Date(1L)
                    cancelCard.result.partial.updatedAt shouldBe Date(1L)
                }
                else -> {
                    fail("Unexpected SingleAPIResult")
                }
            }

            verify(mockPublicKeyService).getCurrentKey()
            verify(mockApiCategory).mutate<String>(
                org.mockito.kotlin.check {
                    assertEquals(CancelVirtualCardMutation.OPERATION_DOCUMENT, it.query)
                },
                any(),
                any(),
            )
            verify(mockKeyManager).decryptWithPrivateKey(anyString(), any(), any())
        }

    @Test
    fun `cancelVirtualCard() should throw when response is null`() =
        runBlocking<Unit> {
            val mockOperation: GraphQLOperation<String> = mock()
            whenever(
                mockApiCategory.mutate<String>(
                    argThat { this.query.equals(CancelVirtualCardMutation.OPERATION_DOCUMENT) },
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
                    shouldThrow<SudoVirtualCardsClient.VirtualCardException.FailedException> {
                        client.cancelVirtualCard("id")
                    }
                }
            deferredResult.start()

            delay(100L)
            deferredResult.await()

            verify(mockPublicKeyService).getCurrentKey()
            verify(mockApiCategory).mutate<String>(
                org.mockito.kotlin.check {
                    assertEquals(CancelVirtualCardMutation.OPERATION_DOCUMENT, it.query)
                },
                any(),
                any(),
            )
        }

    @Test
    fun `cancelVirtualCard() should throw when mutation response has an identity verification error`() =
        runBlocking<Unit> {
            val errors =
                listOf(
                    GraphQLResponse.Error(
                        "mock",
                        null,
                        null,
                        mapOf("errorType" to "IdentityVerificationNotVerifiedError"),
                    ),
                )
            val mockOperation: GraphQLOperation<String> = mock()
            whenever(
                mockApiCategory.mutate<String>(
                    argThat { this.query.equals(CancelVirtualCardMutation.OPERATION_DOCUMENT) },
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
                    shouldThrow<SudoVirtualCardsClient.VirtualCardException.IdentityVerificationException> {
                        client.cancelVirtualCard("id")
                    }
                }
            deferredResult.start()

            delay(100L)
            deferredResult.await()

            verify(mockPublicKeyService).getCurrentKey()
            verify(mockApiCategory).mutate<String>(
                org.mockito.kotlin.check {
                    assertEquals(CancelVirtualCardMutation.OPERATION_DOCUMENT, it.query)
                },
                any(),
                any(),
            )
        }

    @Test
    fun `cancelVirtualCard() should throw when response has card not found error`() =
        runBlocking<Unit> {
            val errors =
                listOf(
                    GraphQLResponse.Error(
                        "mock",
                        null,
                        null,
                        mapOf("errorType" to "CardNotFoundError"),
                    ),
                )
            val mockOperation: GraphQLOperation<String> = mock()
            whenever(
                mockApiCategory.mutate<String>(
                    argThat { this.query.equals(CancelVirtualCardMutation.OPERATION_DOCUMENT) },
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
                    shouldThrow<SudoVirtualCardsClient.VirtualCardException.CardNotFoundException> {
                        client.cancelVirtualCard("id")
                    }
                }
            deferredResult.start()
            delay(100L)
            deferredResult.await()

            verify(mockPublicKeyService).getCurrentKey()
            verify(mockApiCategory).mutate<String>(
                org.mockito.kotlin.check {
                    assertEquals(CancelVirtualCardMutation.OPERATION_DOCUMENT, it.query)
                },
                any(),
                any(),
            )
        }

    @Test
    fun `cancelVirtualCard() should throw when response has an account locked error`() =
        runBlocking<Unit> {
            val errors =
                listOf(
                    GraphQLResponse.Error(
                        "mock",
                        null,
                        null,
                        mapOf("errorType" to "AccountLockedError"),
                    ),
                )
            val mockOperation: GraphQLOperation<String> = mock()
            whenever(
                mockApiCategory.mutate<String>(
                    argThat { this.query.equals(CancelVirtualCardMutation.OPERATION_DOCUMENT) },
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
                    shouldThrow<SudoVirtualCardsClient.VirtualCardException.AccountLockedException> {
                        client.cancelVirtualCard("id")
                    }
                }
            deferredResult.start()
            delay(100L)
            verify(mockPublicKeyService).getCurrentKey()
            verify(mockApiCategory).mutate<String>(
                org.mockito.kotlin.check {
                    assertEquals(CancelVirtualCardMutation.OPERATION_DOCUMENT, it.query)
                },
                any(),
                any(),
            )
        }

    @Test
    fun `cancelVirtualCard() should throw when password retrieval fails`() =
        runBlocking<Unit> {
            mockPublicKeyService.stub {
                onBlocking { getCurrentKey() } doThrow
                    PublicKeyService.PublicKeyServiceException.KeyCreateException(
                        "Mock PublicKey Service Exception",
                    )
            }

            shouldThrow<SudoVirtualCardsClient.VirtualCardException.PublicKeyException> {
                client.cancelVirtualCard("id")
            }

            verify(mockPublicKeyService).getCurrentKey()
        }

    @Test
    fun `cancelVirtualCard() should throw when unsealing fails`() =
        runBlocking<Unit> {
            mockApiCategory.stub {
                on {
                    mockApiCategory.mutate<String>(
                        argThat { this.query.equals(CancelVirtualCardMutation.OPERATION_DOCUMENT) },
                        any(),
                        any(),
                    )
                } doThrow
                    Unsealer.UnsealerException.SealedDataTooShortException(
                        "Mock Unsealer Exception",
                    )
            }

            shouldThrow<SudoVirtualCardsClient.VirtualCardException.UnsealingException> {
                client.cancelVirtualCard("id")
            }

            verify(mockApiCategory).mutate<String>(
                org.mockito.kotlin.check {
                    assertEquals(CancelVirtualCardMutation.OPERATION_DOCUMENT, it.query)
                },
                any(),
                any(),
            )
            verify(mockPublicKeyService).getCurrentKey()
        }

    @Test
    fun `cancelVirtualCard() should throw when http error occurs`() =
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
                mockApiCategory.mutate<String>(
                    argThat { this.query.equals(CancelVirtualCardMutation.OPERATION_DOCUMENT) },
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
                    shouldThrow<SudoVirtualCardsClient.VirtualCardException.CancelFailedException> {
                        client.cancelVirtualCard("id")
                    }
                }
            deferredResult.start()
            delay(100L)
            deferredResult.await()

            verify(mockApiCategory).mutate<String>(
                org.mockito.kotlin.check {
                    assertEquals(CancelVirtualCardMutation.OPERATION_DOCUMENT, it.query)
                },
                any(),
                any(),
            )
            verify(mockPublicKeyService).getCurrentKey()
        }

    @Test
    fun `cancelVirtualCard() should throw when unknown error occurs()`() =
        runBlocking<Unit> {
            mockApiCategory.stub {
                on {
                    mockApiCategory.mutate<String>(
                        argThat { this.query.equals(CancelVirtualCardMutation.OPERATION_DOCUMENT) },
                        any(),
                        any(),
                    )
                } doThrow RuntimeException("Mock Runtime Exception")
            }

            val deferredResult =
                async(Dispatchers.IO) {
                    shouldThrow<SudoVirtualCardsClient.VirtualCardException.UnknownException> {
                        client.cancelVirtualCard("id")
                    }
                }
            deferredResult.start()
            delay(100L)

            deferredResult.await()

            verify(mockApiCategory).mutate<String>(
                org.mockito.kotlin.check {
                    assertEquals(CancelVirtualCardMutation.OPERATION_DOCUMENT, it.query)
                },
                any(),
                any(),
            )
            verify(mockPublicKeyService).getCurrentKey()
        }

    @Test
    fun `cancelVirtualCard() should not block coroutine cancellation exception`() =
        runBlocking<Unit> {
            mockApiCategory.stub {
                on {
                    mockApiCategory.mutate<String>(
                        argThat { this.query.equals(CancelVirtualCardMutation.OPERATION_DOCUMENT) },
                        any(),
                        any(),
                    )
                } doThrow CancellationException("Mock Cancellation Exception")
            }

            shouldThrow<CancellationException> {
                client.cancelVirtualCard("id")
            }

            verify(mockApiCategory).mutate<String>(
                org.mockito.kotlin.check {
                    assertEquals(CancelVirtualCardMutation.OPERATION_DOCUMENT, it.query)
                },
                any(),
                any(),
            )
            verify(mockPublicKeyService).getCurrentKey()
        }
}
