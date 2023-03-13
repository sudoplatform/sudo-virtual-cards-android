/*
 * Copyright © 2023 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards.util

import android.content.Context
import androidx.annotation.Keep
import com.google.gson.GsonBuilder
import com.sudoplatform.sudovirtualcards.R
import java.io.InputStreamReader

/**
 * Locale related utility methods.
 */
class LocaleUtil private constructor() {

    companion object {
        /**
         * Convert from an ISO 3166 Alpha-3 country code to an ISO 3166 Alpha-2 country code.
         * For example "USA" -> "US"
         *
         * @param context Context needed to read resource files
         * @param alpha3CountryCode Tthe ISO 3166 Alpha-3 country code to convert
         * @return The ISO 3166 Alpha-2 country code or null if it could not be converted
         */
        @JvmStatic
        fun toCountryCodeAlpha2(context: Context, alpha3CountryCode: String): String? {
            if (alpha3CountryCode.trim().length != 3) {
                return alpha3CountryCode.trim()
            }

            val gson = GsonBuilder().create()
            val mapReader = InputStreamReader(context.resources.openRawResource(R.raw.svc_iso3166map))
            val iso3166Map = gson.fromJson(mapReader, Iso3166Map::class.java)
            return iso3166Map.cc3to2[alpha3CountryCode]
        }

        /**
         * Convert from an ISO 3166 Alpha-2 country code to an ISO 3166 Alpha-3 country code.
         * For example "US" -> "USA"
         *
         * @param context Context needed to read resource files
         * @param alpha2CountryCode The ISO 3166 Alpha-2 country code to convert
         * @return The ISO 3166 Alpha-3 country code or null if it could not be converted
         */
        @JvmStatic
        fun toCountryCodeAlpha3(context: Context, alpha2CountryCode: String): String? {
            if (alpha2CountryCode.trim().length != 2) {
                return alpha2CountryCode.trim()
            }

            val gson = GsonBuilder().create()
            val mapReader = InputStreamReader(context.resources.openRawResource(R.raw.svc_iso3166map))
            val iso3166Map = gson.fromJson(mapReader, Iso3166Map::class.java)
            return iso3166Map.cc2to3[alpha2CountryCode]
        }
    }

    /** In memory deserialized form of the svc_iso3166map.json file lookup table. */
    @Keep
    private data class Iso3166Map(
        val cc3to2: Map<String, String>,
        val cc2to3: Map<String, String>
    )
}
