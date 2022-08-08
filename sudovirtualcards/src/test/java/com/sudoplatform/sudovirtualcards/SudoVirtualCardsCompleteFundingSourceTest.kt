/*
 * Copyright © 2022 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards

import android.content.Context
import com.amazonaws.mobileconnectors.appsync.AWSAppSyncClient
import com.amazonaws.util.Base64
import com.apollographql.apollo.api.Response
import com.apollographql.apollo.exception.ApolloHttpException
import com.google.gson.Gson
import com.sudoplatform.sudokeymanager.KeyManagerInterface
import com.sudoplatform.sudoprofiles.SudoProfilesClient
import com.sudoplatform.sudouser.SudoUserClient
import com.sudoplatform.sudovirtualcards.graphql.CallbackHolder
import com.sudoplatform.sudovirtualcards.graphql.CompleteFundingSourceMutation
import com.sudoplatform.sudovirtualcards.graphql.type.CompleteFundingSourceRequest
import com.sudoplatform.sudovirtualcards.graphql.type.CreditCardNetwork
import com.sudoplatform.sudovirtualcards.graphql.type.FundingSourceState
import com.sudoplatform.sudovirtualcards.types.ProviderCompletionData
import com.sudoplatform.sudovirtualcards.types.inputs.CompleteFundingSourceInput
import com.sudoplatform.sudovirtualcards.graphql.fragment.FundingSource as FundingSourceFragment
import com.sudoplatform.sudologging.Logger
import com.sudoplatform.sudovirtualcards.types.FundingSource
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
 * Test the correct operation of [SudoVirtualCardsClient.completeFundingSource]
 * using mocks and spies.
 */
class SudoVirtualCardsCompleteFundingSourceTest : BaseTests() {

    private val providerCompletionData = ProviderCompletionData("stripe", 1, "paymentMethod")
    private val input by before {
        CompleteFundingSourceInput(
            "id",
            providerCompletionData,
            null
        )
    }
    private val encodedCompletionData by before {
        val encodedCompletionDataString = Gson().toJson(input.completionData)
        Base64.encode(encodedCompletionDataString.toByteArray()).toString(Charsets.UTF_8)
    }

    private val mutationRequest = CompleteFundingSourceRequest.builder()
        .id("id")
        .completionData("completionData")
        .build()

    private val mutationResult by before {
        CompleteFundingSourceMutation.CompleteFundingSource(
            "CompleteFundingSource",
            CompleteFundingSourceMutation.CompleteFundingSource.Fragments(
                FundingSourceFragment(
                    "FundingSource",
                    "id",
                    "owner",
                    1,
                    1.0,
                    10.0,
                    FundingSourceState.ACTIVE,
                    "USD",
                    "last4",
                    CreditCardNetwork.VISA
                )
            )
        )
    }

    private val mutationResponse by before {
        Response.builder<CompleteFundingSourceMutation.Data>(CompleteFundingSourceMutation(mutationRequest))
            .data(CompleteFundingSourceMutation.Data(mutationResult))
            .build()
    }

    private val mutationHolder = CallbackHolder<CompleteFundingSourceMutation.Data>()

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
            on { mutate(any<CompleteFundingSourceMutation>()) } doReturn mutationHolder.mutationOperation
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
    fun `completeFundingSource() should return results when no error present`() = runBlocking<Unit> {

        mutationHolder.callback shouldBe null

        val deferredResult = async(Dispatchers.IO) {
            client.completeFundingSource(input)
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
            state shouldBe FundingSource.State.ACTIVE
            currency shouldBe "USD"
            last4 shouldBe "last4"
            network shouldBe FundingSource.CreditCardNetwork.VISA
        }

        verify(mockAppSyncClient).mutate<
            CompleteFundingSourceMutation.Data,
            CompleteFundingSourceMutation,
            CompleteFundingSourceMutation.Variables>(
            check {
                it.variables().input().id() shouldBe "id"
                it.variables().input().completionData() shouldBe encodedCompletionData
                it.variables().input().updateCardFundingSource() shouldBe null
            }
        )
    }

    @Test
    fun `completeFundingSource() should throw when response is null`() = runBlocking<Unit> {

        mutationHolder.callback shouldBe null

        val nullMutationResponse by before {
            Response.builder<CompleteFundingSourceMutation.Data>(CompleteFundingSourceMutation(mutationRequest))
                .data(null)
                .build()
        }

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.FundingSourceException.CompletionFailedException> {
                client.completeFundingSource(input)
            }
        }
        deferredResult.start()

        delay(100L)
        mutationHolder.callback shouldNotBe null
        mutationHolder.callback?.onResponse(nullMutationResponse)

        verify(mockAppSyncClient).mutate<
            CompleteFundingSourceMutation.Data,
            CompleteFundingSourceMutation,
            CompleteFundingSourceMutation.Variables>(
            check {
                it.variables().input().id() shouldBe "id"
                it.variables().input().completionData() shouldBe encodedCompletionData
                it.variables().input().updateCardFundingSource() shouldBe null
            }
        )
    }

    @Test
    fun `completeFundingSource() should throw when a provisional funding source not found error occurs`() = runBlocking<Unit> {

        mutationHolder.callback shouldBe null

        val errorResponse by before {
            val error = com.apollographql.apollo.api.Error(
                "mock",
                emptyList(),
                mapOf("errorType" to "ProvisionalFundingSourceNotFoundError")
            )
            Response.builder<CompleteFundingSourceMutation.Data>(CompleteFundingSourceMutation(mutationRequest))
                .errors(listOf(error))
                .data(null)
                .build()
        }

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.FundingSourceException.ProvisionalFundingSourceNotFoundException> {
                client.completeFundingSource(input)
            }
        }
        deferredResult.start()

        delay(100L)
        mutationHolder.callback shouldNotBe null
        mutationHolder.callback?.onResponse(errorResponse)

        verify(mockAppSyncClient).mutate<
            CompleteFundingSourceMutation.Data,
            CompleteFundingSourceMutation,
            CompleteFundingSourceMutation.Variables>(
            check {
                it.variables().input().id() shouldBe "id"
                it.variables().input().completionData() shouldBe encodedCompletionData
                it.variables().input().updateCardFundingSource() shouldBe null
            }
        )
    }

    @Test
    fun `completeFundingSource() should throw when a funding source state error occurs`() = runBlocking<Unit> {

        mutationHolder.callback shouldBe null

        val errorResponse by before {
            val error = com.apollographql.apollo.api.Error(
                "mock",
                emptyList(),
                mapOf("errorType" to "FundingSourceStateError")
            )
            Response.builder<CompleteFundingSourceMutation.Data>(CompleteFundingSourceMutation(mutationRequest))
                .errors(listOf(error))
                .data(null)
                .build()
        }

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.FundingSourceException.FundingSourceStateException> {
                client.completeFundingSource(input)
            }
        }
        deferredResult.start()

        delay(100L)
        mutationHolder.callback shouldNotBe null
        mutationHolder.callback?.onResponse(errorResponse)

        verify(mockAppSyncClient).mutate<
            CompleteFundingSourceMutation.Data,
            CompleteFundingSourceMutation,
            CompleteFundingSourceMutation.Variables>(
            check {
                it.variables().input().id() shouldBe "id"
                it.variables().input().completionData() shouldBe encodedCompletionData
                it.variables().input().updateCardFundingSource() shouldBe null
            }
        )
    }

    @Test
    fun `completeFundingSource() should throw when a funding source not setup error occurs`() = runBlocking<Unit> {

        mutationHolder.callback shouldBe null

        val errorResponse by before {
            val error = com.apollographql.apollo.api.Error(
                "mock",
                emptyList(),
                mapOf("errorType" to "FundingSourceNotSetupErrorCode")
            )
            Response.builder<CompleteFundingSourceMutation.Data>(CompleteFundingSourceMutation(mutationRequest))
                .errors(listOf(error))
                .data(null)
                .build()
        }

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.FundingSourceException.SetupFailedException> {
                client.completeFundingSource(input)
            }
        }
        deferredResult.start()

        delay(100L)
        mutationHolder.callback shouldNotBe null
        mutationHolder.callback?.onResponse(errorResponse)

        verify(mockAppSyncClient).mutate<
            CompleteFundingSourceMutation.Data,
            CompleteFundingSourceMutation,
            CompleteFundingSourceMutation.Variables>(
            check {
                it.variables().input().id() shouldBe "id"
                it.variables().input().completionData() shouldBe encodedCompletionData
                it.variables().input().updateCardFundingSource() shouldBe null
            }
        )
    }

    @Test
    fun `completeFundingSource() should throw when a completion data invalid error occurs`() = runBlocking<Unit> {

        mutationHolder.callback shouldBe null

        val errorResponse by before {
            val error = com.apollographql.apollo.api.Error(
                "mock",
                emptyList(),
                mapOf("errorType" to "FundingSourceCompletionDataInvalidError")
            )
            Response.builder<CompleteFundingSourceMutation.Data>(CompleteFundingSourceMutation(mutationRequest))
                .errors(listOf(error))
                .data(null)
                .build()
        }

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.FundingSourceException.CompletionDataInvalidException> {
                client.completeFundingSource(input)
            }
        }
        deferredResult.start()

        delay(100L)
        mutationHolder.callback shouldNotBe null
        mutationHolder.callback?.onResponse(errorResponse)

        verify(mockAppSyncClient).mutate<
            CompleteFundingSourceMutation.Data,
            CompleteFundingSourceMutation,
            CompleteFundingSourceMutation.Variables>(
            check {
                it.variables().input().id() shouldBe "id"
                it.variables().input().completionData() shouldBe encodedCompletionData
                it.variables().input().updateCardFundingSource() shouldBe null
            }
        )
    }

    @Test
    fun `completeFundingSource() should throw when an unacceptable funding source error occurs`() = runBlocking<Unit> {

        mutationHolder.callback shouldBe null

        val errorResponse by before {
            val error = com.apollographql.apollo.api.Error(
                "mock",
                emptyList(),
                mapOf("errorType" to "UnacceptableFundingSourceError")
            )
            Response.builder<CompleteFundingSourceMutation.Data>(CompleteFundingSourceMutation(mutationRequest))
                .errors(listOf(error))
                .data(null)
                .build()
        }

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.FundingSourceException.UnacceptableFundingSourceException> {
                client.completeFundingSource(input)
            }
        }
        deferredResult.start()

        delay(100L)
        mutationHolder.callback shouldNotBe null
        mutationHolder.callback?.onResponse(errorResponse)

        verify(mockAppSyncClient).mutate<
            CompleteFundingSourceMutation.Data,
            CompleteFundingSourceMutation,
            CompleteFundingSourceMutation.Variables>(
            check {
                it.variables().input().id() shouldBe "id"
                it.variables().input().completionData() shouldBe encodedCompletionData
                it.variables().input().updateCardFundingSource() shouldBe null
            }
        )
    }

    @Test
    fun `completeFundingSource() should throw when http error occurs`() = runBlocking<Unit> {

        mutationHolder.callback shouldBe null

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.FundingSourceException.CompletionFailedException> {
                client.completeFundingSource(input)
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
            CompleteFundingSourceMutation.Data,
            CompleteFundingSourceMutation,
            CompleteFundingSourceMutation.Variables>(
            check {
                it.variables().input().id() shouldBe "id"
                it.variables().input().completionData() shouldBe encodedCompletionData
                it.variables().input().updateCardFundingSource() shouldBe null
            }
        )
    }

    @Test
    fun `completeFundingSource() should throw when unknown error occurs`() = runBlocking<Unit> {

        mutationHolder.callback shouldBe null

        mockAppSyncClient.stub {
            on { mutate(any<CompleteFundingSourceMutation>()) } doThrow RuntimeException("Mock Runtime Exception")
        }

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.FundingSourceException.UnknownException> {
                client.completeFundingSource(input)
            }
        }
        deferredResult.start()
        delay(100L)

        deferredResult.await()

        verify(mockAppSyncClient).mutate<
            CompleteFundingSourceMutation.Data,
            CompleteFundingSourceMutation,
            CompleteFundingSourceMutation.Variables>(
            check {
                it.variables().input().id() shouldBe "id"
                it.variables().input().completionData() shouldBe encodedCompletionData
                it.variables().input().updateCardFundingSource() shouldBe null
            }
        )
    }

    @Test
    fun `completeFundingSource() should not block coroutine cancellation exception`() = runBlocking<Unit> {

        mutationHolder.callback shouldBe null

        mockAppSyncClient.stub {
            on { mutate(any<CompleteFundingSourceMutation>()) } doThrow CancellationException("Mock Cancellation Exception")
        }

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<CancellationException> {
                client.completeFundingSource(input)
            }
        }
        deferredResult.start()
        delay(100L)

        deferredResult.await()

        verify(mockAppSyncClient).mutate<
            CompleteFundingSourceMutation.Data,
            CompleteFundingSourceMutation,
            CompleteFundingSourceMutation.Variables>(
            check {
                it.variables().input().id() shouldBe "id"
                it.variables().input().completionData() shouldBe encodedCompletionData
                it.variables().input().updateCardFundingSource() shouldBe null
            }
        )
    }
}
