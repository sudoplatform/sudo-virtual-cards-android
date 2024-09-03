/*
 * Copyright © 2024 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards

import android.content.Context
import com.amplifyframework.api.ApiCategory
import com.amplifyframework.api.graphql.GraphQLOperation
import com.amplifyframework.api.graphql.GraphQLResponse
import com.amplifyframework.core.Consumer
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.sudoplatform.sudokeymanager.KeyManagerInterface
import com.sudoplatform.sudouser.SudoUserClient
import com.sudoplatform.sudouser.amplify.GraphQLClient
import com.sudoplatform.sudovirtualcards.graphql.GetVirtualCardsConfigQuery
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
import org.apache.commons.codec.binary.Base64
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.check
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.stub
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoMoreInteractions
import org.mockito.kotlin.whenever
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

    private val queryResponse by before {
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

        JSONObject(
            """
                {
                    'getVirtualCardsConfig': {
                            '__typename': 'VirtualCardsConfig',
                            'maxFundingSourceVelocity': ['maxFundingSourceVelocity'],
                            'maxFundingSourceFailureVelocity':['maxFundingSourceFailureVelocity'],
                            'maxFundingSourcePendingVelocity':['maxFundingSourcePendingVelocity'],
                            'maxCardCreationVelocity':['maxCardCreationVelocity'],
                            'maxTransactionVelocity':[{
                                'currency': 'USD',
                                'velocity': ['velocity']
                            }],
                            'maxTransactionAmount':[{
                                'currency': 'USD',
                                'amount': 100
                            }],
                            'virtualCardCurrencies': ['virtualCardCurrencies'],
                            'fundingSourceSupportInfo': [{
                                '__typename': 'FundingSourceSupportInfo',
                                'providerType': 'providerType',
                                'fundingSourceType': 'fundingSourceType',
                                'network': 'network',
                                'detail': [{
                                    '__typename': 'Detail',
                                    'cardType': '${CardType.PREPAID}'
                                }]
                            }],
                            'bankAccountFundingSourceExpendableEnabled': true,
                            'bankAccountFundingSourceCreationEnabled': true,
                            'fundingSourceClientConfiguration': {'data': '$encodedFsConfigData'},
                            'clientApplicationsConfiguration': {'data': '$encodedAppConfigData'},
                            'pricingPolicy': {'data': '$encodedPricingPolicy'},
                        }
                }
            """.trimIndent(),
        )
    }

    private val mockContext by before {
        mock<Context>()
    }

    private val mockUserClient by before {
        mock<SudoUserClient>()
    }

    private val mockApiCategory by before {
        mock<ApiCategory>().stub {
            on {
                query<String>(
                    argThat { this.query.equals(GetVirtualCardsConfigQuery.OPERATION_DOCUMENT) },
                    any(), any(),
                )
            } doAnswer {
                val mockOperation: GraphQLOperation<String> = mock()
                @Suppress("UNCHECKED_CAST")
                (it.arguments[1] as Consumer<GraphQLResponse<String>>).accept(
                    GraphQLResponse(queryResponse.toString(), null),
                )
                mockOperation
            }
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
            .setGraphQLClient(GraphQLClient(mockApiCategory))
            .setKeyManager(mockKeyManager)
            .setLogger(mock())
            .setPublicKeyService(mockPublicKeyService)
            .build()
    }

    @After
    fun fini() {
        verifyNoMoreInteractions(
            mockContext,
            mockUserClient,
            mockApiCategory,
            mockKeyManager,
            mockPublicKeyService,
        )
    }

    @Test
    fun `getVirtualCardsConfig() should return results when no error present`() = runBlocking<Unit> {
        val deferredResult = async(Dispatchers.IO) {
            client.getVirtualCardsConfig()
        }
        deferredResult.start()
        delay(100L)
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
        verify(mockApiCategory).query<String>(
            check {
                assertEquals(GetVirtualCardsConfigQuery.OPERATION_DOCUMENT, it.query)
            },
            any(),
            any(),
        )
    }

    @Test
    fun `getVirtualCardsConfig() should return null result when query response is null`() = runBlocking<Unit> {
        val mockOperation: GraphQLOperation<String> = mock()
        whenever(
            mockApiCategory.query<String>(
                argThat { this.query.equals(GetVirtualCardsConfigQuery.OPERATION_DOCUMENT) },
                any(),
                any(),
            ),
        ).thenAnswer {
            @Suppress("UNCHECKED_CAST")
            (it.arguments[1] as Consumer<GraphQLResponse<String>>).accept(
                GraphQLResponse(null, null),
            )
            mockOperation
        }

        val deferredResult = async(Dispatchers.IO) {
            client.getVirtualCardsConfig()
        }
        deferredResult.start()
        delay(100L)
        val result = deferredResult.await()
        result shouldBe null

        verify(mockApiCategory).query<String>(
            check {
                assertEquals(GetVirtualCardsConfigQuery.OPERATION_DOCUMENT, it.query)
            },
            any(),
            any(),
        )
    }

    @Test
    fun `getVirtualCardsConfig() should throw when query response has errors`() = runBlocking<Unit> {
        val errors = listOf(
            GraphQLResponse.Error(
                "mock",
                null,
                null,
                mapOf("errorType" to "IdentityVerificationNotVerifiedError"),
            ),
        )
        val mockOperation: GraphQLOperation<String> = mock()
        whenever(
            mockApiCategory.query<String>(
                argThat { this.query.equals(GetVirtualCardsConfigQuery.OPERATION_DOCUMENT) },
                any(),
                any(),
            ),
        ).thenAnswer {
            @Suppress("UNCHECKED_CAST")
            (it.arguments[1] as Consumer<GraphQLResponse<String>>).accept(
                GraphQLResponse(null, errors),
            )
            mockOperation
        }

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.VirtualCardException.IdentityVerificationException> {
                client.getVirtualCardsConfig()
            }
        }
        deferredResult.start()
        delay(100L)
        deferredResult.await()
        verify(mockApiCategory).query<String>(
            check {
                assertEquals(GetVirtualCardsConfigQuery.OPERATION_DOCUMENT, it.query)
            },
            any(),
            any(),
        )
    }

    @Test
    fun `getVirtualCardsConfig() should throw when http error occurs`() = runBlocking<Unit> {
        val errors = listOf(
            GraphQLResponse.Error(
                "mock",
                null,
                null,
                mapOf("httpStatus" to HttpURLConnection.HTTP_FORBIDDEN),
            ),
        )
        val mockOperation: GraphQLOperation<String> = mock()
        whenever(
            mockApiCategory.query<String>(
                argThat { this.query.equals(GetVirtualCardsConfigQuery.OPERATION_DOCUMENT) },
                any(),
                any(),
            ),
        ).thenAnswer {
            @Suppress("UNCHECKED_CAST")
            (it.arguments[1] as Consumer<GraphQLResponse<String>>).accept(
                GraphQLResponse(null, errors),
            )
            mockOperation
        }
        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.VirtualCardException.FailedException> {
                client.getVirtualCardsConfig()
            }
        }
        deferredResult.start()
        delay(100L)
        deferredResult.await()

        verify(mockApiCategory).query<String>(
            check {
                assertEquals(GetVirtualCardsConfigQuery.OPERATION_DOCUMENT, it.query)
            },
            any(),
            any(),
        )
    }

    @Test
    fun `getVirtualCardsConfig() should throw when unknown error occurs()`() = runBlocking<Unit> {
        mockApiCategory.stub {
            on {
                mockApiCategory.query<String>(
                    argThat { this.query.equals(GetVirtualCardsConfigQuery.OPERATION_DOCUMENT) },
                    any(),
                    any(),
                )
            } doThrow RuntimeException("Mock Runtime Exception")
        }

        val deferredResult = async(Dispatchers.IO) {
            shouldThrow<SudoVirtualCardsClient.VirtualCardException.UnknownException> {
                client.getVirtualCardsConfig()
            }
        }
        deferredResult.start()
        delay(100L)

        deferredResult.await()

        verify(mockApiCategory).query<String>(
            check {
                assertEquals(GetVirtualCardsConfigQuery.OPERATION_DOCUMENT, it.query)
            },
            any(),
            any(),
        )
    }

    @Test
    fun `getVirtualCardsConfig() should not block coroutine cancellation exception`() = runBlocking<Unit> {
        mockApiCategory.stub {
            on {
                mockApiCategory.query<String>(
                    argThat { this.query.equals(GetVirtualCardsConfigQuery.OPERATION_DOCUMENT) },
                    any(),
                    any(),
                )
            } doThrow CancellationException("Mock Cancellation Exception")
        }

        shouldThrow<CancellationException> {
            client.getVirtualCardsConfig()
        }

        verify(mockApiCategory).query<String>(
            check {
                assertEquals(GetVirtualCardsConfigQuery.OPERATION_DOCUMENT, it.query)
            },
            any(),
            any(),
        )
    }
}
