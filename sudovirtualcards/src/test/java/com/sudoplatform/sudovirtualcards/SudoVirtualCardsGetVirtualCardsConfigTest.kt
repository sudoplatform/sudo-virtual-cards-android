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
import com.sudoplatform.sudokeymanager.KeyManagerInterface
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.stub
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoMoreInteractions
import com.sudoplatform.sudouser.SudoUserClient
import com.sudoplatform.sudovirtualcards.graphql.CallbackHolder
import com.sudoplatform.sudovirtualcards.graphql.GetVirtualCardsConfigQuery
import com.sudoplatform.sudovirtualcards.graphql.fragment.VirtualCardsConfig
import com.sudoplatform.sudovirtualcards.graphql.fragment.FundingSourceSupportInfo
import com.sudoplatform.sudovirtualcards.graphql.fragment.FundingSourceSupportDetail
import com.sudoplatform.sudovirtualcards.graphql.type.CardType
import com.sudoplatform.sudovirtualcards.types.CurrencyVelocity
import com.sudoplatform.sudovirtualcards.types.FundingSourceSupportInfo as FundingSourceSupportInfoEntity
import com.sudoplatform.sudovirtualcards.types.FundingSourceSupportDetail as FundingSourceSupportDetailEntity
import com.sudoplatform.sudovirtualcards.types.CardType as CardTypeEntity
import com.sudoplatform.sudovirtualcards.keys.PublicKeyService
import com.sudoplatform.sudovirtualcards.types.CurrencyAmount
import io.kotlintest.shouldBe
import io.kotlintest.shouldNotBe
import io.kotlintest.shouldThrow
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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
 * Test the correct operation of [SudoVirtualCardsClient.getVirtualCardsConfig]
 * using mocks and spies.
 */
class SudoVirtualCardsGetVirtualCardsConfigTest : BaseTests() {

    private val queryResult by before {
        GetVirtualCardsConfigQuery.GetVirtualCardsConfig(
            "typename",
            GetVirtualCardsConfigQuery.GetVirtualCardsConfig.Fragments(
                VirtualCardsConfig(
                    "VirtualCardsConfig",
                    listOf("maxFundingSourceVelocity"),
                    listOf("maxFundingSourceFailureVelocity"),
                    listOf("maxCardCreationVelocity"),
                    listOf(
                        VirtualCardsConfig.MaxTransactionVelocity(
                            "maxTransactionVelocity",
                            "USD",
                            listOf("velocity")
                        )
                    ),
                    listOf(
                        VirtualCardsConfig.MaxTransactionAmount(
                            "maxTransactionAmount",
                            "USD",
                            100
                        )
                    ),
                    listOf("virtualCardCurrencies"),
                    listOf(
                        VirtualCardsConfig.FundingSourceSupportInfo(
                            "FundingSourceSupportInfo",
                            VirtualCardsConfig.FundingSourceSupportInfo.Fragments(
                                FundingSourceSupportInfo(
                                    "FundingSourceSupportInfo",
                                    "providerType",
                                    "fundingSourceType",
                                    "network",
                                    listOf(
                                        FundingSourceSupportInfo.Detail(
                                            "Detail",
                                            FundingSourceSupportInfo.Detail.Fragments(
                                                FundingSourceSupportDetail(
                                                    "CardType",
                                                    CardType.PREPAID
                                                )
                                            )
                                        )
                                    )
                                )
                            )
                        )
                    )
                )
            )
        )
    }

    private val queryResponse by before {
        Response.builder<GetVirtualCardsConfigQuery.Data>(GetVirtualCardsConfigQuery())
            .data(GetVirtualCardsConfigQuery.Data(queryResult))
            .build()
    }

    private val queryHolder = CallbackHolder<GetVirtualCardsConfigQuery.Data>()

    private val mockContext by before {
        mock<Context>()
    }

    private val mockUserClient by before {
        mock<SudoUserClient>()
    }

    private val mockAppSyncClient by before {
        mock<AWSAppSyncClient>().stub {
            on { query(any<GetVirtualCardsConfigQuery>()) } doReturn queryHolder.queryOperation
        }
    }

    private val mockKeyManager by before {
        mock<KeyManagerInterface>()
    }

    private val mockPublicKeyService by before {
        mock<PublicKeyService>()
    }

    private val client by before {
        SudoVirtualCardsClient.builder()
            .setContext(mockContext)
            .setSudoUserClient(mockUserClient)
            .setAppSyncClient(mockAppSyncClient)
            .setKeyManager(mockKeyManager)
            .setLogger(mock())
            .setPublicKeyService(mockPublicKeyService)
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
            mockAppSyncClient,
            mockKeyManager,
            mockPublicKeyService
        )
    }

    @Test
    fun `getVirtualCardsConfig() should return results when no error present`() = runBlocking<Unit> {

        queryHolder.callback shouldBe null

        val deferredResult = async(Dispatchers.IO) {
            client.getVirtualCardsConfig()
        }
        deferredResult.start()

        delay(100L)
        queryHolder.callback shouldNotBe null
        queryHolder.callback?.onResponse(queryResponse)

        val result = deferredResult.await()
        result shouldNotBe null

        with(result!!) {
            maxFundingSourceVelocity shouldBe listOf("maxFundingSourceVelocity")
            maxFundingSourceFailureVelocity shouldBe listOf("maxFundingSourceFailureVelocity")
            maxCardCreationVelocity shouldBe listOf("maxCardCreationVelocity")
            maxTransactionVelocity shouldBe listOf(
                CurrencyVelocity(
                    "USD",
                    listOf("velocity")
                )
            )
            maxTransactionAmount shouldBe listOf(
                CurrencyAmount(
                    "USD",
                    100
                )
            )
            virtualCardCurrencies shouldBe listOf("virtualCardCurrencies")
            fundingSourceSupportInfo shouldBe listOf(
                FundingSourceSupportInfoEntity(
                    "providerType",
                    "fundingSourceType",
                    "network",
                    listOf(
                        FundingSourceSupportDetailEntity(
                            CardTypeEntity.PREPAID
                        )
                    )
                )
            )
        }
        verify(mockAppSyncClient).query(any<GetVirtualCardsConfigQuery>())
    }

    @Test
    fun `getVirtualCardsConfig() should return null result when query response is null`() = runBlocking<Unit> {

        queryHolder.callback shouldBe null

        val nullQueryResponse by before {
            Response.builder<GetVirtualCardsConfigQuery.Data>(GetVirtualCardsConfigQuery())
                .data(null)
                .build()
        }

        val deferredResult = async(Dispatchers.IO) {
            client.getVirtualCardsConfig()
        }
        deferredResult.start()

        delay(100L)
        queryHolder.callback shouldNotBe null
        queryHolder.callback?.onResponse(nullQueryResponse)

        val result = deferredResult.await()
        result shouldBe null

        verify(mockAppSyncClient).query(any<GetVirtualCardsConfigQuery>())
    }

    @Test
    fun `getVirtualCardsConfig() should throw when query response has errors`() = runBlocking<Unit> {

        queryHolder.callback shouldBe null

        val errorQueryResponse by before {
            val error = com.apollographql.apollo.api.Error(
                "mock",
                emptyList(),
                mapOf("errorType" to "IdentityVerificationNotVerifiedError")
            )
            Response.builder<GetVirtualCardsConfigQuery.Data>(GetVirtualCardsConfigQuery())
                .errors(listOf(error))
                .data(null)
                .build()
        }

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.VirtualCardException.IdentityVerificationException> {
                client.getVirtualCardsConfig()
            }
        }
        deferredResult.start()
        delay(100L)

        queryHolder.callback shouldNotBe null
        queryHolder.callback?.onResponse(errorQueryResponse)

        verify(mockAppSyncClient).query(any<GetVirtualCardsConfigQuery>())
    }

    @Test
    fun `getVirtualCardsConfig() should throw when http error occurs`() = runBlocking<Unit> {

        queryHolder.callback shouldBe null

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.VirtualCardException.FailedException> {
                client.getVirtualCardsConfig()
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

        verify(mockAppSyncClient).query(any<GetVirtualCardsConfigQuery>())
    }

    @Test
    fun `getVirtualCardsConfig() should throw when unknown error occurs()`() = runBlocking<Unit> {

        queryHolder.callback shouldBe null

        mockAppSyncClient.stub {
            on { query(any<GetVirtualCardsConfigQuery>()) } doThrow RuntimeException("Mock Runtime Exception")
        }

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.VirtualCardException.UnknownException> {
                client.getVirtualCardsConfig()
            }
        }
        deferredResult.start()
        delay(100L)

        deferredResult.await()

        verify(mockAppSyncClient).query(any<GetVirtualCardsConfigQuery>())
    }

    @Test
    fun `getVirtualCardsConfig() should not block coroutine cancellation exception`() = runBlocking<Unit> {

        mockAppSyncClient.stub {
            on { query(any<GetVirtualCardsConfigQuery>()) } doThrow CancellationException("Mock Cancellation Exception")
        }

        shouldThrow<CancellationException> {
            client.getVirtualCardsConfig()
        }

        verify(mockAppSyncClient).query(any<GetVirtualCardsConfigQuery>())
    }
}
