/*
 * Copyright © 2022 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards

import android.content.Context
import com.amazonaws.mobileconnectors.appsync.AWSAppSyncClient
import com.apollographql.apollo.api.Response
import com.sudoplatform.sudokeymanager.KeyManagerInterface
import com.sudoplatform.sudoprofiles.Sudo
import com.sudoplatform.sudoprofiles.SudoProfilesClient
import com.sudoplatform.sudouser.PublicKey
import com.sudoplatform.sudouser.SudoUserClient
import com.sudoplatform.sudovirtualcards.graphql.CallbackHolder
import com.sudoplatform.sudovirtualcards.graphql.CardProvisionMutation
import com.sudoplatform.sudovirtualcards.keys.PublicKeyService
import com.sudoplatform.sudovirtualcards.graphql.type.AddressInput
import com.sudoplatform.sudovirtualcards.graphql.type.CardProvisionRequest
import com.sudoplatform.sudovirtualcards.graphql.type.DeltaAction
import com.sudoplatform.sudovirtualcards.graphql.type.ProvisioningState
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
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.stub
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoMoreInteractions

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
            "currency"
        )
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

    private val cardProvisionRequest by before {
        CardProvisionRequest.builder()
            .clientRefId("clientRefId")
            .ownerProofs(listOf("ownerProofs"))
            .keyRingId("keyRingId")
            .fundingSourceId("fundingSourceId")
            .cardHolder("cardHolder")
            .alias("alias")
            .billingAddress(address)
            .currency("currency")
            .build()
    }

    private val cardProvisionMutationResult by before {
        CardProvisionMutation.CardProvision(
            "cardProvision",
            "id",
            "owner",
            1,
            1.0,
            1.0,
            "clientRefId",
            ProvisioningState.PROVISIONING,
            emptyList(),
            DeltaAction.DELETE
        )
    }

    private val cardProvisionResponse by before {
        Response.builder<CardProvisionMutation.Data>(CardProvisionMutation(cardProvisionRequest))
            .data(CardProvisionMutation.Data(cardProvisionMutationResult))
            .build()
    }

    private val provisionHolder = CallbackHolder<CardProvisionMutation.Data>()

    private val mockContext by before {
        mock<Context>()
    }

    private val mockUserClient by before {
        mock<SudoUserClient>().stub {
            on { getSubject() } doReturn "subject"
        }
    }

    private val mockSudoClient by before {
        mock<SudoProfilesClient>().stub {
            onBlocking { getOwnershipProof(any<Sudo>(), anyString()) } doReturn "jwt"
        }
    }

    private val mockAppSyncClient by before {
        mock<AWSAppSyncClient>().stub {
            on { mutate(any<CardProvisionMutation>()) } doReturn provisionHolder.mutationOperation
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
        keyRingId = "keyRingId"
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
            .setSudoProfilesClient(mockSudoClient)
            .setAppSyncClient(mockAppSyncClient)
            .setKeyManager(mockKeyManager)
            .setLogger(mock())
            .setPublicKeyService(mockPublicKeyService)
            .build()
    }

    private fun resetCallbacks() {
        provisionHolder.callback = null
    }

    @Before
    fun init() {
        resetCallbacks()
    }

    @After
    fun fini() {
        verifyNoMoreInteractions(
            mockContext,
            mockUserClient,
            mockSudoClient,
            mockKeyManager,
            mockPublicKeyService,
            mockAppSyncClient
        )
    }

    @Test
    fun `provisionVirtualCard() should return results when no error present`() = runBlocking<Unit> {

        provisionHolder.callback shouldBe null

        val deferredResult = async(Dispatchers.IO) {
            client.provisionVirtualCard(input)
        }
        deferredResult.start()

        delay(100L)
        provisionHolder.callback shouldNotBe null
        provisionHolder.callback?.onResponse(cardProvisionResponse)

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
        verify(mockAppSyncClient).mutate(any<CardProvisionMutation>())
    }

    @Test
    fun `provisionVirtualCard() should throw when registered public key retrieval fails`() = runBlocking<Unit> {

        provisionHolder.callback shouldBe null

        mockPublicKeyService.stub {
            onBlocking { getCurrentRegisteredKey() } doThrow PublicKeyService.PublicKeyServiceException.KeyCreateException()
        }

        shouldThrow<SudoVirtualCardsClient.VirtualCardException.PublicKeyException> {
            client.provisionVirtualCard(input)
        }

        verify(mockPublicKeyService).getCurrentRegisteredKey()
    }

    @Test
    fun `provisionVirtualCard() should throw when card mutation response is null`() = runBlocking<Unit> {

        provisionHolder.callback shouldBe null

        val nullProvisionResponse by before {
            Response.builder<CardProvisionMutation.Data>(CardProvisionMutation(cardProvisionRequest))
                .data(null)
                .build()
        }

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.VirtualCardException.ProvisionFailedException> {
                client.provisionVirtualCard(input)
            }
        }
        deferredResult.start()

        delay(100L)
        provisionHolder.callback shouldNotBe null
        provisionHolder.callback?.onResponse(nullProvisionResponse)

        deferredResult.await()

        verify(mockPublicKeyService).getCurrentRegisteredKey()
        verify(mockAppSyncClient).mutate(any<CardProvisionMutation>())
    }

    @Test
    fun `provisionVirtualCard() should throw when response has an identity verification not verified error`() = runBlocking<Unit> {

        provisionHolder.callback shouldBe null

        val errorProvisionResponse by before {
            val error = com.apollographql.apollo.api.Error(
                "mock",
                emptyList(),
                mapOf("errorType" to "IdentityVerificationNotVerifiedError")
            )
            Response.builder<CardProvisionMutation.Data>(CardProvisionMutation(cardProvisionRequest))
                .errors(listOf(error))
                .data(null)
                .build()
        }

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.VirtualCardException.IdentityVerificationException> {
                client.provisionVirtualCard(input)
            }
        }
        deferredResult.start()

        delay(100L)
        provisionHolder.callback shouldNotBe null
        provisionHolder.callback?.onResponse(errorProvisionResponse)

        deferredResult.await()

        verify(mockPublicKeyService).getCurrentRegisteredKey()
        verify(mockAppSyncClient).mutate(any<CardProvisionMutation>())
    }

    @Test
    fun `provisionVirtualCard() should throw when response has an identity verification insufficient error`() = runBlocking<Unit> {

        provisionHolder.callback shouldBe null

        val errorProvisionResponse by before {
            val error = com.apollographql.apollo.api.Error(
                "mock",
                emptyList(),
                mapOf("errorType" to "IdentityVerificationInsufficientError")
            )
            Response.builder<CardProvisionMutation.Data>(CardProvisionMutation(cardProvisionRequest))
                .errors(listOf(error))
                .data(null)
                .build()
        }

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.VirtualCardException.IdentityVerificationInsufficientException> {
                client.provisionVirtualCard(input)
            }
        }
        deferredResult.start()

        delay(100L)
        provisionHolder.callback shouldNotBe null
        provisionHolder.callback?.onResponse(errorProvisionResponse)

        deferredResult.await()

        verify(mockPublicKeyService).getCurrentRegisteredKey()
        verify(mockAppSyncClient).mutate(any<CardProvisionMutation>())
    }

    @Test
    fun `provisionVirtualCard() should throw when response has a funding source not found error`() = runBlocking<Unit> {

        provisionHolder.callback shouldBe null

        val errorProvisionResponse by before {
            val error = com.apollographql.apollo.api.Error(
                "mock",
                emptyList(),
                mapOf("errorType" to "FundingSourceNotFoundError")
            )
            Response.builder<CardProvisionMutation.Data>(CardProvisionMutation(cardProvisionRequest))
                .errors(listOf(error))
                .data(null)
                .build()
        }

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.VirtualCardException.FundingSourceNotFoundException> {
                client.provisionVirtualCard(input)
            }
        }
        deferredResult.start()

        delay(100L)
        provisionHolder.callback shouldNotBe null
        provisionHolder.callback?.onResponse(errorProvisionResponse)

        deferredResult.await()

        verify(mockPublicKeyService).getCurrentRegisteredKey()
        verify(mockAppSyncClient).mutate(any<CardProvisionMutation>())
    }

    @Test
    fun `provisionVirtualCard() should throw when response has a funding source not active error`() = runBlocking<Unit> {

        provisionHolder.callback shouldBe null

        val errorProvisionResponse by before {
            val error = com.apollographql.apollo.api.Error(
                "mock",
                emptyList(),
                mapOf("errorType" to "FundingSourceNotActiveError")
            )
            Response.builder<CardProvisionMutation.Data>(CardProvisionMutation(cardProvisionRequest))
                .errors(listOf(error))
                .data(null)
                .build()
        }

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.VirtualCardException.FundingSourceNotActiveException> {
                client.provisionVirtualCard(input)
            }
        }
        deferredResult.start()

        delay(100L)
        provisionHolder.callback shouldNotBe null
        provisionHolder.callback?.onResponse(errorProvisionResponse)

        deferredResult.await()

        verify(mockPublicKeyService).getCurrentRegisteredKey()
        verify(mockAppSyncClient).mutate(any<CardProvisionMutation>())
    }

    @Test
    fun `provisionVirtualCard() should throw when response has a velocity exceeded error`() = runBlocking<Unit> {

        provisionHolder.callback shouldBe null

        val errorProvisionResponse by before {
            val error = com.apollographql.apollo.api.Error(
                "mock",
                emptyList(),
                mapOf("errorType" to "VelocityExceededError")
            )
            Response.builder<CardProvisionMutation.Data>(CardProvisionMutation(cardProvisionRequest))
                .errors(listOf(error))
                .data(null)
                .build()
        }

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.VirtualCardException.VelocityExceededException> {
                client.provisionVirtualCard(input)
            }
        }
        deferredResult.start()

        delay(100L)
        provisionHolder.callback shouldNotBe null
        provisionHolder.callback?.onResponse(errorProvisionResponse)

        deferredResult.await()

        verify(mockPublicKeyService).getCurrentRegisteredKey()
        verify(mockAppSyncClient).mutate(any<CardProvisionMutation>())
    }

    @Test
    fun `provisionVirtualCard() should throw when response has an entitlement exceeded error`() = runBlocking<Unit> {

        provisionHolder.callback shouldBe null

        val errorProvisionResponse by before {
            val error = com.apollographql.apollo.api.Error(
                "mock",
                emptyList(),
                mapOf("errorType" to "EntitlementExceededError")
            )
            Response.builder<CardProvisionMutation.Data>(CardProvisionMutation(cardProvisionRequest))
                .errors(listOf(error))
                .data(null)
                .build()
        }

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.VirtualCardException.EntitlementExceededException> {
                client.provisionVirtualCard(input)
            }
        }

        deferredResult.start()

        delay(100L)
        provisionHolder.callback shouldNotBe null
        provisionHolder.callback?.onResponse(errorProvisionResponse)

        deferredResult.await()

        verify(mockPublicKeyService).getCurrentRegisteredKey()
        verify(mockAppSyncClient).mutate(any<CardProvisionMutation>())
    }

    @Test
    fun `provisionVirtualCard() should throw when response has an unsupported currency error`() = runBlocking<Unit> {

        provisionHolder.callback shouldBe null

        val errorProvisionResponse by before {
            val error = com.apollographql.apollo.api.Error(
                "mock",
                emptyList(),
                mapOf("errorType" to "UnsupportedCurrencyError")
            )
            Response.builder<CardProvisionMutation.Data>(CardProvisionMutation(cardProvisionRequest))
                .errors(listOf(error))
                .data(null)
                .build()
        }

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.VirtualCardException.UnsupportedCurrencyException> {
                client.provisionVirtualCard(input)
            }
        }
        deferredResult.start()

        delay(100L)
        provisionHolder.callback shouldNotBe null
        provisionHolder.callback?.onResponse(errorProvisionResponse)

        deferredResult.await()

        verify(mockPublicKeyService).getCurrentRegisteredKey()
        verify(mockAppSyncClient).mutate(any<CardProvisionMutation>())
    }

    @Test
    fun `provisionVirtualCard() should throw when response has an invalid token error`() = runBlocking<Unit> {

        provisionHolder.callback shouldBe null

        val errorProvisionResponse by before {
            val error = com.apollographql.apollo.api.Error(
                "mock",
                emptyList(),
                mapOf("errorType" to "InvalidTokenError")
            )
            Response.builder<CardProvisionMutation.Data>(CardProvisionMutation(cardProvisionRequest))
                .errors(listOf(error))
                .data(null)
                .build()
        }

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.VirtualCardException.ProvisionFailedException> {
                client.provisionVirtualCard(input)
            }
        }
        deferredResult.start()

        delay(100L)
        provisionHolder.callback shouldNotBe null
        provisionHolder.callback?.onResponse(errorProvisionResponse)

        deferredResult.await()

        verify(mockPublicKeyService).getCurrentRegisteredKey()
        verify(mockAppSyncClient).mutate(any<CardProvisionMutation>())
    }

    @Test
    fun `provisionVirtualCard() should throw when response has an account locked error`() = runBlocking<Unit> {

        provisionHolder.callback shouldBe null

        val errorProvisionResponse by before {
            val error = com.apollographql.apollo.api.Error(
                "mock",
                emptyList(),
                mapOf("errorType" to "AccountLockedError")
            )
            Response.builder<CardProvisionMutation.Data>(CardProvisionMutation(cardProvisionRequest))
                .errors(listOf(error))
                .data(null)
                .build()
        }

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.VirtualCardException.AccountLockedException> {
                client.provisionVirtualCard(input)
            }
        }
        deferredResult.start()

        delay(100L)
        provisionHolder.callback shouldNotBe null
        provisionHolder.callback?.onResponse(errorProvisionResponse)

        deferredResult.await()

        verify(mockPublicKeyService).getCurrentRegisteredKey()
        verify(mockAppSyncClient).mutate(any<CardProvisionMutation>())
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
    }
}
