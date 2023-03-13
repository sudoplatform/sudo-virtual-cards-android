/*
 * Copyright © 2023 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards.types.inputs

import com.sudoplatform.sudovirtualcards.types.FundingSourceType

/**
 * Input object containing the information required to setup a funding source.
 *
 * @property currency [String] The ISO 4217 currency code that is being used for the setup.
 * @property type [FundingSourceType] The type of funding source being setup.
 * @property supportedProviders [List<String>] The set of providers supported by this client.
 * @property language [String] Some funding source types require presentation of end-user language
 *  specific agreements. This property allows the client application to specify the user's
 *  preferred language. If such presentation is required and has no translation in the requested
 *  language or no preferred language is specified, the default translation will be presented.
 *  The default is a property of service instance configuration. The value is an RFC 5646 language
 *  tag e.g. en-US.
 */
data class SetupFundingSourceInput(
    val currency: String,
    val type: FundingSourceType,
    val supportedProviders: List<String>? = null,
    val language: String? = null
)
