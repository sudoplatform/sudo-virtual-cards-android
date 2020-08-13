/*
 * Copyright © 2020 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards

import android.content.Context
import com.sudoplatform.sudovirtualcards.types.StripeClientConfiguration
import com.sudoplatform.sudovirtualcards.types.StripeSetup
import com.sudoplatform.sudovirtualcards.types.inputs.CreditCardFundingSourceInput

/**
 * Interface encapsulating the functionality to perform processing of payment setup confirmation.
 * This allows abstraction of the payment provider methods to aid in mocking for tests.
 *
 * @since 2020-06-03
 */
interface PaymentProcessInteractions {

    /**
     * Process the payment setup confirmation to return data needed to complete the
     * funding source creation process.
     *
     * @param input Credit card input required to build card and billing details.
     * @param configuration Stripe client configuration containing API key.
     * @param stripeSetup Stripe setup data.
     * @param context Application context.
     * @return The encoded completion data.
     */
    fun process(
        input: CreditCardFundingSourceInput,
        configuration: StripeClientConfiguration,
        stripeSetup: StripeSetup,
        context: Context
    ): String
}
