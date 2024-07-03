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
import com.sudoplatform.sudovirtualcards.graphql.ListProvisionalFundingSourcesQuery
import com.sudoplatform.sudovirtualcards.graphql.fragment.ProvisionalFundingSource
import com.sudoplatform.sudovirtualcards.graphql.type.FundingSourceType
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
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.stub
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoMoreInteractions
import java.net.HttpURLConnection

/**
 * Test the correct operation of [SudoVirtualCardsClient.listProvisionalFundingSources]
 * using mocks and spies.
 */
class SudoVirtualCardsListProvisionalFundingSourcesTest : BaseTests() {

    private val queryResult by before {
        val stripeSetupData =
            StripeCardProvisioningData(
                "stripe",
                1,
                "intent",
                "clientSecret",
                com.sudoplatform.sudovirtualcards.types.FundingSourceType.CREDIT_CARD,
            )

        ListProvisionalFundingSourcesQuery.ListProvisionalFundingSources(
            "ListProvisionalFundingSources",
            listOf(
                ListProvisionalFundingSourcesQuery.Item(
                    "typename",
                    ListProvisionalFundingSourcesQuery.Item.Fragments(
                        ProvisionalFundingSource(
                            "ProvisionalFundingSource",
                            "id",
                            "owner",
                            1,
                            1.0,
                            1.0,
                            FundingSourceType.CREDIT_CARD,
                            Base64.encodeBase64String(Gson().toJson(stripeSetupData).toByteArray()),
                            ProvisionalFundingSourceState.PROVISIONING,
                            "1234",
                        ),
                    ),
                ),
            ),
            null,
        )
    }

    private val response by before {
        Response.builder<ListProvisionalFundingSourcesQuery.Data>(ListProvisionalFundingSourcesQuery(null, 10, null))
            .data(ListProvisionalFundingSourcesQuery.Data(queryResult))
            .build()
    }

    private var holder = CallbackHolder<ListProvisionalFundingSourcesQuery.Data>()

    private val mockContext by before {
        mock<Context>()
    }

    private val mockUserClient by before {
        mock<SudoUserClient>()
    }

    private val mockAppSyncClient by before {
        mock<AWSAppSyncClient>().stub {
            on { query(any<ListProvisionalFundingSourcesQuery>()) } doReturn holder.queryOperation
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
    fun `ListProvisionalFundingSources() should return results when no error present`() = runBlocking<Unit> {
        holder.callback shouldBe null

        val deferredResult = async(Dispatchers.IO) {
            client.listProvisionalFundingSources()
        }
        deferredResult.start()

        delay(100L)
        holder.callback shouldNotBe null
        holder.callback?.onResponse(response)

        val result = deferredResult.await()
        result shouldNotBe null
        result.items.isEmpty() shouldBe false
        result.items.size shouldBe 1
        result.nextToken shouldBe null

        with(result.items[0]) {
            id shouldBe("id")
            owner shouldBe("owner")
            version shouldBe 1
            state shouldBe com.sudoplatform.sudovirtualcards.types.ProvisionalFundingSource.ProvisioningState.PROVISIONING
            last4 shouldBe "1234"
        }

        verify(mockAppSyncClient).query(any<ListProvisionalFundingSourcesQuery>())
    }

    @Test
    fun `ListProvisionalFundingSources() should return results when populating nextToken`() = runBlocking<Unit> {
        holder.callback shouldBe null

        val queryResultWithNextToken by before {
            val stripeSetupData =
                StripeCardProvisioningData(
                    "stripe",
                    1,
                    "intent",
                    "clientSecret",
                    com.sudoplatform.sudovirtualcards.types.FundingSourceType.CREDIT_CARD,
                )

            ListProvisionalFundingSourcesQuery.ListProvisionalFundingSources(
                "ListProvisionalFundingSources",
                listOf(
                    ListProvisionalFundingSourcesQuery.Item(
                        "typename",
                        ListProvisionalFundingSourcesQuery.Item.Fragments(
                            ProvisionalFundingSource(
                                "ProvisionalFundingSource",
                                "id",
                                "owner",
                                1,
                                1.0,
                                1.0,
                                FundingSourceType.CREDIT_CARD,
                                Base64.encodeBase64String(Gson().toJson(stripeSetupData).toByteArray()),
                                ProvisionalFundingSourceState.PROVISIONING,
                                "1234",
                            ),
                        ),
                    ),
                ),
                "dummyNextToken",
            )
        }

        val responseWithNextToken by before {
            Response.builder<ListProvisionalFundingSourcesQuery.Data>(ListProvisionalFundingSourcesQuery(null, 1, "dummyNextToken"))
                .data(ListProvisionalFundingSourcesQuery.Data(queryResultWithNextToken))
                .build()
        }

        val deferredResult = async(Dispatchers.IO) {
            client.listProvisionalFundingSources()
        }
        deferredResult.start()

        delay(100L)
        holder.callback shouldNotBe null
        holder.callback?.onResponse(responseWithNextToken)

        val result = deferredResult.await()
        result shouldNotBe null
        result.items.isEmpty() shouldBe false
        result.items.size shouldBe 1
        result.nextToken shouldBe "dummyNextToken"

        with(result.items[0]) {
            id shouldBe("id")
            owner shouldBe("owner")
            version shouldBe 1
            state shouldBe com.sudoplatform.sudovirtualcards.types.ProvisionalFundingSource.ProvisioningState.PROVISIONING
            last4 shouldBe "1234"
            type shouldBe com.sudoplatform.sudovirtualcards.types.FundingSourceType.CREDIT_CARD
        }

        verify(mockAppSyncClient).query(any<ListProvisionalFundingSourcesQuery>())
    }

    @Test
    fun `ListProvisionalFundingSources() should return empty list output when query result data is empty`() = runBlocking<Unit> {
        holder.callback shouldBe null

        val queryResultWithEmptyList by before {
            ListProvisionalFundingSourcesQuery.ListProvisionalFundingSources(
                "typename",
                emptyList(),
                null,
            )
        }

        val responseWithEmptyList by before {
            Response.builder<ListProvisionalFundingSourcesQuery.Data>(ListProvisionalFundingSourcesQuery(null, 10, null))
                .data(ListProvisionalFundingSourcesQuery.Data(queryResultWithEmptyList))
                .build()
        }

        val deferredResult = async(Dispatchers.IO) {
            client.listProvisionalFundingSources()
        }
        deferredResult.start()
        delay(100L)

        holder.callback shouldNotBe null
        holder.callback?.onResponse(responseWithEmptyList)

        val result = deferredResult.await()
        result shouldNotBe null
        result.items.isEmpty() shouldBe true
        result.items.size shouldBe 0
        result.nextToken shouldBe null

        verify(mockAppSyncClient).query(any<ListProvisionalFundingSourcesQuery>())
    }

    @Test
    fun `ListProvisionalFundingSources() should return empty list output when query response is null`() = runBlocking<Unit> {
        holder.callback shouldBe null

        val nullResponse by before {
            Response.builder<ListProvisionalFundingSourcesQuery.Data>(ListProvisionalFundingSourcesQuery(null, 10, null))
                .data(null)
                .build()
        }

        val deferredResult = async(Dispatchers.IO) {
            client.listProvisionalFundingSources()
        }
        deferredResult.start()
        delay(100L)

        holder.callback shouldNotBe null
        holder.callback?.onResponse(nullResponse)

        val result = deferredResult.await()
        result shouldNotBe null
        result.items.isEmpty() shouldBe true
        result.items.size shouldBe 0
        result.nextToken shouldBe null

        verify(mockAppSyncClient).query(any<ListProvisionalFundingSourcesQuery>())
    }

    @Test
    fun `ListProvisionalFundingSources() should throw when http error occurs`() = runBlocking<Unit> {
        holder.callback shouldBe null

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.FundingSourceException.FailedException> {
                client.listProvisionalFundingSources()
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

        verify(mockAppSyncClient).query(any<ListProvisionalFundingSourcesQuery>())
    }

    @Test
    fun `ListProvisionalFundingSources() should throw when unknown error occurs`() = runBlocking<Unit> {
        holder.callback shouldBe null

        mockAppSyncClient.stub {
            on { query(any<ListProvisionalFundingSourcesQuery>()) } doThrow RuntimeException("Mock Runtime Exception")
        }

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.FundingSourceException.UnknownException> {
                client.listProvisionalFundingSources()
            }
        }
        deferredResult.start()
        delay(100L)

        deferredResult.await()

        verify(mockAppSyncClient).query(any<ListProvisionalFundingSourcesQuery>())
    }

    @Test
    fun `ListProvisionalFundingSources() should not suppress CancellationException`() = runBlocking<Unit> {
        holder.callback shouldBe null

        mockAppSyncClient.stub {
            on { query(any<ListProvisionalFundingSourcesQuery>()) } doThrow CancellationException("Mock Cancellation Exception")
        }

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<CancellationException> {
                client.listProvisionalFundingSources()
            }
        }
        deferredResult.start()
        delay(100L)

        deferredResult.await()

        verify(mockAppSyncClient).query(any<ListProvisionalFundingSourcesQuery>())
    }
}
