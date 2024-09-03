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
import com.sudoplatform.sudovirtualcards.graphql.UpdateVirtualCardMutation
import com.sudoplatform.sudovirtualcards.graphql.type.CardState
import com.sudoplatform.sudovirtualcards.graphql.type.CardUpdateRequest
import com.sudoplatform.sudovirtualcards.keys.PublicKeyService
import com.sudoplatform.sudovirtualcards.types.SingleAPIResult
import com.sudoplatform.sudovirtualcards.types.inputs.UpdateVirtualCardInput
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
import com.sudoplatform.sudovirtualcards.types.CardState as CardStateEntity

/**
 * Test the correct operation of [SudoVirtualCardsClient.updateVirtualCard]
 * using mocks and spies.
 */
class SudoVirtualCardsUpdateVirtualCardTest : BaseTests() {

    private val input by before {
        UpdateVirtualCardInput(
            "id",
            10,
            "newCardHolder",
            "newAlias",
            null,
            "addressLine1",
            "addressLine2",
            "city",
            "state",
            "postalCode",
            "country",
        )
    }

    private val mutationResponse by before {
        JSONObject(
            """
                {
                    'updateCard': {
                        '__typename': 'SealedCard',
                        'id':'id',
                        'owner': 'owner',
                        'version': 2,
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

    private val currentKey = PublicKey(
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
                mutate<String>(
                    argThat { this.query.equals(UpdateVirtualCardMutation.OPERATION_DOCUMENT) },
                    any(), any(),
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
    fun `updateVirtualCard() should return success result when no error present`() = runBlocking<Unit> {
        val deferredResult = async(Dispatchers.IO) {
            client.updateVirtualCard(input)
        }
        deferredResult.start()

        delay(100L)
        val updateCard = deferredResult.await()
        updateCard shouldNotBe null

        when (updateCard) {
            is SingleAPIResult.Success -> {
                updateCard.result.id shouldBe "id"
                updateCard.result.owners shouldNotBe null
                updateCard.result.owner shouldBe "owner"
                updateCard.result.version shouldBe 2
                updateCard.result.fundingSourceId shouldBe "fundingSourceId"
                updateCard.result.state shouldBe CardStateEntity.ISSUED
                updateCard.result.cardHolder shouldNotBe null
                updateCard.result.alias shouldNotBe null
                updateCard.result.last4 shouldBe "last4"
                updateCard.result.cardNumber shouldNotBe null
                updateCard.result.securityCode shouldNotBe null
                updateCard.result.billingAddress shouldNotBe null
                updateCard.result.expiry shouldNotBe null
                updateCard.result.currency shouldBe "currency"
                updateCard.result.activeTo shouldNotBe null
                updateCard.result.createdAt shouldBe Date(1L)
                updateCard.result.updatedAt shouldBe Date(1L)
            }
            else -> {
                fail("Unexpected SingleAPIResult")
            }
        }

        verifyUpdateVirtualCardMutation()

        verify(mockPublicKeyService).getCurrentKey()
        verify(mockKeyManager, times(12)).decryptWithPrivateKey(anyString(), any(), any())
        verify(mockKeyManager, times(12)).decryptWithSymmetricKey(any<ByteArray>(), any<ByteArray>())
    }

    @Test
    fun `updateVirtualCard() should return partial result when unsealing fails`() = runBlocking<Unit> {
        mockKeyManager.stub {
            on { decryptWithPrivateKey(anyString(), any(), any()) } doThrow KeyManagerException("KeyManagerException")
        }

        val deferredResult = async(Dispatchers.IO) {
            client.updateVirtualCard(input)
        }
        deferredResult.start()

        delay(100L)
        deferredResult.await()

        val updateCard = deferredResult.await()
        updateCard shouldNotBe null

        when (updateCard) {
            is SingleAPIResult.Partial -> {
                updateCard.result.partial.id shouldBe "id"
                updateCard.result.partial.owners shouldNotBe null
                updateCard.result.partial.owner shouldBe "owner"
                updateCard.result.partial.version shouldBe 2
                updateCard.result.partial.fundingSourceId shouldBe "fundingSourceId"
                updateCard.result.partial.state shouldBe CardStateEntity.ISSUED
                updateCard.result.partial.last4 shouldBe "last4"
                updateCard.result.partial.currency shouldBe "currency"
                updateCard.result.partial.activeTo shouldNotBe null
                updateCard.result.partial.cancelledAt shouldBe null
                updateCard.result.partial.createdAt shouldBe Date(1L)
                updateCard.result.partial.updatedAt shouldBe Date(1L)
            }
            else -> {
                fail("Unexpected SingleAPIResult")
            }
        }

        verifyUpdateVirtualCardMutation()

        verify(mockKeyManager).decryptWithPrivateKey(anyString(), any(), any())
        verify(mockPublicKeyService).getCurrentKey()
    }

    @Test
    fun `updateVirtualCard() should throw when response is null`() = runBlocking<Unit> {
        val mockOperation: GraphQLOperation<String> = mock()
        whenever(
            mockApiCategory.mutate<String>(
                argThat { this.query.equals(UpdateVirtualCardMutation.OPERATION_DOCUMENT) },
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
            shouldThrow<SudoVirtualCardsClient.VirtualCardException.FailedException> {
                client.updateVirtualCard(input)
            }
        }
        deferredResult.start()

        delay(100L)
        deferredResult.await()

        verifyUpdateVirtualCardMutation()

        verify(mockPublicKeyService).getCurrentKey()
    }

    @Test
    fun `updateVirtualCard() should throw when mutation response has an identity verification error`() = runBlocking<Unit> {
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
                argThat { this.query.equals(UpdateVirtualCardMutation.OPERATION_DOCUMENT) },
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
                client.updateVirtualCard(input)
            }
        }
        deferredResult.start()

        delay(100L)
        deferredResult.await()

        verifyUpdateVirtualCardMutation()

        verify(mockPublicKeyService).getCurrentKey()
    }

    @Test
    fun `updateVirtualCard() should throw when response has a card not found error`() = runBlocking<Unit> {
        val errors = listOf(
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
                argThat { this.query.equals(UpdateVirtualCardMutation.OPERATION_DOCUMENT) },
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
            shouldThrow<SudoVirtualCardsClient.VirtualCardException.CardNotFoundException> {
                client.updateVirtualCard(input)
            }
        }
        deferredResult.start()

        delay(100L)
        deferredResult.await()

        verifyUpdateVirtualCardMutation()

        verify(mockPublicKeyService).getCurrentKey()
    }

    @Test
    fun `updateVirtualCard() should throw when response has an invalid card state error`() = runBlocking<Unit> {
        val errors = listOf(
            GraphQLResponse.Error(
                "mock",
                null,
                null,
                mapOf("errorType" to "CardStateError"),
            ),
        )
        val mockOperation: GraphQLOperation<String> = mock()
        whenever(
            mockApiCategory.mutate<String>(
                argThat { this.query.equals(UpdateVirtualCardMutation.OPERATION_DOCUMENT) },
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
            shouldThrow<SudoVirtualCardsClient.VirtualCardException.CardStateException> {
                client.updateVirtualCard(input)
            }
        }
        deferredResult.start()

        delay(100L)
        deferredResult.await()
        verifyUpdateVirtualCardMutation()

        verify(mockPublicKeyService).getCurrentKey()
    }

    @Test
    fun `updateVirtualCard() should throw when current key pair retrieval returns null`() = runBlocking<Unit> {
        mockPublicKeyService.stub {
            onBlocking { getCurrentKey() } doReturn null
        }

        shouldThrow<SudoVirtualCardsClient.VirtualCardException.PublicKeyException> {
            client.updateVirtualCard(input)
        }

        verify(mockPublicKeyService).getCurrentKey()
    }

    @Test
    fun `updateVirtualCard() should throw when unsealing fails`() = runBlocking<Unit> {
        mockApiCategory.stub {
            on {
                mutate<String>(
                    argThat { this.query.equals(UpdateVirtualCardMutation.OPERATION_DOCUMENT) },
                    any(),
                    any(),
                )
            } doThrow Unsealer.UnsealerException.SealedDataTooShortException(
                "Mock Unsealer Exception",
            )
        }

        shouldThrow<SudoVirtualCardsClient.VirtualCardException.UnsealingException> {
            client.updateVirtualCard(input)
        }

        verifyUpdateVirtualCardMutation()

        verify(mockPublicKeyService).getCurrentKey()
    }

    @Test
    fun `updateVirtualCard() should throw when http error occurs`() = runBlocking<Unit> {
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
                argThat { this.query.equals(UpdateVirtualCardMutation.OPERATION_DOCUMENT) },
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
            shouldThrow<SudoVirtualCardsClient.VirtualCardException.UpdateFailedException> {
                client.updateVirtualCard(input)
            }
        }
        deferredResult.start()
        delay(100L)
        deferredResult.await()

        verifyUpdateVirtualCardMutation()

        verify(mockPublicKeyService).getCurrentKey()
    }

    @Test
    fun `updateVirtualCard() should throw when unknown error occurs()`() = runBlocking<Unit> {
        mockApiCategory.stub {
            on {
                mutate<String>(
                    argThat { this.query.equals(UpdateVirtualCardMutation.OPERATION_DOCUMENT) },
                    any(),
                    any(),
                )
            } doThrow RuntimeException("Mock Runtime Exception")
        }

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.VirtualCardException.UnknownException> {
                client.updateVirtualCard(input)
            }
        }
        deferredResult.start()
        delay(100L)

        deferredResult.await()

        verifyUpdateVirtualCardMutation()

        verify(mockPublicKeyService).getCurrentKey()
    }

    @Test
    fun `updateVirtualCard() should not block coroutine cancellation exception`() = runBlocking<Unit> {
        mockApiCategory.stub {
            on {
                mutate<String>(
                    argThat { this.query.equals(UpdateVirtualCardMutation.OPERATION_DOCUMENT) },
                    any(),
                    any(),
                )
            } doThrow CancellationException("Mock Cancellation Exception")
        }

        shouldThrow<CancellationException> {
            client.updateVirtualCard(input)
        }

        verifyUpdateVirtualCardMutation()
        verify(mockPublicKeyService).getCurrentKey()
    }

    private fun verifyUpdateVirtualCardMutation() {
        verify(mockApiCategory).mutate<String>(
            check {
                assertEquals(UpdateVirtualCardMutation.OPERATION_DOCUMENT, it.query)
                val mutationInput = it.variables["input"] as CardUpdateRequest?
                mutationInput?.id shouldBe "id"
                mutationInput?.expectedVersion?.getOrNull() shouldBe 10
                mutationInput?.cardHolder?.getOrNull() shouldBe "newCardHolder"
                mutationInput?.alias?.getOrNull() shouldBe "newAlias"
                mutationInput?.billingAddress?.getOrNull()?.addressLine1 shouldBe "addressLine1"
                mutationInput?.billingAddress?.getOrNull()?.addressLine2?.getOrNull() shouldBe "addressLine2"
                mutationInput?.billingAddress?.getOrNull()?.city shouldBe "city"
                mutationInput?.billingAddress?.getOrNull()?.state shouldBe "state"
                mutationInput?.billingAddress?.getOrNull()?.postalCode shouldBe "postalCode"
                mutationInput?.billingAddress?.getOrNull()?.country shouldBe "country"
            },
            any(),
            any(),
        )
    }
}
