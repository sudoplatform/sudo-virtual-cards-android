/*
 * Copyright © 2022 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards.types

import android.os.Parcelable
import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName
import com.sudoplatform.sudovirtualcards.graphql.type.FundingSourceType
import kotlinx.parcelize.Parcelize

/**
 * Initializes [ProviderDefaults].
 */
object ProviderDefaults {
    /** Stripe Provider String. */
    const val stripeProvider = "stripe"
    /** Checkout Provider String. */
    const val checkoutProvider = "checkout"
    /** Associated supported version. */
    const val version = 1
    /** Configuration Type. */
    const val configurationType = "string"
}

/**
 * Representation of [ProviderCommonData] which provides common data
 * for all funding source provider based data.
 *
 * @property provider Funding source provider.
 * @property version Associated supported version.
 * @property type Funding Source Type
 */
abstract class ProviderCommonData {
    abstract val provider: String
    abstract val version: Int
    abstract val type: FundingSourceType
}

sealed class ProviderProvisioningData : ProviderCommonData(), Parcelable

/**
 * Representation of a based provisioning data type used to provision a checkout funding source. The client must
 * be robust to receiving a ProvisioningData it does not expect.
 *
 * @property provider See [ProviderCommonData.provider].
 * @property version See [ProviderCommonData.version].
 * @property type See [ProviderCommonData.type]
 */
@Keep
@Parcelize
data class BaseProvisioningData(
    override val provider: String = ProviderDefaults.checkoutProvider,
    override val version: Int,
    override val type: FundingSourceType,
) : ProviderProvisioningData(), Parcelable

/**
 * Representation of [StripeCardProvisioningData] used to provision a stripe funding source.
 *
 * @property provider See [ProviderCommonData.provider].
 * @property version See [ProviderCommonData.version].
 * @property type See [ProviderCommonData.type]
 * @property intent Intent of setup data.
 * @property clientSecret Provider setup intent client secret
 */
@Keep
@Parcelize
data class StripeCardProvisioningData(
    override val provider: String = ProviderDefaults.stripeProvider,
    override val version: Int,
    val intent: String,
    @SerializedName("client_secret")
    val clientSecret: String,
    override val type: FundingSourceType = FundingSourceType.CREDIT_CARD
) : ProviderProvisioningData()

/**
 * Representation of [CheckoutCardProvisioningData] used to provision a checkout funding source.
 *
 * @property provider See [ProviderCommonData.provider].
 * @property version See [ProviderCommonData.version].
 * @property type See [ProviderCommonData.type]
 */
@Keep
@Parcelize
data class CheckoutCardProvisioningData(
    override val provider: String = ProviderDefaults.checkoutProvider,
    override val version: Int = ProviderDefaults.version,
    override val type: FundingSourceType = FundingSourceType.CREDIT_CARD,
) : ProviderProvisioningData()

/**
 * Backward compatible ProvisioningData - represents stripe
 */
@Deprecated("Use provider-specific provisioning data")
typealias ProvisioningData = StripeCardProvisioningData

sealed class ProviderCompletionData : ProviderCommonData(), Parcelable

/**
 * Representation of [StripeCardProviderCompletionData] sent to the provider and
 * used to complete the funding source creation.
 *
 * @property provider See [ProviderCommonData.provider].
 * @property version See [ProviderCommonData.version].
 * @property type See [ProviderCommonData.type]
 * @property paymentMethod Specifies payment method bound to confirmed setup intent.
 */
@Keep
@Parcelize
data class StripeCardProviderCompletionData(
    override val provider: String = ProviderDefaults.stripeProvider,
    override val version: Int = ProviderDefaults.version,
    @SerializedName("payment_method")
    val paymentMethod: String,
    // Odd ordering for backwards compatibility
    override val type: FundingSourceType = FundingSourceType.CREDIT_CARD
) : ProviderCompletionData()

/**
 * Representation of [CheckoutCardProviderCompletionData] sent to the provider and
 * used to complete the funding source creation.
 *
 * @property provider See [ProviderCommonData.provider].
 * @property version See [ProviderCommonData.version].
 * @property type See [ProviderCommonData.type]
 * @property paymentToken Specifies payment token associated with the funding source credit card.
 */
@Keep
@Parcelize
data class CheckoutCardProviderCompletionData(
    override val provider: String = ProviderDefaults.checkoutProvider,
    override val version: Int = ProviderDefaults.version,
    override val type: FundingSourceType = FundingSourceType.CREDIT_CARD,
    @SerializedName("payment_token")
    val paymentToken: String
) : ProviderCompletionData()

sealed class ProviderUserInteractionData : ProviderCommonData(), Parcelable

/**
 * Representation of a based user interaction data type used to provision a checkout funding source. The client must
 * be robust to receiving a UserInteractionData it does not expect.
 *
 * @property provider See [ProviderCommonData.provider].
 * @property version See [ProviderCommonData.version].
 * @property type See [ProviderCommonData.type]
 */
@Keep
@Parcelize
data class BaseUserInteractionData(
    override val provider: String = ProviderDefaults.checkoutProvider,
    override val version: Int,
    override val type: FundingSourceType,
) : ProviderUserInteractionData()

/**
 * Returned when user interaction is required during the funding source setup operation
 * for checkout.com funding sources
 *
 * @property provider See [ProviderCommonData.provider].
 * @property version See [ProviderCommonData.version].
 * @property type See [ProviderCommonData.type]
 * @property redirectUrl Mandatory URL which indicates where the user should go for additional interaction
 */
@Keep
@Parcelize
data class CheckoutCardUserInteractionData(
    override val provider: String = ProviderDefaults.checkoutProvider,
    override val version: Int = ProviderDefaults.version,
    override val type: FundingSourceType = FundingSourceType.CREDIT_CARD,
    val redirectUrl: String
) : ProviderUserInteractionData()
