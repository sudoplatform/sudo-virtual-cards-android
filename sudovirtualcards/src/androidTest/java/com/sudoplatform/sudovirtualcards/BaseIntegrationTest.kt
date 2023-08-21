/*
 * Copyright © 2023 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.sudoplatform.sudoentitlements.SudoEntitlementsClient
import com.sudoplatform.sudoentitlementsadmin.SudoEntitlementsAdminClient
import com.sudoplatform.sudoentitlementsadmin.types.Entitlement
import com.sudoplatform.sudoidentityverification.DefaultSudoIdentityVerificationClient
import com.sudoplatform.sudoidentityverification.types.inputs.VerifyIdentityInput
import com.sudoplatform.sudokeymanager.KeyManagerFactory
import com.sudoplatform.sudoprofiles.DefaultSudoProfilesClient
import com.sudoplatform.sudoprofiles.Sudo
import com.sudoplatform.sudouser.DefaultSudoUserClient
import com.sudoplatform.sudouser.TESTAuthenticationProvider
import com.sudoplatform.sudovirtualcards.simulator.SudoVirtualCardsSimulatorClient
import com.sudoplatform.sudovirtualcards.simulator.types.inputs.SimulateAuthorizationInput
import com.sudoplatform.sudovirtualcards.simulator.types.inputs.SimulateDebitInput
import com.sudoplatform.sudovirtualcards.simulator.types.inputs.SimulateRefundInput
import com.sudoplatform.sudovirtualcards.types.AuthorizationText
import com.sudoplatform.sudovirtualcards.types.CheckoutBankAccountProviderCompletionData
import com.sudoplatform.sudovirtualcards.types.CheckoutBankAccountProvisioningData
import com.sudoplatform.sudovirtualcards.types.ClientApplicationData
import com.sudoplatform.sudovirtualcards.types.FundingSource
import com.sudoplatform.sudovirtualcards.types.FundingSourceType
import com.sudoplatform.sudovirtualcards.types.ProvisionalVirtualCard
import com.sudoplatform.sudovirtualcards.types.StripeCardProvisioningData
import com.sudoplatform.sudovirtualcards.types.VirtualCard
import com.sudoplatform.sudovirtualcards.types.ProviderCompletionData
import com.sudoplatform.sudovirtualcards.types.VirtualCardsConfig
import com.sudoplatform.sudovirtualcards.types.inputs.CompleteFundingSourceInput
import com.sudoplatform.sudovirtualcards.types.inputs.CreditCardFundingSourceInput
import com.sudoplatform.sudovirtualcards.types.inputs.ProvisionVirtualCardInput
import com.sudoplatform.sudovirtualcards.types.inputs.SetupFundingSourceInput
import com.sudoplatform.sudovirtualcards.util.FundingSourceProviders
import com.sudoplatform.sudovirtualcards.util.CheckoutTokenWorker
import com.sudoplatform.sudovirtualcards.util.CreateBankAccountFundingSourceOptions
import com.sudoplatform.sudovirtualcards.util.LocaleUtil
import com.sudoplatform.sudovirtualcards.util.StripeIntentWorker
import com.sudoplatform.sudovirtualcards.util.CreateCardFundingSourceOptions
import io.kotlintest.matchers.numerics.shouldBeGreaterThan
import io.kotlintest.shouldBe
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.util.Calendar

/**
 * Test the operation of the [SudoVirtualCardsClient].
 */
abstract class BaseIntegrationTest {

    // We cache our SudoUserClient instance because it has an
    // embedded AWSAppSync instance of its own. Each AWSAppSync
    // instance registers a connectivity watcher call back but
    // never unregisters it. This quickly hits the limit of
    // such registered callbacks and thus limits the number of
    // tests we can have. A singleton user client that we reset
    // between tests resolves this issue.
    protected companion object {
        val context: Context = ApplicationProvider.getApplicationContext()
        val userClientInstance = DefaultSudoUserClient(context, "vc-client-test")
    }

    protected val userClient by lazy {
        userClientInstance.reset()
        userClientInstance
    }

    protected val sudoClient by lazy {
        val containerURI = Uri.fromFile(context.cacheDir)
        DefaultSudoProfilesClient(context, userClient, containerURI)
    }

    private val entitlementsClient by lazy {
        SudoEntitlementsClient.builder()
            .setContext(context)
            .setSudoUserClient(userClient)
            .build()
    }

    private val entitlementsAdminClient by lazy {
        val adminApiKey = readArgument("ADMIN_API_KEY", "api.key")
        SudoEntitlementsAdminClient.builder(context, adminApiKey).build()
    }

    private val identityVerificationClient by lazy {
        DefaultSudoIdentityVerificationClient(context, userClient)
    }

    protected val vcSimulatorClient by lazy {
        val adminApiKey = readArgument("ADMIN_API_KEY", "api.key")
        SudoVirtualCardsSimulatorClient.builder()
            .setContext(context)
            .setApiKey(adminApiKey)
            .build()
    }

    protected val keyManager by lazy {
        KeyManagerFactory(context).createAndroidKeyManager("vc-client-test")
    }

    private var fundingSourceProviders: FundingSourceProviders? = null

    private fun readTextFile(fileName: String): String {
        return context.assets.open(fileName).bufferedReader().use {
            it.readText().trim()
        }
    }

    private fun readArgument(argumentName: String, fallbackFileName: String?): String {
        println(InstrumentationRegistry.getArguments()).toString()
        val argumentValue = InstrumentationRegistry.getArguments().getString(argumentName)?.trim()
        if (argumentValue != null) {
            return argumentValue
        }
        if (fallbackFileName != null) {
            return readTextFile(fallbackFileName)
        }
        throw IllegalArgumentException("$argumentName property not found")
    }

    /** Returns the next calendar month. */
    protected fun expirationMonth(): Int {
        val calendar = Calendar.getInstance()
        return calendar.get(Calendar.MONTH) + 1
    }

    /** Returns the next calendar year. */
    protected fun expirationYear(): Int {
        val calendar = Calendar.getInstance()
        calendar.add(Calendar.YEAR, 1)
        return calendar.get(Calendar.YEAR)
    }

    protected suspend fun register() {
        userClient.isRegistered() shouldBe false

        val privateKey = readArgument("REGISTER_KEY", "register_key.private")
        val keyId = readArgument("REGISTER_KEY_ID", "register_key.id")

        val authProvider = TESTAuthenticationProvider(
            name = "vc-client-test",
            privateKey = privateKey,
            publicKey = null,
            keyManager = keyManager,
            keyId = keyId
        )

        userClient.registerWithAuthenticationProvider(authProvider, "vc-client-test")
    }

    protected suspend fun deregister() {
        userClient.deregister()
    }

    protected suspend fun signIn() {
        userClient.signInWithKey()
    }

    private suspend fun registerAndSignIn() {
        if (!userClient.isRegistered()) {
            register()
        }
        userClient.isRegistered() shouldBe true
        if (userClient.isSignedIn()) {
            userClient.getRefreshToken()?.let { userClient.refreshTokens(it) }
        } else {
            signIn()
        }
        userClient.isSignedIn() shouldBe true
    }

    protected suspend fun registerSignInAndEntitle(config: VirtualCardsConfig? = null) {
        registerAndSignIn()
        val externalId = entitlementsClient.getExternalId()
        val entitlements = mutableListOf(
            Entitlement("sudoplatform.sudo.max", "test", 3),
            Entitlement("sudoplatform.identity-verification.verifyIdentityUserEntitled", "test", 1),
            Entitlement("sudoplatform.virtual-cards.serviceUserEntitled", "test", 1),
            Entitlement("sudoplatform.virtual-cards.virtualCardMaxPerSudo", "test", 5),
            Entitlement("sudoplatform.virtual-cards.virtualCardProvisionUserEntitled", "test", 1),
            Entitlement("sudoplatform.virtual-cards.virtualCardTransactUserEntitled", "test", 1)
        )
        if (config != null) {
            if (config.bankAccountFundingSourceExpendableEnabled) {
                entitlements.add(
                    Entitlement("sudoplatform.virtual-cards.bankAccountFundingSourceExpendable", "test", 5)
                )
            }
        }
        entitlementsAdminClient.applyEntitlementsToUser(externalId, entitlements)
        entitlementsClient.redeemEntitlements()
    }

    protected suspend fun verifyTestUserIdentity() {
        val countryCodeAlpha3 = LocaleUtil.toCountryCodeAlpha3(context, TestData.VerifiedUser.country)
            ?: throw IllegalArgumentException("Unable to convert country code to ISO 3166 Alpha-3")

        val input = VerifyIdentityInput(
            firstName = TestData.VerifiedUser.firstName,
            lastName = TestData.VerifiedUser.lastName,
            address = TestData.VerifiedUser.addressLine1,
            city = TestData.VerifiedUser.city,
            state = TestData.VerifiedUser.state,
            postalCode = TestData.VerifiedUser.postalCode,
            country = countryCodeAlpha3,
            dateOfBirth = TestData.VerifiedUser.dateOfBirth
        )
        identityVerificationClient.verifyIdentity(input)
    }

    protected suspend fun createSudo(sudoInput: Sudo): Sudo {
        return sudoClient.createSudo(sudoInput)
    }

    protected suspend fun getOwnershipProof(sudo: Sudo): String {
        return sudoClient.getOwnershipProof(sudo, "sudoplatform.virtual-cards.virtual-card")
    }

    protected suspend fun retrieveVirtualCardsConfig(client: SudoVirtualCardsClient): VirtualCardsConfig? {
        registerAndSignIn()
        return client.getVirtualCardsConfig()
    }

    protected suspend fun createCardFundingSource(
        client: SudoVirtualCardsClient,
        input: CreditCardFundingSourceInput,
        options: CreateCardFundingSourceOptions
    ): FundingSource {
        val cardProviders = fundingSourceProviders ?: determineFundingSourceProviders(client)
        // Perform the funding source setup operation
        val setupInput = SetupFundingSourceInput(
            options.currency,
            FundingSourceType.CREDIT_CARD,
            ClientApplicationData(options.applicationName),
            options.supportedProviders
        )
        val provisionalFundingSource = client.setupFundingSource(setupInput)

        val provisionalData = provisionalFundingSource.provisioningData
        val completionData: ProviderCompletionData

        when (provisionalData.provider) {
            "stripe" -> {
                // Process stripe data
                cardProviders.apis.stripe ?: throw AssertionError("No stripe API but provisioning data is for stripe")
                val stripeIntentWorker = StripeIntentWorker(context, cardProviders.apis.stripe)
                completionData = stripeIntentWorker.confirmSetupIntent(
                    input,
                    (provisionalFundingSource.provisioningData as StripeCardProvisioningData).clientSecret
                )
            }
            "checkout" -> {
                // Process checkout data
                cardProviders.apis.checkout ?: throw AssertionError("No checkout API but provisioning data is for checkout")
                input.name ?: throw java.lang.AssertionError("Checkout requires cardholder name")

                val checkoutTokenWorker = CheckoutTokenWorker(cardProviders.apis.checkout)
                completionData = checkoutTokenWorker.generatePaymentToken(input)
            }
            else -> {
                throw AssertionError("Unsupported funding source type")
            }
        }

        // Perform the funding source completion operation
        val completeInput = CompleteFundingSourceInput(
            provisionalFundingSource.id,
            completionData,
            options.updateCardFundingSource
        )
        return client.completeFundingSource(completeInput)
    }

    protected suspend fun createBankAccountFundingSource(
        virtualCardsClient: SudoVirtualCardsClient,
        options: CreateBankAccountFundingSourceOptions?
    ): FundingSource {
        val setupInput = SetupFundingSourceInput(
            options?.currency ?: "USD",
            FundingSourceType.BANK_ACCOUNT,
            ClientApplicationData(options?.applicationName ?: "androidApplication"),
            options?.supportedProviders
        )
        val provisionalFundingSource = virtualCardsClient.setupFundingSource(setupInput)
        val provisioningData =
            provisionalFundingSource.provisioningData as CheckoutBankAccountProvisioningData

        val institutionId = "ins_109508" // Plaid Sandbox Bank Account - Platypus
        val username = options?.username ?: TestData.TestBankAccountUsername.customChecking

        val plaidSandboxData = virtualCardsClient.sandboxGetPlaidData(institutionId, username)

        val authorizationText = AuthorizationText(
            provisioningData.authorizationText[0].language,
            provisioningData.authorizationText[0].content,
            provisioningData.authorizationText[0].contentType,
            provisioningData.authorizationText[0].hash,
            provisioningData.authorizationText[0].hashAlgorithm
        )
        val accountMetadata = plaidSandboxData.accountMetadata[0]
        val checkoutInput = CompleteFundingSourceInput(
            provisionalFundingSource.id,
            CheckoutBankAccountProviderCompletionData(
                "checkout",
                1,
                FundingSourceType.BANK_ACCOUNT,
                plaidSandboxData.publicToken,
                accountMetadata.accountId,
                institutionId,
                authorizationText
            )
        )
        return virtualCardsClient.completeFundingSource(checkoutInput)
    }

    protected suspend fun provisionVirtualCard(client: SudoVirtualCardsClient, input: ProvisionVirtualCardInput): VirtualCard {
        val provisionalCard1 = client.provisionVirtualCard(input)
        var state = provisionalCard1.provisioningState

        return withTimeout(20_000L) {
            var card: VirtualCard? = null
            while (state == ProvisionalVirtualCard.ProvisioningState.PROVISIONING) {
                val provisionalCard2 = client.getProvisionalCard(provisionalCard1.id)
                if (provisionalCard2?.provisioningState == ProvisionalVirtualCard.ProvisioningState.COMPLETED) {
                    card = provisionalCard2.card
                } else {
                    delay(2_000L)
                }
                state = provisionalCard2?.provisioningState ?: ProvisionalVirtualCard.ProvisioningState.PROVISIONING
            }
            card ?: throw AssertionError("Provisioned card should not be null")
        }
    }

    protected suspend fun simulateTransactions(virtualCard: VirtualCard) {
        // Create an authorization for a purchase (debit)
        val merchant = vcSimulatorClient.getSimulatorMerchants().first()
        val originalAmount = 75
        val authInput = SimulateAuthorizationInput(
            cardNumber = virtualCard.cardNumber,
            amount = originalAmount,
            merchantId = merchant.id,
            securityCode = virtualCard.securityCode,
            expirationMonth = virtualCard.expiry.mm.toInt(),
            expirationYear = virtualCard.expiry.yyyy.toInt()
        )
        val authResponse = vcSimulatorClient.simulateAuthorization(authInput)
        with(authResponse) {
            isApproved shouldBe true
            amount shouldBe authInput.amount
            currency shouldBe merchant.currency
            createdAt.time shouldBeGreaterThan 0L
            updatedAt.time shouldBeGreaterThan 0L
            declineReason shouldBe null
        }

        // Create a debit for the authorized amount
        val debitInput = SimulateDebitInput(
            authorizationId = authResponse.id,
            amount = authInput.amount
        )
        val debitResponse = vcSimulatorClient.simulateDebit(debitInput)
        with(debitResponse) {
            id.isBlank() shouldBe false
            amount shouldBe authInput.amount
            currency shouldBe merchant.currency
            createdAt.time shouldBeGreaterThan 0L
            updatedAt.time shouldBeGreaterThan 0L
        }

        // Refund the debit
        val refundInput = SimulateRefundInput(
            debitId = debitResponse.id,
            amount = debitInput.amount
        )
        val refundResponse = vcSimulatorClient.simulateRefund(refundInput)
        with(refundResponse) {
            id.isBlank() shouldBe false
            amount shouldBe refundInput.amount
            currency shouldBe merchant.currency
            createdAt.time shouldBeGreaterThan 0L
            updatedAt.time shouldBeGreaterThan 0L
        }
    }

    protected suspend fun getProvidersList(client: SudoVirtualCardsClient): List<String> {
        val providers = determineFundingSourceProviders(client)
        val cardProviders = mutableListOf<String>()
        if (providers.checkoutCardEnabled) {
            cardProviders.add("checkout")
        }
        if (providers.stripeCardEnabled) {
            cardProviders.add("stripe")
        }
        return cardProviders
    }

    protected suspend fun isStripeEnabled(client: SudoVirtualCardsClient): Boolean {
        return determineFundingSourceProviders(client).stripeCardEnabled
    }

    protected suspend fun isCheckoutEnabled(client: SudoVirtualCardsClient): Boolean {
        val providers = determineFundingSourceProviders(client)
        return providers.checkoutCardEnabled || providers.checkoutBankAccountEnabled
    }
    protected suspend fun isCheckoutCardEnabled(client: SudoVirtualCardsClient): Boolean {
        return determineFundingSourceProviders(client).checkoutCardEnabled
    }
    protected suspend fun isCheckoutBankAccountEnabled(client: SudoVirtualCardsClient): Boolean {
        return determineFundingSourceProviders(client).checkoutBankAccountEnabled
    }
    protected suspend fun getProviderToUse(client: SudoVirtualCardsClient): String {
        if (isStripeEnabled(client)) {
            return "stripe"
        }
        if (isCheckoutCardEnabled(client)) {
            return "checkout"
        }
        throw AssertionError("No card funding source providers available")
    }

    private suspend fun determineFundingSourceProviders(client: SudoVirtualCardsClient): FundingSourceProviders {
        fundingSourceProviders = fundingSourceProviders ?: FundingSourceProviders.getFundingSourceProviders(client, context)
        return fundingSourceProviders ?: throw AssertionError("CardFundingSourceProviders not set")
    }
}
