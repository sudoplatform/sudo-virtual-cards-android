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
import com.sudoplatform.sudologging.Logger
import com.sudoplatform.sudoprofiles.SudoProfilesClient
import com.sudoplatform.sudouser.SudoUserClient
import com.sudoplatform.sudovirtualcards.graphql.CallbackHolder
import com.sudoplatform.sudovirtualcards.graphql.UpdateCardMutation
import com.sudoplatform.sudovirtualcards.keys.PublicKeyService
import com.sudoplatform.sudovirtualcards.graphql.type.AddressInput
import com.sudoplatform.sudovirtualcards.graphql.type.CardState
import com.sudoplatform.sudovirtualcards.graphql.type.CardUpdateRequest
import com.sudoplatform.sudovirtualcards.types.VirtualCard
import com.sudoplatform.sudovirtualcards.types.inputs.UpdateVirtualCardInput
import com.sudoplatform.sudovirtualcards.types.transformers.Unsealer
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
        UpdateCardMutation.BillingAddress(
            "typename",
            mockSeal("addressLine1"),
            mockSeal("addressLine2"),
            mockSeal("city"),
            mockSeal("state"),
            mockSeal("postalCode"),
            mockSeal("country")
        )
    }

    private val expiry by before {
        UpdateCardMutation.Expiry(
            "typename",
            mockSeal("01"),
            mockSeal("2021")
        )
    }

    private val input by before {
        UpdateVirtualCardInput(
            "id",
            10,
            "newCardHolder",
            "newAlias",
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
        UpdateCardMutation.UpdateCard(
            "typename",
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
            null
        )
    }

    private val mutationResponse by before {
        Response.builder<UpdateCardMutation.Data>(UpdateCardMutation(mutationRequest))
            .data(UpdateCardMutation.Data(mutationResult))
            .build()
    }

    private val mutationHolder = CallbackHolder<UpdateCardMutation.Data>()

    private val mockContext by before {
        mock<Context>()
    }

    private val mockUserClient by before {
        mock<SudoUserClient>().stub {
            on { getSubject() } doReturn "subject"
        }
    }

    private val mockSudoClient by before {
        mock<SudoProfilesClient>()
    }

    private val mockAppSyncClient by before {
        mock<AWSAppSyncClient>().stub {
            on { mutate(any<UpdateCardMutation>()) } doReturn mutationHolder.mutationOperation
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
            .setSudoProfilesClient(mockSudoClient)
            .setAppSyncClient(mockAppSyncClient)
            .setKeyManager(mockKeyManager)
            .setLogger(mock<Logger>())
            .build()
    }

    @Before
    fun init() {
        mutationHolder.callback = null
    }

    @After
    fun fini() {
        verifyNoMoreInteractions(mockContext, mockUserClient, mockSudoClient, mockKeyManager, mockAppSyncClient)
    }

    @Test
    fun `updateVirtualCard() should return results when no error present`() = runBlocking<Unit> {

        mutationHolder.callback shouldBe null

        val deferredResult = async(Dispatchers.IO) {
            client.updateVirtualCard(input)
        }
        deferredResult.start()

        delay(100L)
        mutationHolder.callback shouldNotBe null
        mutationHolder.callback?.onResponse(mutationResponse)

        val result = deferredResult.await()
        result shouldNotBe null

        with(result) {
            id shouldBe "id"
            owners shouldNotBe null
            owner shouldBe "owner"
            version shouldBe 2
            fundingSourceId shouldBe "fundingSourceId"
            state shouldBe VirtualCard.State.ISSUED
            cardHolder shouldNotBe null
            alias shouldNotBe null
            last4 shouldBe "last4"
            cardNumber shouldNotBe null
            securityCode shouldNotBe null
            billingAddress shouldNotBe null
            expiry shouldNotBe null
            currency shouldBe "currency"
            activeTo shouldNotBe null
            cancelledAt shouldBe null
            createdAt shouldBe Date(1L)
            updatedAt shouldBe Date(1L)
        }

        verify(mockAppSyncClient).mutate<UpdateCardMutation.Data, UpdateCardMutation, UpdateCardMutation.Variables>(
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
        verify(mockKeyManager).getPassword(anyString())
        verify(mockKeyManager).getPublicKeyData(anyString())
        verify(mockKeyManager).getPrivateKeyData(anyString())
        verify(mockKeyManager, times(12)).decryptWithPrivateKey(anyString(), any(), any())
        verify(mockKeyManager, times(12)).decryptWithSymmetricKey(any<ByteArray>(), any<ByteArray>())
        verify(mockUserClient).getSubject()
    }

    @Test
    fun `updateVirtualCard() should throw when response is null`() = runBlocking<Unit> {

        mutationHolder.callback shouldBe null

        val nullMutationResponse by before {
            Response.builder<UpdateCardMutation.Data>(UpdateCardMutation(mutationRequest))
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

        verify(mockAppSyncClient).mutate<UpdateCardMutation.Data, UpdateCardMutation, UpdateCardMutation.Variables>(
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
        verify(mockKeyManager).getPassword(anyString())
        verify(mockKeyManager).getPublicKeyData(anyString())
        verify(mockKeyManager).getPrivateKeyData(anyString())
        verify(mockUserClient).getSubject()
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
            Response.builder<UpdateCardMutation.Data>(UpdateCardMutation(mutationRequest))
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

        verify(mockAppSyncClient).mutate<UpdateCardMutation.Data, UpdateCardMutation, UpdateCardMutation.Variables>(
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
        verify(mockKeyManager).getPassword(anyString())
        verify(mockKeyManager).getPublicKeyData(anyString())
        verify(mockKeyManager).getPrivateKeyData(anyString())
        verify(mockUserClient).getSubject()
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
            Response.builder<UpdateCardMutation.Data>(UpdateCardMutation(mutationRequest))
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

        verify(mockAppSyncClient).mutate<UpdateCardMutation.Data, UpdateCardMutation, UpdateCardMutation.Variables>(
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
        verify(mockKeyManager).getPassword(anyString())
        verify(mockKeyManager).getPublicKeyData(anyString())
        verify(mockKeyManager).getPrivateKeyData(anyString())
        verify(mockUserClient).getSubject()
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
            Response.builder<UpdateCardMutation.Data>(UpdateCardMutation(mutationRequest))
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

        verify(mockAppSyncClient).mutate<UpdateCardMutation.Data, UpdateCardMutation, UpdateCardMutation.Variables>(
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
        verify(mockKeyManager).getPassword(anyString())
        verify(mockKeyManager).getPublicKeyData(anyString())
        verify(mockKeyManager).getPrivateKeyData(anyString())
        verify(mockUserClient).getSubject()
    }

    @Test
    fun `updateVirtualCard() should throw when password retrieval fails`() = runBlocking<Unit> {

        mockKeyManager.stub {
            on { getPassword(anyString()) } doThrow PublicKeyService.PublicKeyServiceException.KeyCreateException(
                "Mock PublicKey Service Exception"
            )
        }

        shouldThrow<SudoVirtualCardsClient.VirtualCardException.PublicKeyException> {
            client.updateVirtualCard(input)
        }

        verify(mockKeyManager).getPassword(anyString())
    }

    @Test
    fun `updateVirtualCard() should throw when public key data retrieval fails`() = runBlocking<Unit> {

        mockKeyManager.stub {
            on { getPublicKeyData(anyString()) } doThrow PublicKeyService.PublicKeyServiceException.KeyCreateException(
                "Mock PublicKey Service Exception"
            )
        }

        shouldThrow<SudoVirtualCardsClient.VirtualCardException.PublicKeyException> {
            client.updateVirtualCard(input)
        }

        verify(mockKeyManager).getPassword(anyString())
        verify(mockKeyManager).getPublicKeyData(anyString())
    }

    @Test
    fun `updateVirtualCard() should throw when private key data retrieval fails`() = runBlocking<Unit> {

        mockKeyManager.stub {
            on { getPrivateKeyData(anyString()) } doThrow PublicKeyService.PublicKeyServiceException.KeyCreateException(
                "Mock PublicKey Service Exception"
            )
        }

        shouldThrow<SudoVirtualCardsClient.VirtualCardException.PublicKeyException> {
            client.updateVirtualCard(input)
        }

        verify(mockKeyManager).getPassword(anyString())
        verify(mockKeyManager).getPublicKeyData(anyString())
        verify(mockKeyManager).getPrivateKeyData(anyString())
    }

    @Test
    fun `updateVirtualCard() should throw when unsealing fails`() = runBlocking<Unit> {

        mockAppSyncClient.stub {
            on { mutate(any<UpdateCardMutation>()) } doThrow Unsealer.UnsealerException.SealedDataTooShortException(
                "Mock Unsealer Exception"
            )
        }

        shouldThrow<SudoVirtualCardsClient.VirtualCardException.UnsealingException> {
            client.updateVirtualCard(input)
        }

        verify(mockAppSyncClient).mutate<UpdateCardMutation.Data, UpdateCardMutation, UpdateCardMutation.Variables>(
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
        verify(mockKeyManager).getPassword(anyString())
        verify(mockKeyManager).getPublicKeyData(anyString())
        verify(mockKeyManager).getPrivateKeyData(anyString())
        verify(mockUserClient).getSubject()
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

        verify(mockAppSyncClient).mutate<UpdateCardMutation.Data, UpdateCardMutation, UpdateCardMutation.Variables>(
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
        verify(mockKeyManager).getPassword(anyString())
        verify(mockKeyManager).getPublicKeyData(anyString())
        verify(mockKeyManager).getPrivateKeyData(anyString())
        verify(mockUserClient).getSubject()
    }

    @Test
    fun `updateVirtualCard() should throw when unknown error occurs()`() = runBlocking<Unit> {

        mutationHolder.callback shouldBe null

        mockAppSyncClient.stub {
            on { mutate(any<UpdateCardMutation>()) } doThrow RuntimeException("Mock Runtime Exception")
        }

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.VirtualCardException.UnknownException> {
                client.updateVirtualCard(input)
            }
        }
        deferredResult.start()
        delay(100L)

        deferredResult.await()

        verify(mockAppSyncClient).mutate<UpdateCardMutation.Data, UpdateCardMutation, UpdateCardMutation.Variables>(
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
        verify(mockKeyManager).getPassword(anyString())
        verify(mockKeyManager).getPublicKeyData(anyString())
        verify(mockKeyManager).getPrivateKeyData(anyString())
        verify(mockUserClient).getSubject()
    }

    @Test
    fun `updateVirtualCard() should not block coroutine cancellation exception`() = runBlocking<Unit> {

        mockAppSyncClient.stub {
            on { mutate(any<UpdateCardMutation>()) } doThrow CancellationException("Mock Cancellation Exception")
        }

        shouldThrow<CancellationException> {
            client.updateVirtualCard(input)
        }

        verify(mockAppSyncClient).mutate<UpdateCardMutation.Data, UpdateCardMutation, UpdateCardMutation.Variables>(
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
        verify(mockKeyManager).getPassword(anyString())
        verify(mockKeyManager).getPublicKeyData(anyString())
        verify(mockKeyManager).getPrivateKeyData(anyString())
        verify(mockUserClient).getSubject()
    }
}
