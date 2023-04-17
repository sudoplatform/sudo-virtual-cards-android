/*
 * Copyright © 2023 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards.util

/**
 * Helper class to manage card-based funding source creation options
 */
data class CreateCardFundingSourceOptions(
    val currency: String = "USD",
    val supportedProviders: List<String>? = null,
    val updateCardFundingSource: Boolean? = null,
    val applicationName: String = "system-test-app"
)
