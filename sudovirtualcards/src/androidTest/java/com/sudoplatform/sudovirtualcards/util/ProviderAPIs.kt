/*
 * Copyright © 2022 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards.util

import android.content.Context
import com.checkout.android_sdk.CheckoutAPIClient
import com.checkout.android_sdk.Utils.Environment
import com.stripe.android.Stripe
import com.sudoplatform.sudovirtualcards.SudoVirtualCardsClient

/**
 * Helper class to manage optional funding source providers.
 */
class ProviderAPIs(
    val stripe: Stripe?,
    val checkout: CheckoutAPIClient?
) {
    companion object {
        /**
         * Returns funding source providers supported by the connected Virtual Cards service.
         *
         * @param client The Virtual Cards Client for which the funding source provider apis will be returned.
         * @param context Application context.
         */
        suspend fun getProviderAPIs(
            client: SudoVirtualCardsClient,
            context: Context
        ): ProviderAPIs {
            var stripe: Stripe? = null
            var checkout: CheckoutAPIClient? = null

            val config = client.getFundingSourceClientConfiguration()
            config.forEach {
                if (it.type == "stripe") {
                    stripe = Stripe(context, it.apiKey)
                }
                if (it.type == "checkout") {
                    checkout = CheckoutAPIClient(context, it.apiKey, Environment.SANDBOX)
                }
            }
            stripe ?: throw AssertionError("stripe is mandatory provider, but no client configuration found")
            return ProviderAPIs(stripe, checkout)
        }
    }
}
