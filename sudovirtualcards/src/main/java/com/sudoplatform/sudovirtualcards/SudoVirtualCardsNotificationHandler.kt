/*
 * Copyright © 2024 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards

import com.sudoplatform.sudovirtualcards.types.VirtualCardsFundingSourceChangedNotification

interface SudoVirtualCardsNotificationHandler {
    fun onFundingSourceChanged(notification: VirtualCardsFundingSourceChangedNotification) {}
}
