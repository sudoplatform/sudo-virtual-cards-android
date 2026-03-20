/*
 * Copyright © 2024 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards.util

import android.content.Context
import com.stripe.android.Stripe
import com.sudoplatform.sudovirtualcards.SudoVirtualCardsClient

/**
 * Helper classes to manage optional funding source providers.
 */

class ProviderAPIs(
    val stripe: Stripe?,
)

class FundingSourceProviders(
    val stripeCardEnabled: Boolean,
    val apis: ProviderAPIs,
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
            context: Context,
        ): FundingSourceProviders {
            var stripe: Stripe? = null
            var stripeCardEnabled = false

            val config = client.getVirtualCardsConfig()
            config?.fundingSourceClientConfiguration?.forEach {
                if (it.type == "stripe") {
                    stripe = Stripe(context, it.apiKey)
                    stripeCardEnabled = true
                }
            }
            stripe ?: throw AssertionError("stripe is mandatory provider, but no client configuration found")
            return FundingSourceProviders(
                stripeCardEnabled,
                ProviderAPIs(stripe),
            )
        }
    }
}
