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
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.stub
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoMoreInteractions
import com.sudoplatform.sudokeymanager.KeyManagerInterface
import com.sudoplatform.sudologging.Logger
import com.sudoplatform.sudouser.SudoUserClient
import com.sudoplatform.sudovirtualcards.graphql.CallbackHolder
import com.sudoplatform.sudovirtualcards.graphql.CancelFundingSourceMutation
import com.sudoplatform.sudovirtualcards.graphql.type.CardType
import com.sudoplatform.sudovirtualcards.graphql.fragment.FundingSource as FundingSourceFragment
import com.sudoplatform.sudovirtualcards.graphql.type.CreditCardNetwork
import com.sudoplatform.sudovirtualcards.graphql.type.FundingSourceState
import com.sudoplatform.sudovirtualcards.graphql.type.IdInput
import com.sudoplatform.sudovirtualcards.types.FundingSource
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
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.net.HttpURLConnection

/**
 * Test the correct operation of [SudoVirtualCardsClient.cancelFundingSource]
 * using mocks and spies.
 */
class SudoVirtualCardsCancelFundingSourceTest : BaseTests() {

    private val idInput = IdInput.builder()
        .id("id")
        .build()

    private val mutationResult by before {
        CancelFundingSourceMutation.CancelFundingSource(
            "CancelFundingSource",
            CancelFundingSourceMutation.CancelFundingSource.Fragments(
                FundingSourceFragment(
                    "FundingSource",
                    "id",
                    "owner",
                    1,
                    1.0,
                    10.0,
                    FundingSourceState.INACTIVE,
                    "USD",
                    "last4",
                    CreditCardNetwork.VISA,
                    CardType.CREDIT,
                )
            )
        )
    }

    private val response by before {
        Response.builder<CancelFundingSourceMutation.Data>(CancelFundingSourceMutation(idInput))
            .data(CancelFundingSourceMutation.Data(mutationResult))
            .build()
    }

    private val holder = CallbackHolder<CancelFundingSourceMutation.Data>()

    private val mockContext by before {
        mock<Context>()
    }

    private val mockUserClient by before {
        mock<SudoUserClient>()
    }

    private val mockAppSyncClient by before {
        mock<AWSAppSyncClient>().stub {
            on { mutate(any<CancelFundingSourceMutation>()) } doReturn holder.mutationOperation
        }
    }

    private val mockKeyManager by before {
        mock<KeyManagerInterface>()
    }

    private val client by before {
        SudoVirtualCardsClient.builder()
            .setContext(mockContext)
            .setSudoUserClient(mockUserClient)
            .setAppSyncClient(mockAppSyncClient)
            .setKeyManager(mockKeyManager)
            .setLogger(mock<Logger>())
            .build()
    }

    @Before
    fun init() {
        holder.callback = null
    }

    @After
    fun fini() {
        verifyNoMoreInteractions(mockContext, mockUserClient, mockKeyManager, mockAppSyncClient)
    }

    @Test
    fun `cancelFundingSource() should return results when no error present`() = runBlocking<Unit> {

        holder.callback shouldBe null

        val deferredResult = async(Dispatchers.IO) {
            client.cancelFundingSource("id")
        }
        deferredResult.start()

        delay(100L)
        holder.callback shouldNotBe null
        holder.callback?.onResponse(response)

        val result = deferredResult.await()
        result shouldNotBe null

        with(result) {
            id shouldBe "id"
            owner shouldBe "owner"
            version shouldBe 1
            state shouldBe FundingSource.State.INACTIVE
            currency shouldBe "USD"
            last4 shouldBe "last4"
            network shouldBe FundingSource.CreditCardNetwork.VISA
        }

        verify(mockAppSyncClient).mutate(any<CancelFundingSourceMutation>())
    }

    @Test
    fun `cancelFundingSource() should throw when mutation response is null`() = runBlocking<Unit> {

        holder.callback shouldBe null

        val nullResponse by before {
            Response.builder<CancelFundingSourceMutation.Data>(CancelFundingSourceMutation(idInput))
                .data(null)
                .build()
        }

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.FundingSourceException.CancelFailedException> {
                client.cancelFundingSource("id")
            }
        }
        deferredResult.start()
        delay(100L)

        holder.callback shouldNotBe null
        holder.callback?.onResponse(nullResponse)

        deferredResult.await()

        verify(mockAppSyncClient).mutate(any<CancelFundingSourceMutation>())
    }

    @Test
    fun `cancelFundingSource() should throw when response has a funding source not found error`() = runBlocking<Unit> {

        holder.callback shouldBe null

        val errorCancelResponse by before {
            val error = com.apollographql.apollo.api.Error(
                "mock",
                emptyList(),
                mapOf("errorType" to "FundingSourceNotFoundError")
            )
            Response.builder<CancelFundingSourceMutation.Data>(CancelFundingSourceMutation(idInput))
                .errors(listOf(error))
                .data(null)
                .build()
        }

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.FundingSourceException.FundingSourceNotFoundException> {
                client.cancelFundingSource("id")
            }
        }
        deferredResult.start()
        delay(100L)

        holder.callback shouldNotBe null
        holder.callback?.onResponse(errorCancelResponse)

        deferredResult.await()

        verify(mockAppSyncClient).mutate(any<CancelFundingSourceMutation>())
    }

    @Test
    fun `cancelFundingSource() should throw when response has an account locked error`() = runBlocking<Unit> {

        holder.callback shouldBe null

        val errorCancelResponse by before {
            val error = com.apollographql.apollo.api.Error(
                "mock",
                emptyList(),
                mapOf("errorType" to "AccountLockedError")
            )
            Response.builder<CancelFundingSourceMutation.Data>(CancelFundingSourceMutation(idInput))
                .errors(listOf(error))
                .data(null)
                .build()
        }

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.FundingSourceException.AccountLockedException> {
                client.cancelFundingSource("id")
            }
        }
        deferredResult.start()
        delay(100L)

        holder.callback shouldNotBe null
        holder.callback?.onResponse(errorCancelResponse)

        deferredResult.await()

        verify(mockAppSyncClient).mutate(any<CancelFundingSourceMutation>())
    }

    @Test
    fun `cancelFundingSource() should throw when http error occurs`() = runBlocking<Unit> {

        holder.callback shouldBe null

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.FundingSourceException.CancelFailedException> {
                client.cancelFundingSource("id")
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

        holder.callback shouldNotBe null
        holder.callback?.onHttpError(ApolloHttpException(forbidden))

        deferredResult.await()

        verify(mockAppSyncClient).mutate(any<CancelFundingSourceMutation>())
    }

    @Test
    fun `cancelFundingSource() should throw when unknown error occurs`() = runBlocking<Unit> {

        holder.callback shouldBe null

        mockAppSyncClient.stub {
            on { mutate(any<CancelFundingSourceMutation>()) } doThrow RuntimeException("Mock Runtime Exception")
        }

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.FundingSourceException.UnknownException> {
                client.cancelFundingSource("id")
            }
        }
        deferredResult.start()
        delay(100L)

        deferredResult.await()

        verify(mockAppSyncClient).mutate(any<CancelFundingSourceMutation>())
    }

    @Test
    fun `cancelFundingSource() should not suppress CancellationException`() = runBlocking<Unit> {

        holder.callback shouldBe null

        mockAppSyncClient.stub {
            on { mutate(any<CancelFundingSourceMutation>()) } doThrow CancellationException("Mock Cancellation Exception")
        }

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<CancellationException> {
                client.cancelFundingSource("id")
            }
        }
        deferredResult.start()
        delay(100L)

        deferredResult.await()

        verify(mockAppSyncClient).mutate(any<CancelFundingSourceMutation>())
    }
}
