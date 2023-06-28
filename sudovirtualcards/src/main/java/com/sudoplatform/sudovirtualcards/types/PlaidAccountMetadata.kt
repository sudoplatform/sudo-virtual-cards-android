package com.sudoplatform.sudovirtualcards.types

/**
 * Data describing a sandbox Plaid account returned by
 * the `sandboxGetPlaidData` method.
 *
 * @property accountId [String] Plaid account ID for passing in completion data to completeFundingSource
 * @property subtype [BankAccountFundingSource.BankAccountType] Account sub type
 */
data class PlaidAccountMetadata(val accountId: String, val subtype: BankAccountFundingSource.BankAccountType)
