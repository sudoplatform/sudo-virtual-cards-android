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
import com.google.gson.Gson
import com.sudoplatform.sudokeymanager.KeyManagerInterface
import com.sudoplatform.sudologging.Logger
import com.sudoplatform.sudoprofiles.SudoProfilesClient
import com.sudoplatform.sudouser.SudoUserClient
import com.sudoplatform.sudovirtualcards.graphql.CallbackHolder
import com.sudoplatform.sudovirtualcards.graphql.SetupFundingSourceMutation
import com.sudoplatform.sudovirtualcards.graphql.type.FundingSourceType
import com.sudoplatform.sudovirtualcards.graphql.type.ProvisionalFundingSourceState
import com.sudoplatform.sudovirtualcards.graphql.type.SetupFundingSourceRequest
import com.sudoplatform.sudovirtualcards.graphql.type.StateReason
import com.sudoplatform.sudovirtualcards.types.ProvisionalFundingSource
import com.sudoplatform.sudovirtualcards.types.ProvisioningData
import com.sudoplatform.sudovirtualcards.types.inputs.FundingSourceType as FundingSourceTypeEntity
import com.sudoplatform.sudovirtualcards.types.inputs.SetupFundingSourceInput
import io.kotlintest.shouldBe
import io.kotlintest.shouldNotBe
import io.kotlintest.shouldThrow
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
import org.mockito.kotlin.any
import org.mockito.kotlin.check
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.stub
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoMoreInteractions
import java.net.HttpURLConnection
import java.util.concurrent.CancellationException

/**
 * Test the correct operation of [SudoVirtualCardsClient.setupFundingSource]
 * using mocks and spies.
 */
class SudoVirtualCardsSetupFundingSourceTest : BaseTests() {

    private val input by before {
        SetupFundingSourceInput(
            "USD",
            FundingSourceTypeEntity.CREDIT_CARD,
        )
    }

    private val mutationRequest = SetupFundingSourceRequest.builder()
        .type(FundingSourceType.CREDIT_CARD)
        .currency("USD")
        .build()

    private val mutationResult by before {
        val setupData = ProvisioningData("provider", 1, "intent", "clientSecret")
        val setupDataStr = Gson().toJson(setupData)
        val encodedSetupData = Base64.encodeBase64String(setupDataStr.toByteArray())
        SetupFundingSourceMutation.SetupFundingSource(
            "typename",
            "id",
            "owner",
            1,
            1.0,
            10.0,
            ProvisionalFundingSourceState.PROVISIONING,
            StateReason.PROCESSING,
            encodedSetupData
        )
    }

    private val mutationResponse by before {
        Response.builder<SetupFundingSourceMutation.Data>(SetupFundingSourceMutation(mutationRequest))
            .data(SetupFundingSourceMutation.Data(mutationResult))
            .build()
    }

    private val mutationHolder = CallbackHolder<SetupFundingSourceMutation.Data>()

    private val mockContext by before {
        mock<Context>()
    }

    private val mockUserClient by before {
        mock<SudoUserClient>()
    }

    private val mockSudoClient by before {
        mock<SudoProfilesClient>()
    }

    private val mockAppSyncClient by before {
        mock<AWSAppSyncClient>().stub {
            on { mutate(any<SetupFundingSourceMutation>()) } doReturn mutationHolder.mutationOperation
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
        verifyNoMoreInteractions(
            mockContext,
            mockUserClient,
            mockSudoClient,
            mockKeyManager,
            mockAppSyncClient,
        )
    }

    @Test
    fun `setupFundingSource() should return results when no error present`() = runBlocking<Unit> {

        mutationHolder.callback shouldBe null

        val deferredResult = async(Dispatchers.IO) {
            client.setupFundingSource(input)
        }
        deferredResult.start()

        delay(100L)
        mutationHolder.callback shouldNotBe null
        mutationHolder.callback?.onResponse(mutationResponse)

        val result = deferredResult.await()
        result shouldNotBe null

        with(result) {
            id shouldBe "id"
            owner shouldBe "owner"
            version shouldBe 1
            createdAt shouldNotBe null
            updatedAt shouldNotBe null
            state shouldBe ProvisionalFundingSource.ProvisioningState.PROVISIONING
            stateReason shouldBe ProvisionalFundingSource.StateReason.PROCESSING
            provisioningData shouldBe ProvisioningData("provider", 1, "intent", "clientSecret")
        }

        verify(mockAppSyncClient).mutate<
            SetupFundingSourceMutation.Data,
            SetupFundingSourceMutation,
            SetupFundingSourceMutation.Variables>(
            check {
                it.variables().input().currency() shouldBe "USD"
                it.variables().input().type() shouldBe FundingSourceType.CREDIT_CARD
            }
        )
    }

    @Test
    fun `setupFundingSource() should throw when response is null`() = runBlocking<Unit> {

        mutationHolder.callback shouldBe null

        val nullMutationResponse by before {
            Response.builder<SetupFundingSourceMutation.Data>(SetupFundingSourceMutation(mutationRequest))
                .data(null)
                .build()
        }

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.FundingSourceException.SetupFailedException> {
                client.setupFundingSource(input)
            }
        }
        deferredResult.start()

        delay(100L)
        mutationHolder.callback shouldNotBe null
        mutationHolder.callback?.onResponse(nullMutationResponse)

        verify(mockAppSyncClient).mutate<
            SetupFundingSourceMutation.Data,
            SetupFundingSourceMutation,
            SetupFundingSourceMutation.Variables>(
            check {
                it.variables().input().currency() shouldBe "USD"
                it.variables().input().type() shouldBe FundingSourceType.CREDIT_CARD
            }
        )
    }

    @Test
    fun `setupFundingSource() should throw when an unsupported currency error occurs`() = runBlocking<Unit> {

        mutationHolder.callback shouldBe null

        val input by before {
            SetupFundingSourceInput(
                "AUD",
                FundingSourceTypeEntity.CREDIT_CARD,
            )
        }

        val errorResponse by before {
            val error = com.apollographql.apollo.api.Error(
                "mock",
                emptyList(),
                mapOf("errorType" to "UnsupportedCurrencyError")
            )
            Response.builder<SetupFundingSourceMutation.Data>(SetupFundingSourceMutation(mutationRequest))
                .errors(listOf(error))
                .data(null)
                .build()
        }

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.FundingSourceException.UnsupportedCurrencyException> {
                client.setupFundingSource(input)
            }
        }
        deferredResult.start()

        delay(100L)
        mutationHolder.callback shouldNotBe null
        mutationHolder.callback?.onResponse(errorResponse)

        verify(mockAppSyncClient).mutate<
            SetupFundingSourceMutation.Data,
            SetupFundingSourceMutation,
            SetupFundingSourceMutation.Variables>(
            check {
                it.variables().input().currency() shouldBe "AUD"
                it.variables().input().type() shouldBe FundingSourceType.CREDIT_CARD
            }
        )
    }

    @Test
    fun `setupFundingSource() should throw when an account locked error occurs`() = runBlocking<Unit> {

        mutationHolder.callback shouldBe null

        val errorResponse by before {
            val error = com.apollographql.apollo.api.Error(
                "mock",
                emptyList(),
                mapOf("errorType" to "AccountLockedError")
            )
            Response.builder<SetupFundingSourceMutation.Data>(SetupFundingSourceMutation(mutationRequest))
                .errors(listOf(error))
                .data(null)
                .build()
        }

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.FundingSourceException.AccountLockedException> {
                client.setupFundingSource(input)
            }
        }
        deferredResult.start()

        delay(100L)
        mutationHolder.callback shouldNotBe null
        mutationHolder.callback?.onResponse(errorResponse)

        verify(mockAppSyncClient).mutate<
            SetupFundingSourceMutation.Data,
            SetupFundingSourceMutation,
            SetupFundingSourceMutation.Variables>(
            check {
                it.variables().input().currency() shouldBe "USD"
                it.variables().input().type() shouldBe FundingSourceType.CREDIT_CARD
            }
        )
    }

    @Test
    fun `setupFundingSource() should throw when an entitlements exceeded error occurs`() = runBlocking<Unit> {

        mutationHolder.callback shouldBe null

        val errorResponse by before {
            val error = com.apollographql.apollo.api.Error(
                "mock",
                emptyList(),
                mapOf("errorType" to "EntitlementExceededError")
            )
            Response.builder<SetupFundingSourceMutation.Data>(SetupFundingSourceMutation(mutationRequest))
                .errors(listOf(error))
                .data(null)
                .build()
        }

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.FundingSourceException.EntitlementExceededException> {
                client.setupFundingSource(input)
            }
        }
        deferredResult.start()

        delay(100L)
        mutationHolder.callback shouldNotBe null
        mutationHolder.callback?.onResponse(errorResponse)

        verify(mockAppSyncClient).mutate<
            SetupFundingSourceMutation.Data,
            SetupFundingSourceMutation,
            SetupFundingSourceMutation.Variables>(
            check {
                it.variables().input().currency() shouldBe "USD"
                it.variables().input().type() shouldBe FundingSourceType.CREDIT_CARD
            }
        )
    }

    @Test
    fun `setupFundingSource() should throw when a velocity exceeded error occurs`() = runBlocking<Unit> {

        mutationHolder.callback shouldBe null

        val errorResponse by before {
            val error = com.apollographql.apollo.api.Error(
                "mock",
                emptyList(),
                mapOf("errorType" to "VelocityExceededError")
            )
            Response.builder<SetupFundingSourceMutation.Data>(SetupFundingSourceMutation(mutationRequest))
                .errors(listOf(error))
                .data(null)
                .build()
        }

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.FundingSourceException.VelocityExceededException> {
                client.setupFundingSource(input)
            }
        }
        deferredResult.start()

        delay(100L)
        mutationHolder.callback shouldNotBe null
        mutationHolder.callback?.onResponse(errorResponse)

        verify(mockAppSyncClient).mutate<
            SetupFundingSourceMutation.Data,
            SetupFundingSourceMutation,
            SetupFundingSourceMutation.Variables>(
            check {
                it.variables().input().currency() shouldBe "USD"
                it.variables().input().type() shouldBe FundingSourceType.CREDIT_CARD
            }
        )
    }

    @Test
    fun `setupFundingSource() should throw when http error occurs`() = runBlocking<Unit> {

        mutationHolder.callback shouldBe null

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.FundingSourceException.SetupFailedException> {
                client.setupFundingSource(input)
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

        verify(mockAppSyncClient).mutate<
            SetupFundingSourceMutation.Data,
            SetupFundingSourceMutation,
            SetupFundingSourceMutation.Variables>(
            check {
                it.variables().input().currency() shouldBe "USD"
                it.variables().input().type() shouldBe FundingSourceType.CREDIT_CARD
            }
        )
    }

    @Test
    fun `setupFundingSource() should throw when unknown error occurs`() = runBlocking<Unit> {

        mutationHolder.callback shouldBe null

        mockAppSyncClient.stub {
            on { mutate(any<SetupFundingSourceMutation>()) } doThrow RuntimeException("Mock Runtime Exception")
        }

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.FundingSourceException.UnknownException> {
                client.setupFundingSource(input)
            }
        }
        deferredResult.start()
        delay(100L)

        deferredResult.await()

        verify(mockAppSyncClient).mutate<
            SetupFundingSourceMutation.Data,
            SetupFundingSourceMutation,
            SetupFundingSourceMutation.Variables>(
            check {
                it.variables().input().currency() shouldBe "USD"
                it.variables().input().type() shouldBe FundingSourceType.CREDIT_CARD
            }
        )
    }

    @Test
    fun `setupFundingSource() should not block coroutine cancellation exception`() = runBlocking<Unit> {

        mutationHolder.callback shouldBe null

        mockAppSyncClient.stub {
            on { mutate(any<SetupFundingSourceMutation>()) } doThrow CancellationException("Mock Cancellation Exception")
        }

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<CancellationException> {
                client.setupFundingSource(input)
            }
        }
        deferredResult.start()
        delay(100L)

        deferredResult.await()

        verify(mockAppSyncClient).mutate<
            SetupFundingSourceMutation.Data,
            SetupFundingSourceMutation,
            SetupFundingSourceMutation.Variables>(
            check {
                it.variables().input().currency() shouldBe "USD"
                it.variables().input().type() shouldBe FundingSourceType.CREDIT_CARD
            }
        )
    }
}
