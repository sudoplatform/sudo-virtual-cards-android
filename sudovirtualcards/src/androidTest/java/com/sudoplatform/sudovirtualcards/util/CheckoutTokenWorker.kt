/*
 * Copyright © 2023 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards.util

import com.checkout.android_sdk.CheckoutAPIClient
import com.checkout.android_sdk.CheckoutAPIClient.OnTokenGenerated
import com.checkout.android_sdk.Models.BillingModel
import com.checkout.android_sdk.Models.PhoneModel
import com.checkout.android_sdk.Request.CardTokenisationRequest
import com.checkout.android_sdk.Response.CardTokenisationFail
import com.checkout.android_sdk.Response.CardTokenisationResponse
import com.checkout.android_sdk.network.NetworkError
import com.sudoplatform.sudovirtualcards.SudoVirtualCardsClient
import com.sudoplatform.sudovirtualcards.types.CheckoutCardProviderCompletionData
import com.sudoplatform.sudovirtualcards.types.inputs.CreditCardFundingSourceInput
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * Test utility worker encapsulating the functionality to perform processing of payment setup
 * confirmation.
 */
internal class CheckoutTokenWorker(
    private val checkoutClient: CheckoutAPIClient
) {
    /**
     * Processes the payment details to return the data needed to complete
     * the funding source creation process.
     *
     * @param input The credit card input required to build the card and billing details.
     */
    suspend fun generatePaymentToken(
        input: CreditCardFundingSourceInput,
    ): CheckoutCardProviderCompletionData {

        val tokenisationRequest = CardTokenisationRequest(
            input.cardNumber,
            input.name,
            input.expirationMonth.toString(),
            input.expirationYear.toString(),
            input.securityCode,
            BillingModel(
                address_line1 = input.addressLine1,
                address_line2 = input.addressLine2 ?: "",
                city = input.city,
                state = input.state,
                zip = input.postalCode,
                country = input.country
            ),
            PhoneModel(country_code = "1", number = "1111111111"),
        )
        val tokenisationResponse = waitForTokenResponse(checkoutClient, tokenisationRequest)

        return CheckoutCardProviderCompletionData(
            paymentToken = tokenisationResponse?.token
                ?: throw SudoVirtualCardsClient.FundingSourceException.FailedException("Unexpected empty tokenisation response")
        )
    }

    private suspend fun waitForTokenResponse(
        checkoutClient: CheckoutAPIClient,
        request: CardTokenisationRequest
    ): CardTokenisationResponse? =
        suspendCoroutine { cont ->
            val tokenListener = object : OnTokenGenerated {
                override fun onTokenGenerated(response: CardTokenisationResponse?) {
                    cont.resume(response)
                }

                override fun onError(error: CardTokenisationFail?) {
                    cont.resumeWithException(SudoVirtualCardsClient.FundingSourceException.FailedException(error?.errorType))
                }

                override fun onNetworkError(error: NetworkError?) {
                    cont.resumeWithException(error?.cause ?: SudoVirtualCardsClient.FundingSourceException.FailedException(error?.message))
                }
            }
            checkoutClient.setTokenListener(tokenListener)
            checkoutClient.generateToken(request)
        }
}
