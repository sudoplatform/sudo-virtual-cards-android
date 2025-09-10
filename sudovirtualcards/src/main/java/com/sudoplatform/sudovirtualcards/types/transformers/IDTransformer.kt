/*
 * Copyright © 2024 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

import com.apollographql.apollo.api.Optional
import com.sudoplatform.sudovirtualcards.graphql.type.IDFilterInput
import com.sudoplatform.sudovirtualcards.types.inputs.IdFilterInput

/**
 * Transform the input type [IdFilterInput] into the corresponding GraphQL
 * type [IDFilterInput].
 */
fun IdFilterInput?.toIDFilterInput(): IDFilterInput? {
    if (this == null) {
        return null
    }
    return IDFilterInput(
        beginsWith = Optional.presentIfNotNull(beginsWith),
        between = Optional.presentIfNotNull(between?.map { it }),
        contains = Optional.presentIfNotNull(contains),
        eq = Optional.presentIfNotNull(eq),
        ge = Optional.presentIfNotNull(ge),
        gt = Optional.presentIfNotNull(gt),
        le = Optional.presentIfNotNull(le),
        lt = Optional.presentIfNotNull(lt),
        ne = Optional.presentIfNotNull(ne),
        notContains = Optional.presentIfNotNull(notContains),
    )
}
