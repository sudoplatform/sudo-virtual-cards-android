/*
 * Copyright © 2020 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards

import android.content.Context
import com.amazonaws.mobileconnectors.appsync.AWSAppSyncClient
import com.apollographql.apollo.api.Response
import com.apollographql.apollo.exception.ApolloHttpException
import com.apollographql.apollo.exception.ApolloNetworkException
import com.google.gson.Gson
import com.nhaarman.mockitokotlin2.any
import com.nhaarman.mockitokotlin2.doReturn
import com.nhaarman.mockitokotlin2.doThrow
import com.nhaarman.mockitokotlin2.mock
import com.nhaarman.mockitokotlin2.stub
import com.nhaarman.mockitokotlin2.verify
import com.nhaarman.mockitokotlin2.verifyNoMoreInteractions
import com.sudoplatform.sudokeymanager.KeyManagerInterface
import com.sudoplatform.sudologging.Logger
import com.sudoplatform.sudoprofiles.SudoProfilesClient
import com.sudoplatform.sudouser.SudoUserClient
import com.sudoplatform.sudovirtualcards.graphql.CallbackHolder
import com.sudoplatform.sudovirtualcards.graphql.CompleteFundingSourceMutation
import com.sudoplatform.sudovirtualcards.graphql.GetFundingSourceClientConfigurationQuery
import com.sudoplatform.sudovirtualcards.graphql.SetupFundingSourceMutation
import com.sudoplatform.sudovirtualcards.graphql.type.CompleteFundingSourceRequest
import com.sudoplatform.sudovirtualcards.graphql.type.FundingSourceState
import com.sudoplatform.sudovirtualcards.graphql.type.FundingSourceType
import com.sudoplatform.sudovirtualcards.graphql.type.SetupFundingSourceRequest
import com.sudoplatform.sudovirtualcards.types.FundingSource
import com.sudoplatform.sudovirtualcards.types.StripeClientConfiguration
import com.sudoplatform.sudovirtualcards.types.StripeData
import com.sudoplatform.sudovirtualcards.types.inputs.CreditCardFundingSourceInput
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
import org.apache.commons.codec.binary.Base64
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.net.HttpURLConnection

/**
 * Test the correct operation of [SudoVirtualCardsClient.createFundingSource]
 * using mocks and spies.
 *
 * @since 2020-05-27
 */
class SudoVirtualCardsCreateFundingSourceTest : BaseTests() {

    private val input by before {
        CreditCardFundingSourceInput(
            "cardNumber",
            1,
            1,
            "securityCode",
            "addressLine1",
            null,
            "city",
            "state",
            "postalCode",
            "country"
        )
    }

    private val configQueryResult by before {
        val config = StripeClientConfiguration(listOf(com.sudoplatform.sudovirtualcards.types.FundingSourceType(apiKey = "test-key")))
        val configStr = Gson().toJson(config)
        val encodedConfigData = Base64.encodeBase64String(configStr.toByteArray())
        GetFundingSourceClientConfigurationQuery.GetFundingSourceClientConfiguration(
            "typename",
            encodedConfigData
        )
    }

    private val configResponse by before {
        Response.builder<GetFundingSourceClientConfigurationQuery.Data>(GetFundingSourceClientConfigurationQuery())
            .data(GetFundingSourceClientConfigurationQuery.Data(configQueryResult))
            .build()
    }

    private val setupRequest = SetupFundingSourceRequest.builder()
        .type(FundingSourceType.CREDIT_CARD)
        .currency("USD")
        .build()

    private val setupMutationResult by before {
        val setupData = StripeData("provider", 1, "intent", "clientSecret")
        val setupDataStr = Gson().toJson(setupData)
        val encodedSetupData = Base64.encodeBase64String(setupDataStr.toByteArray())
        SetupFundingSourceMutation.SetupFundingSource(
            "typename",
            "id",
            "owner",
            1,
            1.0,
            10.0,
            encodedSetupData
        )
    }

    private val setupResponse by before {
        Response.builder<SetupFundingSourceMutation.Data>(SetupFundingSourceMutation(setupRequest))
            .data(SetupFundingSourceMutation.Data(setupMutationResult))
            .build()
    }

    private val completionData = "completionData"
    private val completeRequest = CompleteFundingSourceRequest.builder()
        .id("id")
        .completionData(completionData)
        .build()

    private val completeMutationResult by before {
        CompleteFundingSourceMutation.CompleteFundingSource(
            "typename",
            "id",
            "owner",
            1,
            1.0,
            10.0,
            FundingSourceState.ACTIVE,
            "USD",
            "last4",
            com.sudoplatform.sudovirtualcards.graphql.type.CreditCardNetwork.VISA
        )
    }

    private val completeResponse by before {
        Response.builder<CompleteFundingSourceMutation.Data>(CompleteFundingSourceMutation(completeRequest))
            .data(CompleteFundingSourceMutation.Data(completeMutationResult))
            .build()
    }

    private val configHolder = CallbackHolder<GetFundingSourceClientConfigurationQuery.Data>()
    private val setupHolder = CallbackHolder<SetupFundingSourceMutation.Data>()
    private val completeHolder = CallbackHolder<CompleteFundingSourceMutation.Data>()

    private val mockContext by before {
        mock<Context>()
    }

    private val mockUserClient by before {
        mock<SudoUserClient>()
    }

    private val mockSudoClient by before {
        mock<SudoProfilesClient>()
    }

    private val mockPaymentProcessorInteractions by before {
        mock<PaymentProcessInteractions>().stub {
            on { process(any(), any(), any(), any()) } doReturn completionData
        }
    }

    private val mockAppSyncClient by before {
        mock<AWSAppSyncClient>().stub {
            on { query(any<GetFundingSourceClientConfigurationQuery>()) } doReturn configHolder.queryOperation
            on { mutate(any<SetupFundingSourceMutation>()) } doReturn setupHolder.mutationOperation
            on { mutate(any<CompleteFundingSourceMutation>()) } doReturn completeHolder.mutationOperation
        }
    }

    private val mockKeyManager by before {
        mock<KeyManagerInterface>()
    }

    private val client by before {
        SudoVirtualCardsClient.builder()
            .setContext(mockContext)
            .setSudoUserClient(mockUserClient)
            .setSudoProfilesClient(mockSudoClient)
            .setAppSyncClient(mockAppSyncClient)
            .setPaymentProcessInteractions(mockPaymentProcessorInteractions)
            .setKeyManager(mockKeyManager)
            .setLogger(mock<Logger>())
            .build()
    }

    private fun resetCallbacks() {
        configHolder.callback = null
        setupHolder.callback = null
        completeHolder.callback = null
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
            mockAppSyncClient,
            mockPaymentProcessorInteractions
        )
    }

    @Test
    fun `createFundingSource() should return results when no error present`() = runBlocking<Unit> {

        configHolder.callback shouldBe null
        setupHolder.callback shouldBe null
        completeHolder.callback shouldBe null

        val deferredResult = async(Dispatchers.IO) {
            client.createFundingSource(input)
        }
        deferredResult.start()

        delay(100L)
        configHolder.callback shouldNotBe null
        configHolder.callback?.onResponse(configResponse)

        delay(100L)
        setupHolder.callback shouldNotBe null
        setupHolder.callback?.onResponse(setupResponse)

        delay(100L)
        completeHolder.callback shouldNotBe null
        completeHolder.callback?.onResponse(completeResponse)

        val result = deferredResult.await()
        result shouldNotBe null

        with(result) {
            id shouldBe "id"
            owner shouldBe "owner"
            version shouldBe 1
            state shouldBe FundingSource.State.ACTIVE
            currency shouldBe "USD"
            last4 shouldBe "last4"
            network shouldBe FundingSource.CreditCardNetwork.VISA
        }

        verify(mockAppSyncClient).query(any<GetFundingSourceClientConfigurationQuery>())
        verify(mockAppSyncClient).mutate(any<SetupFundingSourceMutation>())
        verify(mockAppSyncClient).mutate(any<CompleteFundingSourceMutation>())
        verify(mockPaymentProcessorInteractions).process(any(), any(), any(), any())
    }

    @Test
    fun `createFundingSource() should throw when response is null during getFundingSourceClientConfiguration()`() = runBlocking<Unit> {

        configHolder.callback shouldBe null
        setupHolder.callback shouldBe null
        completeHolder.callback shouldBe null

        val nullConfigResponse by before {
            Response.builder<GetFundingSourceClientConfigurationQuery.Data>(GetFundingSourceClientConfigurationQuery())
                .data(null)
                .build()
        }

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.FundingSourceException.FailedException> {
                client.createFundingSource(input)
            }
        }
        deferredResult.start()
        delay(100L)

        setupHolder.callback shouldBe null
        completeHolder.callback shouldBe null
        configHolder.callback shouldNotBe null
        configHolder.callback?.onResponse(nullConfigResponse)

        deferredResult.await()

        verify(mockAppSyncClient).query(any<GetFundingSourceClientConfigurationQuery>())
    }

    @Test
    fun `createFundingSource() should throw when response is null during setupFundingSource()`() = runBlocking<Unit> {

        configHolder.callback shouldBe null
        setupHolder.callback shouldBe null
        completeHolder.callback shouldBe null

        val nullSetupResponse by before {
            Response.builder<SetupFundingSourceMutation.Data>(SetupFundingSourceMutation(setupRequest))
                .data(null)
                .build()
        }

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.FundingSourceException.SetupFailedException> {
                client.createFundingSource(input)
            }
        }
        deferredResult.start()

        delay(100L)
        configHolder.callback shouldNotBe null
        configHolder.callback?.onResponse(configResponse)

        delay(100L)
        setupHolder.callback shouldNotBe null
        setupHolder.callback?.onResponse(nullSetupResponse)

        completeHolder.callback shouldBe null

        deferredResult.await()

        verify(mockAppSyncClient).query(any<GetFundingSourceClientConfigurationQuery>())
        verify(mockAppSyncClient).mutate(any<SetupFundingSourceMutation>())
    }

    @Test
    fun `createFundingSource() should throw when response is null during completeFundingSource()`() = runBlocking<Unit> {

        configHolder.callback shouldBe null
        setupHolder.callback shouldBe null
        completeHolder.callback shouldBe null

        val nullCompleteResponse by before {
            Response.builder<CompleteFundingSourceMutation.Data>(CompleteFundingSourceMutation(completeRequest))
                .data(null)
                .build()
        }

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.FundingSourceException.CompletionFailedException> {
                client.createFundingSource(input)
            }
        }
        deferredResult.start()

        delay(100L)
        configHolder.callback shouldNotBe null
        configHolder.callback?.onResponse(configResponse)

        delay(100L)
        setupHolder.callback shouldNotBe null
        setupHolder.callback?.onResponse(setupResponse)

        delay(100L)
        completeHolder.callback shouldNotBe null
        completeHolder.callback?.onResponse(nullCompleteResponse)

        deferredResult.await()

        verify(mockAppSyncClient).query(any<GetFundingSourceClientConfigurationQuery>())
        verify(mockAppSyncClient).mutate(any<SetupFundingSourceMutation>())
        verify(mockAppSyncClient).mutate(any<CompleteFundingSourceMutation>())
        verify(mockPaymentProcessorInteractions).process(any(), any(), any(), any())
    }

    @Test
    fun `createFundingSource() should throw when response has an identity verification error`
    () = runBlocking<Unit> {

        configHolder.callback shouldBe null
        setupHolder.callback shouldBe null
        completeHolder.callback shouldBe null

        val errorCreateResponse by before {
            val error = com.apollographql.apollo.api.Error(
                "mock",
                emptyList(),
                mapOf("errorType" to "IdentityVerificationNotVerifiedError")
            )
            Response.builder<CompleteFundingSourceMutation.Data>(CompleteFundingSourceMutation(completeRequest))
                .errors(listOf(error))
                .data(null)
                .build()
        }

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.FundingSourceException.IdentityVerificationException> {
                client.createFundingSource(input)
            }
        }
        deferredResult.start()

        delay(100L)
        configHolder.callback shouldNotBe null
        configHolder.callback?.onResponse(configResponse)

        delay(100L)
        setupHolder.callback shouldNotBe null
        setupHolder.callback?.onResponse(setupResponse)

        delay(100L)
        completeHolder.callback shouldNotBe null
        completeHolder.callback?.onResponse(errorCreateResponse)

        deferredResult.await()

        verify(mockAppSyncClient).query(any<GetFundingSourceClientConfigurationQuery>())
        verify(mockAppSyncClient).mutate(any<SetupFundingSourceMutation>())
        verify(mockAppSyncClient).mutate(any<CompleteFundingSourceMutation>())
        verify(mockPaymentProcessorInteractions).process(any(), any(), any(), any())
    }

    @Test
    fun `createFundingSource() should throw when response has a duplicate funding source error`
    () = runBlocking<Unit> {

        configHolder.callback shouldBe null
        setupHolder.callback shouldBe null
        completeHolder.callback shouldBe null

        val errorCreateResponse by before {
            val error = com.apollographql.apollo.api.Error(
                "mock",
                emptyList(),
                mapOf("errorType" to "DuplicateFundingSourceError")
            )
            Response.builder<CompleteFundingSourceMutation.Data>(CompleteFundingSourceMutation(completeRequest))
                .errors(listOf(error))
                .data(null)
                .build()
        }

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.FundingSourceException.DuplicateFundingSourceException> {
                client.createFundingSource(input)
            }
        }
        deferredResult.start()

        delay(100L)
        configHolder.callback shouldNotBe null
        configHolder.callback?.onResponse(configResponse)

        delay(100L)
        setupHolder.callback shouldNotBe null
        setupHolder.callback?.onResponse(setupResponse)

        delay(100L)
        completeHolder.callback shouldNotBe null
        completeHolder.callback?.onResponse(errorCreateResponse)

        deferredResult.await()

        verify(mockAppSyncClient).query(any<GetFundingSourceClientConfigurationQuery>())
        verify(mockAppSyncClient).mutate(any<SetupFundingSourceMutation>())
        verify(mockAppSyncClient).mutate(any<CompleteFundingSourceMutation>())
        verify(mockPaymentProcessorInteractions).process(any(), any(), any(), any())
    }

    @Test
    fun `createFundingSource() should throw when response has a funding source not setup error`
    () = runBlocking<Unit> {

        configHolder.callback shouldBe null
        setupHolder.callback shouldBe null
        completeHolder.callback shouldBe null

        val errorCreateResponse by before {
            val error = com.apollographql.apollo.api.Error(
                "mock",
                emptyList(),
                mapOf("errorType" to "FundingSourceNotSetupErrorCode")
            )
            Response.builder<CompleteFundingSourceMutation.Data>(CompleteFundingSourceMutation(completeRequest))
                .errors(listOf(error))
                .data(null)
                .build()
        }

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.FundingSourceException.CompletionFailedException> {
                client.createFundingSource(input)
            }
        }
        deferredResult.start()

        delay(100L)
        configHolder.callback shouldNotBe null
        configHolder.callback?.onResponse(configResponse)

        delay(100L)
        setupHolder.callback shouldNotBe null
        setupHolder.callback?.onResponse(setupResponse)

        delay(100L)
        completeHolder.callback shouldNotBe null
        completeHolder.callback?.onResponse(errorCreateResponse)

        deferredResult.await()

        verify(mockAppSyncClient).query(any<GetFundingSourceClientConfigurationQuery>())
        verify(mockAppSyncClient).mutate(any<SetupFundingSourceMutation>())
        verify(mockAppSyncClient).mutate(any<CompleteFundingSourceMutation>())
        verify(mockPaymentProcessorInteractions).process(any(), any(), any(), any())
    }

    @Test
    fun `createFundingSource() should throw when response has a completion data invalid error`
    () = runBlocking<Unit> {

        configHolder.callback shouldBe null
        setupHolder.callback shouldBe null
        completeHolder.callback shouldBe null

        val errorCreateResponse by before {
            val error = com.apollographql.apollo.api.Error(
                "mock",
                emptyList(),
                mapOf("errorType" to "FundingSourceCompletionDataInvalidError")
            )
            Response.builder<CompleteFundingSourceMutation.Data>(CompleteFundingSourceMutation(completeRequest))
                .errors(listOf(error))
                .data(null)
                .build()
        }

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.FundingSourceException.CompletionFailedException> {
                client.createFundingSource(input)
            }
        }
        deferredResult.start()

        delay(100L)
        configHolder.callback shouldNotBe null
        configHolder.callback?.onResponse(configResponse)

        delay(100L)
        setupHolder.callback shouldNotBe null
        setupHolder.callback?.onResponse(setupResponse)

        delay(100L)
        completeHolder.callback shouldNotBe null
        completeHolder.callback?.onResponse(errorCreateResponse)

        deferredResult.await()

        verify(mockAppSyncClient).query(any<GetFundingSourceClientConfigurationQuery>())
        verify(mockAppSyncClient).mutate(any<SetupFundingSourceMutation>())
        verify(mockAppSyncClient).mutate(any<CompleteFundingSourceMutation>())
        verify(mockPaymentProcessorInteractions).process(any(), any(), any(), any())
    }

    @Test
    fun `createFundingSource() should throw when response has a provisional funding source not found error`
    () = runBlocking<Unit> {

        configHolder.callback shouldBe null
        setupHolder.callback shouldBe null
        completeHolder.callback shouldBe null

        val errorCreateResponse by before {
            val error = com.apollographql.apollo.api.Error(
                "mock",
                emptyList(),
                mapOf("errorType" to "ProvisionalFundingSourceNotFoundError")
            )
            Response.builder<CompleteFundingSourceMutation.Data>(CompleteFundingSourceMutation(completeRequest))
                .errors(listOf(error))
                .data(null)
                .build()
        }

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.FundingSourceException.FundingSourceNotFoundException> {
                client.createFundingSource(input)
            }
        }
        deferredResult.start()

        delay(100L)
        configHolder.callback shouldNotBe null
        configHolder.callback?.onResponse(configResponse)

        delay(100L)
        setupHolder.callback shouldNotBe null
        setupHolder.callback?.onResponse(setupResponse)

        delay(100L)
        completeHolder.callback shouldNotBe null
        completeHolder.callback?.onResponse(errorCreateResponse)

        deferredResult.await()

        verify(mockAppSyncClient).query(any<GetFundingSourceClientConfigurationQuery>())
        verify(mockAppSyncClient).mutate(any<SetupFundingSourceMutation>())
        verify(mockAppSyncClient).mutate(any<CompleteFundingSourceMutation>())
        verify(mockPaymentProcessorInteractions).process(any(), any(), any(), any())
    }

    @Test
    fun `createFundingSource() should throw when response has an unacceptable funding source error`
    () = runBlocking<Unit> {

        configHolder.callback shouldBe null
        setupHolder.callback shouldBe null
        completeHolder.callback shouldBe null

        val errorCreateResponse by before {
            val error = com.apollographql.apollo.api.Error(
                "mock",
                emptyList(),
                mapOf("errorType" to "UnacceptableFundingSourceError")
            )
            Response.builder<CompleteFundingSourceMutation.Data>(CompleteFundingSourceMutation(completeRequest))
                .errors(listOf(error))
                .data(null)
                .build()
        }

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.FundingSourceException.UnacceptableFundingSourceException> {
                client.createFundingSource(input)
            }
        }
        deferredResult.start()

        delay(100L)
        configHolder.callback shouldNotBe null
        configHolder.callback?.onResponse(configResponse)

        delay(100L)
        setupHolder.callback shouldNotBe null
        setupHolder.callback?.onResponse(setupResponse)

        delay(100L)
        completeHolder.callback shouldNotBe null
        completeHolder.callback?.onResponse(errorCreateResponse)

        deferredResult.await()

        verify(mockAppSyncClient).query(any<GetFundingSourceClientConfigurationQuery>())
        verify(mockAppSyncClient).mutate(any<SetupFundingSourceMutation>())
        verify(mockAppSyncClient).mutate(any<CompleteFundingSourceMutation>())
        verify(mockPaymentProcessorInteractions).process(any(), any(), any(), any())
    }

    @Test
    fun `createFundingSource() should throw when response has an unsupported currency error`
    () = runBlocking<Unit> {

        configHolder.callback shouldBe null
        setupHolder.callback shouldBe null
        completeHolder.callback shouldBe null

        val errorCreateResponse by before {
            val error = com.apollographql.apollo.api.Error(
                "mock",
                emptyList(),
                mapOf("errorType" to "UnsupportedCurrencyError")
            )
            Response.builder<CompleteFundingSourceMutation.Data>(CompleteFundingSourceMutation(completeRequest))
                .errors(listOf(error))
                .data(null)
                .build()
        }

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.FundingSourceException.UnsupportedCurrencyException> {
                client.createFundingSource(input)
            }
        }
        deferredResult.start()

        delay(100L)
        configHolder.callback shouldNotBe null
        configHolder.callback?.onResponse(configResponse)

        delay(100L)
        setupHolder.callback shouldNotBe null
        setupHolder.callback?.onResponse(setupResponse)

        delay(100L)
        completeHolder.callback shouldNotBe null
        completeHolder.callback?.onResponse(errorCreateResponse)

        deferredResult.await()

        verify(mockAppSyncClient).query(any<GetFundingSourceClientConfigurationQuery>())
        verify(mockAppSyncClient).mutate(any<SetupFundingSourceMutation>())
        verify(mockAppSyncClient).mutate(any<CompleteFundingSourceMutation>())
        verify(mockPaymentProcessorInteractions).process(any(), any(), any(), any())
    }

    @Test
    fun `createFundingSource() should throw when response has a service error`
    () = runBlocking<Unit> {

        configHolder.callback shouldBe null
        setupHolder.callback shouldBe null
        completeHolder.callback shouldBe null

        val errorCreateResponse by before {
            val error = com.apollographql.apollo.api.Error(
                "mock",
                emptyList(),
                mapOf("errorType" to "ServiceError")
            )
            Response.builder<CompleteFundingSourceMutation.Data>(CompleteFundingSourceMutation(completeRequest))
                .errors(listOf(error))
                .data(null)
                .build()
        }

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.FundingSourceException.IdentityVerificationException> {
                client.createFundingSource(input)
            }
        }
        deferredResult.start()

        delay(100L)
        configHolder.callback shouldNotBe null
        configHolder.callback?.onResponse(configResponse)

        delay(100L)
        setupHolder.callback shouldNotBe null
        setupHolder.callback?.onResponse(setupResponse)

        delay(100L)
        completeHolder.callback shouldNotBe null
        completeHolder.callback?.onResponse(errorCreateResponse)

        deferredResult.await()

        verify(mockAppSyncClient).query(any<GetFundingSourceClientConfigurationQuery>())
        verify(mockAppSyncClient).mutate(any<SetupFundingSourceMutation>())
        verify(mockAppSyncClient).mutate(any<CompleteFundingSourceMutation>())
        verify(mockPaymentProcessorInteractions).process(any(), any(), any(), any())
    }

    @Test
    fun `createFundingSource() should throw when network fails`() = runBlocking<Unit> {

        configHolder.callback shouldBe null
        setupHolder.callback shouldBe null
        completeHolder.callback shouldBe null

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.FundingSourceException.FailedException> {
                client.createFundingSource(input)
            }
        }
        deferredResult.start()
        delay(100L)

        setupHolder.callback shouldBe null
        completeHolder.callback shouldBe null
        configHolder.callback shouldNotBe null
        configHolder.callback?.onNetworkError(ApolloNetworkException("Mock Network Exception"))

        deferredResult.await()

        verify(mockAppSyncClient).query(any<GetFundingSourceClientConfigurationQuery>())
    }

    @Test
    fun `createFundingSource() should throw when http error occurs`() = runBlocking<Unit> {

        configHolder.callback shouldBe null
        setupHolder.callback shouldBe null
        completeHolder.callback shouldBe null

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.FundingSourceException.FailedException> {
                client.createFundingSource(input)
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
        setupHolder.callback shouldBe null
        completeHolder.callback shouldBe null
        configHolder.callback shouldNotBe null
        configHolder.callback?.onHttpError(ApolloHttpException(forbidden))

        deferredResult.await()

        verify(mockAppSyncClient).query(any<GetFundingSourceClientConfigurationQuery>())
    }

    @Test
    fun `createFundingSource() should throw when unknown error occurs during getFundingSourceClientConfiguration()`() = runBlocking<Unit> {

        configHolder.callback shouldBe null
        setupHolder.callback shouldBe null
        completeHolder.callback shouldBe null

        mockAppSyncClient.stub {
            on { query(any<GetFundingSourceClientConfigurationQuery>()) } doThrow RuntimeException("Mock Runtime Exception")
        }

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.FundingSourceException.UnknownException> {
                client.createFundingSource(input)
            }
        }
        deferredResult.start()
        delay(100L)

        deferredResult.await()

        verify(mockAppSyncClient).query(any<GetFundingSourceClientConfigurationQuery>())
    }

    @Test
    fun `createFundingSource() should throw when unknown error occurs during setupFundingSource()`() = runBlocking<Unit> {

        configHolder.callback shouldBe null
        setupHolder.callback shouldBe null
        completeHolder.callback shouldBe null

        mockAppSyncClient.stub {
            on { mutate(any<SetupFundingSourceMutation>()) } doThrow RuntimeException("Mock Runtime Exception")
        }

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.FundingSourceException.UnknownException> {
                client.createFundingSource(input)
            }
        }
        deferredResult.start()

        delay(100L)
        configHolder.callback shouldNotBe null
        configHolder.callback?.onResponse(configResponse)

        delay(100L)

        deferredResult.await()

        verify(mockAppSyncClient).query(any<GetFundingSourceClientConfigurationQuery>())
        verify(mockAppSyncClient).mutate(any<SetupFundingSourceMutation>())
    }

    @Test
    fun `createFundingSource() should throw when unknown error occurs during completeFundingSource()`() = runBlocking<Unit> {

        configHolder.callback shouldBe null
        setupHolder.callback shouldBe null
        completeHolder.callback shouldBe null

        mockAppSyncClient.stub {
            on { mutate(any<CompleteFundingSourceMutation>()) } doThrow RuntimeException("Mock Runtime Exception")
        }

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.FundingSourceException.UnknownException> {
                client.createFundingSource(input)
            }
        }
        deferredResult.start()

        delay(100L)
        configHolder.callback shouldNotBe null
        configHolder.callback?.onResponse(configResponse)

        delay(100L)
        setupHolder.callback shouldNotBe null
        setupHolder.callback?.onResponse(setupResponse)

        delay(100L)

        deferredResult.await()

        verify(mockAppSyncClient).query(any<GetFundingSourceClientConfigurationQuery>())
        verify(mockAppSyncClient).mutate(any<SetupFundingSourceMutation>())
        verify(mockAppSyncClient).mutate(any<CompleteFundingSourceMutation>())
        verify(mockPaymentProcessorInteractions).process(any(), any(), any(), any())
    }

    @Test
    fun `createFundingSource() should not suppress CancellationException when thrown by getFundingSourceClientConfiguration()`
    () = runBlocking<Unit> {

        configHolder.callback shouldBe null
        setupHolder.callback shouldBe null
        completeHolder.callback shouldBe null

        mockAppSyncClient.stub {
            on { query(any<GetFundingSourceClientConfigurationQuery>()) } doThrow CancellationException("Mock Cancellation Exception")
        }

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<CancellationException> {
                client.createFundingSource(input)
            }
        }
        deferredResult.start()
        delay(100L)

        deferredResult.await()

        verify(mockAppSyncClient).query(any<GetFundingSourceClientConfigurationQuery>())
    }

    @Test
    fun `createFundingSource() should not suppress CancellationException when thrown by setupFundingSource()`() = runBlocking<Unit> {

        configHolder.callback shouldBe null
        setupHolder.callback shouldBe null
        completeHolder.callback shouldBe null

        mockAppSyncClient.stub {
            on { mutate(any<SetupFundingSourceMutation>()) } doThrow CancellationException("Mock Cancellation Exception")
        }

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<CancellationException> {
                client.createFundingSource(input)
            }
        }
        deferredResult.start()

        delay(100L)
        configHolder.callback shouldNotBe null
        configHolder.callback?.onResponse(configResponse)

        delay(100L)

        deferredResult.await()

        verify(mockAppSyncClient).query(any<GetFundingSourceClientConfigurationQuery>())
        verify(mockAppSyncClient).mutate(any<SetupFundingSourceMutation>())
    }

    @Test
    fun `createFundingSource() should not suppress CancellationException when thrown by completeFundingSource()`() = runBlocking<Unit> {

        configHolder.callback shouldBe null
        setupHolder.callback shouldBe null
        completeHolder.callback shouldBe null

        mockAppSyncClient.stub {
            on { mutate(any<CompleteFundingSourceMutation>()) } doThrow CancellationException("Mock Cancellation Exception")
        }

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<CancellationException> {
                client.createFundingSource(input)
            }
        }
        deferredResult.start()

        delay(100L)
        configHolder.callback shouldNotBe null
        configHolder.callback?.onResponse(configResponse)

        delay(100L)
        setupHolder.callback shouldNotBe null
        setupHolder.callback?.onResponse(setupResponse)

        delay(100L)

        deferredResult.await()

        verify(mockAppSyncClient).query(any<GetFundingSourceClientConfigurationQuery>())
        verify(mockAppSyncClient).mutate(any<SetupFundingSourceMutation>())
        verify(mockAppSyncClient).mutate(any<CompleteFundingSourceMutation>())
        verify(mockPaymentProcessorInteractions).process(any(), any(), any(), any())
    }
}
