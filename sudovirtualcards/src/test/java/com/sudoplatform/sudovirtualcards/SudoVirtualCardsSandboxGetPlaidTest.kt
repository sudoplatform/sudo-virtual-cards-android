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
import com.sudoplatform.sudouser.SudoUserClient
import com.sudoplatform.sudouser.amplify.GraphQLClient
import com.sudoplatform.sudovirtualcards.graphql.SandboxGetPlaidDataQuery
import com.sudoplatform.sudovirtualcards.keys.PublicKeyService
import com.sudoplatform.sudovirtualcards.types.BankAccountFundingSource
import com.sudoplatform.sudovirtualcards.types.PlaidAccountMetadata
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
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.stub
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoMoreInteractions
import org.mockito.kotlin.whenever
import java.net.HttpURLConnection

/**
 * Test the correct operation of [SudoVirtualCardsClient.sandboxGetPlaidData]
 * using mocks and spies.
 */
class SudoVirtualCardsSandboxGetPlaidTest : BaseTests() {

    private val queryResponse by before {
        JSONObject(
            """
                {
                    'sandboxGetPlaidData': {
                        'accountMetadata': [{
                            'accountId': 'checkingAccountId',
                            'subtype': 'checking'
                        },{
                            'accountId': 'savingsAccountId',
                            'subtype': 'savings'
                        },{
                            'accountId': 'otherAccountId',
                            'subtype': 'other'
                        },{
                            'accountId': 'unspecifiedAccountId',
                            'subtype': null
                        }],
                        'publicToken':'publicToken'
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
                    argThat { this.query.equals(SandboxGetPlaidDataQuery.OPERATION_DOCUMENT) },
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

    private val mockPublicKeyService by before {
        mock<PublicKeyService>()
    }

    private val client by before {
        SudoVirtualCardsClient.builder()
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
        verifyNoMoreInteractions(
            mockContext,
            mockUserClient,
            mockApiCategory,
            mockKeyManager,
            mockPublicKeyService,
        )
    }

    @Test
    fun `sandboxGetPlaidData() should return results when no error present`() = runBlocking<Unit> {
        val deferredResult = async(Dispatchers.IO) {
            client.sandboxGetPlaidData("institutionId", "plaidUsername")
        }
        deferredResult.start()

        delay(100L)
        val result = deferredResult.await()
        result shouldNotBe null

        with(result) {
            accountMetadata shouldBe listOf(
                PlaidAccountMetadata("checkingAccountId", BankAccountFundingSource.BankAccountType.CHECKING),
                PlaidAccountMetadata("savingsAccountId", BankAccountFundingSource.BankAccountType.SAVING),
                PlaidAccountMetadata("otherAccountId", BankAccountFundingSource.BankAccountType.UNKNOWN),
                PlaidAccountMetadata("unspecifiedAccountId", BankAccountFundingSource.BankAccountType.UNKNOWN),
            )
            publicToken shouldBe "publicToken"
        }
        verify(mockApiCategory).query<String>(
            org.mockito.kotlin.check {
                assertEquals(SandboxGetPlaidDataQuery.OPERATION_DOCUMENT, it.query)
            },
            any(),
            any(),
        )
    }

    @Test
    fun `sandboxGetPlaidData() should throw when query response is null`() = runBlocking<Unit> {
        val mockOperation: GraphQLOperation<String> = mock()
        whenever(
            mockApiCategory.query<String>(
                argThat { this.query.equals(SandboxGetPlaidDataQuery.OPERATION_DOCUMENT) },
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
            shouldThrow<SudoVirtualCardsClient.FundingSourceException.FailedException> {
                client.sandboxGetPlaidData("institutionId", "plaidUsername")
            }
        }
        deferredResult.start()

        delay(100L)
        verify(mockApiCategory).query<String>(
            org.mockito.kotlin.check {
                assertEquals(SandboxGetPlaidDataQuery.OPERATION_DOCUMENT, it.query)
            },
            any(),
            any(),
        )
    }

    @Test
    fun `sandboxGetPlaidData() should throw when query response has errors`() = runBlocking<Unit> {
        val errors = listOf(
            GraphQLResponse.Error(
                "mock",
                null,
                null,
                mapOf("errorType" to "IdentityVerificationNotVerifiedError"),
            ),
        )
        val mockOperation: GraphQLOperation<String> = mock()
        whenever(
            mockApiCategory.query<String>(
                argThat { this.query.equals(SandboxGetPlaidDataQuery.OPERATION_DOCUMENT) },
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
            shouldThrow<SudoVirtualCardsClient.FundingSourceException.IdentityVerificationException> {
                client.sandboxGetPlaidData("institutionId", "plaidUsername")
            }
        }
        deferredResult.start()
        delay(100L)
        deferredResult.await()

        verify(mockApiCategory).query<String>(
            org.mockito.kotlin.check {
                assertEquals(SandboxGetPlaidDataQuery.OPERATION_DOCUMENT, it.query)
            },
            any(),
            any(),
        )
    }

    @Test
    fun `sandboxGetPlaidData() should throw when http error occurs`() = runBlocking<Unit> {
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
                argThat { this.query.equals(SandboxGetPlaidDataQuery.OPERATION_DOCUMENT) },
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
                client.sandboxGetPlaidData("institutionId", "plaidUsername")
            }
        }
        deferredResult.start()
        delay(100L)

        deferredResult.await()

        verify(mockApiCategory).query<String>(
            org.mockito.kotlin.check {
                assertEquals(SandboxGetPlaidDataQuery.OPERATION_DOCUMENT, it.query)
            },
            any(),
            any(),
        )
    }

    @Test
    fun `sandboxGetPlaidData() should throw when unknown error occurs()`() = runBlocking<Unit> {
        mockApiCategory.stub {
            on {
                query<String>(
                    argThat { this.query.equals(SandboxGetPlaidDataQuery.OPERATION_DOCUMENT) },
                    any(),
                    any(),
                )
            } doThrow RuntimeException("Mock Runtime Exception")
        }

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.FundingSourceException.UnknownException> {
                client.sandboxGetPlaidData("institutionId", "plaidUsername")
            }
        }
        deferredResult.start()
        delay(100L)

        deferredResult.await()

        verify(mockApiCategory).query<String>(
            org.mockito.kotlin.check {
                assertEquals(SandboxGetPlaidDataQuery.OPERATION_DOCUMENT, it.query)
            },
            any(),
            any(),
        )
    }

    @Test
    fun `sandboxGetPlaidData() should not block coroutine cancellation exception`() = runBlocking<Unit> {
        mockApiCategory.stub {
            on {
                query<String>(
                    argThat { this.query.equals(SandboxGetPlaidDataQuery.OPERATION_DOCUMENT) },
                    any(),
                    any(),
                )
            } doThrow CancellationException("Mock Cancellation Exception")
        }

        shouldThrow<CancellationException> {
            client.sandboxGetPlaidData("institutionId", "plaidUsername")
        }

        verify(mockApiCategory).query<String>(
            org.mockito.kotlin.check {
                assertEquals(SandboxGetPlaidDataQuery.OPERATION_DOCUMENT, it.query)
            },
            any(),
            any(),
        )
    }
}
