/*
 * Copyright © 2023 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards.types

import android.os.Parcelable
import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName
import com.sudoplatform.sudovirtualcards.SudoVirtualCardsClient
import com.sudoplatform.sudovirtualcards.signing.Signature
import kotlinx.parcelize.Parcelize

/**
 * Initializes [ProviderDefaults].
 */
object ProviderDefaults {
    /** Stripe Provider String. */
    const val stripeProvider = "stripe"
    /** Checkout Provider String. */
    const val checkoutProvider = "checkout"
    /** Associated supported version. */
    const val version = 1
    /** Configuration Type. */
    const val configurationType = "string"
}

/**
 * Representation of [ProviderCommonData] which provides common data
 * for all funding source provider based data.
 *
 * @property provider [String] Funding source provider.
 * @property version [Int] Associated supported version.
 * @property type [FundingSourceType] Funding source type.
 */
abstract class ProviderCommonData {
    abstract val provider: String
    abstract val version: Int
    abstract val type: FundingSourceType
}

sealed class ProviderProvisioningData : ProviderCommonData(), Parcelable

/**
 * Representation of a based provisioning data type used to provision a checkout funding source. The client must
 * be robust to receiving a ProvisioningData it does not expect.
 *
 * @property provider See [ProviderCommonData.provider].
 * @property version See [ProviderCommonData.version].
 * @property type See [ProviderCommonData.type].
 */
@Keep
@Parcelize
data class BaseProvisioningData(
    override val provider: String = ProviderDefaults.checkoutProvider,
    override val version: Int,
    override val type: FundingSourceType,
) : ProviderProvisioningData(), Parcelable

/**
 * Representation of [StripeCardProvisioningData] used to provision a stripe funding source.
 *
 * @property provider See [ProviderCommonData.provider].
 * @property version See [ProviderCommonData.version].
 * @property type See [ProviderCommonData.type].
 * @property intent [String] Intent of setup data.
 * @property clientSecret [String] Provider setup intent client secret.
 */
@Keep
@Parcelize
data class StripeCardProvisioningData(
    override val provider: String = ProviderDefaults.stripeProvider,
    override val version: Int,
    val intent: String,
    @SerializedName("client_secret")
    val clientSecret: String,
    override val type: FundingSourceType = FundingSourceType.CREDIT_CARD
) : ProviderProvisioningData()

/**
 * Representation of [CheckoutCardProvisioningData] used to provision a checkout card funding source.
 *
 * @property provider See [ProviderCommonData.provider].
 * @property version See [ProviderCommonData.version].
 * @property type See [ProviderCommonData.type].
 */
@Keep
@Parcelize
data class CheckoutCardProvisioningData(
    override val provider: String = ProviderDefaults.checkoutProvider,
    override val version: Int = ProviderDefaults.version,
    override val type: FundingSourceType = FundingSourceType.CREDIT_CARD,
) : ProviderProvisioningData()

/**
 * Representation of [CheckoutBankAccountProvisioningData] used to provision a checkout bank account funding source.
 *
 * @property provider See [ProviderCommonData.provider].
 * @property version See [ProviderCommonData.version].
 * @property type See [ProviderCommonData.type].
 * @property linkToken [String] Provider setup link token.
 * @property authorizationText [AuthorizationText] Array of different content type representations of the same agreement
 *  in the language most closely matching the language specified in the call to [SudoVirtualCardsClient.setupFundingSource].
 */
@Keep
@Parcelize
data class CheckoutBankAccountProvisioningData(
    override val provider: String = ProviderDefaults.checkoutProvider,
    override val version: Int = ProviderDefaults.version,
    override val type: FundingSourceType = FundingSourceType.BANK_ACCOUNT,
    val linkToken: String,
    val authorizationText: List<AuthorizationText>
) : ProviderProvisioningData()

/**
 * Backward compatible ProvisioningData - represents stripe
 */
@Deprecated("Use provider-specific provisioning data")
typealias ProvisioningData = StripeCardProvisioningData

sealed class ProviderCompletionData : ProviderCommonData(), Parcelable

/**
 * Representation of [StripeCardProviderCompletionData] sent to the provider and
 * used to complete the funding source creation.
 *
 * @property provider See [ProviderCommonData.provider].
 * @property version See [ProviderCommonData.version].
 * @property type See [ProviderCommonData.type].
 * @property paymentMethod [String] Specifies payment method bound to confirmed setup intent.
 */
@Keep
@Parcelize
data class StripeCardProviderCompletionData(
    override val provider: String = ProviderDefaults.stripeProvider,
    override val version: Int = ProviderDefaults.version,
    @SerializedName("payment_method")
    val paymentMethod: String,
    // Odd ordering for backwards compatibility
    override val type: FundingSourceType = FundingSourceType.CREDIT_CARD
) : ProviderCompletionData()

/**
 * Representation of [CheckoutCardProviderCompletionData] sent to the provider and
 * used to complete the funding source creation.
 *
 * @property provider See [ProviderCommonData.provider].
 * @property version See [ProviderCommonData.version].
 * @property type See [ProviderCommonData.type].
 * @property paymentToken [String] Specifies payment token associated with the funding source credit card.
 */
@Keep
@Parcelize
data class CheckoutCardProviderCompletionData(
    override val provider: String = ProviderDefaults.checkoutProvider,
    override val version: Int = ProviderDefaults.version,
    override val type: FundingSourceType = FundingSourceType.CREDIT_CARD,
    @SerializedName("payment_token")
    val paymentToken: String
) : ProviderCompletionData()

/**
 * Representation of [CheckoutBankAccountProviderCompletionData] sent to the provider and
 * used to complete the funding source creation.
 *
 * @property provider See [ProviderCommonData.provider].
 * @property version See [ProviderCommonData.version].
 * @property type See [ProviderCommonData.type].
 * @property publicToken [String] Token to be exchanged in order to perform bank account operations.
 * @property accountId [String] Identifier of the bank account to be used.
 * @property institutionId [String] Identifier of the institution at which account to be used is held.
 * @property authorizationText [AuthorizationText] Authorization text presented to and agreed to by the user.
 */
@Keep
@Parcelize
data class CheckoutBankAccountProviderCompletionData(
    override val provider: String = ProviderDefaults.checkoutProvider,
    override val version: Int = ProviderDefaults.version,
    override val type: FundingSourceType = FundingSourceType.BANK_ACCOUNT,
    val publicToken: String,
    val accountId: String,
    val institutionId: String,
    val authorizationText: AuthorizationText
) : ProviderCompletionData()

/**
 * Representation of [SerializedCheckoutBankAccountCompletionData] used to serialised completion data
 * required to complete the bank account funding source creation.
 *
 * @property provider See [ProviderCommonData.provider].
 * @property version See [ProviderCommonData.version].
 * @property type See [ProviderCommonData.type].
 * @property keyId [String] Identifier of the key used to sign the authorization text.
 * @property publicToken [String] See [CheckoutBankAccountProviderCompletionData.publicToken].
 * @property accountId [String] See [CheckoutBankAccountProviderCompletionData.accountId].
 * @property institutionId [String] See [CheckoutBankAccountProviderCompletionData.institutionId].
 * @property authorizationTextSignature The signature pertaining to the authorization text.
 */
@Keep
internal data class SerializedCheckoutBankAccountCompletionData(
    override val provider: String = ProviderDefaults.checkoutProvider,
    override val version: Int = ProviderDefaults.version,
    override val type: FundingSourceType = FundingSourceType.BANK_ACCOUNT,
    val keyId: String,
    val publicToken: String,
    val accountId: String,
    val institutionId: String,
    val authorizationTextSignature: Signature
) : ProviderCommonData()

sealed class ProviderUserInteractionData : ProviderCommonData(), Parcelable

/**
 * Representation of a based user interaction data type used to provision a checkout funding source. The client must
 * be robust to receiving a UserInteractionData it does not expect.
 *
 * @property provider See [ProviderCommonData.provider].
 * @property version See [ProviderCommonData.version].
 * @property type See [ProviderCommonData.type].
 */
@Keep
@Parcelize
data class BaseUserInteractionData(
    override val provider: String = ProviderDefaults.checkoutProvider,
    override val version: Int,
    override val type: FundingSourceType,
) : ProviderUserInteractionData()

/**
 * Returned when user interaction is required during the funding source setup operation
 * for checkout.com funding sources
 *
 * @property provider See [ProviderCommonData.provider].
 * @property version See [ProviderCommonData.version].
 * @property type See [ProviderCommonData.type].
 * @property redirectUrl [String] Mandatory URL which indicates where the user should go for additional interaction.
 */
@Keep
@Parcelize
data class CheckoutCardUserInteractionData(
    override val provider: String = ProviderDefaults.checkoutProvider,
    override val version: Int = ProviderDefaults.version,
    override val type: FundingSourceType = FundingSourceType.CREDIT_CARD,
    val redirectUrl: String
) : ProviderUserInteractionData()
