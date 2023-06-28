package com.sudoplatform.sudovirtualcards.types

/**
 * Sandbox data for a specific Plaid sandbox user's accounts
 * for inclusion in completion data when calling completeFundingSource
 * to test bank account funding source creation without full integration
 * of Plaid Link.
 *
 * @property accountMetadata [List<PlaidAccountMetadata>] Test accounts available for the test user specific to the sandboxGetPlaidData method
 * @property publicToken [String] Token for passing in completion data to completeFundingSource
 */
data class SandboxPlaidData(val accountMetdata: List<PlaidAccountMetadata>, val publicToken: String)
