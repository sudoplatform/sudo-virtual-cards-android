/*
 * Copyright © 2022 Anonyome Labs, Inc. All rights reserved.
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
object ProviderDefaults {
    /** Provider String - default to "stripe". */
    const val provider = "stripe"
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
 */
abstract class ProviderCommonData {
    abstract val provider: String
    abstract val version: Int
}

/**
 * Representation of [ProvisioningData] used to provision a funding source.
 *
 * @property provider See [ProviderCommonData.provider].
 * @property version See [ProviderCommonData.version].
 * @property intent Intent of setup data.
 * @property clientSecret Provider setup intent client secret
 */
@Keep
@Parcelize
data class ProvisioningData(
    override val provider: String,
    override val version: Int,
    val intent: String,
    @SerializedName("client_secret")
    val clientSecret: String
) : ProviderCommonData(), Parcelable

/**
 * Representation of [ProviderCompletionData] received from the provider
 * used to complete the funding source creation.
 *
 * @property provider See [ProviderCommonData.provider].
 * @property version See [ProviderCommonData.version].
 * @property paymentMethod Specifies payment method bound to confirmed setup intent.
 */
@Keep
data class ProviderCompletionData(
    override val provider: String = ProviderDefaults.provider,
    override val version: Int = ProviderDefaults.version,
    @SerializedName("payment_method")
    val paymentMethod: String
) : ProviderCommonData()
