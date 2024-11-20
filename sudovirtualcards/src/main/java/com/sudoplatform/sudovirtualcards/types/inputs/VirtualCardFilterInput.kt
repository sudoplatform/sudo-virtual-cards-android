/*
 * Copyright © 2024 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards.types.inputs

/**
 * Input object containing the information required to filter a virtual card list.
 */
data class VirtualCardFilterInput(
    val id: IdFilterInput? = null,
    val state: IdFilterInput? = null,
    val and: ArrayList<VirtualCardFilterInput>? = null,
    val or: ArrayList<VirtualCardFilterInput>? = null,
    val not: VirtualCardFilterInput? = null,
)
