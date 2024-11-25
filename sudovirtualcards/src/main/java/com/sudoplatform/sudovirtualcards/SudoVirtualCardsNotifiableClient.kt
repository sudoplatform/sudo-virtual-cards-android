/*
 * Copyright © 2024 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards

import android.content.Context
import com.sudoplatform.sudokeymanager.AndroidSQLiteStore
import com.sudoplatform.sudokeymanager.KeyManagerInterface
import com.sudoplatform.sudologging.AndroidUtilsLogDriver
import com.sudoplatform.sudologging.LogLevel
import com.sudoplatform.sudologging.Logger
import com.sudoplatform.sudonotification.types.NotifiableClient
import com.sudoplatform.sudovirtualcards.logging.LogConstants
import java.util.Objects

/**
 * Interface encapsulating the [NotifiableClient] used to handle
 * notifications from the Sudo Platform Virtual Cards Service.
 *
 * @sample com.sudoplatform.sudovirtualcards.samples.Samples.sudoVirtualCardsNotifiableClient
 */
interface SudoVirtualCardsNotifiableClient : NotifiableClient {
    companion object {
        /** Create a [Builder] for [SudoVirtualCardsNotifiableClient]. */
        fun builder() = Builder()
    }

    class Builder internal constructor() {
        private var context: Context? = null
        private var keyManager: KeyManagerInterface? = null
        private var logger: Logger =
            Logger(LogConstants.SUDOLOG_TAG, AndroidUtilsLogDriver(LogLevel.INFO))
        private var namespace: String = SudoVirtualCardsClient.DEFAULT_KEY_NAMESPACE
        private var databaseName: String = AndroidSQLiteStore.DEFAULT_DATABASE_NAME
        private var notificationHandler: SudoVirtualCardsNotificationHandler? = null

        /**
         * Provide the application context (required input).
         */
        fun setContext(context: Context) = also {
            this.context = context
        }

        /**
         * Provide the notification handler (required input).
         */
        fun setNotificationHandler(notificationHandler: SudoVirtualCardsNotificationHandler) = also {
            this.notificationHandler = notificationHandler
        }

        /**
         * Provide the implementation of the [KeyManagerInterface] used for key management and
         * cryptographic operations (optional input). If a value is not supplied a default
         * implementation will be used.
         */
        fun setKeyManager(keyManager: KeyManagerInterface) = also {
            this.keyManager = keyManager
        }

        /**
         * Provide the implementation of the [Logger] used for logging errors (optional input).
         * If a value is not supplied a default implementation will be used.
         */
        fun setLogger(logger: Logger) = also {
            this.logger = logger
        }

        /**
         * Provide the namespace to use for internal data and cryptographic keys. This should be unique
         * per client per app to avoid name conflicts between multiple clients. If a value is not supplied
         * a default value will be used.
         */
        fun setNamespace(namespace: String) = also {
            this.namespace = namespace
        }

        /**
         * Provide the database name to use for exportable key store database.
         */
        fun setDatabaseName(databaseName: String) = also {
            this.databaseName = databaseName
        }

        fun build(): SudoVirtualCardsNotifiableClient {
            Objects.requireNonNull(context, "context must be provided")
            Objects.requireNonNull(notificationHandler, "notificationHandler must be provided")

            return DefaultSudoVirtualCardsNotifiableClient(
                notificationHandler!!,
                logger,
            )
        }
    }
}
