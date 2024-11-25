/*
 * Copyright © 2024 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards

import com.sudoplatform.sudonotification.types.NotificationConfiguration
import com.sudoplatform.sudonotification.types.NotificationFilterItem
import com.sudoplatform.sudovirtualcards.notifications.FundingSourceChangedNotification
import com.sudoplatform.sudovirtualcards.types.FundingSourceType
import com.sudoplatform.sudovirtualcards.util.Constants
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// By default, disable all notifications we do not know how to handle
internal val DEFAULT_FIRST_RULE_STRING = JsonObject(
    mapOf(
        Pair(
            "!=",
            JsonArray(
                listOf(
                    JsonObject(
                        mapOf(
                            Pair("var", JsonPrimitive("meta.type")),
                        ),
                    ),
                    JsonPrimitive(FundingSourceChangedNotification.TYPE),
                ),
            ),
        ),
    ),
).toString()

// Disable notification types other than those we know how to handle
internal val DEFAULT_FIRST_RULE = NotificationFilterItem(
    name = Constants.SERVICE_NAME,
    status = NotificationConfiguration.DISABLE_STR,
    rules = DEFAULT_FIRST_RULE_STRING,
)

internal const val DEFAULT_LAST_RULE_STRING = NotificationConfiguration.DEFAULT_RULE_STRING

// Enable all otherwise unfiltered out notifications
internal val DEFAULT_LAST_RULE = NotificationFilterItem(
    name = Constants.SERVICE_NAME,
    status = NotificationConfiguration.ENABLE_STR,
    rules = DEFAULT_LAST_RULE_STRING,
)

internal fun isRuleMatchingFundingSourceId(rule: String?, fundingSourceId: String): Boolean {
    return isRuleMatchingSingleMeta(rule, "fundingSourceId", fundingSourceId)
}
internal fun isRuleMatchingFundingSourceType(rule: String?, fundingSourceType: FundingSourceType): Boolean {
    return isRuleMatchingSingleMeta(rule, "fundingSourceType", fundingSourceType.toString())
}

internal fun isRuleMatchingSingleMeta(rule: String?, metaName: String, metaValue: String): Boolean {
    if (rule == null) {
        return false
    }

    val jsonRules = Json.decodeFromString<JsonObject>(rule)
    val equality = jsonRules["=="]
    if (equality is JsonArray && equality.size == 2) {
        val lhs = equality[0]
        val rhs = equality[1]

        // "var meta.fundingSourceId == fundingSourceId
        if (lhs is JsonObject && rhs is JsonPrimitive && rhs.isString) {
            val v = lhs["var"]
            if (v is JsonPrimitive && v.isString && v.content == "meta.$metaName" && rhs.content == metaValue) {
                return true
            }
        }

        // "fundingSourceId == var meta.fundingSourceId
        else if (rhs is JsonObject && lhs is JsonPrimitive && lhs.isString) {
            val v = rhs["var"]
            if (v is JsonPrimitive && v.isString && v.content == "meta.$metaName" && lhs.content == metaValue) {
                return true
            }
        }
    }

    return false
}

/**
 * Extension function to ensure a [NotificationConfiguration] is initialized for
 * receipt of virtual cards service notifications.
 *
 * @return New NotificationConfiguration with updated rules
 */
fun NotificationConfiguration.initVirtualCardsNotifications(): NotificationConfiguration {
    val newConfigs = this.configs
        .filter { it.name != Constants.SERVICE_NAME }
        .toMutableList()

    val vcServiceConfigs = this.configs
        .filter { it.name == Constants.SERVICE_NAME }
        // Filter out any current or historic default rules.
        // We'll add current default rules back in
        .filter { it.rules != DEFAULT_FIRST_RULE_STRING && it.rules != DEFAULT_LAST_RULE_STRING }
        .toMutableList()

    newConfigs.add(DEFAULT_FIRST_RULE)
    newConfigs.addAll(vcServiceConfigs)
    newConfigs.add(DEFAULT_LAST_RULE)

    return NotificationConfiguration(
        configs = newConfigs.toList(),
    )
}

internal fun NotificationConfiguration.setVirtualCardsNotificationsForSingleMeta(
    metaName: String,
    metaValue: String,
    enabled: Boolean,
): NotificationConfiguration {
    // Start with any rules for other services
    val newRules = this.configs
        .filter { it.name != Constants.SERVICE_NAME }.toMutableList()

    // Then find all the virtual cards service rules except our defaults and
    // any existing rule matching this meta.
    val newVcServiceRules = this.configs
        .filter { it.name == Constants.SERVICE_NAME }
        // Filter out any current or historic default rules.
        // We'll add current default rules back in
        .filter { it.rules != DEFAULT_FIRST_RULE_STRING && it.rules != DEFAULT_LAST_RULE_STRING }
        // Filter out any rule specific to our meta name and value
        .filter { !isRuleMatchingSingleMeta(it.rules, metaName, metaValue) }

    // Re-add DEFAULT_FIRST_RULE
    newRules.add(DEFAULT_FIRST_RULE)

    // Re-add other vc service rules
    newRules.addAll(newVcServiceRules)

    // If we're disabling notifications for this meta value then
    // add an explicit rule for that
    if (!enabled) {
        val newJsonRule = JsonObject(
            mapOf(
                Pair(
                    "==",
                    JsonArray(
                        listOf(
                            JsonObject(
                                mapOf(
                                    Pair("var", JsonPrimitive("meta.$metaName")),
                                ),
                            ),
                            JsonPrimitive(metaValue),
                        ),
                    ),
                ),
            ),
        )

        newRules.add(
            NotificationFilterItem(
                name = Constants.SERVICE_NAME,
                status = NotificationConfiguration.DISABLE_STR,
                rules = newJsonRule.toString(),
            ),
        )
    }

    // Re-add the default catch all enabling rule
    newRules.add(DEFAULT_LAST_RULE)

    return NotificationConfiguration(
        configs = newRules.toList(),
    )
}

/**
 * Extension function to add rules to a [NotificationConfiguration] for enabling
 * or disabling virtual cards service notifications for a particular funding source id.
 *
 * Once all notification configurations across all Sudo platform SDKs have
 * been performed, call the
 * [com.sudoplatform.sudonotification.SudoNotificationClient.setNotificationConfiguration]
 * to set the full notification configuration for your application.
 *
 * @param fundingSourceId
 *      ID of funding source to set virtual cards service notification enablement for
 *
 * @param enabled
 *      Whether or not virtual cards service notifications are to be enabled or disabled for the
 *      funding source with the specified ID.
 *
 * @return New NotificationConfiguration with updated rules
 */
fun NotificationConfiguration.setVirtualCardsNotificationsForFundingSource(
    fundingSourceId: String,
    enabled: Boolean,
): NotificationConfiguration {
    return setVirtualCardsNotificationsForSingleMeta("fundingSourceId", fundingSourceId, enabled)
}

/**
 * Test whether or not virtual cards service notifications are enabled for a particular funding source
 *
 * @param fundingSourceId ID of funding source to test
 *
 * @return Whether or not virtual cards service notifications are enabled for the funding source with the specified ID
 */
fun NotificationConfiguration.isVirtualCardsNotificationForFundingSourceIdEnabled(fundingSourceId: String): Boolean {
    val fundingSourceRule = this.configs
        .filter { it.name == Constants.SERVICE_NAME }
        // Filter out any current or historic default rules.
        // We'll add current default rules back in
        .filter { it.rules != DEFAULT_FIRST_RULE_STRING && it.rules != DEFAULT_LAST_RULE_STRING }
        // Filter out any rule specific to our funding source id
        .find { isRuleMatchingFundingSourceId(it.rules, fundingSourceId) }

    // Notifications are enabled for this funding source id if either there
    // is no matching rule (because the default enables it) or if the
    // matching rule explicitly enables them.
    return fundingSourceRule == null ||
        fundingSourceRule.status == NotificationConfiguration.ENABLE_STR
}

/**
 * Extension function to add rules to a [NotificationConfiguration] for enabling
 * or disabling virtual cards service notifications for a particular funding source type.
 *
 * Once all notification configurations across all Sudo platform SDKs have
 * been performed, call the
 * [com.sudoplatform.sudonotification.SudoNotificationClient.setNotificationConfiguration]
 * to set the full notification configuration for your application.
 *
 * @param fundingSourceType: FundingSourceType
 *      type of funding source to set virtual cards service notification enablement for
 *
 * @param enabled
 *      Whether or not virtual cards service notifications are to be enabled or disabled for
 *      funding sources with the specified type.
 *
 * @return New NotificationConfiguration with updated rules
 */
fun NotificationConfiguration.setVirtualCardsNotificationsForFundingSourceType(
    fundingSourceType: FundingSourceType,
    enabled: Boolean,
): NotificationConfiguration {
    return setVirtualCardsNotificationsForSingleMeta("fundingSourceType", fundingSourceType.toString(), enabled)
}

/**
 * Test whether or not virtual cards service notifications are enabled for a particular funding source type
 *
 * @param fundingSourceType Type of funding source to test
 *
 * @return Whether or not virtual cards service notifications are enabled for the funding source with the specified type
 */
fun NotificationConfiguration.isVirtualCardsNotificationForFundingSourceTypeEnabled(fundingSourceType: FundingSourceType): Boolean {
    val fundingSourceRule = this.configs
        .filter { it.name == Constants.SERVICE_NAME }
        // Filter out any current or historic default rules.
        // We'll add current default rules back in
        .filter { it.rules != DEFAULT_FIRST_RULE_STRING && it.rules != DEFAULT_LAST_RULE_STRING }
        // Filter out any rule specific to our funding source id
        .find { isRuleMatchingFundingSourceType(it.rules, fundingSourceType) }

    // Notifications are enabled for this funding source type if either there
    // is no matching rule (because the default enables it) or if the
    // matching rule explicitly enables them.
    return fundingSourceRule == null ||
        fundingSourceRule.status == NotificationConfiguration.ENABLE_STR
}
