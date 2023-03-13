/*
 * Copyright © 2023 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards

import android.content.Context
import com.amazonaws.mobileconnectors.appsync.AWSAppSyncClient
import com.apollographql.apollo.api.Response
import com.apollographql.apollo.exception.ApolloHttpException
import com.sudoplatform.sudokeymanager.KeyManagerException
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.stub
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoMoreInteractions
import com.sudoplatform.sudokeymanager.KeyManagerInterface
import com.sudoplatform.sudouser.PublicKey
import com.sudoplatform.sudouser.SudoUserClient
import com.sudoplatform.sudovirtualcards.graphql.CallbackHolder
import com.sudoplatform.sudovirtualcards.graphql.CancelVirtualCardMutation
import com.sudoplatform.sudovirtualcards.graphql.fragment.SealedAddressAttribute
import com.sudoplatform.sudovirtualcards.graphql.fragment.SealedCard
import com.sudoplatform.sudovirtualcards.graphql.fragment.SealedCardWithLastTransaction
import com.sudoplatform.sudovirtualcards.graphql.fragment.SealedExpiryAttribute
import com.sudoplatform.sudovirtualcards.keys.PublicKeyService
import com.sudoplatform.sudovirtualcards.graphql.type.CardCancelRequest
import com.sudoplatform.sudovirtualcards.graphql.type.CardState
import com.sudoplatform.sudovirtualcards.types.CardState as CardStateEntity
import com.sudoplatform.sudovirtualcards.types.SingleAPIResult
import com.sudoplatform.sudovirtualcards.types.transformers.Unsealer
import io.kotlintest.fail
import io.kotlintest.shouldBe
import io.kotlintest.shouldNotBe
import io.kotlintest.shouldThrow
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.anyString
import java.net.HttpURLConnection
import java.util.Date

/**
 * Test the correct operation of [SudoVirtualCardsClient.cancelVirtualCard]
 * using mocks and spies.
 */
class SudoVirtualCardsCancelVirtualCardTest : BaseTests() {

    private val billingAddress by before {
        SealedCard.BillingAddress(
            "BillingAddress",
            SealedCard.BillingAddress.Fragments(
                SealedAddressAttribute(
                    "SealedAddressAttribute",
                    mockSeal("addressLine1"),
                    mockSeal("addressLine2"),
                    mockSeal("city"),
                    mockSeal("state"),
                    mockSeal("postalCode"),
                    mockSeal("country")
                )
            )
        )
    }

    private val expiry by before {
        SealedCard.Expiry(
            "Expiry",
            SealedCard.Expiry.Fragments(
                SealedExpiryAttribute(
                    "SealedExpiryAttribute",
                    mockSeal("01"),
                    mockSeal("2021")
                )
            )
        )
    }

    private val mutationRequest by before {
        CardCancelRequest.builder()
            .id("id")
            .keyId("keyId")
            .build()
    }

    private val mutationResult by before {
        CancelVirtualCardMutation.CancelCard(
            "CancelCard",
            CancelVirtualCardMutation.CancelCard.Fragments(
                SealedCardWithLastTransaction(
                    "SealedCardWithLastTransaction",
                    null,
                    SealedCardWithLastTransaction.Fragments(
                        SealedCard(
                            "SealedCard",
                            "id",
                            "owner",
                            1,
                            1.0,
                            1.0,
                            "algorithm",
                            "keyId",
                            "keyRingId",
                            emptyList(),
                            "fundingSourceId",
                            "currency",
                            CardState.CLOSED,
                            1.0,
                            1.0,
                            "last4",
                            mockSeal("newCardHolder"),
                            mockSeal("newAlias"),
                            mockSeal("pan"),
                            mockSeal("csc"),
                            billingAddress,
                            expiry,
                            null,
                        )
                    )
                )
            )
        )
    }

    private val mutationResponse by before {
        Response.builder<CancelVirtualCardMutation.Data>(CancelVirtualCardMutation(mutationRequest))
            .data(CancelVirtualCardMutation.Data(mutationResult))
            .build()
    }

    private val mutationHolder = CallbackHolder<CancelVirtualCardMutation.Data>()

    private val mockContext by before {
        mock<Context>()
    }

    private val mockUserClient by before {
        mock<SudoUserClient>().stub {
            on { getSubject() } doReturn "subject"
        }
    }

    private val mockAppSyncClient by before {
        mock<AWSAppSyncClient>().stub {
            on { mutate(any<CancelVirtualCardMutation>()) } doReturn mutationHolder.mutationOperation
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

    private val currentKey = PublicKey(
        keyId = "keyId",
        publicKey = "publicKey".toByteArray(),
    )

    private val mockPublicKeyService by before {
        mock<PublicKeyService>().stub {
            onBlocking { getCurrentKey() } doReturn currentKey
        }
    }

    private val client by before {
        SudoVirtualCardsClient.builder()
            .setContext(mockContext)
            .setSudoUserClient(mockUserClient)
            .setAppSyncClient(mockAppSyncClient)
            .setKeyManager(mockKeyManager)
            .setLogger(mock())
            .setPublicKeyService(mockPublicKeyService)
            .build()
    }

    @Before
    fun init() {
        mutationHolder.callback = null
    }

    @After
    fun fini() {
        verifyNoMoreInteractions(mockContext, mockUserClient, mockKeyManager, mockAppSyncClient)
    }

    @Test
    fun `cancelVirtualCard() should return success result when no error present`() = runBlocking<Unit> {

        mutationHolder.callback shouldBe null

        val deferredResult = async(Dispatchers.IO) {
            client.cancelVirtualCard("id")
        }
        deferredResult.start()

        delay(100L)
        mutationHolder.callback shouldNotBe null
        mutationHolder.callback?.onResponse(mutationResponse)

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
        verify(mockAppSyncClient).mutate(any<CancelVirtualCardMutation>())
        verify(mockKeyManager, times(12)).decryptWithPrivateKey(anyString(), any(), any())
        verify(mockKeyManager, times(12)).decryptWithSymmetricKey(any<ByteArray>(), any<ByteArray>())
    }

    @Test
    fun `cancelVirtualCard() should return partial result when unsealing fails`() = runBlocking<Unit> {

        mockKeyManager.stub {
            on { decryptWithPrivateKey(anyString(), any(), any()) } doThrow KeyManagerException("KeyManagerException")
        }

        val deferredResult = async(Dispatchers.IO) {
            client.cancelVirtualCard("id")
        }
        deferredResult.start()

        delay(100L)
        mutationHolder.callback shouldNotBe null
        mutationHolder.callback?.onResponse(mutationResponse)

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
        verify(mockAppSyncClient).mutate(any<CancelVirtualCardMutation>())
        verify(mockKeyManager).decryptWithPrivateKey(anyString(), any(), any())
    }

    @Test
    fun `cancelVirtualCard() should throw when response is null`() = runBlocking<Unit> {

        mutationHolder.callback shouldBe null

        val nullMutationResponse by before {
            Response.builder<CancelVirtualCardMutation.Data>(CancelVirtualCardMutation(mutationRequest))
                .data(null)
                .build()
        }

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.VirtualCardException.FailedException> {
                client.cancelVirtualCard("id")
            }
        }
        deferredResult.start()

        delay(100L)
        mutationHolder.callback shouldNotBe null
        mutationHolder.callback?.onResponse(nullMutationResponse)

        verify(mockPublicKeyService).getCurrentKey()
        verify(mockAppSyncClient).mutate(any<CancelVirtualCardMutation>())
    }

    @Test
    fun `cancelVirtualCard() should throw when mutation response has an identity verification error`() = runBlocking<Unit> {

        mutationHolder.callback shouldBe null

        val errorMutationResponse by before {
            val error = com.apollographql.apollo.api.Error(
                "mock",
                emptyList(),
                mapOf("errorType" to "IdentityVerificationNotVerifiedError")
            )
            Response.builder<CancelVirtualCardMutation.Data>(CancelVirtualCardMutation(mutationRequest))
                .errors(listOf(error))
                .data(null)
                .build()
        }

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.VirtualCardException.IdentityVerificationException> {
                client.cancelVirtualCard("id")
            }
        }
        deferredResult.start()

        delay(100L)
        mutationHolder.callback shouldNotBe null
        mutationHolder.callback?.onResponse(errorMutationResponse)

        verify(mockPublicKeyService).getCurrentKey()
        verify(mockAppSyncClient).mutate(any<CancelVirtualCardMutation>())
    }

    @Test
    fun `cancelVirtualCard() should throw when response has card not found error`() = runBlocking<Unit> {

        mutationHolder.callback shouldBe null

        val errorMutationResponse by before {
            val error = com.apollographql.apollo.api.Error(
                "mock",
                emptyList(),
                mapOf("errorType" to "CardNotFoundError")
            )
            Response.builder<CancelVirtualCardMutation.Data>(CancelVirtualCardMutation(mutationRequest))
                .errors(listOf(error))
                .data(null)
                .build()
        }

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.VirtualCardException.CardNotFoundException> {
                client.cancelVirtualCard("id")
            }
        }
        deferredResult.start()

        delay(100L)
        mutationHolder.callback shouldNotBe null
        mutationHolder.callback?.onResponse(errorMutationResponse)

        verify(mockPublicKeyService).getCurrentKey()
        verify(mockAppSyncClient).mutate(any<CancelVirtualCardMutation>())
    }

    @Test
    fun `cancelVirtualCard() should throw when response has an account locked error`() = runBlocking<Unit> {

        mutationHolder.callback shouldBe null

        val errorMutationResponse by before {
            val error = com.apollographql.apollo.api.Error(
                "mock",
                emptyList(),
                mapOf("errorType" to "AccountLockedError")
            )
            Response.builder<CancelVirtualCardMutation.Data>(CancelVirtualCardMutation(mutationRequest))
                .errors(listOf(error))
                .data(null)
                .build()
        }

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.VirtualCardException.AccountLockedException> {
                client.cancelVirtualCard("id")
            }
        }
        deferredResult.start()

        delay(100L)
        mutationHolder.callback shouldNotBe null
        mutationHolder.callback?.onResponse(errorMutationResponse)

        verify(mockPublicKeyService).getCurrentKey()
        verify(mockAppSyncClient).mutate(any<CancelVirtualCardMutation>())
    }

    @Test
    fun `cancelVirtualCard() should throw when password retrieval fails`() = runBlocking<Unit> {

        mockPublicKeyService.stub {
            onBlocking { getCurrentKey() } doThrow PublicKeyService.PublicKeyServiceException.KeyCreateException(
                "Mock PublicKey Service Exception"
            )
        }

        shouldThrow<SudoVirtualCardsClient.VirtualCardException.PublicKeyException> {
            client.cancelVirtualCard("id")
        }

        verify(mockPublicKeyService).getCurrentKey()
    }

    @Test
    fun `cancelVirtualCard() should throw when unsealing fails`() = runBlocking<Unit> {

        mockAppSyncClient.stub {
            on { mutate(any<CancelVirtualCardMutation>()) } doThrow Unsealer.UnsealerException.SealedDataTooShortException(
                "Mock Unsealer Exception"
            )
        }

        shouldThrow<SudoVirtualCardsClient.VirtualCardException.UnsealingException> {
            client.cancelVirtualCard("id")
        }

        verify(mockAppSyncClient).mutate(any<CancelVirtualCardMutation>())
        verify(mockPublicKeyService).getCurrentKey()
    }

    @Test
    fun `cancelVirtualCard() should throw when http error occurs`() = runBlocking<Unit> {

        mutationHolder.callback shouldBe null

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.VirtualCardException.CancelFailedException> {
                client.cancelVirtualCard("id")
            }
        }
        deferredResult.start()
        delay(100L)

        val request = okhttp3.Request.Builder()
            .get()
            .url("http://www.smh.com.au")
            .build()
        val responseBody = "{}".toResponseBody("application/json; charset=utf-8".toMediaType())
        val forbidden = okhttp3.Response.Builder()
            .protocol(Protocol.HTTP_1_1)
            .code(HttpURLConnection.HTTP_FORBIDDEN)
            .request(request)
            .message("Forbidden")
            .body(responseBody)
            .build()

        mutationHolder.callback shouldNotBe null
        mutationHolder.callback?.onHttpError(ApolloHttpException(forbidden))

        deferredResult.await()

        verify(mockAppSyncClient).mutate(any<CancelVirtualCardMutation>())
        verify(mockPublicKeyService).getCurrentKey()
    }

    @Test
    fun `cancelVirtualCard() should throw when unknown error occurs()`() = runBlocking<Unit> {

        mutationHolder.callback shouldBe null

        mockAppSyncClient.stub {
            on { mutate(any<CancelVirtualCardMutation>()) } doThrow RuntimeException("Mock Runtime Exception")
        }

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.VirtualCardException.UnknownException> {
                client.cancelVirtualCard("id")
            }
        }
        deferredResult.start()
        delay(100L)

        deferredResult.await()

        verify(mockAppSyncClient).mutate(any<CancelVirtualCardMutation>())
        verify(mockPublicKeyService).getCurrentKey()
    }

    @Test
    fun `cancelVirtualCard() should not block coroutine cancellation exception`() = runBlocking<Unit> {

        mockAppSyncClient.stub {
            on { mutate(any<CancelVirtualCardMutation>()) } doThrow CancellationException("Mock Cancellation Exception")
        }

        shouldThrow<CancellationException> {
            client.cancelVirtualCard("id")
        }

        verify(mockAppSyncClient).mutate(any<CancelVirtualCardMutation>())
        verify(mockPublicKeyService).getCurrentKey()
    }
}
