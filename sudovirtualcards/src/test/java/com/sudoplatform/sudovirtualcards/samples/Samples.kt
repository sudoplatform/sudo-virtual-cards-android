/*
 * Copyright © 2022 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards.samples

import android.content.Context
import org.mockito.kotlin.mock
import com.sudoplatform.sudoprofiles.SudoProfilesClient
import com.sudoplatform.sudouser.SudoUserClient
import com.sudoplatform.sudovirtualcards.BaseTests
import com.sudoplatform.sudovirtualcards.SudoVirtualCardsClient
import org.junit.Test
import org.junit.runner.RunWith
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

    fun sudoVirtualCardsClient(sudoUserClient: SudoUserClient, sudoProfilesClient: SudoProfilesClient) {
        val virtualCardsClient = SudoVirtualCardsClient.builder()
            .setContext(context)
            .setSudoUserClient(sudoUserClient)
            .setSudoProfilesClient(sudoProfilesClient)
            .build()
    }
}
