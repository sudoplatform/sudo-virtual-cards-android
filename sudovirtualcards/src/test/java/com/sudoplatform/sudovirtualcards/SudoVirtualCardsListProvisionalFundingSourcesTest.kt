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
import com.sudoplatform.sudovirtualcards.graphql.ListProvisionalFundingSourcesQuery
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
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.check
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.stub
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoMoreInteractions
import org.mockito.kotlin.whenever
import java.net.HttpURLConnection

/**
 * Test the correct operation of [SudoVirtualCardsClient.listProvisionalFundingSources]
 * using mocks and spies.
 */
class SudoVirtualCardsListProvisionalFundingSourcesTest : BaseTests() {

    private val queryResponse by before {
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
                    'listProvisionalFundingSources': {
                        'items': [{
                            '__typename': 'ProvisionalFundingSource',
                            'id':'id',
                            'owner': 'owner',
                            'version': 1,
                            'createdAtEpochMs': 1.0,
                            'updatedAtEpochMs': 1.0,
                            'type': '${FundingSourceType.CREDIT_CARD}',
                            'provisioningData': '${Base64.encodeBase64String(Gson().toJson(stripeSetupData).toByteArray())}',
                            'state': '${ProvisionalFundingSourceState.PROVISIONING}',
                            'last4':'1234'
                        }],
                        'nextToken': null
                    }
                }
            """.trimIndent(),
        )
    }

    private val queryResponseWithNextToken by before {
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
                    'listProvisionalFundingSources': {
                        'items': [{
                            '__typename': 'ProvisionalFundingSource',
                            'id':'id',
                            'owner': 'owner',
                            'version': 1,
                            'createdAtEpochMs': 1.0,
                            'updatedAtEpochMs': 1.0,
                            'type': '${FundingSourceType.CREDIT_CARD}',
                            'provisioningData': '${Base64.encodeBase64String(Gson().toJson(stripeSetupData).toByteArray())}',
                            'state': '${ProvisionalFundingSourceState.PROVISIONING}',
                            'last4':'1234'
                        }],
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
                    'listProvisionalFundingSources': {
                        'items': []
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
                query<String>(
                    argThat { this.query.equals(ListProvisionalFundingSourcesQuery.OPERATION_DOCUMENT) },
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
        mock<KeyManagerInterface>()
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
    fun `ListProvisionalFundingSources() should return results when no error present`() = runBlocking<Unit> {
        val deferredResult = async(Dispatchers.IO) {
            client.listProvisionalFundingSources()
        }
        deferredResult.start()

        delay(100L)
        val result = deferredResult.await()
        result shouldNotBe null
        result.items.isEmpty() shouldBe false
        result.items.size shouldBe 1
        result.nextToken shouldBe null

        with(result.items[0]) {
            id shouldBe("id")
            owner shouldBe("owner")
            version shouldBe 1
            state shouldBe com.sudoplatform.sudovirtualcards.types.ProvisionalFundingSource.ProvisioningState.PROVISIONING
            last4 shouldBe "1234"
        }

        verify(mockApiCategory).query<String>(
            check {
                assertEquals(ListProvisionalFundingSourcesQuery.OPERATION_DOCUMENT, it.query)
            },
            any(),
            any(),
        )
    }

    @Test
    fun `ListProvisionalFundingSources() should return results when populating nextToken`() = runBlocking<Unit> {
        val mockOperation: GraphQLOperation<String> = mock()
        whenever(
            mockApiCategory.query<String>(
                argThat { this.query.equals(ListProvisionalFundingSourcesQuery.OPERATION_DOCUMENT) },
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

        val deferredResult = async(Dispatchers.IO) {
            client.listProvisionalFundingSources()
        }
        deferredResult.start()

        delay(100L)
        val result = deferredResult.await()

        result shouldNotBe null
        result.items.isEmpty() shouldBe false
        result.items.size shouldBe 1
        result.nextToken shouldBe "dummyNextToken"

        with(result.items[0]) {
            id shouldBe("id")
            owner shouldBe("owner")
            version shouldBe 1
            state shouldBe com.sudoplatform.sudovirtualcards.types.ProvisionalFundingSource.ProvisioningState.PROVISIONING
            last4 shouldBe "1234"
            type shouldBe com.sudoplatform.sudovirtualcards.types.FundingSourceType.CREDIT_CARD
        }

        verify(mockApiCategory).query<String>(
            check {
                assertEquals(ListProvisionalFundingSourcesQuery.OPERATION_DOCUMENT, it.query)
            },
            any(),
            any(),
        )
    }

    @Test
    fun `ListProvisionalFundingSources() should return empty list output when query result data is empty`() = runBlocking<Unit> {
        val mockOperation: GraphQLOperation<String> = mock()
        whenever(
            mockApiCategory.query<String>(
                argThat { this.query.equals(ListProvisionalFundingSourcesQuery.OPERATION_DOCUMENT) },
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
            client.listProvisionalFundingSources()
        }
        deferredResult.start()
        delay(100L)

        val result = deferredResult.await()
        result shouldNotBe null
        result.items.isEmpty() shouldBe true
        result.items.size shouldBe 0
        result.nextToken shouldBe null

        verify(mockApiCategory).query<String>(
            check {
                assertEquals(ListProvisionalFundingSourcesQuery.OPERATION_DOCUMENT, it.query)
            },
            any(),
            any(),
        )
    }

    @Test
    fun `ListProvisionalFundingSources() should return empty list output when query response is null`() = runBlocking<Unit> {
        val mockOperation: GraphQLOperation<String> = mock()
        whenever(
            mockApiCategory.query<String>(
                argThat { this.query.equals(ListProvisionalFundingSourcesQuery.OPERATION_DOCUMENT) },
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
            client.listProvisionalFundingSources()
        }
        deferredResult.start()
        delay(100L)

        val result = deferredResult.await()
        result shouldNotBe null
        result.items.isEmpty() shouldBe true
        result.items.size shouldBe 0
        result.nextToken shouldBe null

        verify(mockApiCategory).query<String>(
            check {
                assertEquals(ListProvisionalFundingSourcesQuery.OPERATION_DOCUMENT, it.query)
            },
            any(),
            any(),
        )
    }

    @Test
    fun `ListProvisionalFundingSources() should throw when http error occurs`() = runBlocking<Unit> {
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
                argThat { this.query.equals(ListProvisionalFundingSourcesQuery.OPERATION_DOCUMENT) },
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
            shouldThrow<SudoVirtualCardsClient.FundingSourceException.FailedException> {
                client.listProvisionalFundingSources()
            }
        }
        deferredResult.start()
        delay(100L)
        deferredResult.await()

        verify(mockApiCategory).query<String>(
            check {
                assertEquals(ListProvisionalFundingSourcesQuery.OPERATION_DOCUMENT, it.query)
            },
            any(),
            any(),
        )
    }

    @Test
    fun `ListProvisionalFundingSources() should throw when unknown error occurs`() = runBlocking<Unit> {
        mockApiCategory.stub {
            on {
                query<String>(
                    argThat { this.query.equals(ListProvisionalFundingSourcesQuery.OPERATION_DOCUMENT) },
                    any(),
                    any(),
                )
            } doThrow RuntimeException("Mock Runtime Exception")
        }

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.FundingSourceException.UnknownException> {
                client.listProvisionalFundingSources()
            }
        }
        deferredResult.start()
        delay(100L)

        deferredResult.await()

        verify(mockApiCategory).query<String>(
            check {
                assertEquals(ListProvisionalFundingSourcesQuery.OPERATION_DOCUMENT, it.query)
            },
            any(),
            any(),
        )
    }

    @Test
    fun `ListProvisionalFundingSources() should not suppress CancellationException`() = runBlocking<Unit> {
        mockApiCategory.stub {
            on {
                query<String>(
                    argThat { this.query.equals(ListProvisionalFundingSourcesQuery.OPERATION_DOCUMENT) },
                    any(),
                    any(),
                )
            } doThrow CancellationException("Mock Cancellation Exception")
        }

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<CancellationException> {
                client.listProvisionalFundingSources()
            }
        }
        deferredResult.start()
        delay(100L)

        deferredResult.await()

        verify(mockApiCategory).query<String>(
            check {
                assertEquals(ListProvisionalFundingSourcesQuery.OPERATION_DOCUMENT, it.query)
            },
            any(),
            any(),
        )
    }
}
