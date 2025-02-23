/*
 * Copyright © 2024 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards.samples

import android.content.Context
import com.sudoplatform.sudonotification.SudoNotificationClient
import com.sudoplatform.sudouser.SudoUserClient
import com.sudoplatform.sudovirtualcards.BaseTests
import com.sudoplatform.sudovirtualcards.SudoVirtualCardsClient
import com.sudoplatform.sudovirtualcards.SudoVirtualCardsNotifiableClient
import com.sudoplatform.sudovirtualcards.SudoVirtualCardsNotificationHandler
import com.sudoplatform.sudovirtualcards.types.VirtualCardsFundingSourceChangedNotification
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner

/**
 * These are sample snippets of code that are included in the generated documentation. They are
 * placed here in the test code so that at least we know they will compile.
 */
@RunWith(RobolectricTestRunner::class)
@Suppress("UNUSED_VARIABLE")
class Samples : BaseTests() {

    private val context by before { mock<Context>() }

    @Test
    fun mockTest() {
        // Just to keep junit happy
    }

    fun sudoVirtualCardsClient(sudoUserClient: SudoUserClient) {
        val virtualCardsClient = SudoVirtualCardsClient.builder()
            .setContext(context)
            .setSudoUserClient(sudoUserClient)
            .build()
    }

    fun sudoVirtualCardsNotifiableClient(sudoUserClient: SudoUserClient) {
        val notificationHandler = object : SudoVirtualCardsNotificationHandler {
            override fun onFundingSourceChanged(notification: VirtualCardsFundingSourceChangedNotification) {
                // Handle fundingSourceChanged notification
            }
        }

        val notifiableClient = SudoVirtualCardsNotifiableClient.builder()
            .setContext(context)
            .setNotificationHandler(notificationHandler)
            .build()

        val sudoNotificationClient = SudoNotificationClient.builder()
            .setSudoUserClient(sudoUserClient)
            .setNotifiableClients(listOf(notifiableClient))
            .build()
    }
}
