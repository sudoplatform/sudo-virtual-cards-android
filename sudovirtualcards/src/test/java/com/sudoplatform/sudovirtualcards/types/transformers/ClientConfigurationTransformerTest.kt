/*
 * Copyright © 2023 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards.types.transformers

import com.google.gson.Gson
import com.sudoplatform.sudovirtualcards.SudoVirtualCardsClient
import com.sudoplatform.sudovirtualcards.SudoVirtualCardsGetVirtualCardsConfigTest
import com.sudoplatform.sudovirtualcards.types.ClientApplicationConfiguration
import com.sudoplatform.sudovirtualcards.types.FundingSourceClientConfiguration
import com.sudoplatform.sudovirtualcards.types.FundingSourceProviders
import com.sudoplatform.sudovirtualcards.types.FundingSourceType
import com.sudoplatform.sudovirtualcards.types.FundingSourceTypes
import com.sudoplatform.sudovirtualcards.types.PlaidApplicationConfiguration
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
}
