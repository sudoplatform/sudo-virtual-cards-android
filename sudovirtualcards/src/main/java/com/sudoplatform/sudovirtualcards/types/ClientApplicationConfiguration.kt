/*
 * Copyright © 2023 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards.types

import android.os.Parcelable
import com.google.gson.annotations.SerializedName
import kotlinx.parcelize.Parcelize

/**
 * Plaid specific application configuration to support funding source creation.
 *
 * @property clientName [String] Name of the client application.
 * @property androidPackageName [String] The Android Package Name of the app to support OAuth
 *  redirect flows.
 */
@Parcelize
data class PlaidApplicationConfiguration(
    @SerializedName("client_name")
    val clientName: String,
    @SerializedName("android_package_name")
    val androidPackageName: String
) : Parcelable

/**
 * The funding source provider configuration.
 *
 * @property plaid [PlaidApplicationConfiguration] The Plaid specific configuration.
 */
@Parcelize
data class FundingSourceProviders(
    val plaid: PlaidApplicationConfiguration
) : Parcelable

/**
 * The client application configuration containing information associated with Android client applications.
 *
 * @property fundingSourceProviders [FundingSourceProviders] The configuration for each funding source provider.
 */
@Parcelize
data class ClientApplicationConfiguration(
    @SerializedName("funding_source_providers")
    val fundingSourceProviders: FundingSourceProviders
) : Parcelable
