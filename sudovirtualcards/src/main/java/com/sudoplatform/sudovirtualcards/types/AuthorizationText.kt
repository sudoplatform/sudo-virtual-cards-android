/*
 * Copyright © 2023 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards.types

import android.os.Parcelable
import androidx.annotation.Keep
import com.sudoplatform.sudovirtualcards.SudoVirtualCardsClient
import kotlinx.parcelize.Parcelize

/**
 * Representation of text of an authorization to be presented to and agreed to
 * by the user when adding a bank account funding source. The AuthorizationText
 * presented must be submitted as part of the completion data on calling
 * [SudoVirtualCardsClient.completeFundingSource].
 *
 * @property language [String] RFC5646 language tag in which the text is written.
 * @property content [String] The text of the authorization.
 * @property contentType [String] The content type of the authorization (e.g. text/html, text/plain, ...).
 * @property hash [String] Hash of the content.
 * @property hashAlgorithm [String] Algorithm used to generate hash of the content. Only `SHA-256`
 *  is currently used.
 */
@Parcelize
@Keep
data class AuthorizationText(
    val language: String,
    val content: String,
    val contentType: String,
    val hash: String,
    val hashAlgorithm: String,
) : Parcelable
