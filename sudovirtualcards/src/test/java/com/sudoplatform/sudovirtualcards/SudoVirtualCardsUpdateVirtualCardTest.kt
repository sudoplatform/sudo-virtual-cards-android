/*
 * Copyright © 2022 Anonyome Labs, Inc. All rights reserved.
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
import org.mockito.kotlin.check
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
import com.sudoplatform.sudovirtualcards.graphql.UpdateVirtualCardMutation
import com.sudoplatform.sudovirtualcards.graphql.fragment.SealedAddressAttribute
import com.sudoplatform.sudovirtualcards.graphql.fragment.SealedCard
import com.sudoplatform.sudovirtualcards.graphql.fragment.SealedCardWithLastTransaction
import com.sudoplatform.sudovirtualcards.graphql.fragment.SealedExpiryAttribute
import com.sudoplatform.sudovirtualcards.keys.PublicKeyService
import com.sudoplatform.sudovirtualcards.graphql.type.AddressInput
import com.sudoplatform.sudovirtualcards.graphql.type.CardState
import com.sudoplatform.sudovirtualcards.graphql.type.CardUpdateRequest
import com.sudoplatform.sudovirtualcards.types.SingleAPIResult
import com.sudoplatform.sudovirtualcards.types.CardState as CardStateEntity
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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.ResponseBody.Companion.toResponseBody
import org.bouncycastle.util.encoders.Base64
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.anyString
import java.net.HttpURLConnection
import java.util.Date

/**
 * Test the correct operation of [SudoVirtualCardsClient.updateVirtualCard]
 * using mocks and spies.
 */
class SudoVirtualCardsUpdateVirtualCardTest : BaseTests() {

    private fun mockSeal(value: String): String {
        val valueBytes = value.toByteArray()
        val data = ByteArray(256)
        valueBytes.copyInto(data)
        return String(Base64.encode(data), Charsets.UTF_8)
    }

    private val address by before {
        AddressInput.builder()
            .addressLine1("addressLine1")
            .addressLine2("addressLine2")
            .city("city")
            .state("state")
            .postalCode("postalCode")
            .country("country")
            .build()
    }

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
            "country"
        )
    }

    private val mutationRequest by before {
        CardUpdateRequest.builder()
            .id("id")
            .keyId("keyId")
            .expectedVersion(1)
            .cardHolder("cardHolder")
            .alias("alias")
            .billingAddress(address)
            .build()
    }

    private val mutationResult by before {
        UpdateVirtualCardMutation.UpdateCard(
            "UpdateCard",
            UpdateVirtualCardMutation.UpdateCard.Fragments(
                SealedCardWithLastTransaction(
                    "SealedCardWithLastTransaction",
                    null,
                    SealedCardWithLastTransaction.Fragments(
                        SealedCard(
                            "SealedCard",
                            "id",
                            "owner",
                            2,
                            1.0,
                            1.0,
                            "algorithm",
                            "keyId",
                            "keyRingId",
                            emptyList(),
                            "fundingSourceId",
                            "currency",
                            CardState.ISSUED,
                            1.0,
                            null,
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
        Response.builder<UpdateVirtualCardMutation.Data>(UpdateVirtualCardMutation(mutationRequest))
            .data(UpdateVirtualCardMutation.Data(mutationResult))
            .build()
    }

    private val mutationHolder = CallbackHolder<UpdateVirtualCardMutation.Data>()

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

    private val mockAppSyncClient by before {
        mock<AWSAppSyncClient>().stub {
            on { mutate(any<UpdateVirtualCardMutation>()) } doReturn mutationHolder.mutationOperation
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
        verifyNoMoreInteractions(
            mockContext,
            mockUserClient,
            mockKeyManager,
            mockPublicKeyService,
            mockAppSyncClient
        )
    }

    @Test
    fun `updateVirtualCard() should return success result when no error present`() = runBlocking<Unit> {

        mutationHolder.callback shouldBe null

        val deferredResult = async(Dispatchers.IO) {
            client.updateVirtualCard(input)
        }
        deferredResult.start()

        delay(100L)
        mutationHolder.callback shouldNotBe null
        mutationHolder.callback?.onResponse(mutationResponse)

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

        verify(mockAppSyncClient).mutate<UpdateVirtualCardMutation.Data, UpdateVirtualCardMutation, UpdateVirtualCardMutation.Variables>(
            check {
                it.variables().input().id() shouldBe "id"
                it.variables().input().expectedVersion() shouldBe 10
                it.variables().input().cardHolder() shouldBe "newCardHolder"
                it.variables().input().alias() shouldBe "newAlias"
                it.variables().input().billingAddress()?.addressLine1() shouldBe "addressLine1"
                it.variables().input().billingAddress()?.addressLine2() shouldBe "addressLine2"
                it.variables().input().billingAddress()?.city() shouldBe "city"
                it.variables().input().billingAddress()?.state() shouldBe "state"
                it.variables().input().billingAddress()?.postalCode() shouldBe "postalCode"
                it.variables().input().billingAddress()?.country() shouldBe "country"
            }
        )
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
        mutationHolder.callback shouldNotBe null
        mutationHolder.callback?.onResponse(mutationResponse)

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

        verify(mockAppSyncClient).mutate<UpdateVirtualCardMutation.Data, UpdateVirtualCardMutation, UpdateVirtualCardMutation.Variables>(
            check {
                it.variables().input().id() shouldBe "id"
                it.variables().input().expectedVersion() shouldBe 10
                it.variables().input().cardHolder() shouldBe "newCardHolder"
                it.variables().input().alias() shouldBe "newAlias"
                it.variables().input().billingAddress()?.addressLine1() shouldBe "addressLine1"
                it.variables().input().billingAddress()?.addressLine2() shouldBe "addressLine2"
                it.variables().input().billingAddress()?.city() shouldBe "city"
                it.variables().input().billingAddress()?.state() shouldBe "state"
                it.variables().input().billingAddress()?.postalCode() shouldBe "postalCode"
                it.variables().input().billingAddress()?.country() shouldBe "country"
            }
        )
        verify(mockKeyManager).decryptWithPrivateKey(anyString(), any(), any())
        verify(mockPublicKeyService).getCurrentKey()
    }

    @Test
    fun `updateVirtualCard() should throw when response is null`() = runBlocking<Unit> {

        mutationHolder.callback shouldBe null

        val nullMutationResponse by before {
            Response.builder<UpdateVirtualCardMutation.Data>(UpdateVirtualCardMutation(mutationRequest))
                .data(null)
                .build()
        }

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.VirtualCardException.FailedException> {
                client.updateVirtualCard(input)
            }
        }
        deferredResult.start()

        delay(100L)
        mutationHolder.callback shouldNotBe null
        mutationHolder.callback?.onResponse(nullMutationResponse)

        verify(mockAppSyncClient).mutate<UpdateVirtualCardMutation.Data, UpdateVirtualCardMutation, UpdateVirtualCardMutation.Variables>(
            check {
                it.variables().input().id() shouldBe "id"
                it.variables().input().expectedVersion() shouldBe 10
                it.variables().input().cardHolder() shouldBe "newCardHolder"
                it.variables().input().alias() shouldBe "newAlias"
                it.variables().input().billingAddress()?.addressLine1() shouldBe "addressLine1"
                it.variables().input().billingAddress()?.addressLine2() shouldBe "addressLine2"
                it.variables().input().billingAddress()?.city() shouldBe "city"
                it.variables().input().billingAddress()?.state() shouldBe "state"
                it.variables().input().billingAddress()?.postalCode() shouldBe "postalCode"
                it.variables().input().billingAddress()?.country() shouldBe "country"
            }
        )
        verify(mockPublicKeyService).getCurrentKey()
    }

    @Test
    fun `updateVirtualCard() should throw when mutation response has an identity verification error`() = runBlocking<Unit> {

        mutationHolder.callback shouldBe null

        val errorMutationResponse by before {
            val error = com.apollographql.apollo.api.Error(
                "mock",
                emptyList(),
                mapOf("errorType" to "IdentityVerificationNotVerifiedError")
            )
            Response.builder<UpdateVirtualCardMutation.Data>(UpdateVirtualCardMutation(mutationRequest))
                .errors(listOf(error))
                .data(null)
                .build()
        }

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.VirtualCardException.IdentityVerificationException> {
                client.updateVirtualCard(input)
            }
        }
        deferredResult.start()

        delay(100L)
        mutationHolder.callback shouldNotBe null
        mutationHolder.callback?.onResponse(errorMutationResponse)

        verify(mockAppSyncClient).mutate<UpdateVirtualCardMutation.Data, UpdateVirtualCardMutation, UpdateVirtualCardMutation.Variables>(
            check {
                it.variables().input().id() shouldBe "id"
                it.variables().input().expectedVersion() shouldBe 10
                it.variables().input().cardHolder() shouldBe "newCardHolder"
                it.variables().input().alias() shouldBe "newAlias"
                it.variables().input().billingAddress()?.addressLine1() shouldBe "addressLine1"
                it.variables().input().billingAddress()?.addressLine2() shouldBe "addressLine2"
                it.variables().input().billingAddress()?.city() shouldBe "city"
                it.variables().input().billingAddress()?.state() shouldBe "state"
                it.variables().input().billingAddress()?.postalCode() shouldBe "postalCode"
                it.variables().input().billingAddress()?.country() shouldBe "country"
            }
        )
        verify(mockPublicKeyService).getCurrentKey()
    }

    @Test
    fun `updateVirtualCard() should throw when response has a card not found error`() = runBlocking<Unit> {

        mutationHolder.callback shouldBe null

        val errorMutationResponse by before {
            val error = com.apollographql.apollo.api.Error(
                "mock",
                emptyList(),
                mapOf("errorType" to "CardNotFoundError")
            )
            Response.builder<UpdateVirtualCardMutation.Data>(UpdateVirtualCardMutation(mutationRequest))
                .errors(listOf(error))
                .data(null)
                .build()
        }

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.VirtualCardException.CardNotFoundException> {
                client.updateVirtualCard(input)
            }
        }
        deferredResult.start()

        delay(100L)
        mutationHolder.callback shouldNotBe null
        mutationHolder.callback?.onResponse(errorMutationResponse)

        verify(mockAppSyncClient).mutate<UpdateVirtualCardMutation.Data, UpdateVirtualCardMutation, UpdateVirtualCardMutation.Variables>(
            check {
                it.variables().input().id() shouldBe "id"
                it.variables().input().expectedVersion() shouldBe 10
                it.variables().input().cardHolder() shouldBe "newCardHolder"
                it.variables().input().alias() shouldBe "newAlias"
                it.variables().input().billingAddress()?.addressLine1() shouldBe "addressLine1"
                it.variables().input().billingAddress()?.addressLine2() shouldBe "addressLine2"
                it.variables().input().billingAddress()?.city() shouldBe "city"
                it.variables().input().billingAddress()?.state() shouldBe "state"
                it.variables().input().billingAddress()?.postalCode() shouldBe "postalCode"
                it.variables().input().billingAddress()?.country() shouldBe "country"
            }
        )
        verify(mockPublicKeyService).getCurrentKey()
    }

    @Test
    fun `updateVirtualCard() should throw when response has an invalid card state error`() = runBlocking<Unit> {

        mutationHolder.callback shouldBe null

        val errorMutationResponse by before {
            val error = com.apollographql.apollo.api.Error(
                "mock",
                emptyList(),
                mapOf("errorType" to "CardStateError")
            )
            Response.builder<UpdateVirtualCardMutation.Data>(UpdateVirtualCardMutation(mutationRequest))
                .errors(listOf(error))
                .data(null)
                .build()
        }

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.VirtualCardException.CardStateException> {
                client.updateVirtualCard(input)
            }
        }
        deferredResult.start()

        delay(100L)
        mutationHolder.callback shouldNotBe null
        mutationHolder.callback?.onResponse(errorMutationResponse)

        verify(mockAppSyncClient).mutate<UpdateVirtualCardMutation.Data, UpdateVirtualCardMutation, UpdateVirtualCardMutation.Variables>(
            check {
                it.variables().input().id() shouldBe "id"
                it.variables().input().expectedVersion() shouldBe 10
                it.variables().input().cardHolder() shouldBe "newCardHolder"
                it.variables().input().alias() shouldBe "newAlias"
                it.variables().input().billingAddress()?.addressLine1() shouldBe "addressLine1"
                it.variables().input().billingAddress()?.addressLine2() shouldBe "addressLine2"
                it.variables().input().billingAddress()?.city() shouldBe "city"
                it.variables().input().billingAddress()?.state() shouldBe "state"
                it.variables().input().billingAddress()?.postalCode() shouldBe "postalCode"
                it.variables().input().billingAddress()?.country() shouldBe "country"
            }
        )
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

        mockAppSyncClient.stub {
            on { mutate(any<UpdateVirtualCardMutation>()) } doThrow Unsealer.UnsealerException.SealedDataTooShortException(
                "Mock Unsealer Exception"
            )
        }

        shouldThrow<SudoVirtualCardsClient.VirtualCardException.UnsealingException> {
            client.updateVirtualCard(input)
        }

        verify(mockAppSyncClient).mutate<UpdateVirtualCardMutation.Data, UpdateVirtualCardMutation, UpdateVirtualCardMutation.Variables>(
            check {
                it.variables().input().id() shouldBe "id"
                it.variables().input().expectedVersion() shouldBe 10
                it.variables().input().cardHolder() shouldBe "newCardHolder"
                it.variables().input().alias() shouldBe "newAlias"
                it.variables().input().billingAddress()?.addressLine1() shouldBe "addressLine1"
                it.variables().input().billingAddress()?.addressLine2() shouldBe "addressLine2"
                it.variables().input().billingAddress()?.city() shouldBe "city"
                it.variables().input().billingAddress()?.state() shouldBe "state"
                it.variables().input().billingAddress()?.postalCode() shouldBe "postalCode"
                it.variables().input().billingAddress()?.country() shouldBe "country"
            }
        )
        verify(mockPublicKeyService).getCurrentKey()
    }

    @Test
    fun `updateVirtualCard() should throw when http error occurs`() = runBlocking<Unit> {

        mutationHolder.callback shouldBe null

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.VirtualCardException.UpdateFailedException> {
                client.updateVirtualCard(input)
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

        verify(mockAppSyncClient).mutate<UpdateVirtualCardMutation.Data, UpdateVirtualCardMutation, UpdateVirtualCardMutation.Variables>(
            check {
                it.variables().input().id() shouldBe "id"
                it.variables().input().expectedVersion() shouldBe 10
                it.variables().input().cardHolder() shouldBe "newCardHolder"
                it.variables().input().alias() shouldBe "newAlias"
                it.variables().input().billingAddress()?.addressLine1() shouldBe "addressLine1"
                it.variables().input().billingAddress()?.addressLine2() shouldBe "addressLine2"
                it.variables().input().billingAddress()?.city() shouldBe "city"
                it.variables().input().billingAddress()?.state() shouldBe "state"
                it.variables().input().billingAddress()?.postalCode() shouldBe "postalCode"
                it.variables().input().billingAddress()?.country() shouldBe "country"
            }
        )
        verify(mockPublicKeyService).getCurrentKey()
    }

    @Test
    fun `updateVirtualCard() should throw when unknown error occurs()`() = runBlocking<Unit> {

        mutationHolder.callback shouldBe null

        mockAppSyncClient.stub {
            on { mutate(any<UpdateVirtualCardMutation>()) } doThrow RuntimeException("Mock Runtime Exception")
        }

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.VirtualCardException.UnknownException> {
                client.updateVirtualCard(input)
            }
        }
        deferredResult.start()
        delay(100L)

        deferredResult.await()

        verify(mockAppSyncClient).mutate<UpdateVirtualCardMutation.Data, UpdateVirtualCardMutation, UpdateVirtualCardMutation.Variables>(
            check {
                it.variables().input().id() shouldBe "id"
                it.variables().input().expectedVersion() shouldBe 10
                it.variables().input().cardHolder() shouldBe "newCardHolder"
                it.variables().input().alias() shouldBe "newAlias"
                it.variables().input().billingAddress()?.addressLine1() shouldBe "addressLine1"
                it.variables().input().billingAddress()?.addressLine2() shouldBe "addressLine2"
                it.variables().input().billingAddress()?.city() shouldBe "city"
                it.variables().input().billingAddress()?.state() shouldBe "state"
                it.variables().input().billingAddress()?.postalCode() shouldBe "postalCode"
                it.variables().input().billingAddress()?.country() shouldBe "country"
            }
        )
        verify(mockPublicKeyService).getCurrentKey()
    }

    @Test
    fun `updateVirtualCard() should not block coroutine cancellation exception`() = runBlocking<Unit> {

        mockAppSyncClient.stub {
            on { mutate(any<UpdateVirtualCardMutation>()) } doThrow CancellationException("Mock Cancellation Exception")
        }

        shouldThrow<CancellationException> {
            client.updateVirtualCard(input)
        }

        verify(mockAppSyncClient).mutate<UpdateVirtualCardMutation.Data, UpdateVirtualCardMutation, UpdateVirtualCardMutation.Variables>(
            check {
                it.variables().input().id() shouldBe "id"
                it.variables().input().expectedVersion() shouldBe 10
                it.variables().input().cardHolder() shouldBe "newCardHolder"
                it.variables().input().alias() shouldBe "newAlias"
                it.variables().input().billingAddress()?.addressLine1() shouldBe "addressLine1"
                it.variables().input().billingAddress()?.addressLine2() shouldBe "addressLine2"
                it.variables().input().billingAddress()?.city() shouldBe "city"
                it.variables().input().billingAddress()?.state() shouldBe "state"
                it.variables().input().billingAddress()?.postalCode() shouldBe "postalCode"
                it.variables().input().billingAddress()?.country() shouldBe "country"
            }
        )
        verify(mockPublicKeyService).getCurrentKey()
    }
}
