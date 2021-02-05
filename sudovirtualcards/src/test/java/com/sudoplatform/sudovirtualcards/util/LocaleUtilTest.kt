/*
 * Copyright © 2020 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.kotlintest.shouldBe
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Test that the country codes are correctly converted.
 *
 * @since 2020-06-23
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class LocaleUtilTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun check3CharConversion() {
        mapOf("AUS" to "AU", "USA" to "US", "GBR" to "GB", "VNM" to "VN").forEach { (three, two) ->
            LocaleUtil.toCountryCodeAlpha2(context, three) shouldBe two
        }
    }

    @Test
    fun check2CharConversion() {
        mapOf("AU" to "AUS", "US" to "USA", "GB" to "GBR", "VN" to "VNM").forEach { (two, three) ->
            LocaleUtil.toCountryCodeAlpha3(context, two) shouldBe three
        }
    }
}
