/*
 * Copyright © 2024 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards.types

import android.os.Parcelable
import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName
import kotlinx.parcelize.Parcelize

/**
 * Initializes [ProviderDefaults].
 */
@Suppress("ktlint:standard:property-naming")
object ProviderDefaults {
    /** Stripe Provider String. */
    const val stripeProvider = "stripe"

    /** Associated supported version. */
    const val version = 1

    /** Configuration Type. */
    const val configurationType = "string"
}

/**
 * Representation of [ProviderCommonData] which provides common data
 * for all funding source provider based data.
 *
 * @property provider [String] Funding source provider.
 * @property version [Int] Associated supported version.
 * @property type [FundingSourceType] Funding source type.
 */
abstract class ProviderCommonData {
    abstract val provider: String
    abstract val version: Int
    abstract val type: FundingSourceType
}

sealed class ProviderProvisioningData :
    ProviderCommonData(),
    Parcelable

/**
 * Representation of a based provisioning data type used to provision a funding source. The client must
 * be robust to receiving a ProvisioningData it does not expect.
 *
 * @property provider See [ProviderCommonData.provider].
 * @property version See [ProviderCommonData.version].
 * @property type See [ProviderCommonData.type].
 */
@Keep
@Parcelize
data class BaseProvisioningData(
    override val provider: String,
    override val version: Int,
    override val type: FundingSourceType,
) : ProviderProvisioningData(),
    Parcelable

/**
 * Representation of [StripeCardProvisioningData] used to provision a stripe funding source.
 *
 * @property provider See [ProviderCommonData.provider].
 * @property version See [ProviderCommonData.version].
 * @property type See [ProviderCommonData.type].
 * @property intent [String] Intent of setup data.
 * @property clientSecret [String] Provider setup intent client secret.
 */
@Keep
@Parcelize
data class StripeCardProvisioningData(
    override val provider: String = ProviderDefaults.stripeProvider,
    override val version: Int,
    val intent: String,
    @SerializedName("client_secret")
    val clientSecret: String,
    override val type: FundingSourceType = FundingSourceType.CREDIT_CARD,
) : ProviderProvisioningData()

/**
 * Backward compatible ProvisioningData - represents stripe
 */
@Deprecated("Use provider-specific provisioning data")
typealias ProvisioningData = StripeCardProvisioningData

sealed class ProviderCompletionData :
    ProviderCommonData(),
    Parcelable

/**
 * Representation of [StripeCardProviderCompletionData] sent to the provider and
 * used to complete the funding source creation.
 *
 * @property provider See [ProviderCommonData.provider].
 * @property version See [ProviderCommonData.version].
 * @property type See [ProviderCommonData.type].
 * @property paymentMethod [String] Specifies payment method bound to confirmed setup intent.
 */
@Keep
@Parcelize
data class StripeCardProviderCompletionData(
    override val provider: String = ProviderDefaults.stripeProvider,
    override val version: Int = ProviderDefaults.version,
    @SerializedName("payment_method")
    val paymentMethod: String,
    // Odd ordering for backwards compatibility
    override val type: FundingSourceType = FundingSourceType.CREDIT_CARD,
) : ProviderCompletionData()

@Keep
@Parcelize
internal data class ProviderSetupData(
    val applicationName: String,
) : Parcelable
