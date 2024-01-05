/*
 * Copyright © 2023 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards.types

/**
 * Client application data required by providers for funding source setup and refresh operations.
 *
 * @property applicationName [String] Unique application name which maps to configuration data stored at the service.
 */
data class ClientApplicationData(
    val applicationName: String,
)
