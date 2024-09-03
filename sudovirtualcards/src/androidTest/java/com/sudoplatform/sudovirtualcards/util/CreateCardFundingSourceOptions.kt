/*
 * Copyright © 2024 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards.util

/**
 * Helper classes to manage funding source creation options
 */
abstract class CreateFundingSourceOptions {
    abstract val currency: String
    abstract val supportedProviders: List<String>?
    abstract val applicationName: String
}

data class CreateCardFundingSourceOptions(
    override val currency: String = "USD",
    override val supportedProviders: List<String>? = null,
    override val applicationName: String = "system-test-app",
    val updateCardFundingSource: Boolean? = null,
) : CreateFundingSourceOptions()

data class CreateBankAccountFundingSourceOptions(
    override val currency: String = "USD",
    override val supportedProviders: List<String>? = null,
    override val applicationName: String = "system-test-app",
    val username: String,
) : CreateFundingSourceOptions()
