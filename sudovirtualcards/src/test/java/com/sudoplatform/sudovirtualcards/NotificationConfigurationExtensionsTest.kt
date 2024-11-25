/*
 * Copyright © 2024 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards

import com.sudoplatform.sudonotification.types.NotificationConfiguration
import com.sudoplatform.sudonotification.types.NotificationFilterItem
import com.sudoplatform.sudovirtualcards.types.FundingSourceType
import io.kotlintest.matchers.collections.shouldHaveSize
import io.kotlintest.shouldBe
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Test correct manipulation of Notification SDK's NotificationConfiguration class by the Virtual Cards SDK
 * extensions to this class.
 */
@RunWith(RobolectricTestRunner::class)
class NotificationConfigurationExtensionsTest {
    @Test
    fun `initVirtualCardsNotifications should accept all funding source changed notifications`() {
        val emptyConfig = NotificationConfiguration(configs = listOf())
        val config = emptyConfig.initVirtualCardsNotifications()

        val configs = config.configs

        configs shouldHaveSize 2

        // First rule should be to exclude unrecognized notifications
        configs[0].status shouldBe NotificationConfiguration.DISABLE_STR
        configs[0].rules shouldBe "{\"!=\":[{\"var\":\"meta.type\"},\"fundingSourceChanged\"]}"

        // Second rule should be permit everything
        configs[1].status shouldBe NotificationConfiguration.ENABLE_STR
        configs[1].rules shouldBe NotificationConfiguration.DEFAULT_RULE_STRING
    }

    @Test
    fun `initVirtualCardsNotifications() should preserve existing rules`() {
        val existingItem = NotificationFilterItem(
            name = "vcService",
            status = NotificationConfiguration.DISABLE_STR,
            rules = "{\"==\":[{\"var\":\"meta.fundingSourceId\"}, \"ignored-funding-source-id\"]}",
        )

        val initialConfig = NotificationConfiguration(configs = listOf(existingItem))

        val config = initialConfig.initVirtualCardsNotifications()

        val configs = config.configs

        configs shouldHaveSize 3

        // First rule should be to exclude unrecognized notifications
        configs[0].status shouldBe NotificationConfiguration.DISABLE_STR
        configs[0].rules shouldBe "{\"!=\":[{\"var\":\"meta.type\"},\"fundingSourceChanged\"]}"

        // Second rule should match existing entry
        configs[1] shouldBe existingItem

        // Third rule should be permit everything
        configs[2].status shouldBe NotificationConfiguration.ENABLE_STR
        configs[2].rules shouldBe NotificationConfiguration.DEFAULT_RULE_STRING
    }

    @Test
    fun `initVirtualCardsNotifications() should preserve existing rules on reinitialisation`() {
        val existingItem = NotificationFilterItem(
            name = "vcService",
            status = NotificationConfiguration.DISABLE_STR,
            rules = "{\"==\":[{\"var\":\"meta.fundingSourceId\"}, \"ignored-funding-source-id\"]}",
        )

        val initialConfig = NotificationConfiguration(configs = listOf(existingItem))
            .initVirtualCardsNotifications()

        val config = initialConfig.initVirtualCardsNotifications()

        val configs = config.configs

        configs shouldHaveSize 3

        // First rule should be to exclude unrecognized notifications
        configs[0].status shouldBe NotificationConfiguration.DISABLE_STR
        configs[0].rules shouldBe "{\"!=\":[{\"var\":\"meta.type\"},\"fundingSourceChanged\"]}"

        // Second rule should match existing entry
        configs[1] shouldBe existingItem

        // Third rule should be permit everything
        configs[2].status shouldBe NotificationConfiguration.ENABLE_STR
        configs[2].rules shouldBe NotificationConfiguration.DEFAULT_RULE_STRING
    }

    @Test
    fun `enabling funding source when not disabled has no effect`() {
        val initialConfig = NotificationConfiguration(configs = listOf())
            .initVirtualCardsNotifications()

        val config = initialConfig.setVirtualCardsNotificationsForFundingSource(
            fundingSourceId = "funding-source-id",
            enabled = true,
        )

        config shouldBe initialConfig
    }

    fun `disabling funding source when not disabled adds rule`() {
        val initialConfig = NotificationConfiguration(configs = listOf())
            .initVirtualCardsNotifications()

        val fundingSourceId = "funding-source-id"
        val config = initialConfig.setVirtualCardsNotificationsForFundingSource(
            fundingSourceId = fundingSourceId,
            enabled = false,
        )

        val expectedItem = NotificationFilterItem(
            name = "vcService",
            status = NotificationConfiguration.DISABLE_STR,
            rules = "{\"==\":[{\"var\":\"meta.fundingSourceId\"},\"$fundingSourceId\"]}",
        )

        config.configs shouldHaveSize 3

        config.configs[0] shouldBe initialConfig.configs[0]
        config.configs[1] shouldBe expectedItem
        config.configs[2] shouldBe initialConfig.configs[1]
    }

    @Test
    fun `disabling funding source id when already disabled has no effect`() {
        val fundingSourceId = "funding-source-id"

        val initialConfig = NotificationConfiguration(configs = listOf())
            .initVirtualCardsNotifications()
            .setVirtualCardsNotificationsForFundingSource(fundingSourceId = fundingSourceId, enabled = false)

        val config = initialConfig.setVirtualCardsNotificationsForFundingSource(
            fundingSourceId = fundingSourceId,
            enabled = false,
        )

        config shouldBe initialConfig
    }

    @Test
    fun `enabling funding source when disabled removes disable rule`() {
        val fundingSourceId = "funding-source-id"

        val initialConfig = NotificationConfiguration(configs = listOf())
            .initVirtualCardsNotifications()
            .setVirtualCardsNotificationsForFundingSource(fundingSourceId = fundingSourceId, enabled = false)

        val config = initialConfig.setVirtualCardsNotificationsForFundingSource(
            fundingSourceId = fundingSourceId,
            enabled = true,
        )

        config.configs shouldHaveSize 2

        config.configs[0] shouldBe initialConfig.configs[0]
        config.configs[1] shouldBe initialConfig.configs[2]
    }

    @Test
    fun `disabling funding source preserves existing rule`() {
        val fundingSourceId1 = "funding-source-id-1"
        val fundingSourceId2 = "funding-source-id-2"
        val fundingSourceType = FundingSourceType.BANK_ACCOUNT

        val initialConfig = NotificationConfiguration(configs = listOf())
            .initVirtualCardsNotifications()
            .setVirtualCardsNotificationsForFundingSource(fundingSourceId = fundingSourceId1, enabled = false)
            .setVirtualCardsNotificationsForFundingSourceType(fundingSourceType = fundingSourceType, enabled = false)

        val config = initialConfig.setVirtualCardsNotificationsForFundingSource(
            fundingSourceId = fundingSourceId2,
            enabled = false,
        )

        val expectedItem = NotificationFilterItem(
            name = "vcService",
            status = NotificationConfiguration.DISABLE_STR,
            rules = "{\"==\":[{\"var\":\"meta.fundingSourceId\"},\"$fundingSourceId2\"]}",
        )

        config.configs shouldHaveSize 5

        config.configs[0] shouldBe initialConfig.configs[0]
        config.configs[4] shouldBe initialConfig.configs[3]

        // Not really order dependent but easiest to verify this way
        config.configs[1] shouldBe initialConfig.configs[1]
        config.configs[2] shouldBe initialConfig.configs[2]
        config.configs[3] shouldBe expectedItem
    }

    @Test
    fun `enabling funding source preserves existing rules`() {
        val fundingSourceId1 = "funding-source-id-1"
        val fundingSourceId2 = "funding-source-id-2"
        val fundingSourceType = FundingSourceType.CREDIT_CARD

        val initialConfig = NotificationConfiguration(configs = listOf())
            .initVirtualCardsNotifications()
            .setVirtualCardsNotificationsForFundingSource(fundingSourceId = fundingSourceId1, enabled = false)
            .setVirtualCardsNotificationsForFundingSource(fundingSourceId = fundingSourceId2, enabled = false)
            .setVirtualCardsNotificationsForFundingSourceType(fundingSourceType = fundingSourceType, enabled = false)

        val config = initialConfig.setVirtualCardsNotificationsForFundingSource(
            fundingSourceId = fundingSourceId2,
            enabled = true,
        )

        config.configs shouldHaveSize 4

        config.configs[0] shouldBe initialConfig.configs[0]
        config.configs[3] shouldBe initialConfig.configs[4]

        // Not really order dependent but easiest to verify this way
        config.configs[1] shouldBe initialConfig.configs[1]
        config.configs[2] shouldBe initialConfig.configs[3]
    }

    @Test
    fun `isVirtualCardsNotificationForFundingSourceIdEnabled() should return true for initial configuration`() {
        val config = NotificationConfiguration(configs = listOf())
            .initVirtualCardsNotifications()

        config.isVirtualCardsNotificationForFundingSourceIdEnabled(fundingSourceId = "funding-source-id") shouldBe true
    }

    @Test
    fun `isVirtualCardsNotificationForFundingSourceIdEnabled() should return false if disabled`() {
        val fundingSourceId = "funding-source-id"

        val config = NotificationConfiguration(configs = listOf())
            .initVirtualCardsNotifications()
            .setVirtualCardsNotificationsForFundingSource(fundingSourceId = fundingSourceId, enabled = false)

        config.isVirtualCardsNotificationForFundingSourceIdEnabled(fundingSourceId = "funding-source-id") shouldBe false
    }

    @Test
    fun `isVirtualCardsNotificationForFundingSourceIdEnabled() should return true if other id is disabled`() {
        val fundingSourceId1 = "funding-source-id-1"
        val fundingSourceId2 = "funding-source-id-2"

        val config = NotificationConfiguration(configs = listOf())
            .initVirtualCardsNotifications()
            .setVirtualCardsNotificationsForFundingSource(fundingSourceId = fundingSourceId1, enabled = false)

        config.isVirtualCardsNotificationForFundingSourceIdEnabled(fundingSourceId = fundingSourceId2) shouldBe true
    }

    @Test
    fun `isVirtualCardsNotificationForFundingSourceIdEnabled() should return false for multiple disabled ids`() {
        val fundingSourceId1 = "funding-source-id-1"
        val fundingSourceId2 = "funding-source-id-2"

        val config = NotificationConfiguration(configs = listOf())
            .initVirtualCardsNotifications()
            .setVirtualCardsNotificationsForFundingSource(fundingSourceId = fundingSourceId1, enabled = false)
            .setVirtualCardsNotificationsForFundingSource(fundingSourceId = fundingSourceId2, enabled = false)

        config.isVirtualCardsNotificationForFundingSourceIdEnabled(fundingSourceId = fundingSourceId1) shouldBe false
        config.isVirtualCardsNotificationForFundingSourceIdEnabled(fundingSourceId = fundingSourceId2) shouldBe false
    }

    @Test
    fun `enabling funding source type when not disabled has no effect`() {
        val initialConfig = NotificationConfiguration(configs = listOf())
            .initVirtualCardsNotifications()

        val config = initialConfig.setVirtualCardsNotificationsForFundingSourceType(
            fundingSourceType = FundingSourceType.BANK_ACCOUNT,
            enabled = true,
        )

        config shouldBe initialConfig
    }

    @Test
    fun `disabling funding source type when not disabled adds rule`() {
        val initialConfig = NotificationConfiguration(configs = listOf())
            .initVirtualCardsNotifications()

        val config = initialConfig.setVirtualCardsNotificationsForFundingSourceType(
            fundingSourceType = FundingSourceType.BANK_ACCOUNT,
            enabled = false,
        )

        val fundingSourceTypeString = FundingSourceType.BANK_ACCOUNT.toString()

        val expectedItem = NotificationFilterItem(
            name = "vcService",
            status = NotificationConfiguration.DISABLE_STR,
            rules = "{\"==\":[{\"var\":\"meta.fundingSourceType\"}," + "\"$fundingSourceTypeString\"]}",
        )

        config.configs shouldHaveSize 3

        config.configs[0] shouldBe initialConfig.configs[0]
        config.configs[1] shouldBe expectedItem
        config.configs[2] shouldBe initialConfig.configs[1]
    }

    @Test
    fun `disabling funding source type when already disabled has no effect`() {
        val fundingSourceType = FundingSourceType.CREDIT_CARD

        val initialConfig = NotificationConfiguration(configs = listOf())
            .initVirtualCardsNotifications()
            .setVirtualCardsNotificationsForFundingSourceType(fundingSourceType = fundingSourceType, enabled = false)

        val config = initialConfig.setVirtualCardsNotificationsForFundingSourceType(
            fundingSourceType = fundingSourceType,
            enabled = false,
        )

        config shouldBe initialConfig
    }

    @Test
    fun `enabling funding source type when disabled removes disable rule`() {
        val fundingSourceType = FundingSourceType.BANK_ACCOUNT

        val initialConfig = NotificationConfiguration(configs = listOf())
            .initVirtualCardsNotifications()
            .setVirtualCardsNotificationsForFundingSourceType(fundingSourceType = fundingSourceType, enabled = false)

        val config = initialConfig.setVirtualCardsNotificationsForFundingSourceType(
            fundingSourceType = fundingSourceType,
            enabled = true,
        )

        config.configs shouldHaveSize 2

        config.configs[0] shouldBe initialConfig.configs[0]
        config.configs[1] shouldBe initialConfig.configs[2]
    }

    @Test
    fun `disabling funding source type preserves existing rules`() {
        val fundingSourceId = "funding-source-id-1"
        val fundingSourceType = FundingSourceType.BANK_ACCOUNT

        val initialConfig = NotificationConfiguration(configs = listOf())
            .initVirtualCardsNotifications()
            .setVirtualCardsNotificationsForFundingSourceType(fundingSourceType = fundingSourceType, enabled = false)
            .setVirtualCardsNotificationsForFundingSource(fundingSourceId = fundingSourceId, enabled = false)

        val config = initialConfig.setVirtualCardsNotificationsForFundingSourceType(
            fundingSourceType = FundingSourceType.CREDIT_CARD,
            enabled = false,
        )

        val fundingSourceTypeString = FundingSourceType.CREDIT_CARD.toString()
        val expectedItem = NotificationFilterItem(
            name = "vcService",
            status = NotificationConfiguration.DISABLE_STR,
            rules = "{\"==\":[{\"var\":\"meta.fundingSourceType\"},\"$fundingSourceTypeString\"]}",
        )

        config.configs shouldHaveSize 5

        config.configs[0] shouldBe initialConfig.configs[0]
        config.configs[4] shouldBe initialConfig.configs[3]

        // Not really order dependent but easiest to verify this way
        config.configs[1] shouldBe initialConfig.configs[1]
        config.configs[2] shouldBe initialConfig.configs[2]
        config.configs[3] shouldBe expectedItem
    }

    @Test
    fun `enabling funding source type preserves existing rules`() {
        val fundingSourceType = FundingSourceType.BANK_ACCOUNT
        val fundingSourceId1 = "funding-source-id-1"
        val fundingSourceId2 = "funding-source-id-2"

        val initialConfig = NotificationConfiguration(configs = listOf())
            .initVirtualCardsNotifications()
            .setVirtualCardsNotificationsForFundingSource(fundingSourceId = fundingSourceId1, enabled = false)
            .setVirtualCardsNotificationsForFundingSource(fundingSourceId = fundingSourceId2, enabled = false)
            .setVirtualCardsNotificationsForFundingSourceType(fundingSourceType = fundingSourceType, enabled = false)

        val config = initialConfig.setVirtualCardsNotificationsForFundingSourceType(
            fundingSourceType = fundingSourceType,
            enabled = true,
        )

        config.configs shouldHaveSize 4

        config.configs[0] shouldBe initialConfig.configs[0]
        config.configs[3] shouldBe initialConfig.configs[4]

        // Not really order dependent but easiest to verify this way
        config.configs[1] shouldBe initialConfig.configs[1]
        config.configs[2] shouldBe initialConfig.configs[2]
    }

    @Test
    fun `isVirtualCardsNotificationForFundingSourceTypeEnabled() should return true for initial configuration`() {
        val config = NotificationConfiguration(configs = listOf())
            .initVirtualCardsNotifications()

        config.isVirtualCardsNotificationForFundingSourceTypeEnabled(fundingSourceType = FundingSourceType.BANK_ACCOUNT) shouldBe true
    }

    @Test
    fun `isVirtualCardsNotificationForFundingSourceTypeEnabled() should return false if disabled`() {
        val fundingSourceType = FundingSourceType.CREDIT_CARD

        val config = NotificationConfiguration(configs = listOf())
            .initVirtualCardsNotifications()
            .setVirtualCardsNotificationsForFundingSourceType(
                fundingSourceType = fundingSourceType,
                enabled = false,
            )

        config.isVirtualCardsNotificationForFundingSourceTypeEnabled(fundingSourceType = fundingSourceType) shouldBe false
    }

    @Test
    fun `isVirtualCardsNotificationForFundingSourceTypeEnabled() should return true if other type is disabled`() {
        val fundingSourceType1 = FundingSourceType.CREDIT_CARD
        val fundingSourceType2 = FundingSourceType.BANK_ACCOUNT

        val config = NotificationConfiguration(configs = listOf())
            .initVirtualCardsNotifications()
            .setVirtualCardsNotificationsForFundingSourceType(fundingSourceType = fundingSourceType1, enabled = false)

        config.isVirtualCardsNotificationForFundingSourceTypeEnabled(fundingSourceType = fundingSourceType2) shouldBe true
    }

    @Test
    fun `isVirtualCardsNotificationForFundingSourceTypeEnabled() should return false for multiple disabled types`() {
        val fundingSourceType1 = FundingSourceType.CREDIT_CARD
        val fundingSourceType2 = FundingSourceType.BANK_ACCOUNT

        val config = NotificationConfiguration(configs = listOf())
            .initVirtualCardsNotifications()
            .setVirtualCardsNotificationsForFundingSourceType(fundingSourceType = fundingSourceType1, enabled = false)
            .setVirtualCardsNotificationsForFundingSourceType(fundingSourceType = fundingSourceType2, enabled = false)

        config.isVirtualCardsNotificationForFundingSourceTypeEnabled(fundingSourceType = fundingSourceType1) shouldBe false
        config.isVirtualCardsNotificationForFundingSourceTypeEnabled(fundingSourceType = fundingSourceType2) shouldBe false
    }
}
