/*
 * Copyright © 2020 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards

import android.content.Context
import com.amazonaws.util.Base64
import com.google.gson.Gson
import com.stripe.android.Stripe
import com.stripe.android.exception.StripeException
import com.stripe.android.model.Address
import com.stripe.android.model.ConfirmSetupIntentParams
import com.stripe.android.model.PaymentMethod
import com.stripe.android.model.PaymentMethodCreateParams
import com.sudoplatform.sudovirtualcards.types.StripeClientConfiguration
import com.sudoplatform.sudovirtualcards.types.StripeCompletionData
import com.sudoplatform.sudovirtualcards.types.StripeSetup
import com.sudoplatform.sudovirtualcards.types.inputs.CreditCardFundingSourceInput
import com.sudoplatform.sudovirtualcards.util.LocaleUtil

/**
 * Stripe implementation of the [PaymentProcessInteractions] interface.
 *
 * @since 2020-05-21
 */
class StripePaymentProcessInteractions : PaymentProcessInteractions {

    override fun process(
        input: CreditCardFundingSourceInput,
        configuration: StripeClientConfiguration,
        stripeSetup: StripeSetup,
        context: Context
    ): String {

        // Build card details
        val cardDetails = PaymentMethodCreateParams.Card.Builder()
            .setNumber(input.cardNumber)
            .setExpiryMonth(input.expirationMonth)
            .setExpiryYear(input.expirationYear)
            .setCvc(input.securityCode)
            .build()

        // Build billing details
        val billingDetails = PaymentMethod.BillingDetails.Builder()
            .setAddress(Address.Builder()
                .setLine1(input.addressLine1)
                .setLine2(input.addressLine2)
                .setCity(input.city)
                .setState(input.state)
                .setPostalCode(input.postalCode)
                .setCountry(ensureAlpha2CountryCode(context, input.country))
                .build())
            .build()

        // Confirm setup
        val cardParams = PaymentMethodCreateParams.create(cardDetails, billingDetails)
        val apiKey = configuration.fundingSourceTypes.first().apiKey
        val stripeClient = Stripe(context, apiKey)
        val confirmParams = ConfirmSetupIntentParams.create(cardParams, stripeSetup.data.clientSecret)
        val setupIntent =
            try {
                stripeClient.confirmSetupIntentSynchronous(confirmParams)
            } catch (e: StripeException) {
                throw SudoVirtualCardsClient.FundingSourceException.FailedException(e.message)
            }

        // Build completion data
        setupIntent?.paymentMethodId?.let {
            val completionData = StripeCompletionData(paymentMethod = it)
            val encodedCompletionDataString = Gson().toJson(completionData)
            return Base64.encode(encodedCompletionDataString.toByteArray()).toString(Charsets.UTF_8)
        }
            ?: throw SudoVirtualCardsClient.FundingSourceException.FailedException()
    }

    private fun ensureAlpha2CountryCode(context: Context, countryCode: String): String {
        if (countryCode.trim().length != 3) {
            return countryCode.trim()
        }
        return LocaleUtil.toCountryCodeAlpha2(context, countryCode)
            ?: countryCode
    }
}
