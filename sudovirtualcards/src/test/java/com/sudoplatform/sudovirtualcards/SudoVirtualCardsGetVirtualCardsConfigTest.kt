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
import com.google.gson.annotations.SerializedName
import com.sudoplatform.sudokeymanager.KeyManagerInterface
import com.sudoplatform.sudouser.SudoUserClient
import com.sudoplatform.sudovirtualcards.graphql.CallbackHolder
import com.sudoplatform.sudovirtualcards.graphql.GetFundingSourceClientConfigurationQuery
import com.sudoplatform.sudovirtualcards.graphql.GetVirtualCardsConfigQuery
import com.sudoplatform.sudovirtualcards.graphql.fragment.FundingSourceSupportDetail
import com.sudoplatform.sudovirtualcards.graphql.fragment.FundingSourceSupportInfo
import com.sudoplatform.sudovirtualcards.graphql.fragment.VirtualCardsConfig
import com.sudoplatform.sudovirtualcards.graphql.type.CardType
import com.sudoplatform.sudovirtualcards.keys.PublicKeyService
import com.sudoplatform.sudovirtualcards.types.CheckoutPricingPolicy
import com.sudoplatform.sudovirtualcards.types.ClientApplicationConfiguration
import com.sudoplatform.sudovirtualcards.types.CurrencyAmount
import com.sudoplatform.sudovirtualcards.types.CurrencyVelocity
import com.sudoplatform.sudovirtualcards.types.FundingSourceProviders
import com.sudoplatform.sudovirtualcards.types.FundingSourceType
import com.sudoplatform.sudovirtualcards.types.FundingSourceTypes
import com.sudoplatform.sudovirtualcards.types.Markup
import com.sudoplatform.sudovirtualcards.types.PlaidApplicationConfiguration
import com.sudoplatform.sudovirtualcards.types.PricingPolicy
import com.sudoplatform.sudovirtualcards.types.StripePricingPolicy
import com.sudoplatform.sudovirtualcards.types.TieredMarkup
import com.sudoplatform.sudovirtualcards.types.TieredMarkupPolicy
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
import com.sudoplatform.sudovirtualcards.types.CardType as CardTypeEntity
import com.sudoplatform.sudovirtualcards.types.FundingSourceClientConfiguration as FundingSourceClientConfigurationEntity
import com.sudoplatform.sudovirtualcards.types.FundingSourceSupportDetail as FundingSourceSupportDetailEntity
import com.sudoplatform.sudovirtualcards.types.FundingSourceSupportInfo as FundingSourceSupportInfoEntity

/**
 * Test the correct operation of [SudoVirtualCardsClient.getVirtualCardsConfig]
 * using mocks and spies.
 */
class SudoVirtualCardsGetVirtualCardsConfigTest : BaseTests() {

    data class SerializedClientApplicationConfiguration(
        @SerializedName("client_application_configuration")
        val clientApplicationConfiguration: ClientApplicationConfiguration,
    )

    private val queryResult by before {
        val fsConfig = FundingSourceTypes(
            listOf(
                FundingSourceClientConfigurationEntity(
                    apiKey = "test-key",
                    fundingSourceType = FundingSourceType.CREDIT_CARD,
                ),
                FundingSourceClientConfigurationEntity(
                    apiKey = "test-key",
                    fundingSourceType = FundingSourceType.BANK_ACCOUNT,
                ),
            ),
        )
        val fsConfigStr = Gson().toJson(fsConfig)
        val encodedFsConfigData = Base64.encodeBase64String(fsConfigStr.toByteArray())
        GetFundingSourceClientConfigurationQuery.GetFundingSourceClientConfiguration(
            "typename",
            encodedFsConfigData,
        )

        val appConfig = SerializedClientApplicationConfiguration(
            clientApplicationConfiguration = ClientApplicationConfiguration(
                fundingSourceProviders = FundingSourceProviders(
                    plaid = PlaidApplicationConfiguration(
                        clientName = "client-name",
                        androidPackageName = "android-package-name",
                    ),
                ),
            ),
        )
        val appConfigStr = Gson().toJson(appConfig)
        val encodedAppConfigData = Base64.encodeBase64String(appConfigStr.toByteArray())

        val pricingPolicy = PricingPolicy(
            stripe = StripePricingPolicy(
                creditCard = mapOf(
                    Pair(
                        "DEFAULT",
                        TieredMarkupPolicy(
                            tiers = listOf(
                                TieredMarkup(
                                    markup = Markup(
                                        flat = 1000,
                                        percent = 10,
                                    ),
                                    minThreshold = 0,
                                ),
                            ),
                        ),
                    ),
                ),
            ),
            checkout = CheckoutPricingPolicy(
                creditCard = mapOf(
                    Pair(
                        "DEFAULT",
                        TieredMarkupPolicy(
                            tiers = listOf(
                                TieredMarkup(
                                    markup = Markup(
                                        flat = 2500,
                                        percent = 25,
                                    ),
                                    minThreshold = 0,
                                ),
                            ),
                        ),
                    ),
                ),
                bankAccount = mapOf(
                    Pair(
                        "DEFAULT",
                        TieredMarkupPolicy(
                            tiers = listOf(
                                TieredMarkup(
                                    minThreshold = 0,
                                    markup = Markup(
                                        flat = 1000,
                                        percent = 0,
                                    ),
                                ),
                                TieredMarkup(
                                    minThreshold = 10000,
                                    markup = Markup(
                                        flat = 2000,
                                        percent = 0,
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val pricingPolicyStr = Gson().toJson(pricingPolicy)
        val encodedPricingPolicy = Base64.encodeBase64String((pricingPolicyStr.toByteArray()))

        GetVirtualCardsConfigQuery.GetVirtualCardsConfig(
            "typename",
            GetVirtualCardsConfigQuery.GetVirtualCardsConfig.Fragments(
                VirtualCardsConfig(
                    "VirtualCardsConfig",
                    listOf("maxFundingSourceVelocity"),
                    listOf("maxFundingSourceFailureVelocity"),
                    listOf("maxFundingSourcePendingVelocity"),
                    listOf("maxCardCreationVelocity"),
                    listOf(
                        VirtualCardsConfig.MaxTransactionVelocity(
                            "maxTransactionVelocity",
                            "USD",
                            listOf("velocity"),
                        ),
                    ),
                    listOf(
                        VirtualCardsConfig.MaxTransactionAmount(
                            "maxTransactionAmount",
                            "USD",
                            100,
                        ),
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
                                                    CardType.PREPAID,
                                                ),
                                            ),
                                        ),
                                    ),
                                ),
                            ),
                        ),
                    ),
                    true,
                    true,
                    VirtualCardsConfig.FundingSourceClientConfiguration(
                        "typename",
                        encodedFsConfigData,
                    ),
                    VirtualCardsConfig.ClientApplicationsConfiguration(
                        "typename",
                        encodedAppConfigData,
                    ),
                    VirtualCardsConfig.PricingPolicy(
                        "typename",
                        encodedPricingPolicy,
                    ),
                ),
            ),
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
            mockPublicKeyService,
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
            val fundingSourceTypes = listOf(FundingSourceType.CREDIT_CARD, FundingSourceType.BANK_ACCOUNT)
            for (i in result.fundingSourceClientConfiguration.indices) {
                result.fundingSourceClientConfiguration[i].fundingSourceType shouldBe fundingSourceTypes[i]
                result.fundingSourceClientConfiguration[i].type shouldBe "string"
                result.fundingSourceClientConfiguration[i].version shouldBe 1
                result.fundingSourceClientConfiguration[i].apiKey shouldBe "test-key"
            }
            maxFundingSourceVelocity shouldBe listOf("maxFundingSourceVelocity")
            maxFundingSourceFailureVelocity shouldBe listOf("maxFundingSourceFailureVelocity")
            maxFundingSourcePendingVelocity shouldBe listOf("maxFundingSourcePendingVelocity")
            maxCardCreationVelocity shouldBe listOf("maxCardCreationVelocity")
            maxTransactionVelocity shouldBe listOf(
                CurrencyVelocity(
                    "USD",
                    listOf("velocity"),
                ),
            )
            maxTransactionAmount shouldBe listOf(
                CurrencyAmount(
                    "USD",
                    100,
                ),
            )
            virtualCardCurrencies shouldBe listOf("virtualCardCurrencies")
            fundingSourceSupportInfo shouldBe listOf(
                FundingSourceSupportInfoEntity(
                    "providerType",
                    "fundingSourceType",
                    "network",
                    listOf(
                        FundingSourceSupportDetailEntity(
                            CardTypeEntity.PREPAID,
                        ),
                    ),
                ),
            )
            clientApplicationConfiguration shouldBe mapOf(
                Pair(
                    "client_application_configuration",
                    ClientApplicationConfiguration(
                        FundingSourceProviders(
                            PlaidApplicationConfiguration(
                                "client-name",
                                "android-package-name",
                            ),
                        ),
                    ),
                ),
            )
            pricingPolicy shouldBe PricingPolicy(
                StripePricingPolicy(
                    mapOf(
                        Pair(
                            "DEFAULT",
                            TieredMarkupPolicy(
                                listOf(
                                    TieredMarkup(
                                        Markup(
                                            10,
                                            1000,
                                        ),
                                        0,
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
                CheckoutPricingPolicy(
                    mapOf(
                        Pair(
                            "DEFAULT",
                            TieredMarkupPolicy(
                                listOf(
                                    TieredMarkup(
                                        Markup(
                                            25,
                                            2500,
                                        ),
                                        0,
                                    ),
                                ),
                            ),
                        ),
                    ),
                    mapOf(
                        Pair(
                            "DEFAULT",
                            TieredMarkupPolicy(
                                listOf(
                                    TieredMarkup(
                                        Markup(
                                            0,
                                            1000,
                                        ),
                                        0,
                                    ),
                                    TieredMarkup(
                                        Markup(
                                            0,
                                            2000,
                                        ),
                                        10000,
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
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
                mapOf("errorType" to "IdentityVerificationNotVerifiedError"),
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
