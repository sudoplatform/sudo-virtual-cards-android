/*
 * Copyright © 2020 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards

import android.content.Context
import com.amazonaws.mobileconnectors.appsync.AWSAppSyncClient
import com.apollographql.apollo.api.Response
import com.nhaarman.mockitokotlin2.any
import com.nhaarman.mockitokotlin2.doReturn
import com.nhaarman.mockitokotlin2.doThrow
import com.nhaarman.mockitokotlin2.mock
import com.nhaarman.mockitokotlin2.stub
import com.nhaarman.mockitokotlin2.times
import com.nhaarman.mockitokotlin2.verify
import com.nhaarman.mockitokotlin2.verifyNoMoreInteractions
import com.sudoplatform.sudokeymanager.KeyManagerInterface
import com.sudoplatform.sudologging.Logger
import com.sudoplatform.sudoprofiles.Sudo
import com.sudoplatform.sudoprofiles.SudoProfilesClient
import com.sudoplatform.sudouser.AuthenticationTokens
import com.sudoplatform.sudouser.SudoUserClient
import com.sudoplatform.sudovirtualcards.graphql.CallbackHolder
import com.sudoplatform.sudovirtualcards.graphql.CardProvisionMutation
import com.sudoplatform.sudovirtualcards.graphql.CreatePublicKeyForVirtualCardsMutation
import com.sudoplatform.sudovirtualcards.graphql.GetKeyRingForVirtualCardsQuery
import com.sudoplatform.sudovirtualcards.keys.PublicKeyService
import com.sudoplatform.sudovirtualcards.graphql.type.AddressInput
import com.sudoplatform.sudovirtualcards.graphql.type.CardProvisionRequest
import com.sudoplatform.sudovirtualcards.graphql.type.CreatePublicKeyInput
import com.sudoplatform.sudovirtualcards.graphql.type.DeltaAction
import com.sudoplatform.sudovirtualcards.graphql.type.ProvisioningState
import com.sudoplatform.sudovirtualcards.types.ProvisionalCard
import com.sudoplatform.sudovirtualcards.types.inputs.ProvisionCardInput
import io.kotlintest.shouldBe
import io.kotlintest.shouldNotBe
import io.kotlintest.shouldThrow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.bouncycastle.util.encoders.Base64
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.anyString

/**
 * Test the correct operation of [SudoVirtualCardsClient.provisionCard] using mocks and spies.
 *
 * @since 2020-06-23
 */
class SudoVirtualCardsProvisionCardTest : BaseTests() {

    private val input by before {
        ProvisionCardInput(
            "clientRefId",
            "sudoId",
            "fundingSourceId",
            "cardHolder",
            "alias",
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

    private val keyRingQueryRequest by before {
        GetKeyRingForVirtualCardsQuery.builder()
            .keyRingId("keyRingId")
            .build()
    }

    private val keyRingQueryResult by before {
        val item = GetKeyRingForVirtualCardsQuery.Item(
            "typename",
            "id",
            "keyId",
            "keyRingId",
            "algorithm",
            String(Base64.encode("publicKey".toByteArray()), Charsets.UTF_8),
            "owner",
            1,
            1.0,
            1.0
        )
        GetKeyRingForVirtualCardsQuery.GetKeyRingForVirtualCards(
            "typename",
            listOf(item),
            "nextToken"
        )
    }

    private val publicKeyRequest by before {
        CreatePublicKeyInput.builder()
            .keyId("keyId")
            .keyRingId("keyRingId")
            .algorithm("algorithm")
            .publicKey(String(Base64.encode("publicKey".toByteArray()), Charsets.UTF_8))
            .build()
    }

    private val publicKeyResult by before {
        CreatePublicKeyForVirtualCardsMutation.CreatePublicKeyForVirtualCards(
            "typename",
            "id",
            "keyId",
            "keyRingId",
            "algorithm",
            String(Base64.encode("publicKey".toByteArray()), Charsets.UTF_8),
            "owner",
            1,
            1.0,
            1.0
        )
    }

    private val cardProvisionResponse by before {
        Response.builder<CardProvisionMutation.Data>(CardProvisionMutation(cardProvisionRequest))
            .data(CardProvisionMutation.Data(cardProvisionMutationResult))
            .build()
    }

    private val keyRingResponse by before {
        Response.builder<GetKeyRingForVirtualCardsQuery.Data>(keyRingQueryRequest)
            .data(GetKeyRingForVirtualCardsQuery.Data(keyRingQueryResult))
            .build()
    }

    private val publicKeyResponse by before {
        Response.builder<CreatePublicKeyForVirtualCardsMutation.Data>(CreatePublicKeyForVirtualCardsMutation(publicKeyRequest))
            .data(CreatePublicKeyForVirtualCardsMutation.Data(publicKeyResult))
            .build()
    }

    private val provisionHolder = CallbackHolder<CardProvisionMutation.Data>()
    private val keyRingHolder = CallbackHolder<GetKeyRingForVirtualCardsQuery.Data>()
    private val publicKeyHolder = CallbackHolder<CreatePublicKeyForVirtualCardsMutation.Data>()

    private val mockContext by before {
        mock<Context>()
    }

    private val mockUserClient by before {
        mock<SudoUserClient>().stub {
            on { getSubject() } doReturn "subject"
            on { getRefreshToken() } doReturn "refreshToken"
            onBlocking { refreshTokens(anyString()) } doReturn AuthenticationTokens("idToken", "accessToken", "refreshToken", 0)
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
            on { mutate(any<CreatePublicKeyForVirtualCardsMutation>()) } doReturn publicKeyHolder.mutationOperation
            on { query(any<GetKeyRingForVirtualCardsQuery>()) } doReturn keyRingHolder.queryOperation
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
            .setSudoProfilesClient(mockSudoClient)
            .setAppSyncClient(mockAppSyncClient)
            .setKeyManager(mockKeyManager)
            .setLogger(mock<Logger>())
            .build()
    }

    private fun resetCallbacks() {
        provisionHolder.callback = null
        keyRingHolder.callback = null
        publicKeyHolder.callback = null
    }

    @Before
    fun init() {
        resetCallbacks()
    }

    @After
    fun fini() {
        verifyNoMoreInteractions(mockContext, mockUserClient, mockSudoClient, mockKeyManager, mockAppSyncClient)
    }

    @Test
    fun `provisionCard() should return results when no error present`() = runBlocking<Unit> {

        provisionHolder.callback shouldBe null
        keyRingHolder.callback shouldBe null
        publicKeyHolder.callback shouldBe null

        val deferredResult = async(Dispatchers.IO) {
            client.provisionCard(input)
        }
        deferredResult.start()

        delay(100L)
        keyRingHolder.callback shouldNotBe null
        keyRingHolder.callback?.onResponse(keyRingResponse)

        delay(100L)
        publicKeyHolder.callback shouldNotBe null
        publicKeyHolder.callback?.onResponse(publicKeyResponse)

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
            state shouldBe ProvisionalCard.State.PROVISIONING
            card shouldBe null
            createdAt shouldNotBe null
            updatedAt shouldNotBe null
        }

        verify(mockAppSyncClient).mutate(any<CardProvisionMutation>())
        verify(mockAppSyncClient).query(any<GetKeyRingForVirtualCardsQuery>())
        verify(mockAppSyncClient).mutate(any<CreatePublicKeyForVirtualCardsMutation>())
        verify(mockKeyManager).getPassword(anyString())
        verify(mockKeyManager).getPublicKeyData(anyString())
        verify(mockKeyManager).getPrivateKeyData(anyString())
        verify(mockUserClient, times(2)).getSubject()
        verify(mockUserClient).refreshTokens(anyString())
        verify(mockUserClient).getRefreshToken()
        verify(mockSudoClient).getOwnershipProof(any<Sudo>(), anyString())
    }

    @Test
    fun `provisionCard() should throw when public key response is null`() = runBlocking<Unit> {

        provisionHolder.callback shouldBe null
        keyRingHolder.callback shouldBe null
        publicKeyHolder.callback shouldBe null

        val nullPublicKeyResponse by before {
            Response.builder<CreatePublicKeyForVirtualCardsMutation.Data>(CreatePublicKeyForVirtualCardsMutation(publicKeyRequest))
                .data(null)
                .build()
        }

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.CardException.PublicKeyException> {
                client.provisionCard(input)
            }
        }
        deferredResult.start()
        delay(100L)

        keyRingHolder.callback shouldNotBe null
        keyRingHolder.callback?.onResponse(keyRingResponse)

        delay(100L)
        publicKeyHolder.callback shouldNotBe null
        publicKeyHolder.callback?.onResponse(nullPublicKeyResponse)

        verify(mockAppSyncClient).query(any<GetKeyRingForVirtualCardsQuery>())
        verify(mockAppSyncClient).mutate(any<CreatePublicKeyForVirtualCardsMutation>())
        verify(mockKeyManager).getPassword(anyString())
        verify(mockKeyManager).getPublicKeyData(anyString())
        verify(mockKeyManager).getPrivateKeyData(anyString())
        verify(mockUserClient).getSubject()
        verify(mockUserClient).refreshTokens(anyString())
        verify(mockUserClient).getRefreshToken()
    }

    @Test
    fun `provisionCard() should throw when card mutation response is null`() = runBlocking<Unit> {

        provisionHolder.callback shouldBe null
        keyRingHolder.callback shouldBe null
        publicKeyHolder.callback shouldBe null

        val nullProvisionResponse by before {
            Response.builder<CardProvisionMutation.Data>(CardProvisionMutation(cardProvisionRequest))
                .data(null)
                .build()
        }

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.CardException.ProvisionFailedException> {
                client.provisionCard(input)
            }
        }
        deferredResult.start()
        delay(100L)

        keyRingHolder.callback shouldNotBe null
        keyRingHolder.callback?.onResponse(keyRingResponse)

        delay(100L)
        publicKeyHolder.callback shouldNotBe null
        publicKeyHolder.callback?.onResponse(publicKeyResponse)

        delay(100L)
        provisionHolder.callback shouldNotBe null
        provisionHolder.callback?.onResponse(nullProvisionResponse)

        verify(mockAppSyncClient).query(any<GetKeyRingForVirtualCardsQuery>())
        verify(mockAppSyncClient).mutate(any<CreatePublicKeyForVirtualCardsMutation>())
        verify(mockAppSyncClient).mutate(any<CardProvisionMutation>())
        verify(mockKeyManager).getPassword(anyString())
        verify(mockKeyManager).getPublicKeyData(anyString())
        verify(mockKeyManager).getPrivateKeyData(anyString())
        verify(mockUserClient, times(2)).getSubject()
        verify(mockUserClient).refreshTokens(anyString())
        verify(mockUserClient).getRefreshToken()
        verify(mockSudoClient).getOwnershipProof(any<Sudo>(), anyString())
    }

    @Test
    fun `provisionCard() should throw when response has an identity verification not verified error`() = runBlocking<Unit> {

        provisionHolder.callback shouldBe null
        keyRingHolder.callback shouldBe null
        publicKeyHolder.callback shouldBe null

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
            shouldThrow<SudoVirtualCardsClient.CardException.IdentityVerificationException> {
                client.provisionCard(input)
            }
        }
        deferredResult.start()
        delay(100L)

        keyRingHolder.callback shouldNotBe null
        keyRingHolder.callback?.onResponse(keyRingResponse)

        delay(100L)
        publicKeyHolder.callback shouldNotBe null
        publicKeyHolder.callback?.onResponse(publicKeyResponse)

        delay(100L)
        provisionHolder.callback shouldNotBe null
        provisionHolder.callback?.onResponse(errorProvisionResponse)

        verify(mockAppSyncClient).query(any<GetKeyRingForVirtualCardsQuery>())
        verify(mockAppSyncClient).mutate(any<CreatePublicKeyForVirtualCardsMutation>())
        verify(mockAppSyncClient).mutate(any<CardProvisionMutation>())
        verify(mockKeyManager).getPassword(anyString())
        verify(mockKeyManager).getPublicKeyData(anyString())
        verify(mockKeyManager).getPrivateKeyData(anyString())
        verify(mockUserClient, times(2)).getSubject()
        verify(mockUserClient).refreshTokens(anyString())
        verify(mockUserClient).getRefreshToken()
        verify(mockSudoClient).getOwnershipProof(any<Sudo>(), anyString())
    }

    @Test
    fun `provisionCard() should throw when response has an identity verification insufficient error`() = runBlocking<Unit> {

        provisionHolder.callback shouldBe null
        keyRingHolder.callback shouldBe null
        publicKeyHolder.callback shouldBe null

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
            shouldThrow<SudoVirtualCardsClient.CardException.IdentityVerificationInsufficientException> {
                client.provisionCard(input)
            }
        }
        deferredResult.start()
        delay(100L)

        keyRingHolder.callback shouldNotBe null
        keyRingHolder.callback?.onResponse(keyRingResponse)

        delay(100L)
        publicKeyHolder.callback shouldNotBe null
        publicKeyHolder.callback?.onResponse(publicKeyResponse)

        delay(100L)
        provisionHolder.callback shouldNotBe null
        provisionHolder.callback?.onResponse(errorProvisionResponse)

        verify(mockAppSyncClient).query(any<GetKeyRingForVirtualCardsQuery>())
        verify(mockAppSyncClient).mutate(any<CreatePublicKeyForVirtualCardsMutation>())
        verify(mockAppSyncClient).mutate(any<CardProvisionMutation>())
        verify(mockKeyManager).getPassword(anyString())
        verify(mockKeyManager).getPublicKeyData(anyString())
        verify(mockKeyManager).getPrivateKeyData(anyString())
        verify(mockUserClient, times(2)).getSubject()
        verify(mockUserClient).refreshTokens(anyString())
        verify(mockUserClient).getRefreshToken()
        verify(mockSudoClient).getOwnershipProof(any<Sudo>(), anyString())
    }

    @Test
    fun `provisionCard() should throw when response has a funding source not found error`() = runBlocking<Unit> {

        provisionHolder.callback shouldBe null
        keyRingHolder.callback shouldBe null
        publicKeyHolder.callback shouldBe null

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
            shouldThrow<SudoVirtualCardsClient.CardException.FundingSourceNotFoundException> {
                client.provisionCard(input)
            }
        }
        deferredResult.start()
        delay(100L)

        keyRingHolder.callback shouldNotBe null
        keyRingHolder.callback?.onResponse(keyRingResponse)

        delay(100L)
        publicKeyHolder.callback shouldNotBe null
        publicKeyHolder.callback?.onResponse(publicKeyResponse)

        delay(100L)
        provisionHolder.callback shouldNotBe null
        provisionHolder.callback?.onResponse(errorProvisionResponse)

        verify(mockAppSyncClient).query(any<GetKeyRingForVirtualCardsQuery>())
        verify(mockAppSyncClient).mutate(any<CreatePublicKeyForVirtualCardsMutation>())
        verify(mockAppSyncClient).mutate(any<CardProvisionMutation>())
        verify(mockKeyManager).getPassword(anyString())
        verify(mockKeyManager).getPublicKeyData(anyString())
        verify(mockKeyManager).getPrivateKeyData(anyString())
        verify(mockUserClient, times(2)).getSubject()
        verify(mockUserClient).refreshTokens(anyString())
        verify(mockUserClient).getRefreshToken()
        verify(mockSudoClient).getOwnershipProof(any<Sudo>(), anyString())
    }

    @Test
    fun `provisionCard() should throw when response has a funding source not active error`() = runBlocking<Unit> {

        provisionHolder.callback shouldBe null
        keyRingHolder.callback shouldBe null
        publicKeyHolder.callback shouldBe null

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
            shouldThrow<SudoVirtualCardsClient.CardException.FundingSourceNotActiveException> {
                client.provisionCard(input)
            }
        }
        deferredResult.start()
        delay(100L)

        keyRingHolder.callback shouldNotBe null
        keyRingHolder.callback?.onResponse(keyRingResponse)

        delay(100L)
        publicKeyHolder.callback shouldNotBe null
        publicKeyHolder.callback?.onResponse(publicKeyResponse)

        delay(100L)
        provisionHolder.callback shouldNotBe null
        provisionHolder.callback?.onResponse(errorProvisionResponse)

        verify(mockAppSyncClient).query(any<GetKeyRingForVirtualCardsQuery>())
        verify(mockAppSyncClient).mutate(any<CreatePublicKeyForVirtualCardsMutation>())
        verify(mockAppSyncClient).mutate(any<CardProvisionMutation>())
        verify(mockKeyManager).getPassword(anyString())
        verify(mockKeyManager).getPublicKeyData(anyString())
        verify(mockKeyManager).getPrivateKeyData(anyString())
        verify(mockUserClient, times(2)).getSubject()
        verify(mockUserClient).refreshTokens(anyString())
        verify(mockUserClient).getRefreshToken()
        verify(mockSudoClient).getOwnershipProof(any<Sudo>(), anyString())
    }

    @Test
    fun `provisionCard() should throw when response has a velocity exceeded error`() = runBlocking<Unit> {

        provisionHolder.callback shouldBe null
        keyRingHolder.callback shouldBe null
        publicKeyHolder.callback shouldBe null

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
            shouldThrow<SudoVirtualCardsClient.CardException.VelocityExceededException> {
                client.provisionCard(input)
            }
        }
        deferredResult.start()
        delay(100L)

        keyRingHolder.callback shouldNotBe null
        keyRingHolder.callback?.onResponse(keyRingResponse)

        delay(100L)
        publicKeyHolder.callback shouldNotBe null
        publicKeyHolder.callback?.onResponse(publicKeyResponse)

        delay(100L)
        provisionHolder.callback shouldNotBe null
        provisionHolder.callback?.onResponse(errorProvisionResponse)

        verify(mockAppSyncClient).query(any<GetKeyRingForVirtualCardsQuery>())
        verify(mockAppSyncClient).mutate(any<CreatePublicKeyForVirtualCardsMutation>())
        verify(mockAppSyncClient).mutate(any<CardProvisionMutation>())
        verify(mockKeyManager).getPassword(anyString())
        verify(mockKeyManager).getPublicKeyData(anyString())
        verify(mockKeyManager).getPrivateKeyData(anyString())
        verify(mockUserClient, times(2)).getSubject()
        verify(mockUserClient).refreshTokens(anyString())
        verify(mockUserClient).getRefreshToken()
        verify(mockSudoClient).getOwnershipProof(any<Sudo>(), anyString())
    }

    @Test
    fun `provisionCard() should throw when response has an entitlement exceeded error`() = runBlocking<Unit> {

        provisionHolder.callback shouldBe null
        keyRingHolder.callback shouldBe null
        publicKeyHolder.callback shouldBe null

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
            shouldThrow<SudoVirtualCardsClient.CardException.EntitlementExceededException> {
                client.provisionCard(input)
            }
        }
        deferredResult.start()
        delay(100L)

        keyRingHolder.callback shouldNotBe null
        keyRingHolder.callback?.onResponse(keyRingResponse)

        delay(100L)
        publicKeyHolder.callback shouldNotBe null
        publicKeyHolder.callback?.onResponse(publicKeyResponse)

        delay(100L)
        provisionHolder.callback shouldNotBe null
        provisionHolder.callback?.onResponse(errorProvisionResponse)

        verify(mockAppSyncClient).query(any<GetKeyRingForVirtualCardsQuery>())
        verify(mockAppSyncClient).mutate(any<CreatePublicKeyForVirtualCardsMutation>())
        verify(mockAppSyncClient).mutate(any<CardProvisionMutation>())
        verify(mockKeyManager).getPassword(anyString())
        verify(mockKeyManager).getPublicKeyData(anyString())
        verify(mockKeyManager).getPrivateKeyData(anyString())
        verify(mockUserClient, times(2)).getSubject()
        verify(mockUserClient).refreshTokens(anyString())
        verify(mockUserClient).getRefreshToken()
        verify(mockSudoClient).getOwnershipProof(any<Sudo>(), anyString())
    }

    @Test
    fun `provisionCard() should throw when response has an unsupported currency error`() = runBlocking<Unit> {

        provisionHolder.callback shouldBe null
        keyRingHolder.callback shouldBe null
        publicKeyHolder.callback shouldBe null

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
            shouldThrow<SudoVirtualCardsClient.CardException.UnsupportedCurrencyException> {
                client.provisionCard(input)
            }
        }
        deferredResult.start()
        delay(100L)

        keyRingHolder.callback shouldNotBe null
        keyRingHolder.callback?.onResponse(keyRingResponse)

        delay(100L)
        publicKeyHolder.callback shouldNotBe null
        publicKeyHolder.callback?.onResponse(publicKeyResponse)

        delay(100L)
        provisionHolder.callback shouldNotBe null
        provisionHolder.callback?.onResponse(errorProvisionResponse)

        verify(mockAppSyncClient).query(any<GetKeyRingForVirtualCardsQuery>())
        verify(mockAppSyncClient).mutate(any<CreatePublicKeyForVirtualCardsMutation>())
        verify(mockAppSyncClient).mutate(any<CardProvisionMutation>())
        verify(mockKeyManager).getPassword(anyString())
        verify(mockKeyManager).getPublicKeyData(anyString())
        verify(mockKeyManager).getPrivateKeyData(anyString())
        verify(mockUserClient, times(2)).getSubject()
        verify(mockUserClient).refreshTokens(anyString())
        verify(mockUserClient).getRefreshToken()
        verify(mockSudoClient).getOwnershipProof(any<Sudo>(), anyString())
    }

    @Test
    fun `provisionCard() should throw when response has an invalid token error`() = runBlocking<Unit> {

        provisionHolder.callback shouldBe null
        keyRingHolder.callback shouldBe null
        publicKeyHolder.callback shouldBe null

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
            shouldThrow<SudoVirtualCardsClient.CardException.ProvisionFailedException> {
                client.provisionCard(input)
            }
        }
        deferredResult.start()
        delay(100L)

        keyRingHolder.callback shouldNotBe null
        keyRingHolder.callback?.onResponse(keyRingResponse)

        delay(100L)
        publicKeyHolder.callback shouldNotBe null
        publicKeyHolder.callback?.onResponse(publicKeyResponse)

        delay(100L)
        provisionHolder.callback shouldNotBe null
        provisionHolder.callback?.onResponse(errorProvisionResponse)

        verify(mockAppSyncClient).query(any<GetKeyRingForVirtualCardsQuery>())
        verify(mockAppSyncClient).mutate(any<CreatePublicKeyForVirtualCardsMutation>())
        verify(mockAppSyncClient).mutate(any<CardProvisionMutation>())
        verify(mockKeyManager).getPassword(anyString())
        verify(mockKeyManager).getPublicKeyData(anyString())
        verify(mockKeyManager).getPrivateKeyData(anyString())
        verify(mockUserClient, times(2)).getSubject()
        verify(mockUserClient).refreshTokens(anyString())
        verify(mockUserClient).getRefreshToken()
        verify(mockSudoClient).getOwnershipProof(any<Sudo>(), anyString())
    }

    @Test
    fun `provisionCard() should throw when response has an account locked error`() = runBlocking<Unit> {

        provisionHolder.callback shouldBe null
        keyRingHolder.callback shouldBe null
        publicKeyHolder.callback shouldBe null

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
            shouldThrow<SudoVirtualCardsClient.CardException.AccountLockedException> {
                client.provisionCard(input)
            }
        }
        deferredResult.start()
        delay(100L)

        keyRingHolder.callback shouldNotBe null
        keyRingHolder.callback?.onResponse(keyRingResponse)

        delay(100L)
        publicKeyHolder.callback shouldNotBe null
        publicKeyHolder.callback?.onResponse(publicKeyResponse)

        delay(100L)
        provisionHolder.callback shouldNotBe null
        provisionHolder.callback?.onResponse(errorProvisionResponse)

        verify(mockAppSyncClient).query(any<GetKeyRingForVirtualCardsQuery>())
        verify(mockAppSyncClient).mutate(any<CreatePublicKeyForVirtualCardsMutation>())
        verify(mockAppSyncClient).mutate(any<CardProvisionMutation>())
        verify(mockKeyManager).getPassword(anyString())
        verify(mockKeyManager).getPublicKeyData(anyString())
        verify(mockKeyManager).getPrivateKeyData(anyString())
        verify(mockUserClient, times(2)).getSubject()
        verify(mockUserClient).refreshTokens(anyString())
        verify(mockUserClient).getRefreshToken()
        verify(mockSudoClient).getOwnershipProof(any<Sudo>(), anyString())
    }

    @Test
    fun `provisionCard() should not block coroutine cancellation exception`() = runBlocking<Unit> {

        mockKeyManager.stub {
            on { getPassword(anyString()) } doThrow CancellationException("mock")
        }

        shouldThrow<CancellationException> {
            client.provisionCard(input)
        }
        verify(mockKeyManager).getPassword(anyString())
        verify(mockUserClient).refreshTokens(anyString())
        verify(mockUserClient).getRefreshToken()
    }

    @Test
    fun `provisionCard() should throw when key registration fails`() = runBlocking<Unit> {

        mockKeyManager.stub {
            on { getPassword(anyString()) } doThrow PublicKeyService.PublicKeyServiceException.KeyCreateException("mock")
        }

        shouldThrow<SudoVirtualCardsClient.CardException.PublicKeyException> {
            client.provisionCard(input)
        }
        verify(mockKeyManager).getPassword(anyString())
        verify(mockUserClient).refreshTokens(anyString())
        verify(mockUserClient).getRefreshToken()
    }
}
