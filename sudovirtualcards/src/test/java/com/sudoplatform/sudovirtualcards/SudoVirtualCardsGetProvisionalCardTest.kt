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
import com.sudoplatform.sudologging.Logger
import com.sudoplatform.sudouser.SudoUserClient
import com.sudoplatform.sudouser.amplify.GraphQLClient
import com.sudoplatform.sudovirtualcards.graphql.GetProvisionalCardQuery
import com.sudoplatform.sudovirtualcards.graphql.type.ProvisioningState
import com.sudoplatform.sudovirtualcards.types.ProvisionalVirtualCard
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
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.check
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.stub
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoMoreInteractions
import org.mockito.kotlin.whenever

/**
 * Test the correct operation of [SudoVirtualCardsClient.getProvisionalCard]
 * using mocks and spies.
 */
class SudoVirtualCardsGetProvisionalCardTest : BaseTests() {

    private val queryResponse by before {
        JSONObject(
            """
                {
                    'getProvisionalCard': {
                            '__typename': 'ProvisionalCard',
                            'id':'id',
                            'owner': 'owner',
                            'version': 1,
                            'createdAtEpochMs': 1.0,
                            'updatedAtEpochMs': 1.0,
                            'clientRefId': 'clientRefId',
                            'provisioningState': '${ProvisioningState.PROVISIONING}'
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
                    argThat { this.query.equals(GetProvisionalCardQuery.OPERATION_DOCUMENT) },
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
    fun `getProvisionalCard() should return results when no error present`() = runBlocking<Unit> {
        val deferredResult = async(Dispatchers.IO) {
            client.getProvisionalCard("id")
        }
        deferredResult.start()
        delay(100L)
        val result = deferredResult.await()
        result shouldNotBe null

        with(result!!) {
            id shouldBe "id"
            clientRefId shouldBe "clientRefId"
            owner shouldBe "owner"
            version shouldBe 1
            provisioningState shouldBe ProvisionalVirtualCard.ProvisioningState.PROVISIONING
            card shouldBe null
            createdAt shouldNotBe null
            updatedAt shouldNotBe null
        }

        verify(mockApiCategory).query<String>(
            check {
                assertEquals(GetProvisionalCardQuery.OPERATION_DOCUMENT, it.query)
            },
            any(),
            any(),
        )
    }

    @Test
    fun `getProvisionalCard() should throw when query response is null`() = runBlocking<Unit> {
        val mockOperation: GraphQLOperation<String> = mock()
        whenever(
            mockApiCategory.query<String>(
                argThat { this.query.equals(GetProvisionalCardQuery.OPERATION_DOCUMENT) },
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
            client.getProvisionalCard("id")
        }
        deferredResult.start()
        delay(100L)
        val result = deferredResult.await()
        result shouldBe null

        verify(mockApiCategory).query<String>(
            check {
                assertEquals(GetProvisionalCardQuery.OPERATION_DOCUMENT, it.query)
            },
            any(),
            any(),
        )
    }

    @Test
    fun `getProvisionalCard() should throw when query response has errors`() = runBlocking<Unit> {
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
                argThat { this.query.equals(GetProvisionalCardQuery.OPERATION_DOCUMENT) },
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
            shouldThrow<SudoVirtualCardsClient.VirtualCardException.IdentityVerificationException> {
                client.getProvisionalCard("id")
            }
        }
        deferredResult.start()
        delay(100L)

        verify(mockApiCategory).query<String>(
            check {
                assertEquals(GetProvisionalCardQuery.OPERATION_DOCUMENT, it.query)
            },
            any(),
            any(),
        )
    }

    @Test
    fun `getProvisionalCard() should not block coroutine cancellation exception`() = runBlocking<Unit> {
        mockApiCategory.stub {
            on {
                mockApiCategory.query<String>(
                    argThat { this.query.equals(GetProvisionalCardQuery.OPERATION_DOCUMENT) },
                    any(),
                    any(),
                )
            } doThrow CancellationException("mock")
        }

        shouldThrow<CancellationException> {
            client.getProvisionalCard("id")
        }
        verify(mockApiCategory).query<String>(
            check {
                assertEquals(GetProvisionalCardQuery.OPERATION_DOCUMENT, it.query)
            },
            any(),
            any(),
        )
    }

    @Test
    fun `getProvisionalCard() should throw when key registration fails`() = runBlocking<Unit> {
        mockApiCategory.stub {
            on {
                mockApiCategory.query<String>(
                    argThat { this.query.equals(GetProvisionalCardQuery.OPERATION_DOCUMENT) },
                    any(),
                    any(),
                )
            } doThrow Unsealer.UnsealerException.SealedDataTooShortException("mock")
        }

        shouldThrow<SudoVirtualCardsClient.VirtualCardException.UnsealingException> {
            client.getProvisionalCard("id")
        }
        verify(mockApiCategory).query<String>(
            check {
                assertEquals(GetProvisionalCardQuery.OPERATION_DOCUMENT, it.query)
            },
            any(),
            any(),
        )
    }
}
