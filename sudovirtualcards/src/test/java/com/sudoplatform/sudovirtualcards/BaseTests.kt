/*
 * Copyright © 2024 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards

import com.sudoplatform.sudologging.LogDriverInterface
import com.sudoplatform.sudologging.LogLevel
import com.sudoplatform.sudologging.Logger
import com.sudoplatform.sudovirtualcards.rules.ActualPropertyResetter
import com.sudoplatform.sudovirtualcards.rules.PropertyResetRule
import com.sudoplatform.sudovirtualcards.rules.PropertyResetter
import com.sudoplatform.sudovirtualcards.rules.TimberLogRule
import org.bouncycastle.util.encoders.Base64
import org.junit.Rule
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.stub

/**
 * Base class that sets up:
 * - [TimberLogRule]
 * - [PropertyResetRule]
 *
 * And provides convenient access to the [PropertyResetRule.before] via [PropertyResetter.before].
 */
abstract class BaseTests : PropertyResetter by ActualPropertyResetter() {
    @Rule @JvmField
    val timberLogRule = TimberLogRule()

    private val mockLogDriver: LogDriverInterface =
        mock<LogDriverInterface>().stub {
            on { logLevel } doReturn LogLevel.VERBOSE
        }

    protected val mockLogger by before {
        Logger("mock", mockLogDriver)
    }

    protected fun missingProvider(provider: String): java.lang.AssertionError = AssertionError("Missing provider $provider")

    protected fun mockSeal(value: String): String {
        val valueBytes = value.toByteArray()
        val data = ByteArray(256)
        valueBytes.copyInto(data)
        return String(Base64.encode(data), Charsets.UTF_8)
    }
}
