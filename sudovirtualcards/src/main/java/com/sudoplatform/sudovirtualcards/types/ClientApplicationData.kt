package com.sudoplatform.sudovirtualcards.types

/**
 * Client application data required by providers for funding source setup and refresh operations.
 *
 * @property applicationName [String] Unique application name which maps to configuration data stored at the service.
 */
data class ClientApplicationData(
    val applicationName: String
)
