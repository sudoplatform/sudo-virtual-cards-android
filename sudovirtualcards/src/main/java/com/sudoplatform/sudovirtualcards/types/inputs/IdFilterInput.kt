/*
 * Copyright © 2024 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards.types.inputs

/**
 * Input object containing the information required to filter by id.
 *
 */
data class IdFilterInput(
    val ne: String? = null,
    val eq: String? = null,
    val le: String? = null,
    val lt: String? = null,
    val ge: String? = null,
    val gt: String? = null,
    val contains: String? = null,
    val notContains: String? = null,
    val between: ArrayList<String>? = null,
    val beginsWith: String? = null,
)
