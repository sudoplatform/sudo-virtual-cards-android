/*
 * Copyright © 2024 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards

import com.google.firebase.messaging.RemoteMessage
import com.sudoplatform.sudovirtualcards.notifications.FundingSourceChangedNotification
import com.sudoplatform.sudovirtualcards.types.FundingSourceFlags
import com.sudoplatform.sudovirtualcards.types.FundingSourceState
import com.sudoplatform.sudovirtualcards.types.FundingSourceType
import com.sudoplatform.sudovirtualcards.types.VirtualCardsFundingSourceChangedNotification
import io.kotlintest.matchers.collections.shouldContainAll
import io.kotlintest.matchers.collections.shouldHaveSize
import io.kotlintest.matchers.string.shouldStartWith
import io.kotlintest.shouldBe
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.reset
import org.mockito.kotlin.stub
import org.robolectric.RobolectricTestRunner
import java.util.Date

/**
 * Test the correct operation of the [SudoVirtualCardsNotifiableClient] using mocks
 * and spies.
 */
@RunWith(RobolectricTestRunner::class)
class SudoVirtualCardsNotifiableClientTest : BaseTests() {

    private val mockRemoteMessage by before {
        mock<RemoteMessage>()
    }

    private val testNotificationHandler = object : SudoVirtualCardsNotificationHandler {
        val fundingSourceChangedNotifications = mutableListOf<VirtualCardsFundingSourceChangedNotification>()

        override fun onFundingSourceChanged(notification: VirtualCardsFundingSourceChangedNotification) {
            fundingSourceChangedNotifications.add(notification)
        }
    }

    private val client by before {
        DefaultSudoVirtualCardsNotifiableClient(
            testNotificationHandler,
            mockLogger,
        )
    }

    @Test
    fun `serviceName should be vcService`() {
        client.serviceName shouldBe "vcService"
    }

    @Test
    fun `getSchema should return schema`() {
        val schema = client.getSchema()

        schema.serviceName shouldBe "vcService"

        schema.schema shouldHaveSize 3

        schema.schema.forEach {
            it.type shouldBe "string"
            it.fieldName shouldStartWith "meta."
        }

        schema.schema.map { it.fieldName } shouldContainAll listOf(
            "meta.type",
            "meta.fundingSourceId",
            "meta.fundingSourceType",
        )
    }

    @Test
    fun `processPayload does nothing for badly formatted payloads`() {
        val payloads = listOf(
            mapOf(),
            mapOf(Pair("sudoplatform", "this is not JSON")),
            mapOf(Pair("sudoplatform", "{}")),
            mapOf(Pair("sudoplatform", "{\"servicename\":\"sudoService\",\"data\":\"\"}")),
            mapOf(Pair("sudoplatform", "{\"servicename\":\"vcService\",\"data\":\"this is not json\"}")),
            mapOf(Pair("sudoplatform", "{\"servicename\":\"vcService\",\"data\":\"{\\\"wrong\\\":\\\"property\\\"}\"}")),
            mapOf(
                Pair(
                    "sudoplatform",
                    "{\"servicename\":\"emService\",\"data\":" +
                        "\"{\\\"keyId\\\":\\\"key-id\\\"," +
                        "\\\"algorithm\\\":\\\"algorithm\\\"," +
                        "\\\"sealed\\\":\\\"invalid-sealed-data\\\"}\"}",
                ),
            ),
        )

        payloads.forEach { payload ->
            reset(mockRemoteMessage)
            mockRemoteMessage.stub { on { data } doReturn payload }

            client.processPayload(mockRemoteMessage)

            testNotificationHandler.fundingSourceChangedNotifications shouldHaveSize 0
        }
    }

    @Test
    fun `processPayload invokes application handler for fundingSourceChanged messages`() {
        val payload =
            mapOf(
                Pair(
                    "sudoplatform",
                    "{\"servicename\":\"vcService\",\"data\":\"" +
                        "{\\\"type\\\":\\\"fundingSourceChanged\\\"," +
                        "\\\"owner\\\":\\\"owner-id\\\"," +
                        "\\\"fundingSourceId\\\":\\\"funding-source-id\\\"," +
                        "\\\"fundingSourceType\\\":\\\"BANK_ACCOUNT\\\"," +
                        "\\\"last4\\\":\\\"1234\\\"," +
                        "\\\"state\\\":\\\"ACTIVE\\\"," +
                        "\\\"flags\\\":[]," +
                        "\\\"updatedAtEpochMs\\\":2000}\"}",
                ),
            )

        mockRemoteMessage.stub { on { data } doReturn payload }

        val internalNotification = FundingSourceChangedNotification(
            type = "fundingSourceChanged",
            owner = "owner-id",
            fundingSourceId = "funding-source-id",
            fundingSourceType = FundingSourceType.BANK_ACCOUNT,
            last4 = "1234",
            state = FundingSourceState.ACTIVE,
            flags = emptyList<FundingSourceFlags>(),
            updatedAtEpochMs = 2000,
        )

        client.processPayload(mockRemoteMessage)

        testNotificationHandler.fundingSourceChangedNotifications shouldHaveSize 1

        testNotificationHandler.fundingSourceChangedNotifications[0] shouldBe VirtualCardsFundingSourceChangedNotification(
            id = internalNotification.fundingSourceId,
            owner = internalNotification.owner,
            type = internalNotification.fundingSourceType,
            last4 = internalNotification.last4,
            state = internalNotification.state,
            flags = internalNotification.flags,
            updatedAt = Date(internalNotification.updatedAtEpochMs),
        )
    }
}
