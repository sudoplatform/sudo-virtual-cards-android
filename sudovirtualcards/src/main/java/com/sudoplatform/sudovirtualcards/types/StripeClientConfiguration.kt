/*
 * Copyright © 2020 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards.types

import androidx.annotation.Keep

/**
 * Representation of a Stripe [FundingSourceType] configuration.
 *
 * @property type Configuration Type.
 * @property version Associated supported version.
 * @property apiKey Stripe API key.
 *
 * @since 2020-05-25
 */
@Keep
data class FundingSourceType(
    val type: String = StripeDefaults.configurationType,
    val version: Int = StripeDefaults.version,
    val apiKey: String
)

/**
 * Representation of a [StripeClientConfiguration] used to perform API calls to
 * Stripe.
 *
 * @property fundingSourceTypes List holding the types of funding source providers.
 *
 * @since 2020-05-25
 */
@Keep
data class StripeClientConfiguration(
    val fundingSourceTypes: List<FundingSourceType>
)
