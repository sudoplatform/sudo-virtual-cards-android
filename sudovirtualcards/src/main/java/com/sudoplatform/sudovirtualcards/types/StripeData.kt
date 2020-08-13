/*
 * Copyright © 2020 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards.types

import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName

/**
 * Initializes [StripeDefaults].
 *
 * @since 2020-05-25
 */
object StripeDefaults {
    /** Provider String - default to "stripe". */
    const val provider = "stripe"
    /** Associated supported version. */
    const val version = 1
    /** Configuration Type. */
    const val configurationType = "string"
}

/**
 * Representation of [StripeCommonData] which provides common data
 * for all Stripe based data.
 *
 * @property provider Funding source provider.
 * @property version Associated supported version.
 *
 * @since 2020-05-25
 */
abstract class StripeCommonData {
    abstract val provider: String
    abstract val version: Int
}

/**
 * Representation of [StripeData] used for the [StripeSetup] intent.
 *
 * @property provider See [StripeCommonData.provider].
 * @property version See [StripeCommonData.version].
 * @property intent Stripe setup intent ID.
 * @property clientSecret Stripe setup intent client secret
 *
 * @since 2020-05-25
 */
@Keep
data class StripeData(
    override val provider: String,
    override val version: Int,
    val intent: String,
    @SerializedName("client_secret")
    val clientSecret: String
) : StripeCommonData()

/**
 * Representation of [StripeSetup] data from the setup intent.
 *
 * @property id Identifier associated with the provisioning of the funding source.
 * @property data The [StripeData] from the setup intent.
 *
 * @since 2020-05-25
 */
@Keep
data class StripeSetup(
    val id: String,
    val data: StripeData
)

/**
 * Representation of [StripeCompletionData] received from Stripe
 * used to complete the funding source creation.
 *
 * @property provider See [StripeCommonData.provider].
 * @property version See [StripeCommonData.version].
 * @property paymentMethod Specifies payment method bound to confirmed setup intent.
 *
 * @since 2020-05-25
 */
@Keep
data class StripeCompletionData(
    override val provider: String = StripeDefaults.provider,
    override val version: Int = StripeDefaults.version,
    @SerializedName("payment_method")
    val paymentMethod: String
) : StripeCommonData()
