/*
 * Copyright © 2024 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sudoplatform.sudokeymanager.KeyManagerFactory
import com.sudoplatform.sudovirtualcards.logging.LogConstants
import com.sudoplatform.sudovirtualcards.subscription.FundingSourceSubscriber
import com.sudoplatform.sudovirtualcards.subscription.Subscriber
import com.sudoplatform.sudovirtualcards.subscription.TransactionSubscriber
import com.sudoplatform.sudovirtualcards.types.AuthorizationText
import com.sudoplatform.sudovirtualcards.types.BankAccountFundingSource
import com.sudoplatform.sudovirtualcards.types.CardState
import com.sudoplatform.sudovirtualcards.types.CheckoutBankAccountProviderCompletionData
import com.sudoplatform.sudovirtualcards.types.CheckoutBankAccountProviderRefreshData
import com.sudoplatform.sudovirtualcards.types.CheckoutBankAccountProvisioningData
import com.sudoplatform.sudovirtualcards.types.ClientApplicationData
import com.sudoplatform.sudovirtualcards.types.CreditCardFundingSource
import com.sudoplatform.sudovirtualcards.types.FundingSource
import com.sudoplatform.sudovirtualcards.types.FundingSourceState
import com.sudoplatform.sudovirtualcards.types.FundingSourceType
import com.sudoplatform.sudovirtualcards.types.JsonValue
import com.sudoplatform.sudovirtualcards.types.ListAPIResult
import com.sudoplatform.sudovirtualcards.types.ProvisionalFundingSource
import com.sudoplatform.sudovirtualcards.types.ProvisionalVirtualCard
import com.sudoplatform.sudovirtualcards.types.SingleAPIResult
import com.sudoplatform.sudovirtualcards.types.SortOrder
import com.sudoplatform.sudovirtualcards.types.StripeCardProviderCompletionData
import com.sudoplatform.sudovirtualcards.types.StripeCardProvisioningData
import com.sudoplatform.sudovirtualcards.types.Transaction
import com.sudoplatform.sudovirtualcards.types.inputs.CompleteFundingSourceInput
import com.sudoplatform.sudovirtualcards.types.inputs.CreditCardFundingSourceInput
import com.sudoplatform.sudovirtualcards.types.inputs.FundingSourceFilterInput
import com.sudoplatform.sudovirtualcards.types.inputs.IdFilterInput
import com.sudoplatform.sudovirtualcards.types.inputs.ProvisionVirtualCardInput
import com.sudoplatform.sudovirtualcards.types.inputs.ProvisionalFundingSourceFilterInput
import com.sudoplatform.sudovirtualcards.types.inputs.ProvisionalFundingSourceStateFilterInput
import com.sudoplatform.sudovirtualcards.types.inputs.RefreshFundingSourceInput
import com.sudoplatform.sudovirtualcards.types.inputs.SetupFundingSourceInput
import com.sudoplatform.sudovirtualcards.types.inputs.UpdateVirtualCardInput
import com.sudoplatform.sudovirtualcards.types.inputs.VirtualCardFilterInput
import com.sudoplatform.sudovirtualcards.util.CreateBankAccountFundingSourceOptions
import com.sudoplatform.sudovirtualcards.util.CreateCardFundingSourceOptions
import io.kotlintest.fail
import io.kotlintest.matchers.numerics.shouldBeGreaterThan
import io.kotlintest.matchers.numerics.shouldBeGreaterThanOrEqual
import io.kotlintest.matchers.types.shouldBeInstanceOf
import io.kotlintest.shouldBe
import io.kotlintest.shouldNotBe
import io.kotlintest.shouldThrow
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import timber.log.Timber
import java.util.UUID
import java.util.logging.Logger
import kotlin.time.Duration.Companion.parseIsoString

/**
 * Test the operation of the [SudoVirtualCardsClient].
 */
@RunWith(AndroidJUnit4::class)
class SudoVirtualCardsClientIntegrationTest : BaseIntegrationTest() {
    private val verbose = false

    private lateinit var vcClient: SudoVirtualCardsClient

    @Before
    fun init() {
        Timber.plant(Timber.DebugTree())

        if (verbose) {
            Logger.getLogger("com.amazonaws").level = java.util.logging.Level.FINEST
            Logger.getLogger("org.apache.http").level = java.util.logging.Level.FINEST
        }
        Logger.getLogger(LogConstants.SUDOLOG_TAG).level = java.util.logging.Level.FINEST

        // Remove all keys from the Android Keystore so we can start with a clean slate.
        KeyManagerFactory(context).createAndroidKeyManager().removeAllKeys()
        sudoClient.generateEncryptionKey()
        vcClient =
            SudoVirtualCardsClient
                .builder()
                .setContext(context)
                .setSudoUserClient(userClient)
                .build()
    }

    @After
    fun fini() =
        runBlocking {
            if (userClient.isRegistered()) {
                deregister()
            }
            vcClient.reset()
            sudoClient.reset()
            userClient.reset()
            sudoClient.generateEncryptionKey()

            Timber.uprootAll()
        }

    @Test
    fun shouldThrowIfRequiredItemsNotProvidedToBuilder() {
        // All required items not provided
        shouldThrow<NullPointerException> {
            SudoVirtualCardsClient.builder().build()
        }

        // Context not provided
        shouldThrow<NullPointerException> {
            SudoVirtualCardsClient
                .builder()
                .setSudoUserClient(userClient)
                .build()
        }

        // SudoUserClient not provided
        shouldThrow<NullPointerException> {
            SudoVirtualCardsClient
                .builder()
                .setContext(context)
                .build()
        }
    }

    @Test
    fun shouldNotThrowIfTheRequiredItemsAreProvidedToBuilder() {
        SudoVirtualCardsClient
            .builder()
            .setContext(context)
            .setSudoUserClient(userClient)
            .build()
    }

    @Test
    fun shouldBeAbleToRegisterAndDeregister() =
        runBlocking {
            userClient.isRegistered() shouldBe false
            register()
            userClient.isRegistered() shouldBe true
            signIn()
            userClient.isSignedIn() shouldBe true
            deregister()
            userClient.isRegistered() shouldBe false
        }

    @Test
    fun setupFundingSourceShouldReturnProvisionalFundingSourceResult() =
        runBlocking {
            registerSignInAndEntitle()
            verifyTestUserIdentity()

            if (isStripeEnabled(vcClient)) {
                val setupStripeInput =
                    SetupFundingSourceInput(
                        "USD",
                        FundingSourceType.CREDIT_CARD,
                        ClientApplicationData("system-test-app"),
                        listOf("stripe"),
                    )
                val stripeProvisionalFundingSource = vcClient.setupFundingSource(setupStripeInput)

                with(stripeProvisionalFundingSource) {
                    id shouldNotBe null
                    owner shouldBe userClient.getSubject()
                    version shouldBe 1
                    state shouldBe ProvisionalFundingSource.ProvisioningState.PROVISIONING
                    provisioningData.provider shouldBe "stripe"
                    provisioningData.version shouldBe 1
                    val stripeProvisioningData = provisioningData as StripeCardProvisioningData
                    stripeProvisioningData.clientSecret shouldNotBe null
                    stripeProvisioningData.intent shouldNotBe null
                }
            }

            if (isCheckoutBankAccountEnabled(vcClient)) {
                val setupCheckoutBankAccountInput =
                    SetupFundingSourceInput(
                        "USD",
                        FundingSourceType.BANK_ACCOUNT,
                        ClientApplicationData("system-test-app"),
                        listOf("checkout"),
                    )
                val checkoutBankAccountProvisionalFundingSource = vcClient.setupFundingSource(setupCheckoutBankAccountInput)

                with(checkoutBankAccountProvisionalFundingSource) {
                    id shouldNotBe null
                    owner shouldBe userClient.getSubject()
                    version shouldBe 1
                    type shouldBe FundingSourceType.BANK_ACCOUNT
                    state shouldBe ProvisionalFundingSource.ProvisioningState.PROVISIONING
                    provisioningData.provider shouldBe "checkout"
                    provisioningData.version shouldBe 1
                    val checkoutProvisioningData = provisioningData as CheckoutBankAccountProvisioningData
                    checkoutProvisioningData shouldNotBe null
                }
            }
        }

    @Test
    fun setupFundingSourceShouldThrowWithUnsupportedCurrency() =
        runBlocking<Unit> {
            registerSignInAndEntitle()
            verifyTestUserIdentity()

            if (isStripeEnabled(vcClient)) {
                val stripeSetupInput =
                    SetupFundingSourceInput(
                        "AUD",
                        FundingSourceType.CREDIT_CARD,
                        ClientApplicationData("system-test-app"),
                        listOf("stripe"),
                    )
                shouldThrow<SudoVirtualCardsClient.FundingSourceException.UnsupportedCurrencyException> {
                    vcClient.setupFundingSource(stripeSetupInput)
                }
            }

            if (isCheckoutBankAccountEnabled(vcClient)) {
                val checkoutSetupInput =
                    SetupFundingSourceInput(
                        "AUD",
                        FundingSourceType.BANK_ACCOUNT,
                        ClientApplicationData("system-test-app"),
                        listOf("checkout"),
                    )
                shouldThrow<SudoVirtualCardsClient.FundingSourceException.UnsupportedCurrencyException> {
                    vcClient.setupFundingSource(checkoutSetupInput)
                }
            }
        }

    @Test
    fun completeFundingSourceShouldReturnFundingSourceResult() =
        runBlocking {
            val config = retrieveVirtualCardsConfig(vcClient)
            registerSignInAndEntitle(config)
            verifyTestUserIdentity()

            if (isStripeEnabled(vcClient)) {
                getProvidersList(vcClient).forEach {
                    val testCard = TestData.TestCards[it]?.get("Visa-No3DS-1") ?: throw AssertionError("Unable to locate test card")

                    val input =
                        CreditCardFundingSourceInput(
                            testCard.creditCardNumber,
                            expirationMonth(),
                            expirationYear(),
                            testCard.securityCode,
                            testCard.address.addressLine1,
                            testCard.address.addressLine2,
                            testCard.address.city,
                            testCard.address.state,
                            testCard.address.postalCode,
                            testCard.address.country,
                            TestData.VerifiedUser.fullName,
                        )

                    val fundingSource =
                        createCardFundingSource(
                            vcClient,
                            input,
                            CreateCardFundingSourceOptions(supportedProviders = listOf(it)),
                        )

                    when (fundingSource) {
                        is CreditCardFundingSource -> {
                            fundingSource.id shouldNotBe null
                            fundingSource.owner shouldBe userClient.getSubject()
                            fundingSource.version shouldBe 1
                            fundingSource.createdAt.time shouldBeGreaterThan 0L
                            fundingSource.updatedAt.time shouldBeGreaterThan 0L
                            fundingSource.state shouldBe FundingSourceState.ACTIVE
                            fundingSource.currency shouldBe "USD"
                            fundingSource.last4 shouldBe testCard.last4
                            fundingSource.network shouldBe CreditCardFundingSource.CreditCardNetwork.VISA
                        }
                        else -> {
                            fail("Unexpected FundingSource type")
                        }
                    }
                }
            }

            if (isCheckoutBankAccountEnabled(vcClient)) {
                vcClient.createKeysIfAbsent()

                val fundingSource =
                    createBankAccountFundingSource(
                        vcClient,
                        CreateBankAccountFundingSourceOptions(
                            supportedProviders = listOf("checkout"),
                            username = TestData.TestBankAccountUsername.customChecking,
                        ),
                    )

                when (fundingSource) {
                    is BankAccountFundingSource -> {
                        fundingSource.id shouldNotBe null
                        fundingSource.owner shouldBe userClient.getSubject()
                        fundingSource.version shouldBe 1
                        fundingSource.createdAt.time shouldBeGreaterThan 0L
                        fundingSource.updatedAt.time shouldBeGreaterThan 0L
                        fundingSource.state shouldBe FundingSourceState.ACTIVE
                        fundingSource.currency shouldBe "USD"
                        fundingSource.bankAccountType shouldBe BankAccountFundingSource.BankAccountType.CHECKING
                        fundingSource.institutionName shouldBe "First Platypus Bank"
                        fundingSource.unfundedAmount shouldBe null
                    }

                    else -> {
                        fail("Unexpected FundingSource type")
                    }
                }
            }
        }

    @Test
    fun completeFundingSourceShouldThrowWithIdentityVerificationNotVerifiedError() =
        runBlocking<Unit> {
            val config = retrieveVirtualCardsConfig(vcClient)
            registerSignInAndEntitle(config)
            verifyTestUserIdentity()

            if (isCheckoutBankAccountEnabled(vcClient)) {
                vcClient.createKeysIfAbsent()
                shouldThrow<SudoVirtualCardsClient.FundingSourceException.IdentityVerificationException> {
                    createBankAccountFundingSource(
                        vcClient,
                        CreateBankAccountFundingSourceOptions(
                            supportedProviders = listOf("checkout"),
                            username = TestData.TestBankAccountUsername.customIdentityMismatch,
                        ),
                    )
                }
            }
        }

    @Test
    fun completeFundingSourceShouldThrowWithProvisionalFundingSourceNotFound() =
        runBlocking<Unit> {
            registerSignInAndEntitle()
            verifyTestUserIdentity()

            if (isStripeEnabled(vcClient)) {
                val stripeInput =
                    CompleteFundingSourceInput(
                        UUID.randomUUID().toString(),
                        StripeCardProviderCompletionData(
                            "stripe",
                            1,
                            "paymentMethod",
                            FundingSourceType.CREDIT_CARD,
                        ),
                        null,
                    )
                shouldThrow<SudoVirtualCardsClient.FundingSourceException.ProvisionalFundingSourceNotFoundException> {
                    vcClient.completeFundingSource(stripeInput)
                }
            }

            if (isCheckoutBankAccountEnabled(vcClient)) {
                vcClient.createKeysIfAbsent()
                val checkoutInput =
                    CompleteFundingSourceInput(
                        UUID.randomUUID().toString(),
                        CheckoutBankAccountProviderCompletionData(
                            "checkout",
                            1,
                            FundingSourceType.BANK_ACCOUNT,
                            "publicToken",
                            "accountId",
                            "institutionId",
                            AuthorizationText(
                                "en-US",
                                "authorization-text-content",
                                "authorization-text-content-type",
                                "authorization-text-hash",
                                "authorization-test-hash-algorithm",
                            ),
                        ),
                        null,
                    )
                shouldThrow<SudoVirtualCardsClient.FundingSourceException.ProvisionalFundingSourceNotFoundException> {
                    vcClient.completeFundingSource(checkoutInput)
                }
            }
        }

    @Test
    fun completeFundingSourceShouldThrowWithCompletionDataInvalid() =
        runBlocking {
            registerSignInAndEntitle()
            verifyTestUserIdentity()

            if (isStripeEnabled(vcClient)) {
                val stripeSetupInput =
                    SetupFundingSourceInput(
                        "USD",
                        FundingSourceType.CREDIT_CARD,
                        ClientApplicationData("system-test-app"),
                        listOf("stripe"),
                    )
                val stripeProvisionalFundingSource = vcClient.setupFundingSource(stripeSetupInput)

                val stripeCompleteInput =
                    CompleteFundingSourceInput(
                        stripeProvisionalFundingSource.id,
                        StripeCardProviderCompletionData(
                            "stripe",
                            1,
                            "paymentMethod",
                            FundingSourceType.CREDIT_CARD,
                        ),
                        null,
                    )
                shouldThrow<SudoVirtualCardsClient.FundingSourceException> {
                    vcClient.completeFundingSource(stripeCompleteInput)
                }
            }

            if (isCheckoutBankAccountEnabled(vcClient)) {
                vcClient.createKeysIfAbsent()
                val checkoutSetupInput =
                    SetupFundingSourceInput(
                        "USD",
                        FundingSourceType.BANK_ACCOUNT,
                        ClientApplicationData("system-test-app"),
                        listOf("checkout"),
                    )
                val checkoutProvisionalFundingSource = vcClient.setupFundingSource(checkoutSetupInput)

                val provisioningData = checkoutProvisionalFundingSource.provisioningData as CheckoutBankAccountProvisioningData
                val checkoutCompleteInput =
                    CompleteFundingSourceInput(
                        checkoutProvisionalFundingSource.id,
                        CheckoutBankAccountProviderCompletionData(
                            "checkout",
                            1,
                            FundingSourceType.BANK_ACCOUNT,
                            "publicToken",
                            "accountId",
                            "institutionId",
                            provisioningData.authorizationText[0],
                        ),
                        null,
                    )
                shouldThrow<SudoVirtualCardsClient.FundingSourceException> {
                    vcClient.completeFundingSource(checkoutCompleteInput)
                }
            }
        }

    fun refreshFundingSourceShouldReturnBankAccountFundingSourceWhenRefreshNotRequired() =
        runBlocking {
            val config = retrieveVirtualCardsConfig(vcClient)
            registerSignInAndEntitle(config)
            verifyTestUserIdentity()

            if (isCheckoutBankAccountEnabled(vcClient)) {
                vcClient.createKeysIfAbsent()
                val fundingSource =
                    createBankAccountFundingSource(
                        vcClient,
                        CreateBankAccountFundingSourceOptions(
                            supportedProviders = listOf("checkout"),
                            username = TestData.TestBankAccountUsername.customChecking,
                        ),
                    )
                fundingSource shouldNotBe null

                val refreshData =
                    CheckoutBankAccountProviderRefreshData(
                        accountId = "accountId",
                        authorizationText =
                            AuthorizationText(
                                "en-US",
                                "authorization-text-content",
                                "authorization-text-content-type",
                                "authorization-text-hash",
                                "authorization-test-hash-algorithm",
                            ),
                    )
                val refreshInput =
                    RefreshFundingSourceInput(
                        fundingSource.id,
                        refreshData,
                        ClientApplicationData("androidApplication"),
                        "en-US",
                    )
                val refreshedFundingSource = vcClient.refreshFundingSource(refreshInput)
                refreshedFundingSource shouldBe fundingSource
            }
        }

    @Test
    fun refreshFundingSourceShouldReturnBankAccountFundingSourceForInactiveFundingSource() =
        runBlocking {
            val config = retrieveVirtualCardsConfig(vcClient)
            registerSignInAndEntitle(config)
            verifyTestUserIdentity()

            if (isCheckoutBankAccountEnabled(vcClient)) {
                vcClient.createKeysIfAbsent()
                val fundingSource =
                    createBankAccountFundingSource(
                        vcClient,
                        CreateBankAccountFundingSourceOptions(
                            supportedProviders = listOf("checkout"),
                            username = TestData.TestBankAccountUsername.customChecking,
                        ),
                    )
                fundingSource.state shouldBe FundingSourceState.ACTIVE

                val cancelledFundingSource = vcClient.cancelFundingSource(fundingSource.id)
                cancelledFundingSource.state shouldBe FundingSourceState.INACTIVE

                val refreshData =
                    CheckoutBankAccountProviderRefreshData(
                        accountId = "accountId",
                        authorizationText =
                            AuthorizationText(
                                "en-US",
                                "authorization-text-content",
                                "authorization-text-content-type",
                                "authorization-text-hash",
                                "authorization-test-hash-algorithm",
                            ),
                    )
                val refreshInput =
                    RefreshFundingSourceInput(
                        fundingSource.id,
                        refreshData,
                        ClientApplicationData("androidApplication"),
                        "en-US",
                    )
                val refreshedFundingSource = vcClient.refreshFundingSource(refreshInput)
                refreshedFundingSource shouldBe cancelledFundingSource
            }
        }

    @Test
    fun getFundingSourceShouldReturnFundingSourceResult() =
        runBlocking {
            registerSignInAndEntitle()
            verifyTestUserIdentity()

            getProvidersList(vcClient).forEach {
                val testCard = TestData.TestCards[it]?.get("Visa-No3DS-1") ?: throw AssertionError("Test card should not be null")
                val input =
                    CreditCardFundingSourceInput(
                        testCard.creditCardNumber,
                        expirationMonth(),
                        expirationYear(),
                        testCard.securityCode,
                        testCard.address.addressLine1,
                        testCard.address.addressLine2,
                        testCard.address.city,
                        testCard.address.state,
                        testCard.address.postalCode,
                        testCard.address.country,
                        TestData.VerifiedUser.fullName,
                    )
                val fundingSource =
                    createCardFundingSource(
                        vcClient,
                        input,
                        CreateCardFundingSourceOptions(supportedProviders = listOf(it)),
                    )
                fundingSource shouldNotBe null

                val retrievedFundingSource =
                    vcClient.getFundingSource(fundingSource.id)
                        ?: throw AssertionError("should not be null")

                when (retrievedFundingSource) {
                    is CreditCardFundingSource -> {
                        with(retrievedFundingSource) {
                            id shouldNotBe null
                            owner shouldBe userClient.getSubject()
                            version shouldBe 1
                            createdAt.time shouldBeGreaterThan 0L
                            updatedAt.time shouldBeGreaterThan 0L
                            state shouldBe FundingSourceState.ACTIVE
                            currency shouldBe "USD"
                            last4 shouldBe testCard.last4
                            network shouldBe CreditCardFundingSource.CreditCardNetwork.VISA
                        }
                    }
                    else -> {
                        fail("Unexpected FundingSource type")
                    }
                }
            }
        }

    @Test
    fun getFundingSourceShouldReturnNullForNonExistentId() =
        runBlocking {
            registerSignInAndEntitle()

            val retrievedFundingSource = vcClient.getFundingSource("NonExistentId")
            retrievedFundingSource shouldBe null
        }

    @Test
    fun listFundingSourcesShouldReturnSingleFundingSourceListOutputResult() =
        runBlocking {
            registerSignInAndEntitle()
            verifyTestUserIdentity()

            val providerToUse = getProviderToUse(vcClient)
            val testCard = TestData.TestCards[providerToUse]?.get("Visa-No3DS-1") ?: throw AssertionError("Test card should not be null")
            val input =
                CreditCardFundingSourceInput(
                    testCard.creditCardNumber,
                    expirationMonth(),
                    expirationYear(),
                    testCard.securityCode,
                    testCard.address.addressLine1,
                    testCard.address.addressLine2,
                    testCard.address.city,
                    testCard.address.state,
                    testCard.address.postalCode,
                    testCard.address.country,
                    TestData.VerifiedUser.fullName,
                )
            val fundingSource =
                createCardFundingSource(
                    vcClient,
                    input,
                    CreateCardFundingSourceOptions(),
                )
            fundingSource shouldNotBe null

            val listFundingSources = vcClient.listFundingSources()
            listFundingSources.items.isEmpty() shouldBe false
            listFundingSources.items.size shouldBe 1
            listFundingSources.nextToken shouldBe null

            val fundingSources = listFundingSources.items
            with(fundingSources[0] as CreditCardFundingSource) {
                id shouldNotBe null
                owner shouldBe userClient.getSubject()
                version shouldBe 1
                createdAt.time shouldBeGreaterThan 0L
                updatedAt.time shouldBeGreaterThan 0L
                state shouldBe FundingSourceState.ACTIVE
                currency shouldBe "USD"
                last4 shouldBe testCard.last4
                network shouldBe CreditCardFundingSource.CreditCardNetwork.VISA
            }
        }

    @Test
    fun listFundingSourcesShouldReturnMultipleFundingSourceListOutputResult() =
        runBlocking {
            registerSignInAndEntitle()
            verifyTestUserIdentity()

            val providers = getProvidersList(vcClient)
            val createdFundingSources = mutableListOf<FundingSource>()
            var fundingSourceCount = 0
            providers.forEach {
                if (it != "checkout") {
                    // TODO: skipping Visa-No3DS-1 because checkout does not conform to no-3DS ruling and requires user interaction
                    val vTestCard = TestData.TestCards[it]?.get("Visa-No3DS-1") ?: throw AssertionError("Test card should not be null")
                    val input1 =
                        CreditCardFundingSourceInput(
                            vTestCard.creditCardNumber,
                            expirationMonth(),
                            expirationYear(),
                            vTestCard.securityCode,
                            vTestCard.address.addressLine1,
                            vTestCard.address.addressLine2,
                            vTestCard.address.city,
                            vTestCard.address.state,
                            vTestCard.address.postalCode,
                            vTestCard.address.country,
                            TestData.VerifiedUser.fullName,
                        )
                    val fundingSource1 =
                        createCardFundingSource(
                            vcClient,
                            input1,
                            CreateCardFundingSourceOptions(supportedProviders = listOf(it)),
                        )
                    fundingSource1 shouldNotBe null
                    createdFundingSources.add(fundingSource1)
                    ++fundingSourceCount
                }

                if (it != "checkout") {
                    // TODO: skipping MC-no3DS because checkout does not conform to no-3DS ruling and requires user interaction
                    val mcTestCard = TestData.TestCards[it]?.get("MC-No3DS-1") ?: throw AssertionError("Test card should not be null")
                    val input2 =
                        CreditCardFundingSourceInput(
                            mcTestCard.creditCardNumber,
                            expirationMonth(),
                            expirationYear(),
                            mcTestCard.securityCode,
                            mcTestCard.address.addressLine1,
                            mcTestCard.address.addressLine2,
                            mcTestCard.address.city,
                            mcTestCard.address.state,
                            mcTestCard.address.postalCode,
                            mcTestCard.address.country,
                            TestData.VerifiedUser.fullName,
                        )
                    val fundingSource2 =
                        createCardFundingSource(
                            vcClient,
                            input2,
                            CreateCardFundingSourceOptions(supportedProviders = listOf(it)),
                        )
                    fundingSource2 shouldNotBe null
                    createdFundingSources.add(fundingSource2)
                    ++fundingSourceCount
                }
            }
            fundingSourceCount shouldBeGreaterThan 0
            // defaults (sortOrder is descending)
            val listFundingSources = vcClient.listFundingSources()
            listFundingSources.items.isEmpty() shouldBe false
            listFundingSources.items.size shouldBe fundingSourceCount
            listFundingSources.nextToken shouldBe null

            val fundingSources = listFundingSources.items
            var sortedFundingSources = createdFundingSources.sortedByDescending { it.updatedAt }
            for (i in 0 until fundingSourceCount) {
                val fundingSource = fundingSources[i] as CreditCardFundingSource
                val createdFundingSource = sortedFundingSources[i] as CreditCardFundingSource
                with(fundingSource) {
                    id shouldBe createdFundingSource.id
                    owner shouldBe createdFundingSource.owner
                    version shouldBe createdFundingSource.version
                    state shouldBe createdFundingSource.state
                    currency shouldBe createdFundingSource.currency
                    last4 shouldBe createdFundingSource.last4
                    network shouldBe createdFundingSource.network
                }
            }

            // ascending sort order
            val listAscendingFundingSources = vcClient.listFundingSources(null, SortOrder.ASC)
            listAscendingFundingSources.items.isEmpty() shouldBe false
            listAscendingFundingSources.items.size shouldBe fundingSourceCount
            listAscendingFundingSources.nextToken shouldBe null
            val ascendingFundingSources = listAscendingFundingSources.items
            sortedFundingSources = createdFundingSources.sortedBy { it.updatedAt }
            for (i in 0 until fundingSourceCount) {
                val fundingSource = ascendingFundingSources[i] as CreditCardFundingSource
                val createdFundingSource = sortedFundingSources[i] as CreditCardFundingSource
                with(fundingSource) {
                    id shouldBe createdFundingSource.id
                    owner shouldBe createdFundingSource.owner
                    version shouldBe createdFundingSource.version
                    state shouldBe createdFundingSource.state
                    currency shouldBe createdFundingSource.currency
                    last4 shouldBe createdFundingSource.last4
                    network shouldBe createdFundingSource.network
                }
            }

            // filtered
            val filterInput = FundingSourceFilterInput(IdFilterInput(eq = createdFundingSources[0].id))
            val listFilteredFundingSources = vcClient.listFundingSources(filterInput, SortOrder.ASC)
            listFilteredFundingSources.items.isEmpty() shouldBe false
            listFilteredFundingSources.items.size shouldBe 1
            listAscendingFundingSources.nextToken shouldBe null
            val filteredFundingSource = listFilteredFundingSources.items[0] as CreditCardFundingSource
            val createdFundingSource = createdFundingSources[0] as CreditCardFundingSource
            with(filteredFundingSource) {
                id shouldBe createdFundingSources[0].id
                owner shouldBe createdFundingSource.owner
                version shouldBe createdFundingSource.version
                state shouldBe createdFundingSource.state
                currency shouldBe createdFundingSource.currency
                last4 shouldBe createdFundingSource.last4
                network shouldBe createdFundingSource.network
            }
        }

    @Test
    fun cancelFundingSourceShouldReturnInactiveFundingSourceResult() =
        runBlocking {
            registerSignInAndEntitle()
            verifyTestUserIdentity()

            val providerToUse = getProviderToUse(vcClient)
            val testCard = TestData.TestCards[providerToUse]?.get("Visa-No3DS-1") ?: throw AssertionError("Test card should not be null")
            val input =
                CreditCardFundingSourceInput(
                    testCard.creditCardNumber,
                    expirationMonth(),
                    expirationYear(),
                    testCard.securityCode,
                    testCard.address.addressLine1,
                    testCard.address.addressLine2,
                    testCard.address.city,
                    testCard.address.state,
                    testCard.address.postalCode,
                    testCard.address.country,
                    TestData.VerifiedUser.fullName,
                )
            val fundingSource =
                createCardFundingSource(
                    vcClient,
                    input,
                    CreateCardFundingSourceOptions(supportedProviders = listOf(providerToUse)),
                )
            fundingSource shouldNotBe null

            when (val cancelledFundingSource = vcClient.cancelFundingSource(fundingSource.id)) {
                is CreditCardFundingSource -> {
                    with(cancelledFundingSource) {
                        id shouldNotBe null
                        owner shouldBe userClient.getSubject()
                        version shouldBe 2
                        createdAt.time shouldBeGreaterThan 0L
                        updatedAt.time shouldBeGreaterThan 0L
                        state shouldBe FundingSourceState.INACTIVE
                        currency shouldBe "USD"
                        last4 shouldBe testCard.last4
                        network shouldBe CreditCardFundingSource.CreditCardNetwork.VISA
                    }
                }
                else -> {
                    fail("Unexpected FundingSource type")
                }
            }
        }

    @Test
    fun cancelFundingSourceShouldThrowWithNonExistentId() =
        runBlocking<Unit> {
            registerSignInAndEntitle()

            shouldThrow<SudoVirtualCardsClient.FundingSourceException.FundingSourceNotFoundException> {
                vcClient.cancelFundingSource("NonExistentId")
            }
        }

    @Test
    fun reviewFundingSourceShouldReturnFundingSourceResult() =
        runBlocking {
            val config = retrieveVirtualCardsConfig(vcClient)
            registerSignInAndEntitle(config)
            verifyTestUserIdentity()
            if (isCheckoutBankAccountEnabled(vcClient)) {
                vcClient.createKeysIfAbsent()
                val fundingSource =
                    createBankAccountFundingSource(
                        vcClient,
                        CreateBankAccountFundingSourceOptions(
                            supportedProviders = listOf("checkout"),
                            username = TestData.TestBankAccountUsername.customChecking,
                        ),
                    )
                fundingSource shouldNotBe null
                fundingSource.state shouldBe FundingSourceState.ACTIVE

                when (val reviewedFundingSource = vcClient.reviewUnfundedFundingSource(fundingSource.id)) {
                    is BankAccountFundingSource -> {
                        with(reviewedFundingSource) {
                            id shouldNotBe null
                            owner shouldBe userClient.getSubject()
                            version shouldBe 1
                            createdAt.time shouldBeGreaterThan 0L
                            updatedAt.time shouldBeGreaterThan 0L
                            state shouldBe FundingSourceState.ACTIVE
                            flags shouldBe emptyList()
                            currency shouldBe "USD"
                        }
                    }

                    else -> {
                        fail("Unexpected FundingSource type")
                    }
                }
            }
        }

    @Test
    fun reviewFundingSourceShouldThrowWithNonExistentId() =
        runBlocking<Unit> {
            registerSignInAndEntitle()

            shouldThrow<SudoVirtualCardsClient.FundingSourceException.FundingSourceNotFoundException> {
                vcClient.reviewUnfundedFundingSource("NonExistentId")
            }
        }

    @Test
    fun listProvisionalFundingSourcesShouldReturnAllProvisional() =
        runBlocking {
            registerSignInAndEntitle()
            verifyTestUserIdentity()

            var stripeProvisionalFundingSource: ProvisionalFundingSource? = null
            var checkoutBankAccountProvisionalFundingSource: ProvisionalFundingSource? = null
            var provisionalFundingSourcesCount = 0

            if (isStripeEnabled(vcClient)) {
                val setupStripeInput =
                    SetupFundingSourceInput(
                        "USD",
                        FundingSourceType.CREDIT_CARD,
                        ClientApplicationData("system-test-app"),
                        listOf("stripe"),
                    )
                stripeProvisionalFundingSource = vcClient.setupFundingSource(setupStripeInput)

                with(stripeProvisionalFundingSource) {
                    id shouldNotBe null
                    owner shouldBe userClient.getSubject()
                    version shouldBe 1
                    state shouldBe ProvisionalFundingSource.ProvisioningState.PROVISIONING
                    provisioningData.provider shouldBe "stripe"
                    provisioningData.version shouldBe 1
                    val stripeProvisioningData = provisioningData as StripeCardProvisioningData
                    stripeProvisioningData.clientSecret shouldNotBe null
                    stripeProvisioningData.intent shouldNotBe null
                }
                ++provisionalFundingSourcesCount
            }

            if (isCheckoutBankAccountEnabled(vcClient)) {
                val setupCheckoutBankAccountInput =
                    SetupFundingSourceInput(
                        "USD",
                        FundingSourceType.BANK_ACCOUNT,
                        ClientApplicationData("system-test-app"),
                        listOf("checkout"),
                    )
                checkoutBankAccountProvisionalFundingSource = vcClient.setupFundingSource(setupCheckoutBankAccountInput)

                with(checkoutBankAccountProvisionalFundingSource) {
                    id shouldNotBe null
                    owner shouldBe userClient.getSubject()
                    version shouldBe 1
                    type shouldBe FundingSourceType.BANK_ACCOUNT
                    state shouldBe ProvisionalFundingSource.ProvisioningState.PROVISIONING
                    provisioningData.provider shouldBe "checkout"
                    provisioningData.version shouldBe 1
                    val checkoutProvisioningData = provisioningData as CheckoutBankAccountProvisioningData
                    checkoutProvisioningData shouldNotBe null
                }
                ++provisionalFundingSourcesCount
            }
            val provisionalFundingSources = vcClient.listProvisionalFundingSources()
            provisionalFundingSources.items.size shouldBe provisionalFundingSourcesCount
            if (stripeProvisionalFundingSource != null) {
                val returnedStripe = provisionalFundingSources.items.first { it.id == stripeProvisionalFundingSource.id }
                returnedStripe shouldNotBe null
                with(returnedStripe) {
                    id shouldNotBe null
                    owner shouldBe userClient.getSubject()
                    version shouldBe 1
                    last4 shouldNotBe null
                    state shouldBe ProvisionalFundingSource.ProvisioningState.PROVISIONING
                    provisioningData.provider shouldBe "stripe"
                    provisioningData.version shouldBe 1
                    val stripeProvisioningData = provisioningData as StripeCardProvisioningData
                    stripeProvisioningData.clientSecret shouldNotBe null
                    stripeProvisioningData.intent shouldNotBe null
                }
            }
            if (checkoutBankAccountProvisionalFundingSource != null) {
                val returnedCheckoutBankAccount =
                    provisionalFundingSources.items.first {
                        it.id == checkoutBankAccountProvisionalFundingSource.id
                    }
                returnedCheckoutBankAccount shouldNotBe null
                with(returnedCheckoutBankAccount) {
                    id shouldNotBe null
                    owner shouldBe userClient.getSubject()
                    version shouldBe 1
                    last4 shouldNotBe null
                    type shouldBe FundingSourceType.BANK_ACCOUNT
                    state shouldBe ProvisionalFundingSource.ProvisioningState.PROVISIONING
                    provisioningData.provider shouldBe "checkout"
                    provisioningData.version shouldBe 1
                    val checkoutProvisioningData = provisioningData as CheckoutBankAccountProvisioningData
                    checkoutProvisioningData shouldNotBe null
                }
            }
        }

    @Test
    fun listProvisionalFundingSourcesFiltersShouldBehaveCorrectly() =
        runBlocking<Unit> {
            registerSignInAndEntitle()
            verifyTestUserIdentity()

            var provisionalCount = 0
            val createdProvisionalFundingSources = mutableListOf<ProvisionalFundingSource>()

            if (isStripeEnabled(vcClient)) {
                val setupStripeInput =
                    SetupFundingSourceInput(
                        "USD",
                        FundingSourceType.CREDIT_CARD,
                        ClientApplicationData("system-test-app"),
                        listOf("stripe"),
                    )
                val stripeProvisionalFundingSource = vcClient.setupFundingSource(setupStripeInput)

                with(stripeProvisionalFundingSource) {
                    id shouldNotBe null
                    owner shouldBe userClient.getSubject()
                    version shouldBe 1
                    state shouldBe ProvisionalFundingSource.ProvisioningState.PROVISIONING
                    provisioningData.provider shouldBe "stripe"
                    provisioningData.version shouldBe 1
                    val stripeProvisioningData = provisioningData as StripeCardProvisioningData
                    stripeProvisioningData.clientSecret shouldNotBe null
                    stripeProvisioningData.intent shouldNotBe null
                }
                createdProvisionalFundingSources.add(stripeProvisionalFundingSource)
                ++provisionalCount
            }

            if (isCheckoutBankAccountEnabled(vcClient)) {
                val setupCheckoutBankAccountInput =
                    SetupFundingSourceInput(
                        "USD",
                        FundingSourceType.BANK_ACCOUNT,
                        ClientApplicationData("system-test-app"),
                        listOf("checkout"),
                    )
                val checkoutBankAccountProvisionalFundingSource = vcClient.setupFundingSource(setupCheckoutBankAccountInput)

                with(checkoutBankAccountProvisionalFundingSource) {
                    id shouldNotBe null
                    owner shouldBe userClient.getSubject()
                    version shouldBe 1
                    type shouldBe FundingSourceType.BANK_ACCOUNT
                    state shouldBe ProvisionalFundingSource.ProvisioningState.PROVISIONING
                    provisioningData.provider shouldBe "checkout"
                    provisioningData.version shouldBe 1
                    val checkoutProvisioningData = provisioningData as CheckoutBankAccountProvisioningData
                    checkoutProvisioningData shouldNotBe null
                }
                createdProvisionalFundingSources.add(checkoutBankAccountProvisionalFundingSource)
                ++provisionalCount
            }
            provisionalCount shouldBeGreaterThan 0
            // defaults (sortOrder is descending)
            val listProvisionalFundingSources = vcClient.listProvisionalFundingSources()
            listProvisionalFundingSources.items.size shouldBe provisionalCount
            val provisionalFundingSources = listProvisionalFundingSources.items
            var sortedProvisionalFundingSources = createdProvisionalFundingSources.sortedByDescending { it.updatedAt }
            for (i in 0 until provisionalCount) {
                val provisionalFundingSource = provisionalFundingSources[i]
                val createdProvisionalFundingSource = sortedProvisionalFundingSources[i]
                with(provisionalFundingSource) {
                    id shouldBe createdProvisionalFundingSource.id
                    owner shouldBe createdProvisionalFundingSource.owner
                    type shouldBe createdProvisionalFundingSource.type
                    updatedAt shouldBe createdProvisionalFundingSource.updatedAt
                    provisioningData shouldBe createdProvisionalFundingSource.provisioningData
                    version shouldBe createdProvisionalFundingSource.version
                    state shouldBe createdProvisionalFundingSource.state
                    last4 shouldBe createdProvisionalFundingSource.last4
                }
            }

            // Ascending sortOrder
            val listAscendingProvisionalFundingSources = vcClient.listProvisionalFundingSources(null, SortOrder.ASC)
            listAscendingProvisionalFundingSources.items.size shouldBe provisionalCount
            val ascendingProvisionalFundingSources = listAscendingProvisionalFundingSources.items
            sortedProvisionalFundingSources = createdProvisionalFundingSources.sortedBy { it.updatedAt }
            for (i in 0 until provisionalCount) {
                val provisionalFundingSource = ascendingProvisionalFundingSources[i]
                val createdProvisionalFundingSource = sortedProvisionalFundingSources[i]
                with(provisionalFundingSource) {
                    id shouldBe createdProvisionalFundingSource.id
                    owner shouldBe createdProvisionalFundingSource.owner
                    type shouldBe createdProvisionalFundingSource.type
                    updatedAt shouldBe createdProvisionalFundingSource.updatedAt
                    provisioningData shouldBe createdProvisionalFundingSource.provisioningData
                    version shouldBe createdProvisionalFundingSource.version
                    state shouldBe createdProvisionalFundingSource.state
                    last4 shouldBe createdProvisionalFundingSource.last4
                }
            }

            val randomOffset = (0 until provisionalCount).random()
            val idToGet = listProvisionalFundingSources.items[randomOffset].id
            val filteredProvisionalFundingSources =
                vcClient.listProvisionalFundingSources(
                    ProvisionalFundingSourceFilterInput(IdFilterInput(null, idToGet)),
                )
            filteredProvisionalFundingSources.items.size shouldBe 1
            filteredProvisionalFundingSources.items[0].id shouldBe idToGet

            val neFilteredProvisionalFundingSources =
                vcClient.listProvisionalFundingSources(
                    ProvisionalFundingSourceFilterInput(IdFilterInput(idToGet)),
                )
            neFilteredProvisionalFundingSources.items.size shouldBe provisionalCount - 1
            shouldThrow<NoSuchElementException> {
                neFilteredProvisionalFundingSources.items.first { it.id == idToGet }
            }

            val orFiltered =
                vcClient.listProvisionalFundingSources(
                    ProvisionalFundingSourceFilterInput(
                        null,
                        null,
                        null,
                        arrayListOf(
                            ProvisionalFundingSourceFilterInput(IdFilterInput(null, idToGet)),
                            ProvisionalFundingSourceFilterInput(
                                null,
                                ProvisionalFundingSourceStateFilterInput(
                                    ProvisionalFundingSource.ProvisioningState.PROVISIONING,
                                ),
                            ),
                        ),
                    ),
                )
            orFiltered.items.size shouldBe provisionalCount
            val andFiltered =
                vcClient.listProvisionalFundingSources(
                    ProvisionalFundingSourceFilterInput(
                        null,
                        null,
                        arrayListOf(
                            ProvisionalFundingSourceFilterInput(IdFilterInput(null, idToGet)),
                            ProvisionalFundingSourceFilterInput(
                                null,
                                ProvisionalFundingSourceStateFilterInput(
                                    ProvisionalFundingSource.ProvisioningState.PROVISIONING,
                                ),
                            ),
                        ),
                    ),
                )
            andFiltered.items.size shouldBe 1
        }

    @Test
    fun listProvisionalFundingSourcesCanListCompleted() =
        runBlocking<Unit> {
            val config = retrieveVirtualCardsConfig(vcClient)
            registerSignInAndEntitle(config)
            verifyTestUserIdentity()

            var provisionalCount = 0

            if (isStripeEnabled(vcClient)) {
                val testCard = TestData.TestCards["stripe"]?.get("Visa-No3DS-1") ?: throw AssertionError("Unable to locate test card")

                val input =
                    CreditCardFundingSourceInput(
                        testCard.creditCardNumber,
                        expirationMonth(),
                        expirationYear(),
                        testCard.securityCode,
                        testCard.address.addressLine1,
                        testCard.address.addressLine2,
                        testCard.address.city,
                        testCard.address.state,
                        testCard.address.postalCode,
                        testCard.address.country,
                        TestData.VerifiedUser.fullName,
                    )

                createCardFundingSource(
                    vcClient,
                    input,
                    CreateCardFundingSourceOptions(supportedProviders = listOf("stripe")),
                )
                ++provisionalCount
            }

            if (isCheckoutBankAccountEnabled(vcClient)) {
                vcClient.createKeysIfAbsent()

                createBankAccountFundingSource(
                    vcClient,
                    CreateBankAccountFundingSourceOptions(
                        supportedProviders = listOf("checkout"),
                        username = TestData.TestBankAccountUsername.customChecking,
                    ),
                )
                ++provisionalCount
            }
            provisionalCount shouldBeGreaterThan 0
            val provisionalFundingSources = vcClient.listProvisionalFundingSources()
            provisionalFundingSources.items.size shouldBe provisionalCount

            val randomOffset = (0 until provisionalCount).random()
            val idToGet = provisionalFundingSources.items[randomOffset].id
            val filteredProvisionalFundingSources =
                vcClient.listProvisionalFundingSources(
                    ProvisionalFundingSourceFilterInput(IdFilterInput(null, idToGet)),
                )
            filteredProvisionalFundingSources.items.size shouldBe 1
            filteredProvisionalFundingSources.items[0].id shouldBe idToGet

            val neFilteredProvisionalFundingSources =
                vcClient.listProvisionalFundingSources(
                    ProvisionalFundingSourceFilterInput(IdFilterInput(idToGet)),
                )
            neFilteredProvisionalFundingSources.items.size shouldBe provisionalCount - 1
            shouldThrow<NoSuchElementException> {
                neFilteredProvisionalFundingSources.items.first { it.id == idToGet }
            }

            val orFiltered =
                vcClient.listProvisionalFundingSources(
                    ProvisionalFundingSourceFilterInput(
                        null,
                        null,
                        null,
                        arrayListOf(
                            ProvisionalFundingSourceFilterInput(IdFilterInput(null, idToGet)),
                            ProvisionalFundingSourceFilterInput(
                                null,
                                ProvisionalFundingSourceStateFilterInput(
                                    ProvisionalFundingSource.ProvisioningState.COMPLETED,
                                ),
                            ),
                        ),
                    ),
                )
            orFiltered.items.size shouldBe provisionalCount
            val andFiltered =
                vcClient.listProvisionalFundingSources(
                    ProvisionalFundingSourceFilterInput(
                        null,
                        null,
                        arrayListOf(
                            ProvisionalFundingSourceFilterInput(IdFilterInput(null, idToGet)),
                            ProvisionalFundingSourceFilterInput(
                                null,
                                ProvisionalFundingSourceStateFilterInput(
                                    ProvisionalFundingSource.ProvisioningState.COMPLETED,
                                ),
                            ),
                        ),
                    ),
                )
            andFiltered.items.size shouldBe 1
        }

    @Test
    fun cancelProvisionalFundingSourceShouldCancel() =
        runBlocking {
            val config = retrieveVirtualCardsConfig(vcClient)
            registerSignInAndEntitle(config)
            verifyTestUserIdentity()

            var provisionalFundingSourcesCount = 0

            if (isStripeEnabled(vcClient)) {
                val setupStripeInput =
                    SetupFundingSourceInput(
                        "USD",
                        FundingSourceType.CREDIT_CARD,
                        ClientApplicationData("system-test-app"),
                        listOf("stripe"),
                    )
                val stripeProvisionalFundingSource = vcClient.setupFundingSource(setupStripeInput)

                with(stripeProvisionalFundingSource) {
                    id shouldNotBe null
                    owner shouldBe userClient.getSubject()
                    version shouldBe 1
                    state shouldBe ProvisionalFundingSource.ProvisioningState.PROVISIONING
                    provisioningData.provider shouldBe "stripe"
                    provisioningData.version shouldBe 1
                    val stripeProvisioningData = provisioningData as StripeCardProvisioningData
                    stripeProvisioningData.clientSecret shouldNotBe null
                    stripeProvisioningData.intent shouldNotBe null
                }
                ++provisionalFundingSourcesCount
            }

            if (isCheckoutBankAccountEnabled(vcClient)) {
                vcClient.createKeysIfAbsent()
                shouldThrow<SudoVirtualCardsClient.FundingSourceException.IdentityVerificationException> {
                    createBankAccountFundingSource(
                        vcClient,
                        CreateBankAccountFundingSourceOptions(
                            supportedProviders = listOf("checkout"),
                            username = TestData.TestBankAccountUsername.customIdentityMismatch,
                        ),
                    )
                }
                ++provisionalFundingSourcesCount
            }

            val provisionalFundingSources = vcClient.listProvisionalFundingSources()
            provisionalFundingSources.items.size shouldBe provisionalFundingSourcesCount

            if (provisionalFundingSourcesCount > 0) {
                val randomOffset = (0 until provisionalFundingSourcesCount).random()
                val idToCancel = provisionalFundingSources.items[randomOffset].id

                val cancelled = vcClient.cancelProvisionalFundingSource(idToCancel)
                cancelled.id shouldBe idToCancel
                cancelled.state shouldBe ProvisionalFundingSource.ProvisioningState.FAILED
                cancelled.last4 shouldBe provisionalFundingSources.items[randomOffset].last4

                val failedFundingSources =
                    vcClient.listProvisionalFundingSources(
                        ProvisionalFundingSourceFilterInput(
                            null,
                            ProvisionalFundingSourceStateFilterInput(ProvisionalFundingSource.ProvisioningState.FAILED),
                        ),
                    )
                failedFundingSources.items.size shouldBe 1
                failedFundingSources.items[0].id shouldBe idToCancel
            }
        }

    @Test
    fun cancelProvisionalFundingSourceShouldThrowIfNoSuchProvisional() =
        runBlocking<Unit> {
            registerSignInAndEntitle()

            shouldThrow<SudoVirtualCardsClient.FundingSourceException.FundingSourceNotFoundException> {
                vcClient.cancelProvisionalFundingSource("invalidId")
            }
        }

    @Test
    fun provisionVirtualCardShouldReturnPendingCard() =
        runBlocking {
            registerSignInAndEntitle()
            verifyTestUserIdentity()

            val providerToUse = getProviderToUse(vcClient)
            val testCard = TestData.TestCards[providerToUse]?.get("Visa-No3DS-1") ?: throw AssertionError("Test card should not be null")
            val input =
                CreditCardFundingSourceInput(
                    testCard.creditCardNumber,
                    expirationMonth(),
                    expirationYear(),
                    testCard.securityCode,
                    testCard.address.addressLine1,
                    testCard.address.addressLine2,
                    testCard.address.city,
                    testCard.address.state,
                    testCard.address.postalCode,
                    testCard.address.country,
                    TestData.VerifiedUser.fullName,
                )
            val fundingSource =
                createCardFundingSource(
                    vcClient,
                    input,
                    CreateCardFundingSourceOptions(supportedProviders = listOf(providerToUse)),
                )
            fundingSource shouldNotBe null

            val sudo = createSudo(TestData.sudo)
            sudo.id shouldNotBe null

            val ownershipProof = getOwnershipProof(sudo)
            ownershipProof shouldNotBe null

            var result = vcClient.createKeysIfAbsent()
            result.keyPair.created shouldBe true

            result = vcClient.createKeysIfAbsent()
            result.keyPair.created shouldBe false

            val provisionCardInput =
                ProvisionVirtualCardInput(
                    ownershipProofs = listOf(ownershipProof),
                    fundingSourceId = fundingSource.id,
                    cardHolder = "Unlimited Cards",
                    metadata = null,
                    addressLine1 = "123 Nowhere St",
                    city = "Menlo Park",
                    state = "CA",
                    postalCode = "94025",
                    country = "US",
                    currency = "USD",
                )
            val provisionalCard1 = vcClient.provisionVirtualCard(provisionCardInput)

            provisionalCard1.provisioningState shouldBe ProvisionalVirtualCard.ProvisioningState.PROVISIONING
            provisionalCard1.id.isBlank() shouldBe false
            provisionalCard1.clientRefId.isBlank() shouldBe false
            provisionalCard1.owner shouldBe userClient.getSubject()
            provisionalCard1.version shouldBeGreaterThan 0
            provisionalCard1.card shouldBe null
            provisionalCard1.createdAt.time shouldBeGreaterThan 0L
            provisionalCard1.updatedAt.time shouldBeGreaterThan 0L

            withTimeout(20_000L) {
                var state = ProvisionalVirtualCard.ProvisioningState.PROVISIONING

                while (state == ProvisionalVirtualCard.ProvisioningState.PROVISIONING) {
                    val provisionalCard2 = vcClient.getProvisionalCard(provisionalCard1.id)
                    provisionalCard2 shouldNotBe null
                    provisionalCard2?.provisioningState shouldNotBe ProvisionalVirtualCard.ProvisioningState.FAILED
                    provisionalCard2?.provisioningState shouldNotBe ProvisionalVirtualCard.ProvisioningState.UNKNOWN
                    provisionalCard2?.id shouldBe provisionalCard1.id
                    provisionalCard2?.clientRefId shouldBe provisionalCard1.clientRefId
                    provisionalCard2?.owner shouldBe provisionalCard1.owner
                    provisionalCard2?.version ?: (0 shouldBeGreaterThanOrEqual provisionalCard1.version)
                    provisionalCard2?.createdAt?.time ?: (0L shouldBe provisionalCard1.createdAt)
                    provisionalCard2?.updatedAt?.time ?: (0L shouldBeGreaterThan 0L)

                    if (provisionalCard2?.provisioningState == ProvisionalVirtualCard.ProvisioningState.COMPLETED) {
                        provisionalCard2.card shouldNotBe null
                        val card = provisionalCard2.card ?: throw AssertionError("should not be null")
                        card.cardNumber.isBlank() shouldBe false
                        card.last4 shouldBe card.cardNumber.substring(card.cardNumber.length - 4)
                        card.cardHolder shouldBe "Unlimited Cards"
                        card.metadata shouldBe null
                        card.fundingSourceId shouldBe fundingSource.id
                        card.state shouldBe CardState.ISSUED
                        card.billingAddress shouldNotBe null
                        card.billingAddress?.addressLine1 shouldBe "123 Nowhere St"
                        card.billingAddress?.city shouldBe "Menlo Park"
                        card.billingAddress?.state shouldBe "CA"
                        card.billingAddress?.postalCode shouldBe "94025"
                        card.billingAddress?.country shouldBe "US"
                        card.currency shouldBe "USD"
                        card.owner shouldBe userClient.getSubject()
                        card.owners.first().id shouldBe sudo.id
                        card.owners.first().issuer shouldBe "sudoplatform.sudoservice"
                        card.cancelledAt shouldBe null
                        card.activeTo.time shouldBeGreaterThan 0L
                        card.createdAt.time shouldBeGreaterThan 0L
                        card.updatedAt.time shouldBeGreaterThan 0L
                        card.expiry.mm.toInt() shouldBeGreaterThan 0
                        card.expiry.yyyy.toInt() shouldBeGreaterThan 0
                        card.securityCode.isBlank() shouldBe false
                        card.version shouldBeGreaterThan 0
                    } else {
                        provisionalCard2?.card shouldBe null
                        delay(2_000L)
                    }

                    state = provisionalCard2?.provisioningState ?: ProvisionalVirtualCard.ProvisioningState.PROVISIONING
                }
            }
        }

    @Test
    fun provisionVirtualCardWithStringMetadata() =
        runBlocking {
            registerSignInAndEntitle()
            verifyTestUserIdentity()

            val providerToUse = getProviderToUse(vcClient)
            val testCard = TestData.TestCards[providerToUse]?.get("Visa-No3DS-1") ?: throw AssertionError("Test card should not be null")
            val fundingSourceInput =
                CreditCardFundingSourceInput(
                    testCard.creditCardNumber,
                    expirationMonth(),
                    expirationYear(),
                    testCard.securityCode,
                    testCard.address.addressLine1,
                    testCard.address.addressLine2,
                    testCard.address.city,
                    testCard.address.state,
                    testCard.address.postalCode,
                    testCard.address.country,
                    TestData.VerifiedUser.fullName,
                )
            val fundingSource =
                createCardFundingSource(
                    vcClient,
                    fundingSourceInput,
                    CreateCardFundingSourceOptions(supportedProviders = listOf(providerToUse)),
                )
            fundingSource shouldNotBe null

            val sudo =
                createSudo(
                    TestData.sudo,
                )
            sudo.id shouldNotBe null

            val ownershipProof = getOwnershipProof(sudo)
            ownershipProof shouldNotBe null

            vcClient.createKeysIfAbsent()

            val provisionCardInput =
                ProvisionVirtualCardInput(
                    ownershipProofs = listOf(ownershipProof),
                    fundingSourceId = fundingSource.id,
                    cardHolder = TestData.ProvisionCardInput.cardHolder,
                    metadata = JsonValue.JsonString("Ted Bear"),
                    addressLine1 = TestData.ProvisionCardInput.addressLine1,
                    city = TestData.ProvisionCardInput.city,
                    state = TestData.ProvisionCardInput.state,
                    postalCode = TestData.ProvisionCardInput.postalCode,
                    country = TestData.ProvisionCardInput.country,
                    currency = TestData.ProvisionCardInput.currency,
                )
            val card = provisionVirtualCard(vcClient, provisionCardInput)

            with(card) {
                metadata shouldBe JsonValue.JsonString("Ted Bear")
                metadata?.unwrap() shouldBe "Ted Bear"
            }
        }

    @Test
    fun provisionVirtualCardWithIntegerMetadata() =
        runBlocking {
            registerSignInAndEntitle()
            verifyTestUserIdentity()

            val providerToUse = getProviderToUse(vcClient)
            val testCard = TestData.TestCards[providerToUse]?.get("Visa-No3DS-1") ?: throw AssertionError("Test card should not be null")
            val fundingSourceInput =
                CreditCardFundingSourceInput(
                    testCard.creditCardNumber,
                    expirationMonth(),
                    expirationYear(),
                    testCard.securityCode,
                    testCard.address.addressLine1,
                    testCard.address.addressLine2,
                    testCard.address.city,
                    testCard.address.state,
                    testCard.address.postalCode,
                    testCard.address.country,
                    TestData.VerifiedUser.fullName,
                )
            val fundingSource =
                createCardFundingSource(
                    vcClient,
                    fundingSourceInput,
                    CreateCardFundingSourceOptions(supportedProviders = listOf(providerToUse)),
                )
            fundingSource shouldNotBe null

            val sudo =
                createSudo(
                    TestData.sudo,
                )
            sudo.id shouldNotBe null

            val ownershipProof = getOwnershipProof(sudo)
            ownershipProof shouldNotBe null

            vcClient.createKeysIfAbsent()

            val provisionCardInput =
                ProvisionVirtualCardInput(
                    ownershipProofs = listOf(ownershipProof),
                    fundingSourceId = fundingSource.id,
                    cardHolder = TestData.ProvisionCardInput.cardHolder,
                    metadata = JsonValue.JsonInteger(42),
                    addressLine1 = TestData.ProvisionCardInput.addressLine1,
                    city = TestData.ProvisionCardInput.city,
                    state = TestData.ProvisionCardInput.state,
                    postalCode = TestData.ProvisionCardInput.postalCode,
                    country = TestData.ProvisionCardInput.country,
                    currency = TestData.ProvisionCardInput.currency,
                )
            val card = provisionVirtualCard(vcClient, provisionCardInput)

            with(card) {
                metadata shouldBe JsonValue.JsonInteger(42)
                metadata?.unwrap() shouldBe 42
            }
        }

    @Test
    fun provisionVirtualCardWithDoubleMetadata() =
        runBlocking {
            registerSignInAndEntitle()
            verifyTestUserIdentity()

            val providerToUse = getProviderToUse(vcClient)
            val testCard = TestData.TestCards[providerToUse]?.get("Visa-No3DS-1") ?: throw AssertionError("Test card should not be null")
            val fundingSourceInput =
                CreditCardFundingSourceInput(
                    testCard.creditCardNumber,
                    expirationMonth(),
                    expirationYear(),
                    testCard.securityCode,
                    testCard.address.addressLine1,
                    testCard.address.addressLine2,
                    testCard.address.city,
                    testCard.address.state,
                    testCard.address.postalCode,
                    testCard.address.country,
                    TestData.VerifiedUser.fullName,
                )
            val fundingSource =
                createCardFundingSource(
                    vcClient,
                    fundingSourceInput,
                    CreateCardFundingSourceOptions(supportedProviders = listOf(providerToUse)),
                )
            fundingSource shouldNotBe null

            val sudo =
                createSudo(
                    TestData.sudo,
                )
            sudo.id shouldNotBe null

            val ownershipProof = getOwnershipProof(sudo)
            ownershipProof shouldNotBe null

            vcClient.createKeysIfAbsent()

            val provisionCardInput =
                ProvisionVirtualCardInput(
                    ownershipProofs = listOf(ownershipProof),
                    fundingSourceId = fundingSource.id,
                    cardHolder = TestData.ProvisionCardInput.cardHolder,
                    metadata = JsonValue.JsonDouble(42.5),
                    addressLine1 = TestData.ProvisionCardInput.addressLine1,
                    city = TestData.ProvisionCardInput.city,
                    state = TestData.ProvisionCardInput.state,
                    postalCode = TestData.ProvisionCardInput.postalCode,
                    country = TestData.ProvisionCardInput.country,
                    currency = TestData.ProvisionCardInput.currency,
                )
            val card = provisionVirtualCard(vcClient, provisionCardInput)

            with(card) {
                metadata shouldBe JsonValue.JsonDouble(42.5)
                metadata?.unwrap() shouldBe 42.5
            }
        }

    @Test
    fun provisionVirtualCardWithBooleanMetadata() =
        runBlocking {
            registerSignInAndEntitle()
            verifyTestUserIdentity()

            val providerToUse = getProviderToUse(vcClient)
            val testCard = TestData.TestCards[providerToUse]?.get("Visa-No3DS-1") ?: throw AssertionError("Test card should not be null")
            val fundingSourceInput =
                CreditCardFundingSourceInput(
                    testCard.creditCardNumber,
                    expirationMonth(),
                    expirationYear(),
                    testCard.securityCode,
                    testCard.address.addressLine1,
                    testCard.address.addressLine2,
                    testCard.address.city,
                    testCard.address.state,
                    testCard.address.postalCode,
                    testCard.address.country,
                    TestData.VerifiedUser.fullName,
                )
            val fundingSource =
                createCardFundingSource(
                    vcClient,
                    fundingSourceInput,
                    CreateCardFundingSourceOptions(supportedProviders = listOf(providerToUse)),
                )
            fundingSource shouldNotBe null

            val sudo =
                createSudo(
                    TestData.sudo,
                )
            sudo.id shouldNotBe null

            val ownershipProof = getOwnershipProof(sudo)
            ownershipProof shouldNotBe null

            vcClient.createKeysIfAbsent()

            val provisionCardInput =
                ProvisionVirtualCardInput(
                    ownershipProofs = listOf(ownershipProof),
                    fundingSourceId = fundingSource.id,
                    cardHolder = TestData.ProvisionCardInput.cardHolder,
                    metadata = JsonValue.JsonBoolean(true),
                    addressLine1 = TestData.ProvisionCardInput.addressLine1,
                    city = TestData.ProvisionCardInput.city,
                    state = TestData.ProvisionCardInput.state,
                    postalCode = TestData.ProvisionCardInput.postalCode,
                    country = TestData.ProvisionCardInput.country,
                    currency = TestData.ProvisionCardInput.currency,
                )
            val card = provisionVirtualCard(vcClient, provisionCardInput)

            with(card) {
                metadata shouldBe JsonValue.JsonBoolean(true)
                metadata?.unwrap() shouldBe true
            }
        }

    @Test
    fun provisionVirtualCardWithArrayMetadata() =
        runBlocking {
            registerSignInAndEntitle()
            verifyTestUserIdentity()

            val providerToUse = getProviderToUse(vcClient)
            val testCard = TestData.TestCards[providerToUse]?.get("Visa-No3DS-1") ?: throw AssertionError("Test card should not be null")
            val fundingSourceInput =
                CreditCardFundingSourceInput(
                    testCard.creditCardNumber,
                    expirationMonth(),
                    expirationYear(),
                    testCard.securityCode,
                    testCard.address.addressLine1,
                    testCard.address.addressLine2,
                    testCard.address.city,
                    testCard.address.state,
                    testCard.address.postalCode,
                    testCard.address.country,
                    TestData.VerifiedUser.fullName,
                )
            val fundingSource =
                createCardFundingSource(
                    vcClient,
                    fundingSourceInput,
                    CreateCardFundingSourceOptions(supportedProviders = listOf(providerToUse)),
                )
            fundingSource shouldNotBe null

            val sudo =
                createSudo(
                    TestData.sudo,
                )
            sudo.id shouldNotBe null

            val ownershipProof = getOwnershipProof(sudo)
            ownershipProof shouldNotBe null

            vcClient.createKeysIfAbsent()

            val provisionCardInput =
                ProvisionVirtualCardInput(
                    ownershipProofs = listOf(ownershipProof),
                    fundingSourceId = fundingSource.id,
                    cardHolder = TestData.ProvisionCardInput.cardHolder,
                    metadata = JsonValue.JsonArray(listOf("foobar", false)),
                    addressLine1 = TestData.ProvisionCardInput.addressLine1,
                    city = TestData.ProvisionCardInput.city,
                    state = TestData.ProvisionCardInput.state,
                    postalCode = TestData.ProvisionCardInput.postalCode,
                    country = TestData.ProvisionCardInput.country,
                    currency = TestData.ProvisionCardInput.currency,
                )
            val card = provisionVirtualCard(vcClient, provisionCardInput)

            with(card) {
                metadata shouldBe JsonValue.JsonArray(listOf("foobar", false))
                metadata?.unwrap() shouldBe listOf("foobar", false)
            }
        }

    @Test
    fun provisionVirtualCardWithMapMetadata() =
        runBlocking {
            registerSignInAndEntitle()
            verifyTestUserIdentity()

            val providerToUse = getProviderToUse(vcClient)
            val testCard = TestData.TestCards[providerToUse]?.get("Visa-No3DS-1") ?: throw AssertionError("Test card should not be null")
            val fundingSourceInput =
                CreditCardFundingSourceInput(
                    testCard.creditCardNumber,
                    expirationMonth(),
                    expirationYear(),
                    testCard.securityCode,
                    testCard.address.addressLine1,
                    testCard.address.addressLine2,
                    testCard.address.city,
                    testCard.address.state,
                    testCard.address.postalCode,
                    testCard.address.country,
                    TestData.VerifiedUser.fullName,
                )
            val fundingSource =
                createCardFundingSource(
                    vcClient,
                    fundingSourceInput,
                    CreateCardFundingSourceOptions(supportedProviders = listOf(providerToUse)),
                )
            fundingSource shouldNotBe null

            val sudo =
                createSudo(
                    TestData.sudo,
                )
            sudo.id shouldNotBe null

            val ownershipProof = getOwnershipProof(sudo)
            ownershipProof shouldNotBe null

            vcClient.createKeysIfAbsent()

            val provisionCardInput =
                ProvisionVirtualCardInput(
                    ownershipProofs = listOf(ownershipProof),
                    fundingSourceId = fundingSource.id,
                    cardHolder = TestData.ProvisionCardInput.cardHolder,
                    metadata =
                        JsonValue.JsonMap(
                            mapOf(
                                "alias" to "Ted Bear",
                                "something" to true,
                            ),
                        ),
                    addressLine1 = TestData.ProvisionCardInput.addressLine1,
                    city = TestData.ProvisionCardInput.city,
                    state = TestData.ProvisionCardInput.state,
                    postalCode = TestData.ProvisionCardInput.postalCode,
                    country = TestData.ProvisionCardInput.country,
                    currency = TestData.ProvisionCardInput.currency,
                )
            val card = provisionVirtualCard(vcClient, provisionCardInput)

            with(card) {
                metadata shouldBe
                    JsonValue.JsonMap(
                        mapOf(
                            "alias" to "Ted Bear",
                            "something" to true,
                        ),
                    )
                metadata?.unwrap() shouldBe
                    mapOf(
                        "alias" to "Ted Bear",
                        "something" to true,
                    )
            }
        }

    @Test
    fun getVirtualCardShouldReturnProvisionedCardResult() =
        runBlocking {
            registerSignInAndEntitle()
            verifyTestUserIdentity()

            val providerToUse = getProviderToUse(vcClient)
            val testCard = TestData.TestCards[providerToUse]?.get("Visa-No3DS-1") ?: throw AssertionError("Test card should not be null")
            val input =
                CreditCardFundingSourceInput(
                    testCard.creditCardNumber,
                    expirationMonth(),
                    expirationYear(),
                    testCard.securityCode,
                    testCard.address.addressLine1,
                    testCard.address.addressLine2,
                    testCard.address.city,
                    testCard.address.state,
                    testCard.address.postalCode,
                    testCard.address.country,
                    TestData.VerifiedUser.fullName,
                )
            val fundingSource =
                createCardFundingSource(
                    vcClient,
                    input,
                    CreateCardFundingSourceOptions(supportedProviders = listOf(providerToUse)),
                )
            fundingSource shouldNotBe null

            vcClient.createKeysIfAbsent()

            val sudo =
                createSudo(
                    TestData.sudo,
                )
            sudo.id shouldNotBe null

            val ownershipProof = getOwnershipProof(sudo)
            ownershipProof shouldNotBe null

            val provisionCardInput =
                ProvisionVirtualCardInput(
                    ownershipProofs = listOf(ownershipProof),
                    fundingSourceId = fundingSource.id,
                    cardHolder = TestData.ProvisionCardInput.cardHolder,
                    addressLine1 = TestData.ProvisionCardInput.addressLine1,
                    city = TestData.ProvisionCardInput.city,
                    state = TestData.ProvisionCardInput.state,
                    postalCode = TestData.ProvisionCardInput.postalCode,
                    country = TestData.ProvisionCardInput.country,
                    currency = TestData.ProvisionCardInput.currency,
                )
            val card = provisionVirtualCard(vcClient, provisionCardInput)
            card shouldNotBe null

            val retrievedCard = vcClient.getVirtualCard(card.id) ?: throw AssertionError("should not be null")

            retrievedCard.id shouldBe card.id
            retrievedCard.cardNumber shouldBe card.cardNumber
            retrievedCard.last4 shouldBe card.last4
            retrievedCard.cardHolder shouldBe card.cardHolder
            retrievedCard.alias shouldBe card.alias
            retrievedCard.fundingSourceId shouldBe card.fundingSourceId
            retrievedCard.state shouldBe card.state
            retrievedCard.billingAddress shouldBe card.billingAddress
            retrievedCard.currency shouldBe card.currency
            retrievedCard.owner shouldBe card.owner
            retrievedCard.owners.first().id shouldBe card.owners.first().id
            retrievedCard.owners.first().issuer shouldBe card.owners.first().issuer
            retrievedCard.cancelledAt shouldBe card.cancelledAt
            retrievedCard.activeTo.time shouldBeGreaterThan 0L
            retrievedCard.createdAt.time shouldBeGreaterThan 0L
            retrievedCard.updatedAt.time shouldBeGreaterThan 0L
            retrievedCard.expiry.mm.toInt() shouldBeGreaterThan 0
            retrievedCard.expiry.yyyy.toInt() shouldBeGreaterThan 0
            retrievedCard.securityCode shouldBe card.securityCode
            retrievedCard.version shouldBe card.version
        }

    @Test
    fun getVirtualCardShouldReturnNullForNonExistentId() =
        runBlocking {
            registerSignInAndEntitle()
            verifyTestUserIdentity()

            vcClient.createKeysIfAbsent()

            val retrievedCard = vcClient.getVirtualCard("NonExistentId")
            retrievedCard shouldBe null
        }

    @Test
    fun getVirtualCardsConfigShouldReturnVirtualCardsConfig() =
        runBlocking {
            registerSignInAndEntitle()

            fun verifyVelocity(s: String) {
                val elements = s.split('/')
                elements.size shouldBe 2

                val amount = elements[0].toInt()
                amount shouldNotBe Double.NaN
                amount shouldBeGreaterThanOrEqual 0

                try {
                    parseIsoString(elements[1])
                } catch (e: IllegalArgumentException) {
                    fail("Provided ISO string is not valid.")
                }
            }

            val virtualCardsConfig = vcClient.getVirtualCardsConfig() ?: throw AssertionError("should not be null")
            with(virtualCardsConfig) {
                maxFundingSourceVelocity.forEach { verifyVelocity(it) }
                maxFundingSourceFailureVelocity.forEach { verifyVelocity(it) }
                maxFundingSourcePendingVelocity.forEach { verifyVelocity(it) }
                maxCardCreationVelocity.forEach { verifyVelocity(it) }
                maxTransactionVelocity.size shouldBeGreaterThanOrEqual 1
                maxTransactionAmount.size shouldBeGreaterThanOrEqual 1
                virtualCardCurrencies.size shouldBeGreaterThanOrEqual 1
                fundingSourceSupportInfo.size shouldBeGreaterThanOrEqual 1
                fundingSourceClientConfiguration.size shouldBeGreaterThanOrEqual 1
            }
        }

    @Test
    fun listVirtualCardsShouldReturnSingleCardListOutputResult() =
        runBlocking {
            registerSignInAndEntitle()
            verifyTestUserIdentity()

            val providerToUse = getProviderToUse(vcClient)
            val testCard = TestData.TestCards[providerToUse]?.get("Visa-No3DS-1") ?: throw AssertionError("Test card should not be null")
            val input =
                CreditCardFundingSourceInput(
                    testCard.creditCardNumber,
                    expirationMonth(),
                    expirationYear(),
                    testCard.securityCode,
                    testCard.address.addressLine1,
                    testCard.address.addressLine2,
                    testCard.address.city,
                    testCard.address.state,
                    testCard.address.postalCode,
                    testCard.address.country,
                    TestData.VerifiedUser.fullName,
                )
            val fundingSource =
                createCardFundingSource(
                    vcClient,
                    input,
                    CreateCardFundingSourceOptions(supportedProviders = listOf(providerToUse)),
                )
            fundingSource shouldNotBe null

            vcClient.createKeysIfAbsent()

            val sudo =
                createSudo(
                    TestData.sudo,
                )
            sudo.id shouldNotBe null

            val ownershipProof = getOwnershipProof(sudo)
            ownershipProof shouldNotBe null

            val provisionCardInput =
                ProvisionVirtualCardInput(
                    ownershipProofs = listOf(ownershipProof),
                    fundingSourceId = fundingSource.id,
                    cardHolder = TestData.ProvisionCardInput.cardHolder,
                    addressLine1 = TestData.ProvisionCardInput.addressLine1,
                    city = TestData.ProvisionCardInput.city,
                    state = TestData.ProvisionCardInput.state,
                    postalCode = TestData.ProvisionCardInput.postalCode,
                    country = TestData.ProvisionCardInput.country,
                    currency = TestData.ProvisionCardInput.currency,
                )
            val card = provisionVirtualCard(vcClient, provisionCardInput)
            card shouldNotBe null

            val listCards = vcClient.listVirtualCards()
            listCards shouldNotBe null

            when (listCards) {
                is ListAPIResult.Success -> {
                    listCards.result.items.isEmpty() shouldBe false
                    listCards.result.items.size shouldBe 1
                    listCards.result.nextToken shouldBe null

                    with(listCards.result.items[0]) {
                        id shouldBe card.id
                        cardNumber shouldBe card.cardNumber
                        last4 shouldBe card.last4
                        cardHolder shouldBe card.cardHolder
                        alias shouldBe card.alias
                        fundingSourceId shouldBe card.fundingSourceId
                        state shouldBe card.state
                        billingAddress shouldBe card.billingAddress
                        currency shouldBe card.currency
                        owner shouldBe card.owner
                        owners.first().id shouldBe card.owners.first().id
                        owners.first().issuer shouldBe card.owners.first().issuer
                        cancelledAt shouldBe card.cancelledAt
                        activeTo.time shouldBeGreaterThan 0L
                        createdAt.time shouldBeGreaterThan 0L
                        updatedAt.time shouldBeGreaterThan 0L
                        expiry.mm.toInt() shouldBeGreaterThan 0
                        expiry.yyyy.toInt() shouldBeGreaterThan 0
                        securityCode shouldBe card.securityCode
                        version shouldBeGreaterThan 0
                    }
                }
                else -> {
                    fail("Unexpected ListAPIResult")
                }
            }
        }

    @Test
    fun listVirtualCardsShouldReturnMultipleCardListOutputResult() =
        runBlocking {
            registerSignInAndEntitle()
            verifyTestUserIdentity()

            val providerToUse = getProviderToUse(vcClient)
            val testCard = TestData.TestCards[providerToUse]?.get("Visa-No3DS-1") ?: throw AssertionError("Test card should not be null")
            val input =
                CreditCardFundingSourceInput(
                    testCard.creditCardNumber,
                    expirationMonth(),
                    expirationYear(),
                    testCard.securityCode,
                    testCard.address.addressLine1,
                    testCard.address.addressLine2,
                    testCard.address.city,
                    testCard.address.state,
                    testCard.address.postalCode,
                    testCard.address.country,
                    TestData.VerifiedUser.fullName,
                )
            val fundingSource =
                createCardFundingSource(
                    vcClient,
                    input,
                    CreateCardFundingSourceOptions(supportedProviders = listOf(providerToUse)),
                )
            fundingSource shouldNotBe null

            vcClient.createKeysIfAbsent()

            val sudo =
                createSudo(
                    TestData.sudo,
                )
            sudo.id shouldNotBe null

            val ownershipProof = getOwnershipProof(sudo)
            ownershipProof shouldNotBe null

            val provisionCardInput =
                ProvisionVirtualCardInput(
                    ownershipProofs = listOf(ownershipProof),
                    fundingSourceId = fundingSource.id,
                    cardHolder = TestData.ProvisionCardInput.cardHolder,
                    addressLine1 = TestData.ProvisionCardInput.addressLine1,
                    city = TestData.ProvisionCardInput.city,
                    state = TestData.ProvisionCardInput.state,
                    postalCode = TestData.ProvisionCardInput.postalCode,
                    country = TestData.ProvisionCardInput.country,
                    currency = TestData.ProvisionCardInput.currency,
                )

            val card1 = provisionVirtualCard(vcClient, provisionCardInput)
            card1 shouldNotBe null
            Thread.sleep(1)
            val card2 = provisionVirtualCard(vcClient, provisionCardInput)
            card2 shouldNotBe null

            card2.createdAt.time shouldBeGreaterThan card1.createdAt.time

            // Since card2 is created after card1 it should be returned first in the list
            val expectedCards = arrayOf(card2, card1)

            // defaults (sortOrder is descending)
            val listCards = vcClient.listVirtualCards()
            listCards shouldNotBe null

            when (listCards) {
                is ListAPIResult.Success -> {
                    listCards.result.items.isEmpty() shouldBe false
                    listCards.result.items.size shouldBe 2
                    listCards.result.nextToken shouldBe null

                    val actualCards = listCards.result.items
                    for (i in expectedCards.indices) {
                        actualCards[i].id shouldBe expectedCards[i].id
                        actualCards[i].cardNumber shouldBe expectedCards[i].cardNumber
                        actualCards[i].last4 shouldBe expectedCards[i].last4
                        actualCards[i].cardHolder shouldBe expectedCards[i].cardHolder
                        actualCards[i].alias shouldBe expectedCards[i].alias
                        actualCards[i].fundingSourceId shouldBe expectedCards[i].fundingSourceId
                        actualCards[i].state shouldBe expectedCards[i].state
                        actualCards[i].billingAddress shouldBe expectedCards[i].billingAddress
                        actualCards[i].currency shouldBe expectedCards[i].currency
                        actualCards[i].owner shouldBe expectedCards[i].owner
                        actualCards[i].owners.first().id shouldBe expectedCards[i].owners.first().id
                        actualCards[i].owners.first().issuer shouldBe expectedCards[i].owners.first().issuer
                        actualCards[i].cancelledAt shouldBe expectedCards[i].cancelledAt
                        actualCards[i].activeTo.time shouldBeGreaterThan 0L
                        actualCards[i].createdAt.time shouldBeGreaterThan 0L
                        actualCards[i].updatedAt.time shouldBeGreaterThan 0L
                        actualCards[i].expiry.mm.toInt() shouldBeGreaterThan 0
                        actualCards[i].expiry.yyyy.toInt() shouldBeGreaterThan 0
                        actualCards[i].securityCode shouldBe expectedCards[i].securityCode
                        actualCards[i].version shouldBeGreaterThan 0
                    }
                }

                else -> {
                    fail("Unexpected ListAPIResult")
                }
            }

            // ascending sort order
            val ascendingListCards = vcClient.listVirtualCards(null, SortOrder.ASC)
            ascendingListCards shouldNotBe null
            when (ascendingListCards) {
                is ListAPIResult.Success -> {
                    ascendingListCards.result.items.isEmpty() shouldBe false
                    ascendingListCards.result.items.size shouldBe 2
                    ascendingListCards.result.nextToken shouldBe null

                    val actualCards = ascendingListCards.result.items
                    val expectedAscendingCards = expectedCards.sortedBy { it.updatedAt }
                    for (i in expectedCards.indices) {
                        actualCards[i].id shouldBe expectedAscendingCards[i].id
                        actualCards[i].cardNumber shouldBe expectedAscendingCards[i].cardNumber
                        actualCards[i].last4 shouldBe expectedAscendingCards[i].last4
                        actualCards[i].cardHolder shouldBe expectedAscendingCards[i].cardHolder
                        actualCards[i].alias shouldBe expectedAscendingCards[i].alias
                        actualCards[i].fundingSourceId shouldBe expectedAscendingCards[i].fundingSourceId
                        actualCards[i].state shouldBe expectedAscendingCards[i].state
                        actualCards[i].billingAddress shouldBe expectedAscendingCards[i].billingAddress
                        actualCards[i].currency shouldBe expectedAscendingCards[i].currency
                        actualCards[i].owner shouldBe expectedAscendingCards[i].owner
                        actualCards[i].owners.first().id shouldBe expectedAscendingCards[i].owners.first().id
                        actualCards[i].owners.first().issuer shouldBe expectedAscendingCards[i].owners.first().issuer
                        actualCards[i].cancelledAt shouldBe expectedAscendingCards[i].cancelledAt
                        actualCards[i].activeTo.time shouldBeGreaterThan 0L
                        actualCards[i].createdAt.time shouldBeGreaterThan 0L
                        actualCards[i].updatedAt.time shouldBeGreaterThan 0L
                        actualCards[i].expiry.mm.toInt() shouldBeGreaterThan 0
                        actualCards[i].expiry.yyyy.toInt() shouldBeGreaterThan 0
                        actualCards[i].securityCode shouldBe expectedAscendingCards[i].securityCode
                        actualCards[i].version shouldBeGreaterThan 0
                    }
                }

                else -> {
                    fail("Unexpected ListAPIResult")
                }
            }

            // filtered
            val filterInput = VirtualCardFilterInput(IdFilterInput(eq = expectedCards[0].id))
            val filteredListCards = vcClient.listVirtualCards(filterInput, SortOrder.ASC)
            filteredListCards shouldNotBe null
            when (filteredListCards) {
                is ListAPIResult.Success -> {
                    filteredListCards.result.items.isEmpty() shouldBe false
                    filteredListCards.result.items.size shouldBe 1
                    filteredListCards.result.nextToken shouldBe null

                    val filteredCard = filteredListCards.result.items[0]
                    val expectedCard = expectedCards[0]
                    with(filteredCard) {
                        id shouldBe expectedCard.id
                        cardNumber shouldBe expectedCard.cardNumber
                        last4 shouldBe expectedCard.last4
                        cardHolder shouldBe expectedCard.cardHolder
                        alias shouldBe expectedCard.alias
                        fundingSourceId shouldBe expectedCard.fundingSourceId
                        state shouldBe expectedCard.state
                        billingAddress shouldBe expectedCard.billingAddress
                        currency shouldBe expectedCard.currency
                        owner shouldBe expectedCard.owner
                        owners.first().id shouldBe expectedCard.owners.first().id
                        owners.first().issuer shouldBe expectedCard.owners.first().issuer
                        cancelledAt shouldBe expectedCard.cancelledAt
                        activeTo.time shouldBeGreaterThan 0L
                        createdAt.time shouldBeGreaterThan 0L
                        updatedAt.time shouldBeGreaterThan 0L
                        expiry.mm.toInt() shouldBeGreaterThan 0
                        expiry.yyyy.toInt() shouldBeGreaterThan 0
                        securityCode shouldBe expectedCard.securityCode
                        version shouldBeGreaterThan 0
                    }
                }

                else -> {
                    fail("Unexpected ListAPIResult")
                }
            }
        }

    @Test
    fun updateVirtualCardShouldReturnUpdatedCardResult() =
        runBlocking {
            registerSignInAndEntitle()
            verifyTestUserIdentity()

            val providerToUse = getProviderToUse(vcClient)
            val testCard = TestData.TestCards[providerToUse]?.get("Visa-No3DS-1") ?: throw AssertionError("Test card should not be null")
            val input =
                CreditCardFundingSourceInput(
                    testCard.creditCardNumber,
                    expirationMonth(),
                    expirationYear(),
                    testCard.securityCode,
                    testCard.address.addressLine1,
                    testCard.address.addressLine2,
                    testCard.address.city,
                    testCard.address.state,
                    testCard.address.postalCode,
                    testCard.address.country,
                    TestData.VerifiedUser.fullName,
                )
            val fundingSource =
                createCardFundingSource(
                    vcClient,
                    input,
                    CreateCardFundingSourceOptions(supportedProviders = listOf(providerToUse)),
                )
            fundingSource shouldNotBe null

            vcClient.createKeysIfAbsent()

            val sudo =
                createSudo(
                    TestData.sudo,
                )
            sudo.id shouldNotBe null

            val ownershipProof = getOwnershipProof(sudo)
            ownershipProof shouldNotBe null

            val provisionCardInput =
                ProvisionVirtualCardInput(
                    ownershipProofs = listOf(ownershipProof),
                    fundingSourceId = fundingSource.id,
                    cardHolder = TestData.ProvisionCardInput.cardHolder,
                    metadata = JsonValue.JsonString("Ted Bear"),
                    addressLine1 = TestData.ProvisionCardInput.addressLine1,
                    city = TestData.ProvisionCardInput.city,
                    state = TestData.ProvisionCardInput.state,
                    postalCode = TestData.ProvisionCardInput.postalCode,
                    country = TestData.ProvisionCardInput.country,
                    currency = TestData.ProvisionCardInput.currency,
                )
            val card = provisionVirtualCard(vcClient, provisionCardInput)
            card shouldNotBe null

            val updateCardInput =
                UpdateVirtualCardInput(
                    id = card.id,
                    expectedCardVersion = card.version,
                    cardHolder = "Not Unlimited Cards",
                    metadata = JsonValue.JsonString("Bed Tear"),
                    addressLine1 = "321 Somewhere St",
                    city = "Olnem Park",
                    state = "NY",
                    postalCode = "52049",
                    country = "US",
                )
            val updatedCard = vcClient.updateVirtualCard(updateCardInput)
            updatedCard shouldNotBe null

            when (updatedCard) {
                is SingleAPIResult.Success -> {
                    updatedCard.result.id shouldBe card.id
                    updatedCard.result.cardHolder shouldNotBe card.cardHolder
                    updatedCard.result.metadata shouldNotBe card.metadata
                    updatedCard.result.billingAddress shouldNotBe card.billingAddress
                }
                else -> {
                    fail("Unexpected SingleAPIResult")
                }
            }
        }

    @Test
    fun updateVirtualCardShouldReturnPartiallyUpdatedCardResult() =
        runBlocking {
            registerSignInAndEntitle()
            verifyTestUserIdentity()

            val providerToUse = getProviderToUse(vcClient)
            val testCard = TestData.TestCards[providerToUse]?.get("Visa-No3DS-1") ?: throw AssertionError("Test card should not be null")
            val input =
                CreditCardFundingSourceInput(
                    testCard.creditCardNumber,
                    expirationMonth(),
                    expirationYear(),
                    testCard.securityCode,
                    testCard.address.addressLine1,
                    testCard.address.addressLine2,
                    testCard.address.city,
                    testCard.address.state,
                    testCard.address.postalCode,
                    testCard.address.country,
                    TestData.VerifiedUser.fullName,
                )
            val fundingSource =
                createCardFundingSource(
                    vcClient,
                    input,
                    CreateCardFundingSourceOptions(supportedProviders = listOf(providerToUse)),
                )
            fundingSource shouldNotBe null

            vcClient.createKeysIfAbsent()

            val sudo =
                createSudo(
                    TestData.sudo,
                )
            sudo.id shouldNotBe null

            val ownershipProof = getOwnershipProof(sudo)
            ownershipProof shouldNotBe null

            val provisionCardInput =
                ProvisionVirtualCardInput(
                    ownershipProofs = listOf(ownershipProof),
                    fundingSourceId = fundingSource.id,
                    cardHolder = TestData.ProvisionCardInput.cardHolder,
                    addressLine1 = TestData.ProvisionCardInput.addressLine1,
                    city = TestData.ProvisionCardInput.city,
                    state = TestData.ProvisionCardInput.state,
                    postalCode = TestData.ProvisionCardInput.postalCode,
                    country = TestData.ProvisionCardInput.country,
                    currency = TestData.ProvisionCardInput.currency,
                )
            val card = provisionVirtualCard(vcClient, provisionCardInput)
            card shouldNotBe null

            val updateCardInput =
                UpdateVirtualCardInput(
                    id = card.id,
                    expectedCardVersion = card.version,
                    cardHolder = "Unlimited Cards",
                    alias = "Bed Tear",
                    null,
                    addressLine1 = "123 Nowhere St",
                    city = "Menlo Park",
                    state = "CA",
                    postalCode = "94025",
                    country = "US",
                )
            val updatedCard = vcClient.updateVirtualCard(updateCardInput)
            updatedCard shouldNotBe null

            when (updatedCard) {
                is SingleAPIResult.Success -> {
                    updatedCard.result.id shouldBe card.id
                    updatedCard.result.cardHolder shouldBe card.cardHolder
                    updatedCard.result.alias shouldNotBe card.alias
                    updatedCard.result.billingAddress shouldBe card.billingAddress
                }
                else -> {
                    fail("Unexpected SingleAPIResult")
                }
            }
        }

    @Test
    @Ignore("Ignore until we have a fix or workaround for AWS AppSync's inability to serialize null inputs - PAY-1199 opened for tracking")
    fun updateVirtualCardWithNullBillingAddressInputShouldReturnUpdatedCardWithNullBillingAddress() =
        runBlocking {
            registerSignInAndEntitle()
            verifyTestUserIdentity()

            val providerToUse = getProviderToUse(vcClient)
            val testCard = TestData.TestCards[providerToUse]?.get("Visa-No3DS-1") ?: throw AssertionError("Test card should not be null")
            val input =
                CreditCardFundingSourceInput(
                    testCard.creditCardNumber,
                    expirationMonth(),
                    expirationYear(),
                    testCard.securityCode,
                    testCard.address.addressLine1,
                    testCard.address.addressLine2,
                    testCard.address.city,
                    testCard.address.state,
                    testCard.address.postalCode,
                    testCard.address.country,
                    TestData.VerifiedUser.fullName,
                )
            val fundingSource =
                createCardFundingSource(
                    vcClient,
                    input,
                    CreateCardFundingSourceOptions(supportedProviders = listOf(providerToUse)),
                )
            fundingSource shouldNotBe null

            vcClient.createKeysIfAbsent()

            val sudo =
                createSudo(
                    TestData.sudo,
                )
            sudo.id shouldNotBe null

            val ownershipProof = getOwnershipProof(sudo)
            ownershipProof shouldNotBe null

            val provisionCardInput =
                ProvisionVirtualCardInput(
                    ownershipProofs = listOf(ownershipProof),
                    fundingSourceId = fundingSource.id,
                    cardHolder = TestData.ProvisionCardInput.cardHolder,
                    addressLine1 = TestData.ProvisionCardInput.addressLine1,
                    city = TestData.ProvisionCardInput.city,
                    state = TestData.ProvisionCardInput.state,
                    postalCode = TestData.ProvisionCardInput.postalCode,
                    country = TestData.ProvisionCardInput.country,
                    currency = TestData.ProvisionCardInput.currency,
                )
            val card = provisionVirtualCard(vcClient, provisionCardInput)
            card shouldNotBe null

            val updateCardInput =
                UpdateVirtualCardInput(
                    id = card.id,
                    expectedCardVersion = card.version,
                    cardHolder = "",
                    alias = "",
                    billingAddress = null,
                )

            val updatedCard = vcClient.updateVirtualCard(updateCardInput)
            updatedCard shouldNotBe null

            when (updatedCard) {
                is SingleAPIResult.Success -> {
                    updatedCard.result.id shouldBe card.id
                    updatedCard.result.cardHolder shouldNotBe card.cardHolder
                    updatedCard.result.alias shouldNotBe card.alias
                    updatedCard.result.billingAddress shouldNotBe card.billingAddress
                    updatedCard.result.billingAddress shouldBe null
                }
                else -> {
                    fail("Unexpected SingleAPIResult")
                }
            }
        }

    @Test
    fun updateVirtualCardShouldThrowWithNonExistentId() =
        runBlocking<Unit> {
            registerSignInAndEntitle()

            val updateCardInput =
                UpdateVirtualCardInput(
                    id = "NonExistentId",
                    cardHolder = "",
                    alias = "",
                    billingAddress = null,
                )

            vcClient.createKeysIfAbsent()

            shouldThrow<SudoVirtualCardsClient.VirtualCardException.CardNotFoundException> {
                vcClient.updateVirtualCard(updateCardInput)
            }
        }

    @Test
    fun cancelVirtualCardShouldReturnClosedCardResult() =
        runBlocking {
            registerSignInAndEntitle()
            verifyTestUserIdentity()

            val providerToUse = getProviderToUse(vcClient)
            val testCard = TestData.TestCards[providerToUse]?.get("Visa-No3DS-1") ?: throw AssertionError("Test card should not be null")
            val input =
                CreditCardFundingSourceInput(
                    testCard.creditCardNumber,
                    expirationMonth(),
                    expirationYear(),
                    testCard.securityCode,
                    testCard.address.addressLine1,
                    testCard.address.addressLine2,
                    testCard.address.city,
                    testCard.address.state,
                    testCard.address.postalCode,
                    testCard.address.country,
                    TestData.VerifiedUser.fullName,
                )
            val fundingSource =
                createCardFundingSource(
                    vcClient,
                    input,
                    CreateCardFundingSourceOptions(supportedProviders = listOf(providerToUse)),
                )
            fundingSource shouldNotBe null

            vcClient.createKeysIfAbsent()

            val sudo =
                createSudo(
                    TestData.sudo,
                )
            sudo.id shouldNotBe null

            val ownershipProof = getOwnershipProof(sudo)
            ownershipProof shouldNotBe null

            val provisionCardInput =
                ProvisionVirtualCardInput(
                    ownershipProofs = listOf(ownershipProof),
                    fundingSourceId = fundingSource.id,
                    cardHolder = TestData.ProvisionCardInput.cardHolder,
                    addressLine1 = TestData.ProvisionCardInput.addressLine1,
                    city = TestData.ProvisionCardInput.city,
                    state = TestData.ProvisionCardInput.state,
                    postalCode = TestData.ProvisionCardInput.postalCode,
                    country = TestData.ProvisionCardInput.country,
                    currency = TestData.ProvisionCardInput.currency,
                )
            val card = provisionVirtualCard(vcClient, provisionCardInput)
            card shouldNotBe null

            val cancelledCard = vcClient.cancelVirtualCard(card.id)
            cancelledCard shouldNotBe null

            when (cancelledCard) {
                is SingleAPIResult.Success -> {
                    cancelledCard.result.id shouldBe card.id
                    cancelledCard.result.cardNumber shouldBe card.cardNumber
                    cancelledCard.result.last4 shouldBe card.last4
                    cancelledCard.result.cardHolder shouldBe card.cardHolder
                    cancelledCard.result.alias shouldBe card.alias
                    cancelledCard.result.fundingSourceId shouldBe card.fundingSourceId
                    cancelledCard.result.state shouldBe CardState.CLOSED
                    cancelledCard.result.billingAddress shouldBe card.billingAddress
                    cancelledCard.result.currency shouldBe card.currency
                    cancelledCard.result.owner shouldBe card.owner
                    cancelledCard.result.owners
                        .first()
                        .id shouldBe card.owners.first().id
                    cancelledCard.result.owners
                        .first()
                        .issuer shouldBe card.owners.first().issuer
                    cancelledCard.result.cancelledAt shouldNotBe card.cancelledAt
                    cancelledCard.result.activeTo.time shouldBeGreaterThan 0L
                    cancelledCard.result.createdAt.time shouldBeGreaterThan 0L
                    cancelledCard.result.updatedAt.time shouldBeGreaterThan 0L
                    cancelledCard.result.expiry.mm
                        .toInt() shouldBeGreaterThan 0
                    cancelledCard.result.expiry.yyyy
                        .toInt() shouldBeGreaterThan 0
                    cancelledCard.result.version shouldBeGreaterThan 0
                }
                else -> {
                    fail("Unexpected SingleAPIResult")
                }
            }
        }

    @Test
    fun cancelVirtualCardShouldThrowWithNonExistentId() =
        runBlocking<Unit> {
            registerSignInAndEntitle()

            vcClient.createKeysIfAbsent()

            shouldThrow<SudoVirtualCardsClient.VirtualCardException.CardNotFoundException> {
                vcClient.cancelVirtualCard("NonExistentId")
            }
        }

    @Test
    fun createKeysIfAbsentShouldCreateKeysOnInitialize() =
        runBlocking {
            registerSignInAndEntitle()

            val result = vcClient.createKeysIfAbsent()

            with(result) {
                symmetricKey.keyId.isBlank() shouldBe false
                symmetricKey.created shouldBe true
                keyPair.keyId.isBlank() shouldBe false
                keyPair.created shouldBe true
            }
        }

    @Test
    fun createKeysIfAbsentShouldReturnExistingKeysOnSubsequentCalls() =
        runBlocking {
            registerSignInAndEntitle()

            val result = vcClient.createKeysIfAbsent()

            val result2 = vcClient.createKeysIfAbsent()

            with(result2) {
                symmetricKey.keyId shouldBe result.symmetricKey.keyId
                symmetricKey.created shouldBe false
                keyPair.keyId shouldBe result.keyPair.keyId
                keyPair.created shouldBe false
            }
        }

    @Test
    fun getTransactionShouldReturnNullForBogusId() =
        runBlocking {
            registerSignInAndEntitle()

            vcClient.createKeysIfAbsent()

            vcClient.getTransaction(UUID.randomUUID().toString()) shouldBe null
        }

    private val transactionSubscriber =
        object : TransactionSubscriber {
            override fun connectionStatusChanged(state: TransactionSubscriber.ConnectionState) { }

            override fun transactionChanged(transaction: Transaction) { }
        }

    private val fundingSourceSubscriber =
        object : FundingSourceSubscriber {
            override fun connectionStatusChanged(state: Subscriber.ConnectionState) { }

            override fun fundingSourceChanged(fundingSource: FundingSource) {}
        }

    @Test
    fun subscribeUnsubscribeShouldNotFail() =
        runBlocking {
            registerSignInAndEntitle()
            verifyTestUserIdentity()

            vcClient.subscribeToTransactions("id", transactionSubscriber)
            vcClient.unsubscribeAllFromTransactions()

            vcClient.subscribeToTransactions("id", transactionSubscriber)
            vcClient.unsubscribeFromTransactions("id")

            vcClient.subscribeToTransactions("id") { }

            vcClient.subscribeToFundingSources("id", fundingSourceSubscriber)
            vcClient.unsubscribeAllFromFundingSources()

            vcClient.subscribeToFundingSources("id", fundingSourceSubscriber)
            vcClient.unsubscribeFromFundingSources("id")

            vcClient.subscribeToFundingSources("id") { }

            vcClient.close()
        }

    @Test
    fun subscribeShouldThrowWhenNotAuthenticated() =
        runBlocking<Unit> {
            vcClient.unsubscribeFromTransactions("id")
            vcClient.unsubscribeAllFromTransactions()

            shouldThrow<SudoVirtualCardsClient.TransactionException.AuthenticationException> {
                vcClient.subscribeToTransactions("id", transactionSubscriber)
            }

            shouldThrow<SudoVirtualCardsClient.TransactionException.AuthenticationException> {
                vcClient.subscribeToTransactions("id") {}
            }

            vcClient.unsubscribeFromFundingSources("id")
            vcClient.unsubscribeAllFromFundingSources()
            shouldThrow<SudoVirtualCardsClient.FundingSourceException.AuthenticationException> {
                vcClient.subscribeToFundingSources("id", fundingSourceSubscriber)
            }

            shouldThrow<SudoVirtualCardsClient.FundingSourceException.AuthenticationException> {
                vcClient.subscribeToFundingSources("id") {}
            }
        }

    @Test
    fun resetShouldNotAffectOtherClients() =
        runBlocking {
            registerSignInAndEntitle()
            assumeTrue(userClient.isRegistered())

            verifyTestUserIdentity()

            val keyManager = KeyManagerFactory(context).createAndroidKeyManager("vc")
            keyManager.removeAllKeys()

            val vcClient =
                SudoVirtualCardsClient
                    .builder()
                    .setContext(context)
                    .setSudoUserClient(userClient)
                    .setKeyManager(keyManager)
                    .build()

            val providerToUse = getProviderToUse(vcClient)
            val testCard = TestData.TestCards[providerToUse]?.get("Visa-No3DS-1") ?: throw AssertionError("Test card should not be null")
            val input =
                CreditCardFundingSourceInput(
                    testCard.creditCardNumber,
                    expirationMonth(),
                    expirationYear(),
                    testCard.securityCode,
                    testCard.address.addressLine1,
                    testCard.address.addressLine2,
                    testCard.address.city,
                    testCard.address.state,
                    testCard.address.postalCode,
                    testCard.address.country,
                    TestData.VerifiedUser.fullName,
                )
            val fundingSource =
                createCardFundingSource(
                    vcClient,
                    input,
                    CreateCardFundingSourceOptions(supportedProviders = listOf(providerToUse)),
                )
            fundingSource shouldNotBe null

            vcClient.createKeysIfAbsent()

            val sudo =
                createSudo(
                    TestData.sudo,
                )
            sudo.id shouldNotBe null

            val ownershipProof = getOwnershipProof(sudo)
            ownershipProof shouldNotBe null

            val provisionCardInput =
                ProvisionVirtualCardInput(
                    ownershipProofs = listOf(ownershipProof),
                    fundingSourceId = fundingSource.id,
                    cardHolder = TestData.ProvisionCardInput.cardHolder,
                    addressLine1 = TestData.ProvisionCardInput.addressLine1,
                    city = TestData.ProvisionCardInput.city,
                    state = TestData.ProvisionCardInput.state,
                    postalCode = TestData.ProvisionCardInput.postalCode,
                    country = TestData.ProvisionCardInput.country,
                    currency = TestData.ProvisionCardInput.currency,
                )
            val card = provisionVirtualCard(vcClient, provisionCardInput)
            card shouldNotBe null

            keyManager.exportKeys().size shouldBe 5

            vcClient.reset()
            keyManager.exportKeys().size shouldBe 0
            assumeTrue(userClient.isRegistered())
        }

    @Test
    fun exportImportKeysShouldSucceed() =
        runBlocking {
            registerSignInAndEntitle()
            assumeTrue(userClient.isRegistered())

            verifyTestUserIdentity()

            val keyManager = KeyManagerFactory(context).createAndroidKeyManager("vc")
            keyManager.removeAllKeys()

            val vcClient =
                SudoVirtualCardsClient
                    .builder()
                    .setContext(context)
                    .setSudoUserClient(userClient)
                    .setKeyManager(keyManager)
                    .build()

            val providerToUse = getProviderToUse(vcClient)
            val testCard = TestData.TestCards[providerToUse]?.get("Visa-No3DS-1") ?: throw AssertionError("Test card should not be null")
            val input =
                CreditCardFundingSourceInput(
                    testCard.creditCardNumber,
                    expirationMonth(),
                    expirationYear(),
                    testCard.securityCode,
                    testCard.address.addressLine1,
                    testCard.address.addressLine2,
                    testCard.address.city,
                    testCard.address.state,
                    testCard.address.postalCode,
                    testCard.address.country,
                    TestData.VerifiedUser.fullName,
                )
            val fundingSource =
                createCardFundingSource(
                    vcClient,
                    input,
                    CreateCardFundingSourceOptions(supportedProviders = listOf(providerToUse)),
                )
            fundingSource shouldNotBe null

            vcClient.createKeysIfAbsent()

            val sudo =
                createSudo(
                    TestData.sudo,
                )
            sudo.id shouldNotBe null

            val ownershipProof = getOwnershipProof(sudo)
            ownershipProof shouldNotBe null

            val provisionCardInput =
                ProvisionVirtualCardInput(
                    ownershipProofs = listOf(ownershipProof),
                    fundingSourceId = fundingSource.id,
                    cardHolder = TestData.ProvisionCardInput.cardHolder,
                    addressLine1 = TestData.ProvisionCardInput.addressLine1,
                    city = TestData.ProvisionCardInput.city,
                    state = TestData.ProvisionCardInput.state,
                    postalCode = TestData.ProvisionCardInput.postalCode,
                    country = TestData.ProvisionCardInput.country,
                    currency = TestData.ProvisionCardInput.currency,
                )
            val card = provisionVirtualCard(vcClient, provisionCardInput)
            card shouldNotBe null

            val exportedKeys = vcClient.exportKeys()
            exportedKeys shouldNotBe null
            // remove all crypto keys from KeyManager
            vcClient.reset()

            try {
                vcClient.getVirtualCard(card.id)
                throw AssertionError("expected getVirtualCard to throw with no keys, but it succeeded")
            } catch (e: Throwable) {
                e.shouldBeInstanceOf<SudoVirtualCardsClient.VirtualCardException.PublicKeyException>()
            }

            // restore keys
            vcClient.importKeys(exportedKeys)

            val restoredKeysCard = vcClient.getVirtualCard(card.id)
            restoredKeysCard shouldBe card
        }
}
