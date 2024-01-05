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
import com.sudoplatform.sudovirtualcards.graphql.GetFundingSourceClientConfigurationQuery
import com.sudoplatform.sudovirtualcards.types.FundingSourceClientConfiguration
import com.sudoplatform.sudovirtualcards.types.FundingSourceType
import com.sudoplatform.sudovirtualcards.types.FundingSourceTypes
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
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.stub
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoMoreInteractions
import java.net.HttpURLConnection
import java.util.concurrent.CancellationException

/**
 * Test the correct operation of [SudoVirtualCardsClient.getFundingSourceClientConfiguration]
 * using mocks and spies.
 */
class SudoVirtualCardsGetFundingSourceConfigurationTest : BaseTests() {

    private val queryResult by before {
        val config = FundingSourceTypes(
            listOf(
                FundingSourceClientConfiguration(
                    apiKey = "test-key",
                    fundingSourceType = FundingSourceType.CREDIT_CARD,
                ),
                FundingSourceClientConfiguration(
                    apiKey = "test-key",
                    fundingSourceType = FundingSourceType.BANK_ACCOUNT,
                ),
            ),
        )
        val configStr = Gson().toJson(config)
        val encodedConfigData = Base64.encodeBase64String(configStr.toByteArray())
        GetFundingSourceClientConfigurationQuery.GetFundingSourceClientConfiguration(
            "typename",
            encodedConfigData,
        )
    }

    private val queryResponse by before {
        Response.builder<GetFundingSourceClientConfigurationQuery.Data>(GetFundingSourceClientConfigurationQuery())
            .data(GetFundingSourceClientConfigurationQuery.Data(queryResult))
            .build()
    }

    private val queryHolder = CallbackHolder<GetFundingSourceClientConfigurationQuery.Data>()

    private val mockContext by before {
        mock<Context>()
    }

    private val mockUserClient by before {
        mock<SudoUserClient>()
    }

    private val mockAppSyncClient by before {
        mock<AWSAppSyncClient>().stub {
            on { query(any<GetFundingSourceClientConfigurationQuery>()) } doReturn queryHolder.queryOperation
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
        queryHolder.callback = null
    }

    @After
    fun fini() {
        verifyNoMoreInteractions(
            mockContext,
            mockUserClient,
            mockKeyManager,
            mockAppSyncClient,
        )
    }

    @Test
    fun `getFundingSourceClientConfiguration() should return results when no error present`() = runBlocking<Unit> {
        queryHolder.callback shouldBe null

        val deferredResult = async(Dispatchers.IO) {
            client.getFundingSourceClientConfiguration()
        }
        deferredResult.start()

        delay(100L)
        queryHolder.callback shouldNotBe null
        queryHolder.callback?.onResponse(queryResponse)

        val result = deferredResult.await()
        result shouldNotBe null

        val fundingSourceTypes = listOf(FundingSourceType.CREDIT_CARD, FundingSourceType.BANK_ACCOUNT)
        for (i in result.indices) {
            result[i].fundingSourceType shouldBe fundingSourceTypes[i]
            result[i].type shouldBe "string"
            result[i].version shouldBe 1
            result[i].apiKey shouldBe "test-key"
        }

        verify(mockAppSyncClient).query(any<GetFundingSourceClientConfigurationQuery>())
    }

    @Test
    fun `getFundingSourceClientConfiguration() should return null result when query response is null`() = runBlocking<Unit> {
        queryHolder.callback shouldBe null

        val nullResponse by before {
            Response.builder<GetFundingSourceClientConfigurationQuery.Data>(GetFundingSourceClientConfigurationQuery())
                .data(null)
                .build()
        }

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.FundingSourceException.FailedException> {
                client.getFundingSourceClientConfiguration()
            }
        }
        deferredResult.start()
        delay(100L)

        queryHolder.callback shouldNotBe null
        queryHolder.callback?.onResponse(nullResponse)

        deferredResult.await()

        verify(mockAppSyncClient).query(any<GetFundingSourceClientConfigurationQuery>())
    }

    @Test
    fun `getFundingSourceClientConfiguration() should throw when http error occurs`() = runBlocking<Unit> {
        queryHolder.callback shouldBe null

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.FundingSourceException.FailedException> {
                client.getFundingSourceClientConfiguration()
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

        queryHolder.callback shouldNotBe null
        queryHolder.callback?.onHttpError(ApolloHttpException(forbidden))

        deferredResult.await()

        verify(mockAppSyncClient).query(any<GetFundingSourceClientConfigurationQuery>())
    }

    @Test
    fun `getFundingSourceClientConfiguration() should throw when unknown error occurs()`() = runBlocking<Unit> {
        queryHolder.callback shouldBe null

        mockAppSyncClient.stub {
            on { query(any<GetFundingSourceClientConfigurationQuery>()) } doThrow RuntimeException("Mock Runtime Exception")
        }

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.FundingSourceException.UnknownException> {
                client.getFundingSourceClientConfiguration()
            }
        }
        deferredResult.start()
        delay(100L)

        deferredResult.await()

        verify(mockAppSyncClient).query(any<GetFundingSourceClientConfigurationQuery>())
    }

    @Test
    fun `getFundingSourceClientConfiguration() should not suppress CancellationException()`() = runBlocking<Unit> {
        queryHolder.callback shouldBe null

        mockAppSyncClient.stub {
            on { query(any<GetFundingSourceClientConfigurationQuery>()) } doThrow CancellationException("Mock Cancellation Exception")
        }

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<CancellationException> {
                client.getFundingSourceClientConfiguration()
            }
        }
        deferredResult.start()
        delay(100L)

        deferredResult.await()

        verify(mockAppSyncClient).query(any<GetFundingSourceClientConfigurationQuery>())
    }
}
