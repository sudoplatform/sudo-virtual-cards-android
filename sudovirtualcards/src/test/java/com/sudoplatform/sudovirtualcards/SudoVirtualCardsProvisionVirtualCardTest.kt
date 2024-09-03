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
import com.sudoplatform.sudovirtualcards.graphql.ProvisionVirtualCardMutation
import com.sudoplatform.sudovirtualcards.graphql.type.ProvisioningState
import com.sudoplatform.sudovirtualcards.keys.PublicKeyService
import com.sudoplatform.sudovirtualcards.keys.PublicKeyWithKeyRingId
import com.sudoplatform.sudovirtualcards.types.ProvisionalVirtualCard
import com.sudoplatform.sudovirtualcards.types.inputs.ProvisionVirtualCardInput
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
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.stub
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoMoreInteractions
import org.mockito.kotlin.whenever

/**
 * Test the correct operation of [SudoVirtualCardsClient.provisionVirtualCard]
 * using mocks and spies.
 */
class SudoVirtualCardsProvisionVirtualCardTest : BaseTests() {

    private val input by before {
        ProvisionVirtualCardInput(
            "clientRefId",
            listOf("ownershipProof"),
            "fundingSourceId",
            "cardHolder",
            "alias",
            null,
            "addressLine1",
            "addressLine2",
            "city",
            "state",
            "postalCode",
            "country",
            "currency",
        )
    }

    private val cardProvisionResponse by before {
        JSONObject(
            """
                {
                    'cardProvision': {
                        '__typename': 'CardProvision',
                        'id':'id',
                        'owner': 'owner',
                        'version': 1,
                        'createdAtEpochMs': 1.0,
                        'updatedAtEpochMs': 1.0,
                        'clientRefId': 'clientRefId',
                        'provisioningState': '${ProvisioningState.PROVISIONING}',
                        'card': [],
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
                    argThat { this.query.equals(ProvisionVirtualCardMutation.OPERATION_DOCUMENT) },
                    any(), any(),
                )
            } doAnswer {
                val mockOperation: GraphQLOperation<String> = mock()
                @Suppress("UNCHECKED_CAST")
                (it.arguments[1] as Consumer<GraphQLResponse<String>>).accept(
                    GraphQLResponse(cardProvisionResponse.toString(), null),
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

    private val currentKey = PublicKey(
        keyId = "keyId",
        publicKey = "publicKey".toByteArray(),
    )

    private val currentKeyWithKeyRingId = PublicKeyWithKeyRingId(
        publicKey = currentKey,
        keyRingId = "keyRingId",
    )

    private val mockPublicKeyService by before {
        mock<PublicKeyService>().stub {
            onBlocking { getCurrentKey() } doReturn currentKey
            onBlocking { getCurrentRegisteredKey() } doReturn currentKeyWithKeyRingId
        }
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
            mockKeyManager,
            mockPublicKeyService,
            mockApiCategory,
        )
    }

    @Test
    fun `provisionVirtualCard() should return results when no error present`() = runBlocking<Unit> {
        val deferredResult = async(Dispatchers.IO) {
            client.provisionVirtualCard(input)
        }
        deferredResult.start()

        delay(100L)

        val result = deferredResult.await()
        result shouldNotBe null

        with(result) {
            id shouldBe "id"
            clientRefId shouldBe "clientRefId"
            owner shouldBe "owner"
            version shouldBe 1
            provisioningState shouldBe ProvisionalVirtualCard.ProvisioningState.PROVISIONING
            card shouldBe null
            createdAt shouldNotBe null
            updatedAt shouldNotBe null
        }

        verify(mockPublicKeyService).getCurrentRegisteredKey()
        verify(mockApiCategory).mutate<String>(
            org.mockito.kotlin.check {
                assertEquals(ProvisionVirtualCardMutation.OPERATION_DOCUMENT, it.query)
            },
            any(),
            any(),
        )
    }

    @Test
    fun `provisionVirtualCard() should throw when registered public key retrieval fails`() = runBlocking<Unit> {
        mockPublicKeyService.stub {
            onBlocking { getCurrentRegisteredKey() } doThrow PublicKeyService.PublicKeyServiceException.KeyCreateException()
        }

        shouldThrow<SudoVirtualCardsClient.VirtualCardException.PublicKeyException> {
            client.provisionVirtualCard(input)
        }

        verify(mockPublicKeyService).getCurrentRegisteredKey()
        verify(mockApiCategory, never()).mutate<String>(
            org.mockito.kotlin.check {
                assertEquals(ProvisionVirtualCardMutation.OPERATION_DOCUMENT, it.query)
            },
            any(),
            any(),
        )
    }

    @Test
    fun `provisionVirtualCard() should throw when card mutation response is null`() = runBlocking<Unit> {
        val mockOperation: GraphQLOperation<String> = mock()
        whenever(
            mockApiCategory.mutate<String>(
                argThat { this.query.equals(ProvisionVirtualCardMutation.OPERATION_DOCUMENT) },
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
            shouldThrow<SudoVirtualCardsClient.VirtualCardException.ProvisionFailedException> {
                client.provisionVirtualCard(input)
            }
        }
        deferredResult.start()

        delay(100L)
        deferredResult.await()

        verify(mockPublicKeyService).getCurrentRegisteredKey()
        verify(mockApiCategory).mutate<String>(
            org.mockito.kotlin.check {
                assertEquals(ProvisionVirtualCardMutation.OPERATION_DOCUMENT, it.query)
            },
            any(),
            any(),
        )
    }

    @Test
    fun `provisionVirtualCard() should throw when response has an identity verification not verified error`() = runBlocking<Unit> {
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
            mockApiCategory.mutate<String>(
                argThat { this.query.equals(ProvisionVirtualCardMutation.OPERATION_DOCUMENT) },
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
                client.provisionVirtualCard(input)
            }
        }
        deferredResult.start()

        delay(100L)
        deferredResult.await()

        verify(mockPublicKeyService).getCurrentRegisteredKey()
        verify(mockApiCategory).mutate<String>(
            org.mockito.kotlin.check {
                assertEquals(ProvisionVirtualCardMutation.OPERATION_DOCUMENT, it.query)
            },
            any(),
            any(),
        )
    }

    @Test
    fun `provisionVirtualCard() should throw when response has an identity verification insufficient error`() = runBlocking<Unit> {
        val errors = listOf(
            GraphQLResponse.Error(
                "mock",
                null,
                null,
                mapOf("errorType" to "IdentityVerificationInsufficientError"),
            ),
        )
        val mockOperation: GraphQLOperation<String> = mock()
        whenever(
            mockApiCategory.mutate<String>(
                argThat { this.query.equals(ProvisionVirtualCardMutation.OPERATION_DOCUMENT) },
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
            shouldThrow<SudoVirtualCardsClient.VirtualCardException.IdentityVerificationInsufficientException> {
                client.provisionVirtualCard(input)
            }
        }
        deferredResult.start()

        delay(100L)
        deferredResult.await()

        verify(mockPublicKeyService).getCurrentRegisteredKey()
        verify(mockApiCategory).mutate<String>(
            org.mockito.kotlin.check {
                assertEquals(ProvisionVirtualCardMutation.OPERATION_DOCUMENT, it.query)
            },
            any(),
            any(),
        )
    }

    @Test
    fun `provisionVirtualCard() should throw when response has a funding source not found error`() = runBlocking<Unit> {
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
                argThat { this.query.equals(ProvisionVirtualCardMutation.OPERATION_DOCUMENT) },
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
            shouldThrow<SudoVirtualCardsClient.VirtualCardException.FundingSourceNotFoundException> {
                client.provisionVirtualCard(input)
            }
        }
        deferredResult.start()

        delay(100L)
        deferredResult.await()

        verify(mockPublicKeyService).getCurrentRegisteredKey()
        verify(mockApiCategory).mutate<String>(
            org.mockito.kotlin.check {
                assertEquals(ProvisionVirtualCardMutation.OPERATION_DOCUMENT, it.query)
            },
            any(),
            any(),
        )
    }

    @Test
    fun `provisionVirtualCard() should throw when response has a funding source not active error`() = runBlocking<Unit> {
        val errors = listOf(
            GraphQLResponse.Error(
                "mock",
                null,
                null,
                mapOf("errorType" to "FundingSourceNotActiveError"),
            ),
        )
        val mockOperation: GraphQLOperation<String> = mock()
        whenever(
            mockApiCategory.mutate<String>(
                argThat { this.query.equals(ProvisionVirtualCardMutation.OPERATION_DOCUMENT) },
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
            shouldThrow<SudoVirtualCardsClient.VirtualCardException.FundingSourceNotActiveException> {
                client.provisionVirtualCard(input)
            }
        }
        deferredResult.start()

        delay(100L)
        deferredResult.await()

        verify(mockPublicKeyService).getCurrentRegisteredKey()
        verify(mockApiCategory).mutate<String>(
            org.mockito.kotlin.check {
                assertEquals(ProvisionVirtualCardMutation.OPERATION_DOCUMENT, it.query)
            },
            any(),
            any(),
        )
    }

    @Test
    fun `provisionVirtualCard() should throw when response has a velocity exceeded error`() = runBlocking<Unit> {
        val errors = listOf(
            GraphQLResponse.Error(
                "mock",
                null,
                null,
                mapOf("errorType" to "VelocityExceededError"),
            ),
        )
        val mockOperation: GraphQLOperation<String> = mock()
        whenever(
            mockApiCategory.mutate<String>(
                argThat { this.query.equals(ProvisionVirtualCardMutation.OPERATION_DOCUMENT) },
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
            shouldThrow<SudoVirtualCardsClient.VirtualCardException.VelocityExceededException> {
                client.provisionVirtualCard(input)
            }
        }
        deferredResult.start()

        delay(100L)

        deferredResult.await()

        verify(mockPublicKeyService).getCurrentRegisteredKey()
        verify(mockApiCategory).mutate<String>(
            org.mockito.kotlin.check {
                assertEquals(ProvisionVirtualCardMutation.OPERATION_DOCUMENT, it.query)
            },
            any(),
            any(),
        )
    }

    @Test
    fun `provisionVirtualCard() should throw when response has an entitlement exceeded error`() = runBlocking<Unit> {
        val errors = listOf(
            GraphQLResponse.Error(
                "mock",
                null,
                null,
                mapOf("errorType" to "EntitlementExceededError"),
            ),
        )
        val mockOperation: GraphQLOperation<String> = mock()
        whenever(
            mockApiCategory.mutate<String>(
                argThat { this.query.equals(ProvisionVirtualCardMutation.OPERATION_DOCUMENT) },
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
            shouldThrow<SudoVirtualCardsClient.VirtualCardException.EntitlementExceededException> {
                client.provisionVirtualCard(input)
            }
        }

        deferredResult.start()

        delay(100L)

        deferredResult.await()

        verify(mockPublicKeyService).getCurrentRegisteredKey()
        verify(mockApiCategory).mutate<String>(
            org.mockito.kotlin.check {
                assertEquals(ProvisionVirtualCardMutation.OPERATION_DOCUMENT, it.query)
            },
            any(),
            any(),
        )
    }

    @Test
    fun `provisionVirtualCard() should throw when response has an unsupported currency error`() = runBlocking<Unit> {
        val errors = listOf(
            GraphQLResponse.Error(
                "mock",
                null,
                null,
                mapOf("errorType" to "UnsupportedCurrencyError"),
            ),
        )
        val mockOperation: GraphQLOperation<String> = mock()
        whenever(
            mockApiCategory.mutate<String>(
                argThat { this.query.equals(ProvisionVirtualCardMutation.OPERATION_DOCUMENT) },
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
            shouldThrow<SudoVirtualCardsClient.VirtualCardException.UnsupportedCurrencyException> {
                client.provisionVirtualCard(input)
            }
        }
        deferredResult.start()

        delay(100L)
        deferredResult.await()

        verify(mockPublicKeyService).getCurrentRegisteredKey()
        verify(mockApiCategory).mutate<String>(
            org.mockito.kotlin.check {
                assertEquals(ProvisionVirtualCardMutation.OPERATION_DOCUMENT, it.query)
            },
            any(),
            any(),
        )
    }

    @Test
    fun `provisionVirtualCard() should throw when response has an invalid token error`() = runBlocking<Unit> {
        val errors = listOf(
            GraphQLResponse.Error(
                "mock",
                null,
                null,
                mapOf("errorType" to "InvalidTokenError"),
            ),
        )
        val mockOperation: GraphQLOperation<String> = mock()
        whenever(
            mockApiCategory.mutate<String>(
                argThat { this.query.equals(ProvisionVirtualCardMutation.OPERATION_DOCUMENT) },
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
            shouldThrow<SudoVirtualCardsClient.VirtualCardException.ProvisionFailedException> {
                client.provisionVirtualCard(input)
            }
        }
        deferredResult.start()

        delay(100L)
        deferredResult.await()

        verify(mockPublicKeyService).getCurrentRegisteredKey()
        verify(mockApiCategory).mutate<String>(
            org.mockito.kotlin.check {
                assertEquals(ProvisionVirtualCardMutation.OPERATION_DOCUMENT, it.query)
            },
            any(),
            any(),
        )
    }

    @Test
    fun `provisionVirtualCard() should throw when response has an account locked error`() = runBlocking<Unit> {
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
                argThat { this.query.equals(ProvisionVirtualCardMutation.OPERATION_DOCUMENT) },
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
            shouldThrow<SudoVirtualCardsClient.VirtualCardException.AccountLockedException> {
                client.provisionVirtualCard(input)
            }
        }
        deferredResult.start()

        delay(100L)

        deferredResult.await()

        verify(mockPublicKeyService).getCurrentRegisteredKey()
        verify(mockApiCategory).mutate<String>(
            org.mockito.kotlin.check {
                assertEquals(ProvisionVirtualCardMutation.OPERATION_DOCUMENT, it.query)
            },
            any(),
            any(),
        )
    }

    @Test
    fun `provisionVirtualCard() should not block coroutine cancellation exception`() = runBlocking<Unit> {
        mockPublicKeyService.stub {
            onBlocking { getCurrentRegisteredKey() } doThrow CancellationException("mock")
        }

        shouldThrow<CancellationException> {
            client.provisionVirtualCard(input)
        }

        verify(mockPublicKeyService).getCurrentRegisteredKey()
        verify(mockApiCategory, never()).mutate<String>(
            org.mockito.kotlin.check {
                assertEquals(ProvisionVirtualCardMutation.OPERATION_DOCUMENT, it.query)
            },
            any(),
            any(),
        )
    }

    @Test
    fun `provisionVirtualCard() should throw when key registration fails`() = runBlocking<Unit> {
        mockPublicKeyService.stub {
            onBlocking { getCurrentRegisteredKey() } doThrow PublicKeyService.PublicKeyServiceException.KeyCreateException("mock")
        }

        shouldThrow<SudoVirtualCardsClient.VirtualCardException.PublicKeyException> {
            client.provisionVirtualCard(input)
        }

        verify(mockPublicKeyService).getCurrentRegisteredKey()
        verify(mockApiCategory, never()).mutate<String>(
            org.mockito.kotlin.check {
                assertEquals(ProvisionVirtualCardMutation.OPERATION_DOCUMENT, it.query)
            },
            any(),
            any(),
        )
    }
}
