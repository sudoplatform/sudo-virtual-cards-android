/*
 * Copyright © 2023 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards.types.transformers

import com.google.gson.Gson
import com.sudoplatform.sudovirtualcards.SudoVirtualCardsClient
import com.sudoplatform.sudovirtualcards.SudoVirtualCardsGetVirtualCardsConfigTest
import com.sudoplatform.sudovirtualcards.types.CheckoutPricingPolicy
import com.sudoplatform.sudovirtualcards.types.ClientApplicationConfiguration
import com.sudoplatform.sudovirtualcards.types.FundingSourceClientConfiguration
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
import io.kotlintest.shouldThrow
import org.apache.commons.codec.binary.Base64
import org.junit.Test

/**
 * Testing the client configuration transformers.
 */
class ClientConfigurationTransformerTest {

    @Test
    fun `decodeFundingSourceClientConfiguration should decode successfully`() {
        val fsConfig = FundingSourceTypes(
            listOf(
                FundingSourceClientConfiguration(
                    apiKey = "test-key",
                    fundingSourceType = FundingSourceType.CREDIT_CARD
                ),
                FundingSourceClientConfiguration(
                    apiKey = "test-key",
                    fundingSourceType = FundingSourceType.BANK_ACCOUNT
                )
            )
        )
        val fsConfigStr = Gson().toJson(fsConfig)
        val encodedFsConfigData = Base64.encodeBase64String(fsConfigStr.toByteArray())

        val decodedFsConfig = decodeFundingSourceClientConfiguration(encodedFsConfigData)
        decodedFsConfig shouldBe listOf(
            FundingSourceClientConfiguration(
                "string",
                FundingSourceType.CREDIT_CARD,
                1,
                "test-key"
            ),
            FundingSourceClientConfiguration(
                "string",
                FundingSourceType.BANK_ACCOUNT,
                1,
                "test-key"
            )
        )
    }

    @Test
    fun `decodeFundingSourceClientConfiguration throw if encoded config data is not valid JSON`() {
        shouldThrow<SudoVirtualCardsClient.VirtualCardException.FailedException> {
            decodeFundingSourceClientConfiguration("this is not JSON")
        }
    }

    @Test
    fun `decodeClientApplicationConfiguration should decode successfully`() {
        val appConfig = SudoVirtualCardsGetVirtualCardsConfigTest.SerializedClientApplicationConfiguration(
            clientApplicationConfiguration = ClientApplicationConfiguration(
                fundingSourceProviders = FundingSourceProviders(
                    plaid = PlaidApplicationConfiguration(
                        clientName = "client-name",
                        androidPackageName = "android-package-name"
                    )
                )
            )
        )
        val appConfigStr = Gson().toJson(appConfig)
        val encodedAppConfigData = Base64.encodeBase64String(appConfigStr.toByteArray())

        val decodedAppConfig = decodeClientApplicationConfiguration(encodedAppConfigData)
        decodedAppConfig shouldBe mapOf(
            Pair(
                "client_application_configuration",
                ClientApplicationConfiguration(
                    FundingSourceProviders(
                        PlaidApplicationConfiguration(
                            "client-name",
                            "android-package-name"
                        )
                    )
                )
            )
        )
    }

    @Test
    fun `decodeClientApplicationConfiguration should throw if encoded config data is not valid JSON`() {
        shouldThrow<SudoVirtualCardsClient.VirtualCardException.FailedException> {
            decodeClientApplicationConfiguration("this is not JSON")
        }
    }

    @Test
    fun `decodePricingPolicy should decode successfully`() {
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
                                        percent = 10
                                    ),
                                    minThreshold = 0
                                )
                            )
                        )
                    )
                )
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
                                        percent = 25
                                    ),
                                    minThreshold = 0
                                )
                            )
                        )
                    )
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
                                        percent = 0
                                    )
                                ),
                                TieredMarkup(
                                    minThreshold = 10000,
                                    markup = Markup(
                                        flat = 2000,
                                        percent = 0
                                    )
                                )
                            )
                        )
                    )
                )
            )
        )
        val pricingPolicyStr = Gson().toJson(pricingPolicy)
        val encodedPricingPolicy = Base64.encodeBase64String(pricingPolicyStr.toByteArray())

        val decodedPricingPolicy = decodePricingPolicy(encodedPricingPolicy)
        decodedPricingPolicy shouldBe pricingPolicy
    }

    @Test
    fun `decodePricingPolicy should throw if encoded policy data is not valid JSON`() {
        shouldThrow<SudoVirtualCardsClient.VirtualCardException.FailedException> {
            decodePricingPolicy("this is not JSON")
        }
    }
}
