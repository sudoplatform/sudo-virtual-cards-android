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
import com.google.gson.Gson
import com.sudoplatform.sudokeymanager.KeyManagerInterface
import com.sudoplatform.sudologging.Logger
import com.sudoplatform.sudouser.SudoUserClient
import com.sudoplatform.sudouser.amplify.GraphQLClient
import com.sudoplatform.sudovirtualcards.graphql.CancelProvisionalFundingSourceMutation
import com.sudoplatform.sudovirtualcards.graphql.type.FundingSourceType
import com.sudoplatform.sudovirtualcards.graphql.type.ProvisionalFundingSourceState
import com.sudoplatform.sudovirtualcards.types.StripeCardProvisioningData
import io.kotlintest.shouldBe
import io.kotlintest.shouldNotBe
import io.kotlintest.shouldThrow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.apache.commons.codec.binary.Base64
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
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoMoreInteractions
import org.mockito.kotlin.whenever
import java.net.HttpURLConnection

/**
 * Test the correct operation of [SudoVirtualCardsClient.cancelProvisionalFundingSource]
 * using mocks and spies.
 */
class SudoVirtualCardsCancelProvisionalFundingSourceTest() : BaseTests() {

    private val provisionalResponse by before {
        val stripeSetupData =
            StripeCardProvisioningData(
                "stripe",
                1,
                "intent",
                "clientSecret",
                com.sudoplatform.sudovirtualcards.types.FundingSourceType.CREDIT_CARD,
            )

        JSONObject(
            """
                {
                    'cancelProvisionalFundingSource': {
                            '__typename': 'typename',
                            'id':'id',
                            'owner': 'owner',
                            'version': 1,
                            'createdAtEpochMs': 1.0,
                            'updatedAtEpochMs': 10.0,
                            'type': '${FundingSourceType.CREDIT_CARD}',
                            'provisioningData': '${Base64.encodeBase64String(Gson().toJson(stripeSetupData).toByteArray())}',
                            'state': '${ProvisionalFundingSourceState.PROVISIONING}',
                            'last4':'1234'
                        }
                }
            """.trimIndent(),
        )
    }

    private val mockContext by before {
        mock<Context>()
    }

    private val mockUserClient by before {
        mock<SudoUserClient>()
    }

    private val mockApiCategory by before {
        mock<ApiCategory>().stub {
            on {
                mutate<String>(
                    argThat { this.query.equals(CancelProvisionalFundingSourceMutation.OPERATION_DOCUMENT) },
                    any(), any(),
                )
            } doAnswer {
                val mockOperation: GraphQLOperation<String> = mock()
                @Suppress("UNCHECKED_CAST")
                (it.arguments[1] as Consumer<GraphQLResponse<String>>).accept(
                    GraphQLResponse(provisionalResponse.toString(), null),
                )
                mockOperation
            }
        }
    }

    private val mockKeyManager by before {
        mock<KeyManagerInterface>().stub {
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
    fun `cancelProvisionalFundingSource() should return results when no error present`() = runBlocking<Unit> {
        val deferredResult = async(Dispatchers.IO) {
            client.cancelProvisionalFundingSource("id")
        }
        deferredResult.start()
        delay(100L)
        val result = deferredResult.await()
        result shouldNotBe null

        with(result) {
            id shouldBe "id"
            owner shouldBe "owner"
            version shouldBe 1
            createdAt shouldNotBe null
            updatedAt shouldNotBe null
            type shouldBe com.sudoplatform.sudovirtualcards.types.FundingSourceType.CREDIT_CARD
            state shouldBe com.sudoplatform.sudovirtualcards.types.ProvisionalFundingSource.ProvisioningState.PROVISIONING
            last4 shouldBe "1234"
        }
        verify(mockApiCategory).mutate<String>(
            org.mockito.kotlin.check {
                assertEquals(CancelProvisionalFundingSourceMutation.OPERATION_DOCUMENT, it.query)
            },
            any(),
            any(),
        )
    }

    @Test
    fun `cancelProvisionalFundingSource() should throw when mutation response is null`() = runBlocking<Unit> {
        val mockOperation: GraphQLOperation<String> = mock()
        whenever(
            mockApiCategory.mutate<String>(
                argThat { this.query.equals(CancelProvisionalFundingSourceMutation.OPERATION_DOCUMENT) },
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
            shouldThrow<SudoVirtualCardsClient.FundingSourceException.CancelFailedException> {
                client.cancelProvisionalFundingSource("id")
            }
        }
        deferredResult.start()
        delay(100L)
        deferredResult.await()

        verify(mockApiCategory).mutate<String>(
            org.mockito.kotlin.check {
                assertEquals(CancelProvisionalFundingSourceMutation.OPERATION_DOCUMENT, it.query)
            },
            any(),
            any(),
        )
    }

    @Test
    fun `cancelProvisionalFundingSource() should throw when response has a funding source not found error`() = runBlocking<Unit> {
        val errors = listOf(
            GraphQLResponse.Error(
                "mock",
                null,
                null,
                mapOf("errorType" to "FundingSourceNotFoundError"),
            ),
        )
        val mockOperation: GraphQLOperation<String> = mock()
        whenever(
            mockApiCategory.mutate<String>(
                argThat { this.query.equals(CancelProvisionalFundingSourceMutation.OPERATION_DOCUMENT) },
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
            shouldThrow<SudoVirtualCardsClient.FundingSourceException.FundingSourceNotFoundException> {
                client.cancelProvisionalFundingSource("id")
            }
        }
        deferredResult.start()
        delay(100L)
        deferredResult.await()

        verify(mockApiCategory).mutate<String>(
            org.mockito.kotlin.check {
                assertEquals(CancelProvisionalFundingSourceMutation.OPERATION_DOCUMENT, it.query)
            },
            any(),
            any(),
        )
    }

    @Test
    fun `cancelProvisionalFundingSource() should throw when response has an account locked error`() = runBlocking<Unit> {
        val errors = listOf(
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
                argThat { this.query.equals(CancelProvisionalFundingSourceMutation.OPERATION_DOCUMENT) },
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
            shouldThrow<SudoVirtualCardsClient.FundingSourceException.AccountLockedException> {
                client.cancelProvisionalFundingSource("id")
            }
        }
        deferredResult.start()
        delay(100L)
        deferredResult.await()

        verify(mockApiCategory).mutate<String>(
            org.mockito.kotlin.check {
                assertEquals(CancelProvisionalFundingSourceMutation.OPERATION_DOCUMENT, it.query)
            },
            any(),
            any(),
        )
    }

    @Test
    fun `cancelProvisionalFundingSource() should throw when http error occurs`() = runBlocking<Unit> {
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
            mockApiCategory.mutate<String>(
                argThat { this.query.equals(CancelProvisionalFundingSourceMutation.OPERATION_DOCUMENT) },
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
            shouldThrow<SudoVirtualCardsClient.FundingSourceException.CancelFailedException> {
                client.cancelProvisionalFundingSource("id")
            }
        }
        deferredResult.start()
        delay(100L)
        deferredResult.await()

        verify(mockApiCategory).mutate<String>(
            org.mockito.kotlin.check {
                assertEquals(CancelProvisionalFundingSourceMutation.OPERATION_DOCUMENT, it.query)
            },
            any(),
            any(),
        )
    }

    @Test
    fun `cancelProvisionalFundingSource() should throw when unknown error occurs`() = runBlocking<Unit> {
        mockApiCategory.stub {
            on {
                mockApiCategory.mutate<String>(
                    argThat { this.query.equals(CancelProvisionalFundingSourceMutation.OPERATION_DOCUMENT) },
                    any(),
                    any(),
                )
            } doThrow RuntimeException("Mock Runtime Exception")
        }

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.FundingSourceException.UnknownException> {
                client.cancelProvisionalFundingSource("id")
            }
        }
        deferredResult.start()
        delay(100L)
        deferredResult.await()

        verify(mockApiCategory).mutate<String>(
            org.mockito.kotlin.check {
                assertEquals(CancelProvisionalFundingSourceMutation.OPERATION_DOCUMENT, it.query)
            },
            any(),
            any(),
        )
    }

    @Test
    fun `cancelProvisionalFundingSource() should not suppress CancellationException`() = runBlocking<Unit> {
        mockApiCategory.stub {
            on {
                mockApiCategory.mutate<String>(
                    argThat { this.query.equals(CancelProvisionalFundingSourceMutation.OPERATION_DOCUMENT) },
                    any(),
                    any(),
                )
            } doThrow CancellationException("Mock Cancellation Exception")
        }

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<CancellationException> {
                client.cancelProvisionalFundingSource("id")
            }
        }
        deferredResult.start()
        delay(100L)
        deferredResult.await()

        verify(mockApiCategory).mutate<String>(
            org.mockito.kotlin.check {
                assertEquals(CancelProvisionalFundingSourceMutation.OPERATION_DOCUMENT, it.query)
            },
            any(),
            any(),
        )
    }
}
