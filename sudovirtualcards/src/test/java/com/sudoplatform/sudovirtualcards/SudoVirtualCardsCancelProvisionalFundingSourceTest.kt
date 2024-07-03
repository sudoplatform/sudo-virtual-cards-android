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
import com.google.gson.Gson
import com.sudoplatform.sudokeymanager.KeyManagerInterface
import com.sudoplatform.sudologging.Logger
import com.sudoplatform.sudouser.SudoUserClient
import com.sudoplatform.sudovirtualcards.graphql.CallbackHolder
import com.sudoplatform.sudovirtualcards.graphql.CancelProvisionalFundingSourceMutation
import com.sudoplatform.sudovirtualcards.graphql.fragment.ProvisionalFundingSource
import com.sudoplatform.sudovirtualcards.graphql.type.FundingSourceType
import com.sudoplatform.sudovirtualcards.graphql.type.IdInput
import com.sudoplatform.sudovirtualcards.graphql.type.ProvisionalFundingSourceState
import com.sudoplatform.sudovirtualcards.types.StripeCardProvisioningData
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
import org.mockito.ArgumentMatchers.anyString
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.stub
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoMoreInteractions
import java.net.HttpURLConnection

/**
 * Test the correct operation of [SudoVirtualCardsClient.cancelProvisionalFundingSource]
 * using mocks and spies.
 */
class SudoVirtualCardsCancelProvisionalFundingSourceTest() : BaseTests() {

    private val idInput = IdInput.builder()
        .id("id")
        .build()

    private val provisionalResult by before {
        val stripeSetupData =
            StripeCardProvisioningData(
                "stripe",
                1,
                "intent",
                "clientSecret",
                com.sudoplatform.sudovirtualcards.types.FundingSourceType.CREDIT_CARD,
            )
        CancelProvisionalFundingSourceMutation.Data(
            CancelProvisionalFundingSourceMutation.CancelProvisionalFundingSource(
                "typename",
                CancelProvisionalFundingSourceMutation.CancelProvisionalFundingSource.Fragments(
                    ProvisionalFundingSource(
                        "ProvisionalFundingSource",
                        "id",
                        "owner",
                        1,
                        1.0,
                        10.0,
                        FundingSourceType.CREDIT_CARD,
                        Base64.encodeBase64String(Gson().toJson(stripeSetupData).toByteArray()),
                        ProvisionalFundingSourceState.PROVISIONING,
                        "1234",
                    ),
                ),
            ),
        )
    }

    private val provisionalResponse by before {
        Response.builder<CancelProvisionalFundingSourceMutation.Data>(CancelProvisionalFundingSourceMutation(idInput))
            .data(provisionalResult)
            .build()
    }

    private val holder = CallbackHolder<CancelProvisionalFundingSourceMutation.Data>()

    private val mockContext by before {
        mock<Context>()
    }

    private val mockUserClient by before {
        mock<SudoUserClient>()
    }

    private val mockAppSyncClient by before {
        mock<AWSAppSyncClient>().stub {
            on { mutate(any<CancelProvisionalFundingSourceMutation>()) } doReturn holder.mutationOperation
        }
    }

    private val mockKeyManager by before {
        mock<KeyManagerInterface>().stub {
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
    fun `cancelProvisionalFundingSource() should return results when no error present`() = runBlocking<Unit> {
        holder.callback shouldBe null

        val deferredResult = async(Dispatchers.IO) {
            client.cancelProvisionalFundingSource("id")
        }
        deferredResult.start()

        delay(100L)
        holder.callback shouldNotBe null
        holder.callback?.onResponse(provisionalResponse)

        val result = deferredResult.await()
        result shouldNotBe null

        with(result) {
            id shouldBe "id"
            owner shouldBe "owner"
            version shouldBe 1
            createdAt shouldNotBe null
            updatedAt shouldNotBe null
            type shouldBe com.sudoplatform.sudovirtualcards.types.FundingSourceType.CREDIT_CARD
            state shouldBe com.sudoplatform.sudovirtualcards.types.ProvisionalFundingSource.ProvisioningState.PROVISIONING
            last4 shouldBe "1234"
        }

        verify(mockAppSyncClient).mutate(any<CancelProvisionalFundingSourceMutation>())
    }

    @Test
    fun `cancelProvisionalFundingSource() should throw when mutation response is null`() = runBlocking<Unit> {
        holder.callback shouldBe null

        val nullResponse by before {
            Response.builder<CancelProvisionalFundingSourceMutation.Data>(CancelProvisionalFundingSourceMutation(idInput))
                .data(null)
                .build()
        }

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.FundingSourceException.CancelFailedException> {
                client.cancelProvisionalFundingSource("id")
            }
        }
        deferredResult.start()
        delay(100L)

        holder.callback shouldNotBe null
        holder.callback?.onResponse(nullResponse)

        deferredResult.await()

        verify(mockAppSyncClient).mutate(any<CancelProvisionalFundingSourceMutation>())
    }

    @Test
    fun `cancelProvisionalFundingSource() should throw when response has a funding source not found error`() = runBlocking<Unit> {
        holder.callback shouldBe null

        val errorCancelResponse by before {
            val error = com.apollographql.apollo.api.Error(
                "mock",
                emptyList(),
                mapOf("errorType" to "FundingSourceNotFoundError"),
            )
            Response.builder<CancelProvisionalFundingSourceMutation.Data>(CancelProvisionalFundingSourceMutation(idInput))
                .errors(listOf(error))
                .data(null)
                .build()
        }

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.FundingSourceException.FundingSourceNotFoundException> {
                client.cancelProvisionalFundingSource("id")
            }
        }
        deferredResult.start()
        delay(100L)

        holder.callback shouldNotBe null
        holder.callback?.onResponse(errorCancelResponse)

        deferredResult.await()

        verify(mockAppSyncClient).mutate(any<CancelProvisionalFundingSourceMutation>())
    }

    @Test
    fun `cancelProvisionalFundingSource() should throw when response has an account locked error`() = runBlocking<Unit> {
        holder.callback shouldBe null

        val errorCancelResponse by before {
            val error = com.apollographql.apollo.api.Error(
                "mock",
                emptyList(),
                mapOf("errorType" to "AccountLockedError"),
            )
            Response.builder<CancelProvisionalFundingSourceMutation.Data>(CancelProvisionalFundingSourceMutation(idInput))
                .errors(listOf(error))
                .data(null)
                .build()
        }

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.FundingSourceException.AccountLockedException> {
                client.cancelProvisionalFundingSource("id")
            }
        }
        deferredResult.start()
        delay(100L)

        holder.callback shouldNotBe null
        holder.callback?.onResponse(errorCancelResponse)

        deferredResult.await()

        verify(mockAppSyncClient).mutate(any<CancelProvisionalFundingSourceMutation>())
    }

    @Test
    fun `cancelProvisionalFundingSource() should throw when http error occurs`() = runBlocking<Unit> {
        holder.callback shouldBe null

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.FundingSourceException.CancelFailedException> {
                client.cancelProvisionalFundingSource("id")
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

        verify(mockAppSyncClient).mutate(any<CancelProvisionalFundingSourceMutation>())
    }

    @Test
    fun `cancelProvisionalFundingSource() should throw when unknown error occurs`() = runBlocking<Unit> {
        holder.callback shouldBe null

        mockAppSyncClient.stub {
            on { mutate(any<CancelProvisionalFundingSourceMutation>()) } doThrow RuntimeException("Mock Runtime Exception")
        }

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.FundingSourceException.UnknownException> {
                client.cancelProvisionalFundingSource("id")
            }
        }
        deferredResult.start()
        delay(100L)

        deferredResult.await()

        verify(mockAppSyncClient).mutate(any<CancelProvisionalFundingSourceMutation>())
    }

    @Test
    fun `cancelProvisionalFundingSource() should not suppress CancellationException`() = runBlocking<Unit> {
        holder.callback shouldBe null

        mockAppSyncClient.stub {
            on { mutate(any<CancelProvisionalFundingSourceMutation>()) } doThrow CancellationException("Mock Cancellation Exception")
        }

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<CancellationException> {
                client.cancelProvisionalFundingSource("id")
            }
        }
        deferredResult.start()
        delay(100L)

        deferredResult.await()

        verify(mockAppSyncClient).mutate(any<CancelProvisionalFundingSourceMutation>())
    }
}
