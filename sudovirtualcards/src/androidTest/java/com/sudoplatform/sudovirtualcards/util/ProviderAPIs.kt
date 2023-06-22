/*
 * Copyright © 2023 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards.util

import android.content.Context
import com.checkout.android_sdk.CheckoutAPIClient
import com.checkout.android_sdk.Utils.Environment
import com.stripe.android.Stripe
import com.sudoplatform.sudovirtualcards.SudoVirtualCardsClient
import com.sudoplatform.sudovirtualcards.types.FundingSourceType

/**
 * Helper classes to manage optional funding source providers.
 */

class ProviderAPIs(
    val stripe: Stripe?,
    val checkout: CheckoutAPIClient?
)
class FundingSourceProviders(
    val stripeCardEnabled: Boolean,
    val checkoutCardEnabled: Boolean,
    val checkoutBankAccountEnabled: Boolean,
    val apis: ProviderAPIs
) {
    companion object {
        /**
         * Returns funding source providers supported by the connected Virtual Cards service.
         *
         * @param client [SudoVirtualCardsClient] The Virtual Cards Client for which the funding
         *  source provider apis will be returned.
         * @param context [Context] Application context.
         */
        suspend fun getFundingSourceProviders(
            client: SudoVirtualCardsClient,
            context: Context
        ): FundingSourceProviders {
            var stripe: Stripe? = null
            var checkout: CheckoutAPIClient? = null
            var stripeCardEnabled = false
            var checkoutCardEnabled = false
            var checkoutBankAccountEnabled = false

            val config = client.getFundingSourceClientConfiguration()
            config.forEach {
                if (it.type == "stripe") {
                    stripe = Stripe(context, it.apiKey)
                    stripeCardEnabled = true
                }
                if (it.type == "checkout") {
                    checkout = checkout ?: CheckoutAPIClient(context, it.apiKey, Environment.SANDBOX)
                    if (it.fundingSourceType == FundingSourceType.CREDIT_CARD) {
                        checkoutCardEnabled = true
                    }
                    if (it.fundingSourceType == FundingSourceType.BANK_ACCOUNT) {
                        checkoutBankAccountEnabled = true
                    }
                }
            }
            stripe ?: throw AssertionError("stripe is mandatory provider, but no client configuration found")
            return FundingSourceProviders(
                stripeCardEnabled,
                checkoutCardEnabled,
                checkoutBankAccountEnabled,
                ProviderAPIs(stripe, checkout)
            )
        }
    }
}
