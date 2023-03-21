/*
 * Copyright © 2023 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards.types.inputs

import com.sudoplatform.sudovirtualcards.types.ProviderRefreshData as RefreshDataInput

/**
 * Input object containing the information required to complete the refresh of a funding source.
 *
 * @property id [String] Identifier of the provisional funding source to be completed and provisioned.
 * @property refreshData [RefreshDataInput] The refresh data used to complete
 *  the funding source refresh operation.
 * @property language [String] Some funding source types require presentation of end-user language
 *  specific agreements. This property allows the client application to specify the user's
 *  preferred language. If such presentation is required and has no translation in the requested
 *  language or no preferred language is specified, the default translation will be presented.
 *  The default is a property of service instance configuration. The value is an RFC 5646 language
 *  tag e.g. en-US.
 */
data class RefreshFundingSourceInput(
    val id: String,
    val refreshData: RefreshDataInput,
    val language: String? = null
)
