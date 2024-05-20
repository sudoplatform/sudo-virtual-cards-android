/*
 * Copyright © 2023 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards.types

import android.os.Parcelable
import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName
import kotlinx.parcelize.Parcelize

/**
 * Representation of the [PricingPolicy] for each funding source provider
 * which make up a component of the virtual cards configuration.
 */
@Parcelize
@Keep
data class PricingPolicy(
    val stripe: StripePricingPolicy? = null,
    val checkout: CheckoutPricingPolicy? = null,
) : Parcelable

@Parcelize
@Keep
data class StripePricingPolicy(
    @SerializedName("CREDIT_CARD")
    val creditCard: Map<String, TieredMarkupPolicy>,
) : Parcelable

@Parcelize
@Keep
data class CheckoutPricingPolicy(
    @SerializedName("CREDIT_CARD")
    val creditCard: Map<String, TieredMarkupPolicy>,
    @SerializedName("BANK_ACCOUNT")
    val bankAccount: Map<String, TieredMarkupPolicy>,
) : Parcelable

@Parcelize
@Keep
data class TieredMarkup(
    val markup: Markup,
    val minThreshold: Int? = null,
) : Parcelable

@Parcelize
@Keep
data class TieredMarkupPolicy(
    val tiers: List<TieredMarkup>,
) : Parcelable
