/*
 * Copyright © 2020 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards

import com.sudoplatform.sudovirtualcards.rules.ActualPropertyResetter
import com.sudoplatform.sudovirtualcards.rules.PropertyResetter
import com.sudoplatform.sudovirtualcards.rules.TimberLogRule
import org.junit.Rule

/**
 * Base class that sets up:
 * - [TimberLogRule]
 * - [com.sudoplatform.sudovirtualcards.rules.PropertyResetRule]
 *
 * And provides convenient access to the [com.sudoplatform.sudovirtualcards.rules.PropertyResetRule.before] via
 * [com.sudoplatform.sudovirtualcards.rules.PropertyResetter.before].
 */
abstract class BaseTests : PropertyResetter by ActualPropertyResetter() {
    @Rule @JvmField val timberLogRule = TimberLogRule()
}
