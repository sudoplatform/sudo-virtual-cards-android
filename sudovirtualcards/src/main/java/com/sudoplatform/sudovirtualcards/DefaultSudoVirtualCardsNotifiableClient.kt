/*
 * Copyright © 2024 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards

import com.google.firebase.messaging.RemoteMessage
import com.sudoplatform.sudologging.AndroidUtilsLogDriver
import com.sudoplatform.sudologging.LogLevel
import com.sudoplatform.sudologging.Logger
import com.sudoplatform.sudonotification.SudoNotificationClient
import com.sudoplatform.sudonotification.types.NotificationMetaData
import com.sudoplatform.sudovirtualcards.logging.LogConstants
import com.sudoplatform.sudovirtualcards.notifications.FundingSourceChangedNotification
import com.sudoplatform.sudovirtualcards.notifications.VirtualCardsServiceNotification
import com.sudoplatform.sudovirtualcards.types.transformers.FundingSourceChangedNotificationTransformer
import com.sudoplatform.sudovirtualcards.util.Constants
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Default implementation of the [SudoVirtualCardsNotifiableClient] interface.
 *
 * @property notificationHandler [SudoVirtualCardsNotificationHandler] ...
 * @property logger [Logger] Errors and warnings will be logged here.
 */
internal class DefaultSudoVirtualCardsNotifiableClient(
    private val notificationHandler: SudoVirtualCardsNotificationHandler,
    private val logger: Logger =
        Logger(
            LogConstants.SUDOLOG_TAG,
            AndroidUtilsLogDriver(LogLevel.INFO),
        ),
) : SudoVirtualCardsNotifiableClient {
    /**
     * Virtual Cards service name key in sudoplatformconfig.json and notifications.
     */
    override val serviceName = Constants.SERVICE_NAME

    /**
     * Return Virtual Cards Service notification filter schema to [SudoNotificationClient].
     */
    override fun getSchema(): NotificationMetaData =
        SudoVirtualCardsNotificationMetaData(
            serviceName = Constants.SERVICE_NAME,
            schema =
                listOf(
                    SudoVirtualCardsNotificationSchemaEntry(
                        description = "Type of notification message",
                        fieldName = "meta.type",
                        type = "string",
                    ),
                    SudoVirtualCardsNotificationSchemaEntry(
                        description = "ID of funding source to match",
                        fieldName = "meta.fundingSourceId",
                        type = "string",
                    ),
                    SudoVirtualCardsNotificationSchemaEntry(
                        description = "Type of funding source to match",
                        fieldName = "meta.fundingSourceType",
                        type = "string",
                    ),
                ),
        )

    /**
     * Process [RemoteMessage].
     *
     * Determines the notification type and delegates further
     * to the application's handler.
     *
     * @param message [RemoteMessage] The remote message to process.
     */
    override fun processPayload(message: RemoteMessage) {
        logger.debug { "Received notification: ${message.data}" }

        val json = Json { ignoreUnknownKeys = true }

        @Serializable
        data class Sudoplatform(
            val servicename: String,
            val data: String,
        )

        val sudoplatform =
            try {
                json.decodeFromString<Sudoplatform>(message.data["sudoplatform"]!!)
            } catch (e: Exception) {
                logger.error { "Unable to decode Sudoplatform notification envelope ${e.message}" }
                return
            }

        // SudoNotificationClient will already have verified that this notification
        // matches our service name but best check.
        if (sudoplatform.servicename != Constants.SERVICE_NAME) {
            logger.error {
                "Unexpectedly handling notification for service " +
                    "${sudoplatform.servicename} when expecting only ${Constants.SERVICE_NAME}"
            }
            return
        }

        val notification =
            try {
                VirtualCardsServiceNotification.decodeFromString(sudoplatform.data)
            } catch (e: Exception) {
                logger.error { "Unable to decode VirtualCardsServiceNotification ${e.message}" }
                return
            }

        // Delegate handling to the registered application handler.
        //
        // Do not catch Exceptions thrown by the application handler.
        // Allow the app to fail so an application errors  can be
        // more easily identified and fixed.
        when (notification) {
            is FundingSourceChangedNotification ->
                this.notificationHandler.onFundingSourceChanged(
                    FundingSourceChangedNotificationTransformer.toEntity(notification),
                )
            else -> logger.error { "Received unexpected VirtualCardsServiceNotification" }
        }
    }
}
