/*
 * Copyright © 2020 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package com.sudoplatform.sudovirtualcards.samples

import android.content.Context
import com.nhaarman.mockitokotlin2.mock
import com.sudoplatform.sudoprofiles.SudoProfilesClient
import com.sudoplatform.sudouser.SudoUserClient
import com.sudoplatform.sudovirtualcards.BaseTests
import com.sudoplatform.sudovirtualcards.SudoVirtualCardsClient
import com.sudoplatform.sudovirtualcards.types.inputs.filters.filterCardsBy
import com.sudoplatform.sudovirtualcards.types.inputs.filters.filterTransactionsBy
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * These are sample snippets of code that are included in the generated documentation. They are
 * placed here in the test code so that at least we know they will compile.
 *
 * @since 2020-08-21
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

    suspend fun cardsFilter(virtualCardsClient: SudoVirtualCardsClient) {
        val issuedCards = virtualCardsClient.listCards {
            filterCardsBy {
                state equalTo "ISSUED"
            }
        }
        val unissuedCards = virtualCardsClient.listCards {
            filterCardsBy {
                state notEqualTo "ISSUED"
            }
        }
    }

    suspend fun transactionFilter(virtualCardsClient: SudoVirtualCardsClient) {
        val myPrimaryCardTransactions = virtualCardsClient.listTransactions {
            filterTransactionsBy {
                cardId equalTo "4242424242424242"
            }
        }
        val allMyCardTransactions = virtualCardsClient.listTransactions {
            filterTransactionsBy {
                cardId between ("4242424242424242" to "4242999999999999")
            }
        }
    }
}
