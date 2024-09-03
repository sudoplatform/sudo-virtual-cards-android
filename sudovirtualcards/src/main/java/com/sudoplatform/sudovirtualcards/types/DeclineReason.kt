/*
 * Copyright © 2024 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards.types

import androidx.annotation.Keep

/**
 * Representation of an enumeration depicting why a [Transaction] was declined in the
 * Sudo Platform Virtual Cards SDK.
 */
@Keep
enum class DeclineReason {
    INSUFFICIENT_FUNDS,
    SUSPICIOUS,
    CARD_STOPPED,
    CARD_EXPIRED,
    MERCHANT_BLOCKED,
    MERCHANT_CODE_BLOCKED,
    MERCHANT_COUNTRY_BLOCKED,
    AVS_CHECK_FAILED,
    CSC_CHECK_FAILED,
    EXPIRY_CHECK_FAILED,
    PROCESSING_ERROR,
    DECLINED,
    VELOCITY_EXCEEDED,
    CURRENCY_BLOCKED,
    FUNDING_ERROR,
    UNKNOWN,
    INSUFFICIENT_ENTITLEMENTS,
    SERVICE_UNAVAILABLE,
}
