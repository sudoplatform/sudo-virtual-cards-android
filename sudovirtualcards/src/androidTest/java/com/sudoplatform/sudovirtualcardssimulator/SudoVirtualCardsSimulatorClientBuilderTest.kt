/*
 * Copyright © 2024 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcardssimulator

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sudoplatform.sudovirtualcards.simulator.SudoVirtualCardsSimulatorClient
import io.kotlintest.shouldThrow
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Test the correct operation of the [SudoVirtualCardsSimulatorClient.builder]
 */
@RunWith(AndroidJUnit4::class)
class SudoVirtualCardsSimulatorClientBuilderTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun simulatorClientBuilderShouldThrowIfRequirementsNotProvided() {
        shouldThrow<NullPointerException> {
            SudoVirtualCardsSimulatorClient.builder().build()
        }

        shouldThrow<NullPointerException> {
            SudoVirtualCardsSimulatorClient
                .builder()
                .setContext(context)
                .build()
        }

        shouldThrow<NullPointerException> {
            SudoVirtualCardsSimulatorClient
                .builder()
                .setContext(context)
                .build()
        }
    }

    @Test
    fun simulatorClientBuilderShouldNotThrowIfApiKeyProvided() {
        SudoVirtualCardsSimulatorClient
            .builder()
            .setContext(context)
            .setApiKey("foo")
            .build()
    }

    @Test
    fun simulatorClientBuilderShouldNotThrowIfUsernamePasswordProvided() {
        SudoVirtualCardsSimulatorClient
            .builder()
            .setContext(context)
            .setUsername("foo")
            .setPassword("bar")
            .build()
    }
}
