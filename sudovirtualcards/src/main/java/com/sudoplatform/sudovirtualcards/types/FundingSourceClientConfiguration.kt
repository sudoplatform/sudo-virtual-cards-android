/*
 * Copyright © 2023 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards.types

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * A representation of the funding source client configuration in the Sudo Platform Virtual Cards SDK.
 *
 * @property type [String] Type of the configuration provider.
 * @property fundingSourceType [FundingSourceType] Type of the funding source.
 * @property version [Int] Configuration version.
 * @property apiKey [String] API key for configuring calls to the provider.
 */
@Parcelize
data class FundingSourceClientConfiguration(
    val type: String = ProviderDefaults.configurationType,
    val fundingSourceType: FundingSourceType,
    val version: Int = ProviderDefaults.version,
    val apiKey: String,
) : Parcelable

/**
 * Representation of a [FundingSourceType] configuration.
 *
 * @property fundingSourceTypes [List<FundingSourceType>] A list of funding source type configuration data.
 */
@Parcelize
data class FundingSourceTypes(
    val fundingSourceTypes: List<FundingSourceClientConfiguration>,
) : Parcelable
