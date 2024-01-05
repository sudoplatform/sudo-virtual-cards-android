/*
 * Copyright © 2023 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards.types

/**
 * Representation of an enumeration depicting the state of a transaction's charge detail
 * in the Sudo Platform Virtual Cards SDK.
 */
enum class ChargeDetailState {
    /** Funding transaction initiated. */
    PENDING,

    /** Funding transaction cleared. */
    CLEARED,

    /** Funding transaction failed due to insufficient funds. */
    INSUFFICIENT_FUNDS,

    /** Funding transaction deemed failed for another reason. */
    FAILED,

    /** Unknown charge detail state. Please check you have the correct (latest) version of this SDK. */
    UNKNOWN,
}
