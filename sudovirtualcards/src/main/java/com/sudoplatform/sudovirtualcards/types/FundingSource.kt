/*
 * Copyright © 2023 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards.types

import android.os.Parcelable
import com.sudoplatform.sudovirtualcards.types.BankAccountFundingSource.BankAccountType
import kotlinx.parcelize.Parcelize
import java.util.Date

/**
 * Representation of the common attributes of a funding source used in the Sudo Platform
 * Virtual Cards SDK.
 *
 * @property id [String] Identifier of the funding source.
 * @property owner [String] Identifier of the user that owns the funding source.
 * @property version [Int] Current version of the funding source.
 * @property createdAt [Date] Date when the funding source was created.
 * @property updatedAt [Date] Date when the funding source was last updated.
 * @property state [FundingSourceState] Current state of the funding source.
 * @property currency [String] Billing currency of the funding source as a 3 character ISO 4217 currency code.
 * @property transactionVelocity [TransactionVelocity] Effective transaction velocity, if any, applied to
 *  virtual card transactions funded by this funding source. This is the combined result of all velocity
 *  policies (global and funding source specific) as at the time this funding source was retrieved.
 * @property type [FundingSourceType] The type of funding source.
 */
abstract class BaseFundingSource {
    abstract val id: String
    abstract val owner: String
    abstract val version: Int
    abstract val createdAt: Date
    abstract val updatedAt: Date
    abstract val state: FundingSourceState
    abstract val currency: String
    abstract val transactionVelocity: TransactionVelocity?
    abstract val type: FundingSourceType
}

sealed class FundingSource : BaseFundingSource(), Parcelable

/**
 * Representation of a credit card funding source used in the Sudo Platform Virtual Cards SDK.
 *
 * @property id See [BaseFundingSource.id].
 * @property owner See [BaseFundingSource.owner].
 * @property version See [BaseFundingSource.version].
 * @property createdAt See [BaseFundingSource.createdAt].
 * @property updatedAt See [BaseFundingSource.updatedAt].
 * @property state See [BaseFundingSource.state].
 * @property currency See [BaseFundingSource.currency].
 * @property transactionVelocity [BaseFundingSource.transactionVelocity].
 * @property type See [BaseFundingSource.type].
 * @property last4 [String] Last 4 digits of the credit card used as the funding source.
 * @property network [CreditCardNetwork] Payments network of the funding source.
 * @property cardType [CardType] The type of credit card used to fund.
 */
@Parcelize
data class CreditCardFundingSource(
    override val id: String,
    override val owner: String,
    override val version: Int,
    override val createdAt: Date,
    override val updatedAt: Date,
    override val state: FundingSourceState,
    override val currency: String,
    override val transactionVelocity: TransactionVelocity? = null,
    override val type: FundingSourceType = FundingSourceType.CREDIT_CARD,
    val last4: String,
    val network: CreditCardNetwork,
    val cardType: CardType,
) : FundingSource() {
    enum class CreditCardNetwork {
        AMEX,
        DINERS,
        DISCOVER,
        JCB,
        MASTERCARD,
        UNIONPAY,
        VISA,
        OTHER,

        /** Unknown network. Please check you have the correct (latest) version of this SDK. */
        UNKNOWN,
    }
}

/**
 * Representation of a bank account funding source used in the Sudo Platform Virtual Cards SDK.
 *
 * @property id See [BaseFundingSource.id].
 * @property owner See [BaseFundingSource.owner].
 * @property version See [BaseFundingSource.version].
 * @property createdAt See [BaseFundingSource.createdAt].
 * @property updatedAt See [BaseFundingSource.updatedAt].
 * @property state See [BaseFundingSource.state].
 * @property currency See [BaseFundingSource.currency].
 * @property transactionVelocity [BaseFundingSource.transactionVelocity].
 * @property type See [BaseFundingSource.type].
 * @property bankAccountType [BankAccountType] The type of bank account.
 * @property last4 [String] Last 4 digits of the bank account number.
 * @property institutionName [String] The name of the institution at which the bank account is held.
 * @property institutionLogo [InstitutionLogo] The Mime type and the Base64 encoded image data of the
 *  institution logo if any.
 */
@Parcelize
data class BankAccountFundingSource(
    override val id: String,
    override val owner: String,
    override val version: Int,
    override val createdAt: Date,
    override val updatedAt: Date,
    override val state: FundingSourceState,
    override val currency: String,
    override val transactionVelocity: TransactionVelocity? = null,
    override val type: FundingSourceType = FundingSourceType.BANK_ACCOUNT,
    val bankAccountType: BankAccountType,
    val last4: String,
    val institutionName: String,
    val institutionLogo: InstitutionLogo?,
) : FundingSource() {
    enum class BankAccountType {
        SAVING,
        CHECKING,

        /** Unknown bank account type. Please check you have the correct (latest) version of this SDK. */
        UNKNOWN,
    }
}

/**
 * Representation of a financial institution's logo.
 *
 * @property type [String] Mime type of the institution logo if any.
 * @property data [String] Base64 encoded image data of institution logo if any.
 */
@Parcelize
data class InstitutionLogo(
    val type: String,
    val data: String,
) : Parcelable

/**
 * Representation of an enumeration depicting the funding source state in the
 * Sudo Platform Virtual Cards SDK.
 */
enum class FundingSourceState {
    /** Funding source is active and can be used. */
    ACTIVE,

    /** Funding source is inactive and cannot be used. */
    INACTIVE,

    /** Funding source is active but may become inactive if user intervention does not occur. */
    REFRESH,

    /** Unknown state. Please check you have the correct (latest) version of this SDK. */
    UNKNOWN,
}
