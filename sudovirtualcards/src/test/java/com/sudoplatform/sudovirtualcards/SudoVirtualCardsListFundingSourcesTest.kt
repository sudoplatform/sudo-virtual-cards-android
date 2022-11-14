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
import com.sudoplatform.sudovirtualcards.graphql.ListFundingSourcesQuery
import com.sudoplatform.sudovirtualcards.graphql.type.CardType
import com.sudoplatform.sudovirtualcards.graphql.fragment.FundingSource as FundingSourceFragment
import com.sudoplatform.sudovirtualcards.graphql.type.CreditCardNetwork
import com.sudoplatform.sudovirtualcards.graphql.type.FundingSourceState
import com.sudoplatform.sudovirtualcards.types.CachePolicy
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
 * Test the correct operation of [SudoVirtualCardsClient.listFundingSources]
 * using mocks and spies.
 */
class SudoVirtualCardsListFundingSourcesTest : BaseTests() {

    private val queryResult by before {
        ListFundingSourcesQuery.ListFundingSources(
            "typename",
            listOf(
                ListFundingSourcesQuery.Item(
                    "typename",
                    ListFundingSourcesQuery.Item.Fragments(
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
                            CreditCardNetwork.VISA,
                            CardType.CREDIT
                        )
                    )
                )
            ),
            null
        )
    }

    private val response by before {
        Response.builder<ListFundingSourcesQuery.Data>(ListFundingSourcesQuery(null, null))
            .data(ListFundingSourcesQuery.Data(queryResult))
            .build()
    }

    private var holder = CallbackHolder<ListFundingSourcesQuery.Data>()

    private val mockContext by before {
        mock<Context>()
    }

    private val mockUserClient by before {
        mock<SudoUserClient>()
    }

    private val mockAppSyncClient by before {
        mock<AWSAppSyncClient>().stub {
            on { query(any<ListFundingSourcesQuery>()) } doReturn holder.queryOperation
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
    fun `listFundingSources() should return results when no error present`() = runBlocking<Unit> {

        holder.callback shouldBe null

        val deferredResult = async(Dispatchers.IO) {
            client.listFundingSources()
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
            state shouldBe FundingSource.State.ACTIVE
            currency shouldBe "USD"
            last4 shouldBe "last4"
            network shouldBe FundingSource.CreditCardNetwork.VISA
        }

        verify(mockAppSyncClient).query(any<ListFundingSourcesQuery>())
    }

    @Test
    fun `listFundingSources() should return results when populating nextToken`() = runBlocking<Unit> {

        holder.callback shouldBe null

        val queryResultWithNextToken by before {
            ListFundingSourcesQuery.ListFundingSources(
                "ListFundingSources",
                listOf(
                    ListFundingSourcesQuery.Item(
                        "Item",
                        ListFundingSourcesQuery.Item.Fragments(
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
                                CreditCardNetwork.VISA,
                                CardType.CREDIT
                            )
                        )
                    )
                ),
                "dummyNextToken"
            )
        }

        val responseWithNextToken by before {
            Response.builder<ListFundingSourcesQuery.Data>(ListFundingSourcesQuery(1, "dummyNextToken"))
                .data(ListFundingSourcesQuery.Data(queryResultWithNextToken))
                .build()
        }

        val deferredResult = async(Dispatchers.IO) {
            client.listFundingSources(1, "dummyNextToken", CachePolicy.REMOTE_ONLY)
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
            state shouldBe FundingSource.State.ACTIVE
            currency shouldBe "USD"
            last4 shouldBe "last4"
            network shouldBe FundingSource.CreditCardNetwork.VISA
        }

        verify(mockAppSyncClient).query(any<ListFundingSourcesQuery>())
    }

    @Test
    fun `listFundingSources() should return empty list output when query result data is empty`() = runBlocking<Unit> {

        holder.callback shouldBe null

        val queryResultWithEmptyList by before {
            ListFundingSourcesQuery.ListFundingSources(
                "typename",
                emptyList(),
                null
            )
        }

        val responseWithEmptyList by before {
            Response.builder<ListFundingSourcesQuery.Data>(ListFundingSourcesQuery(null, null))
                .data(ListFundingSourcesQuery.Data(queryResultWithEmptyList))
                .build()
        }

        val deferredResult = async(Dispatchers.IO) {
            client.listFundingSources()
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

        verify(mockAppSyncClient).query(any<ListFundingSourcesQuery>())
    }

    @Test
    fun `listFundingSources() should return empty list output when query response is null`() = runBlocking<Unit> {
        holder.callback shouldBe null

        val nullResponse by before {
            Response.builder<ListFundingSourcesQuery.Data>(ListFundingSourcesQuery(null, null))
                .data(null)
                .build()
        }

        val deferredResult = async(Dispatchers.IO) {
            client.listFundingSources()
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

        verify(mockAppSyncClient).query(any<ListFundingSourcesQuery>())
    }

    @Test
    fun `listFundingSources() should throw when http error occurs`() = runBlocking<Unit> {

        holder.callback shouldBe null

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.FundingSourceException.FailedException> {
                client.listFundingSources()
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

        verify(mockAppSyncClient).query(any<ListFundingSourcesQuery>())
    }

    @Test
    fun `listFundingSources() should throw when unknown error occurs`() = runBlocking<Unit> {

        holder.callback shouldBe null

        mockAppSyncClient.stub {
            on { query(any<ListFundingSourcesQuery>()) } doThrow RuntimeException("Mock Runtime Exception")
        }

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.FundingSourceException.UnknownException> {
                client.listFundingSources()
            }
        }
        deferredResult.start()
        delay(100L)

        deferredResult.await()

        verify(mockAppSyncClient).query(any<ListFundingSourcesQuery>())
    }

    @Test
    fun `listFundingSources() should not suppress CancellationException`
    () = runBlocking<Unit> {

        holder.callback shouldBe null

        mockAppSyncClient.stub {
            on { query(any<ListFundingSourcesQuery>()) } doThrow CancellationException("Mock Cancellation Exception")
        }

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<CancellationException> {
                client.listFundingSources()
            }
        }
        deferredResult.start()
        delay(100L)

        deferredResult.await()

        verify(mockAppSyncClient).query(any<ListFundingSourcesQuery>())
    }
}
