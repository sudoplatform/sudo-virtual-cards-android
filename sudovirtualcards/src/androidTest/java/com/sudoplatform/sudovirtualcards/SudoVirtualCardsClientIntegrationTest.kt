/*
 * Copyright © 2022 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sudoplatform.sudovirtualcards.subscription.TransactionSubscriber
import io.kotlintest.matchers.numerics.shouldBeGreaterThan
import io.kotlintest.matchers.numerics.shouldBeGreaterThanOrEqual
import io.kotlintest.shouldBe
import io.kotlintest.shouldNotBe
import io.kotlintest.shouldThrow
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import timber.log.Timber
import java.util.logging.Logger
import com.sudoplatform.sudokeymanager.KeyManagerFactory
import com.sudoplatform.sudovirtualcards.logging.LogConstants
import com.sudoplatform.sudovirtualcards.types.CheckoutBankAccountProvisioningData
import com.sudoplatform.sudovirtualcards.types.CheckoutCardProviderCompletionData
import com.sudoplatform.sudovirtualcards.types.StripeCardProviderCompletionData
import com.sudoplatform.sudovirtualcards.types.StripeCardProvisioningData
import com.sudoplatform.sudovirtualcards.types.CheckoutCardProvisioningData
import com.sudoplatform.sudovirtualcards.types.ProvisionalFundingSource
import com.sudoplatform.sudovirtualcards.types.FundingSource
import com.sudoplatform.sudovirtualcards.types.ProvisionalVirtualCard
import com.sudoplatform.sudovirtualcards.types.CardState
import com.sudoplatform.sudovirtualcards.types.JsonValue
import com.sudoplatform.sudovirtualcards.types.ListAPIResult
import com.sudoplatform.sudovirtualcards.types.SingleAPIResult
import com.sudoplatform.sudovirtualcards.types.Transaction
import com.sudoplatform.sudovirtualcards.types.inputs.CompleteFundingSourceInput
import com.sudoplatform.sudovirtualcards.types.inputs.CreditCardFundingSourceInput
import com.sudoplatform.sudovirtualcards.types.inputs.FundingSourceType
import com.sudoplatform.sudovirtualcards.types.inputs.ProvisionVirtualCardInput
import com.sudoplatform.sudovirtualcards.types.inputs.SetupFundingSourceInput
import com.sudoplatform.sudovirtualcards.types.inputs.UpdateVirtualCardInput
import com.sudoplatform.sudovirtualcards.util.CreateCardFundingSourceOptions
import org.junit.Assert.fail
import java.util.UUID
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
        vcClient = SudoVirtualCardsClient.builder()
            .setContext(context)
            .setSudoUserClient(userClient)
            .build()
    }

    @After
    fun fini() = runBlocking {
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
            SudoVirtualCardsClient.builder()
                .setSudoUserClient(userClient)
                .build()
        }

        // SudoUserClient not provided
        shouldThrow<NullPointerException> {
            SudoVirtualCardsClient.builder()
                .setContext(context)
                .build()
        }
    }

    @Test
    fun shouldNotThrowIfTheRequiredItemsAreProvidedToBuilder() {
        SudoVirtualCardsClient.builder()
            .setContext(context)
            .setSudoUserClient(userClient)
            .build()
    }

    @Test
    fun shouldBeAbleToRegisterAndDeregister() = runBlocking {
        userClient.isRegistered() shouldBe false
        register()
        userClient.isRegistered() shouldBe true
        signIn()
        userClient.isSignedIn() shouldBe true
        deregister()
        userClient.isRegistered() shouldBe false
    }

    @Test
    fun setupFundingSourceShouldReturnProvisionalFundingSourceResult() = runBlocking {
        registerSignInAndEntitle()
        verifyTestUserIdentity()

        if (isStripeEnabled(vcClient)) {
            val setupStripeInput = SetupFundingSourceInput("USD", FundingSourceType.CREDIT_CARD, listOf("stripe"))
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

        if (isCheckoutEnabled(vcClient)) {
            val setupCheckoutCardInput = SetupFundingSourceInput("USD", FundingSourceType.CREDIT_CARD, listOf("checkout"))
            val checkoutCardProvisionalFundingSource = vcClient.setupFundingSource(setupCheckoutCardInput)

            with(checkoutCardProvisionalFundingSource) {
                id shouldNotBe null
                owner shouldBe userClient.getSubject()
                version shouldBe 1
                type shouldBe FundingSourceType.CREDIT_CARD
                state shouldBe ProvisionalFundingSource.ProvisioningState.PROVISIONING
                provisioningData.provider shouldBe "checkout"
                provisioningData.version shouldBe 1
                val checkoutProvisioningData = provisioningData as CheckoutCardProvisioningData
                checkoutProvisioningData shouldNotBe null
            }

            val setupCheckoutBankAccountInput = SetupFundingSourceInput("USD", FundingSourceType.BANK_ACCOUNT, listOf("checkout"))
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
    fun setupFundingSourceShouldThrowWithUnsupportedCurrency() = runBlocking<Unit> {
        registerSignInAndEntitle()
        verifyTestUserIdentity()

        val fundingSourceTypes = listOf(FundingSourceType.CREDIT_CARD, FundingSourceType.BANK_ACCOUNT)
        if (isStripeEnabled(vcClient)) {
            val stripeSetupInput = SetupFundingSourceInput("AUD", fundingSourceTypes.first(), listOf("stripe"))
            shouldThrow<SudoVirtualCardsClient.FundingSourceException.UnsupportedCurrencyException> {
                vcClient.setupFundingSource(stripeSetupInput)
            }
        }

        if (isCheckoutEnabled(vcClient)) {
            fundingSourceTypes.forEach {
                val checkoutSetupInput = SetupFundingSourceInput("AUD", it, listOf("checkout"))
                shouldThrow<SudoVirtualCardsClient.FundingSourceException.UnsupportedCurrencyException> {
                    vcClient.setupFundingSource(checkoutSetupInput)
                }
            }
        }
    }

    @Test
    fun completeFundingSourceShouldReturnFundingSourceResult() = runBlocking {
        registerSignInAndEntitle()
        verifyTestUserIdentity()

        getProvidersList(vcClient).forEach {
            val testCard = TestData.TestCards[it]?.get("Visa-No3DS-1") ?: throw AssertionError("Unable to locate test card")

            val input = CreditCardFundingSourceInput(
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
                TestData.VerifiedUser.fullName
            )

            val fundingSource = createCardFundingSource(
                vcClient,
                input,
                CreateCardFundingSourceOptions(supportedProviders = listOf(it))
            )

            with(fundingSource) {
                id shouldNotBe null
                owner shouldBe userClient.getSubject()
                version shouldBe 1
                createdAt.time shouldBeGreaterThan 0L
                updatedAt.time shouldBeGreaterThan 0L
                state shouldBe FundingSource.State.ACTIVE
                currency shouldBe "USD"
                last4 shouldBe testCard.last4
                network shouldBe FundingSource.CreditCardNetwork.VISA
            }
        }
    }

    @Test
    fun completeFundingSourceShouldThrowWithProvisionalFundingSourceNotFound() = runBlocking<Unit> {
        registerSignInAndEntitle()
        verifyTestUserIdentity()

        if (isStripeEnabled(vcClient)) {
            val stripeInput = CompleteFundingSourceInput(
                UUID.randomUUID().toString(),
                StripeCardProviderCompletionData(
                    "stripe",
                    1,
                    "paymentMethod",
                    com.sudoplatform.sudovirtualcards.graphql.type.FundingSourceType.CREDIT_CARD

                ),
                null
            )
            shouldThrow<SudoVirtualCardsClient.FundingSourceException.ProvisionalFundingSourceNotFoundException> {
                vcClient.completeFundingSource(stripeInput)
            }
        }

        if (isCheckoutEnabled(vcClient)) {
            val checkoutInput = CompleteFundingSourceInput(
                UUID.randomUUID().toString(),
                CheckoutCardProviderCompletionData(
                    "stripe",
                    1,
                    com.sudoplatform.sudovirtualcards.graphql.type.FundingSourceType.CREDIT_CARD,
                    "paymentToken"

                ),
                null
            )
            shouldThrow<SudoVirtualCardsClient.FundingSourceException.ProvisionalFundingSourceNotFoundException> {
                vcClient.completeFundingSource(checkoutInput)
            }
        }
    }

    @Test
    fun completeFundingSourceShouldThrowWithCompletionDataInvalid() = runBlocking {
        registerSignInAndEntitle()
        verifyTestUserIdentity()

        if (isStripeEnabled(vcClient)) {
            val stripeSetupInput = SetupFundingSourceInput(
                "USD",
                FundingSourceType.CREDIT_CARD,
                listOf("stripe")
            )
            val stripeProvisionalFundingSource = vcClient.setupFundingSource(stripeSetupInput)

            val stripeCompleteInput = CompleteFundingSourceInput(
                stripeProvisionalFundingSource.id,
                StripeCardProviderCompletionData(
                    "stripe",
                    1,
                    "paymentMethod",
                    com.sudoplatform.sudovirtualcards.graphql.type.FundingSourceType.CREDIT_CARD
                ),
                null
            )
            shouldThrow<SudoVirtualCardsClient.FundingSourceException> {
                vcClient.completeFundingSource(stripeCompleteInput)
            }
        }
        if (isCheckoutEnabled(vcClient)) {
            val checkoutSetupInput = SetupFundingSourceInput(
                "USD",
                FundingSourceType.CREDIT_CARD,
                listOf("checkout")
            )
            val checkoutProvisionalFundingSource = vcClient.setupFundingSource(checkoutSetupInput)

            val checkoutCompleteInput = CompleteFundingSourceInput(
                checkoutProvisionalFundingSource.id,
                CheckoutCardProviderCompletionData(
                    "checkout",
                    1,
                    com.sudoplatform.sudovirtualcards.graphql.type.FundingSourceType.CREDIT_CARD,
                    "paymentToken"
                ),
                null
            )
            shouldThrow<SudoVirtualCardsClient.FundingSourceException> {
                vcClient.completeFundingSource(checkoutCompleteInput)
            }
        }
    }

    @Test
    fun completeCheckoutFundingSourceShouldThrowWithUserInteractionRequired() = runBlocking {
        registerSignInAndEntitle()
        verifyTestUserIdentity()

        if (isCheckoutEnabled(vcClient)) {
            val provider = "checkout"
            val testCard = TestData.TestCards[provider]?.get("Visa-3DS2-1") ?: throw AssertionError("Unable to locate test card")

            val input = CreditCardFundingSourceInput(
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
                TestData.VerifiedUser.fullName
            )

            shouldThrow<SudoVirtualCardsClient.FundingSourceException.FundingSourceRequiresUserInteractionException> {
                createCardFundingSource(
                    vcClient,
                    input,
                    CreateCardFundingSourceOptions(supportedProviders = listOf(provider))
                )
            }
        }
    }

    @Test
    fun completeCheckoutFundingSourceShouldThrowWithUnacceptableForBadAVSCheck() = runBlocking {
        registerSignInAndEntitle()
        verifyTestUserIdentity()

        if (isCheckoutEnabled(vcClient)) {
            val provider = "checkout"
            val testCard = TestData.TestCards[provider]?.get("BadAddress") ?: throw AssertionError("Unable to locate test card")

            val input = CreditCardFundingSourceInput(
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
                TestData.VerifiedUser.fullName
            )

            shouldThrow<SudoVirtualCardsClient.FundingSourceException.UnacceptableFundingSourceException> {
                createCardFundingSource(
                    vcClient,
                    input,
                    CreateCardFundingSourceOptions(supportedProviders = listOf(provider))
                )
            }
        }
    }

    @Test
    fun completeCheckoutFundingSourceShouldThrowWithUnacceptableForBadCVVCheck() = runBlocking {
        registerSignInAndEntitle()
        verifyTestUserIdentity()

        if (isCheckoutEnabled(vcClient)) {
            val provider = "checkout"
            val testCard = TestData.TestCards[provider]?.get("BadCVV") ?: throw AssertionError("Unable to locate test card")

            val input = CreditCardFundingSourceInput(
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
                TestData.VerifiedUser.fullName
            )

            shouldThrow<SudoVirtualCardsClient.FundingSourceException.UnacceptableFundingSourceException> {
                createCardFundingSource(
                    vcClient,
                    input,
                    CreateCardFundingSourceOptions(supportedProviders = listOf(provider))
                )
            }
        }
    }

    @Test
    fun getFundingSourceShouldReturnFundingSourceResult() = runBlocking {
        registerSignInAndEntitle()
        verifyTestUserIdentity()

        getProvidersList(vcClient).forEach {
            val testCard = TestData.TestCards[it]?.get("Visa-No3DS-1") ?: throw AssertionError("Test card should not be null")
            val input = CreditCardFundingSourceInput(
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
                TestData.VerifiedUser.fullName
            )
            val fundingSource = createCardFundingSource(
                vcClient,
                input,
                CreateCardFundingSourceOptions(supportedProviders = listOf(it))
            )
            fundingSource shouldNotBe null

            val retrievedFundingSource = vcClient.getFundingSource(fundingSource.id)
                ?: throw AssertionError("should not be null")

            retrievedFundingSource.id shouldBe fundingSource.id
            retrievedFundingSource.owner shouldBe fundingSource.owner
            retrievedFundingSource.version shouldBe fundingSource.version
            retrievedFundingSource.state shouldBe fundingSource.state
            retrievedFundingSource.currency shouldBe fundingSource.currency
            retrievedFundingSource.last4 shouldBe fundingSource.last4
            retrievedFundingSource.network shouldBe fundingSource.network
        }
    }

    @Test
    fun getFundingSourceShouldReturnNullForNonExistentId() = runBlocking {
        registerSignInAndEntitle()

        val retrievedFundingSource = vcClient.getFundingSource("NonExistentId")
        retrievedFundingSource shouldBe null
    }

    @Test
    fun listFundingSourcesShouldReturnSingleFundingSourceListOutputResult() = runBlocking {
        registerSignInAndEntitle()
        verifyTestUserIdentity()

        val providerToUse = getProviderToUse(vcClient)
        val testCard = TestData.TestCards[providerToUse]?.get("Visa-No3DS-1") ?: throw AssertionError("Test card should not be null")
        val input = CreditCardFundingSourceInput(
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
            TestData.VerifiedUser.fullName
        )
        val fundingSource = createCardFundingSource(
            vcClient,
            input,
            CreateCardFundingSourceOptions()
        )
        fundingSource shouldNotBe null

        val listFundingSources = vcClient.listFundingSources()
        listFundingSources.items.isEmpty() shouldBe false
        listFundingSources.items.size shouldBe 1
        listFundingSources.nextToken shouldBe null

        val fundingSources = listFundingSources.items
        fundingSources[0].id shouldBe fundingSource.id
        fundingSources[0].owner shouldBe fundingSource.owner
        fundingSources[0].version shouldBe fundingSource.version
        fundingSources[0].state shouldBe fundingSource.state
        fundingSources[0].currency shouldBe fundingSource.currency
        fundingSources[0].last4 shouldBe fundingSource.last4
        fundingSources[0].network shouldBe fundingSource.network
    }

    @Test
    fun listFundingSourcesShouldReturnMultipleFundingSourceListOutputResult() = runBlocking {
        registerSignInAndEntitle()
        verifyTestUserIdentity()

        val providers = getProvidersList(vcClient)
        val createdFundingSources = mutableListOf<FundingSource>()
        var fundingSourceCount = 0
        providers.forEach {
            val vTestCard = TestData.TestCards[it]?.get("Visa-No3DS-1") ?: throw AssertionError("Test card should not be null")
            val input1 = CreditCardFundingSourceInput(
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
                TestData.VerifiedUser.fullName
            )
            val fundingSource1 = createCardFundingSource(
                vcClient,
                input1,
                CreateCardFundingSourceOptions(supportedProviders = listOf(it))
            )
            fundingSource1 shouldNotBe null
            createdFundingSources.add(fundingSource1)
            ++fundingSourceCount

            if (it != "checkout") {
                // TODO: skipping MC-no3DS because checkout does not conform to no-3DS ruling and requires user interaction
                val mcTestCard = TestData.TestCards[it]?.get("MC-No3DS-1") ?: throw AssertionError("Test card should not be null")
                val input2 = CreditCardFundingSourceInput(
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
                    TestData.VerifiedUser.fullName
                )
                val fundingSource2 = createCardFundingSource(
                    vcClient,
                    input2,
                    CreateCardFundingSourceOptions(supportedProviders = listOf(it))
                )
                fundingSource2 shouldNotBe null
                createdFundingSources.add(fundingSource2)
                ++fundingSourceCount
            }
        }
        fundingSourceCount shouldBeGreaterThan 0
        val listFundingSources = vcClient.listFundingSources()
        listFundingSources.items.isEmpty() shouldBe false
        listFundingSources.items.size shouldBe fundingSourceCount
        listFundingSources.nextToken shouldBe null

        val fundingSources = listFundingSources.items
        for (i in 0 until fundingSourceCount) {
            fundingSources[i].id shouldBe createdFundingSources[i].id
            fundingSources[i].owner shouldBe createdFundingSources[i].owner
            fundingSources[i].version shouldBe createdFundingSources[i].version
            fundingSources[i].state shouldBe createdFundingSources[i].state
            fundingSources[i].currency shouldBe createdFundingSources[i].currency
            fundingSources[i].last4 shouldBe createdFundingSources[i].last4
            fundingSources[i].network shouldBe createdFundingSources[i].network
        }
    }

    @Test
    fun cancelFundingSourceShouldReturnInactiveFundingSourceResult() = runBlocking {
        registerSignInAndEntitle()
        verifyTestUserIdentity()

        getProvidersList(vcClient).forEach {
            val testCard = TestData.TestCards[it]?.get("Visa-No3DS-1") ?: throw AssertionError("Test card should not be null")
            val input = CreditCardFundingSourceInput(
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
                TestData.VerifiedUser.fullName
            )
            val fundingSource = createCardFundingSource(
                vcClient,
                input,
                CreateCardFundingSourceOptions(supportedProviders = listOf(it))
            )
            fundingSource shouldNotBe null

            val cancelledFundingSource = vcClient.cancelFundingSource(fundingSource.id)
            cancelledFundingSource.id shouldBe fundingSource.id
            cancelledFundingSource.owner shouldBe fundingSource.owner
            cancelledFundingSource.version shouldBe 2
            cancelledFundingSource.state shouldBe FundingSource.State.INACTIVE
            cancelledFundingSource.currency shouldBe fundingSource.currency
            cancelledFundingSource.last4 shouldBe fundingSource.last4
            cancelledFundingSource.network shouldBe fundingSource.network
        }
    }

    @Test
    fun cancelFundingSourceShouldThrowWithNonExistentId() = runBlocking<Unit> {
        registerSignInAndEntitle()

        shouldThrow<SudoVirtualCardsClient.FundingSourceException.FundingSourceNotFoundException> {
            vcClient.cancelFundingSource("NonExistentId")
        }
    }

    @Test
    fun provisionVirtualCardShouldReturnPendingCard() = runBlocking {
        registerSignInAndEntitle()
        verifyTestUserIdentity()

        val providerToUse = getProviderToUse(vcClient)
        val testCard = TestData.TestCards[providerToUse]?.get("Visa-No3DS-1") ?: throw AssertionError("Test card should not be null")
        val input = CreditCardFundingSourceInput(
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
            TestData.VerifiedUser.fullName
        )
        val fundingSource = createCardFundingSource(
            vcClient,
            input,
            CreateCardFundingSourceOptions(supportedProviders = listOf(providerToUse))
        )
        fundingSource shouldNotBe null

        val sudo = createSudo(TestData.sudo)
        sudo.id shouldNotBe null

        val ownershipProof = getOwnershipProof(sudo)
        ownershipProof shouldNotBe null

        vcClient.createKeysIfAbsent()

        val provisionCardInput = ProvisionVirtualCardInput(
            ownershipProofs = listOf(ownershipProof),
            fundingSourceId = fundingSource.id,
            cardHolder = "Unlimited Cards",
            metadata = null,
            addressLine1 = "123 Nowhere St",
            city = "Menlo Park",
            state = "CA",
            postalCode = "94025",
            country = "US",
            currency = "USD"
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
                provisionalCard2?.version ?: 0 shouldBeGreaterThanOrEqual provisionalCard1.version
                provisionalCard2?.createdAt?.time ?: 0L shouldBe provisionalCard1.createdAt
                provisionalCard2?.updatedAt?.time ?: 0L shouldBeGreaterThan 0L

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
    fun provisionVirtualCardWithStringMetadata() = runBlocking {
        registerSignInAndEntitle()
        verifyTestUserIdentity()

        val providerToUse = getProviderToUse(vcClient)
        val testCard = TestData.TestCards[providerToUse]?.get("Visa-No3DS-1") ?: throw AssertionError("Test card should not be null")
        val fundingSourceInput = CreditCardFundingSourceInput(
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
            TestData.VerifiedUser.fullName
        )
        val fundingSource = createCardFundingSource(
            vcClient,
            fundingSourceInput,
            CreateCardFundingSourceOptions(supportedProviders = listOf(providerToUse))
        )
        fundingSource shouldNotBe null

        val sudo = createSudo(
            TestData.sudo
        )
        sudo.id shouldNotBe null

        val ownershipProof = getOwnershipProof(sudo)
        ownershipProof shouldNotBe null

        vcClient.createKeysIfAbsent()

        val provisionCardInput = ProvisionVirtualCardInput(
            ownershipProofs = listOf(ownershipProof),
            fundingSourceId = fundingSource.id,
            cardHolder = TestData.ProvisionCardInput.cardHolder,
            metadata = JsonValue.JsonString("Ted Bear"),
            addressLine1 = TestData.ProvisionCardInput.addressLine1,
            city = TestData.ProvisionCardInput.city,
            state = TestData.ProvisionCardInput.state,
            postalCode = TestData.ProvisionCardInput.postalCode,
            country = TestData.ProvisionCardInput.country,
            currency = TestData.ProvisionCardInput.currency
        )
        val card = provisionVirtualCard(vcClient, provisionCardInput)

        with(card) {
            metadata shouldBe JsonValue.JsonString("Ted Bear")
            metadata?.unwrap() shouldBe "Ted Bear"
        }
    }

    @Test
    fun provisionVirtualCardWithIntegerMetadata() = runBlocking {
        registerSignInAndEntitle()
        verifyTestUserIdentity()

        val providerToUse = getProviderToUse(vcClient)
        val testCard = TestData.TestCards[providerToUse]?.get("Visa-No3DS-1") ?: throw AssertionError("Test card should not be null")
        val fundingSourceInput = CreditCardFundingSourceInput(
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
            TestData.VerifiedUser.fullName
        )
        val fundingSource = createCardFundingSource(
            vcClient,
            fundingSourceInput,
            CreateCardFundingSourceOptions(supportedProviders = listOf(providerToUse))
        )
        fundingSource shouldNotBe null

        val sudo = createSudo(
            TestData.sudo
        )
        sudo.id shouldNotBe null

        val ownershipProof = getOwnershipProof(sudo)
        ownershipProof shouldNotBe null

        vcClient.createKeysIfAbsent()

        val provisionCardInput = ProvisionVirtualCardInput(
            ownershipProofs = listOf(ownershipProof),
            fundingSourceId = fundingSource.id,
            cardHolder = TestData.ProvisionCardInput.cardHolder,
            metadata = JsonValue.JsonInteger(42),
            addressLine1 = TestData.ProvisionCardInput.addressLine1,
            city = TestData.ProvisionCardInput.city,
            state = TestData.ProvisionCardInput.state,
            postalCode = TestData.ProvisionCardInput.postalCode,
            country = TestData.ProvisionCardInput.country,
            currency = TestData.ProvisionCardInput.currency
        )
        val card = provisionVirtualCard(vcClient, provisionCardInput)

        with(card) {
            metadata shouldBe JsonValue.JsonInteger(42)
            metadata?.unwrap() shouldBe 42
        }
    }

    @Test
    fun provisionVirtualCardWithDoubleMetadata() = runBlocking {
        registerSignInAndEntitle()
        verifyTestUserIdentity()

        val providerToUse = getProviderToUse(vcClient)
        val testCard = TestData.TestCards[providerToUse]?.get("Visa-No3DS-1") ?: throw AssertionError("Test card should not be null")
        val fundingSourceInput = CreditCardFundingSourceInput(
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
            TestData.VerifiedUser.fullName
        )
        val fundingSource = createCardFundingSource(
            vcClient,
            fundingSourceInput,
            CreateCardFundingSourceOptions(supportedProviders = listOf(providerToUse))
        )
        fundingSource shouldNotBe null

        val sudo = createSudo(
            TestData.sudo
        )
        sudo.id shouldNotBe null

        val ownershipProof = getOwnershipProof(sudo)
        ownershipProof shouldNotBe null

        vcClient.createKeysIfAbsent()

        val provisionCardInput = ProvisionVirtualCardInput(
            ownershipProofs = listOf(ownershipProof),
            fundingSourceId = fundingSource.id,
            cardHolder = TestData.ProvisionCardInput.cardHolder,
            metadata = JsonValue.JsonDouble(42.5),
            addressLine1 = TestData.ProvisionCardInput.addressLine1,
            city = TestData.ProvisionCardInput.city,
            state = TestData.ProvisionCardInput.state,
            postalCode = TestData.ProvisionCardInput.postalCode,
            country = TestData.ProvisionCardInput.country,
            currency = TestData.ProvisionCardInput.currency
        )
        val card = provisionVirtualCard(vcClient, provisionCardInput)

        with(card) {
            metadata shouldBe JsonValue.JsonDouble(42.5)
            metadata?.unwrap() shouldBe 42.5
        }
    }

    @Test
    fun provisionVirtualCardWithBooleanMetadata() = runBlocking {
        registerSignInAndEntitle()
        verifyTestUserIdentity()

        val providerToUse = getProviderToUse(vcClient)
        val testCard = TestData.TestCards[providerToUse]?.get("Visa-No3DS-1") ?: throw AssertionError("Test card should not be null")
        val fundingSourceInput = CreditCardFundingSourceInput(
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
            TestData.VerifiedUser.fullName
        )
        val fundingSource = createCardFundingSource(
            vcClient,
            fundingSourceInput,
            CreateCardFundingSourceOptions(supportedProviders = listOf(providerToUse))
        )
        fundingSource shouldNotBe null

        val sudo = createSudo(
            TestData.sudo
        )
        sudo.id shouldNotBe null

        val ownershipProof = getOwnershipProof(sudo)
        ownershipProof shouldNotBe null

        vcClient.createKeysIfAbsent()

        val provisionCardInput = ProvisionVirtualCardInput(
            ownershipProofs = listOf(ownershipProof),
            fundingSourceId = fundingSource.id,
            cardHolder = TestData.ProvisionCardInput.cardHolder,
            metadata = JsonValue.JsonBoolean(true),
            addressLine1 = TestData.ProvisionCardInput.addressLine1,
            city = TestData.ProvisionCardInput.city,
            state = TestData.ProvisionCardInput.state,
            postalCode = TestData.ProvisionCardInput.postalCode,
            country = TestData.ProvisionCardInput.country,
            currency = TestData.ProvisionCardInput.currency
        )
        val card = provisionVirtualCard(vcClient, provisionCardInput)

        with(card) {
            metadata shouldBe JsonValue.JsonBoolean(true)
            metadata?.unwrap() shouldBe true
        }
    }

    @Test
    fun provisionVirtualCardWithArrayMetadata() = runBlocking {
        registerSignInAndEntitle()
        verifyTestUserIdentity()

        val providerToUse = getProviderToUse(vcClient)
        val testCard = TestData.TestCards[providerToUse]?.get("Visa-No3DS-1") ?: throw AssertionError("Test card should not be null")
        val fundingSourceInput = CreditCardFundingSourceInput(
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
            TestData.VerifiedUser.fullName
        )
        val fundingSource = createCardFundingSource(
            vcClient,
            fundingSourceInput,
            CreateCardFundingSourceOptions(supportedProviders = listOf(providerToUse))
        )
        fundingSource shouldNotBe null

        val sudo = createSudo(
            TestData.sudo
        )
        sudo.id shouldNotBe null

        val ownershipProof = getOwnershipProof(sudo)
        ownershipProof shouldNotBe null

        vcClient.createKeysIfAbsent()

        val provisionCardInput = ProvisionVirtualCardInput(
            ownershipProofs = listOf(ownershipProof),
            fundingSourceId = fundingSource.id,
            cardHolder = TestData.ProvisionCardInput.cardHolder,
            metadata = JsonValue.JsonArray(listOf("foobar", false)),
            addressLine1 = TestData.ProvisionCardInput.addressLine1,
            city = TestData.ProvisionCardInput.city,
            state = TestData.ProvisionCardInput.state,
            postalCode = TestData.ProvisionCardInput.postalCode,
            country = TestData.ProvisionCardInput.country,
            currency = TestData.ProvisionCardInput.currency
        )
        val card = provisionVirtualCard(vcClient, provisionCardInput)

        with(card) {
            metadata shouldBe JsonValue.JsonArray(listOf("foobar", false))
            metadata?.unwrap() shouldBe listOf("foobar", false)
        }
    }

    @Test
    fun provisionVirtualCardWithMapMetadata() = runBlocking {
        registerSignInAndEntitle()
        verifyTestUserIdentity()

        val providerToUse = getProviderToUse(vcClient)
        val testCard = TestData.TestCards[providerToUse]?.get("Visa-No3DS-1") ?: throw AssertionError("Test card should not be null")
        val fundingSourceInput = CreditCardFundingSourceInput(
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
            TestData.VerifiedUser.fullName
        )
        val fundingSource = createCardFundingSource(
            vcClient,
            fundingSourceInput,
            CreateCardFundingSourceOptions(supportedProviders = listOf(providerToUse))
        )
        fundingSource shouldNotBe null

        val sudo = createSudo(
            TestData.sudo
        )
        sudo.id shouldNotBe null

        val ownershipProof = getOwnershipProof(sudo)
        ownershipProof shouldNotBe null

        vcClient.createKeysIfAbsent()

        val provisionCardInput = ProvisionVirtualCardInput(
            ownershipProofs = listOf(ownershipProof),
            fundingSourceId = fundingSource.id,
            cardHolder = TestData.ProvisionCardInput.cardHolder,
            metadata = JsonValue.JsonMap(
                mapOf(
                    "alias" to "Ted Bear",
                    "something" to true,
                )
            ),
            addressLine1 = TestData.ProvisionCardInput.addressLine1,
            city = TestData.ProvisionCardInput.city,
            state = TestData.ProvisionCardInput.state,
            postalCode = TestData.ProvisionCardInput.postalCode,
            country = TestData.ProvisionCardInput.country,
            currency = TestData.ProvisionCardInput.currency
        )
        val card = provisionVirtualCard(vcClient, provisionCardInput)

        with(card) {
            metadata shouldBe JsonValue.JsonMap(
                mapOf(
                    "alias" to "Ted Bear",
                    "something" to true
                )
            )
            metadata?.unwrap() shouldBe mapOf(
                "alias" to "Ted Bear",
                "something" to true
            )
        }
    }

    @Test
    fun getVirtualCardShouldReturnProvisionedCardResult() = runBlocking {
        registerSignInAndEntitle()
        verifyTestUserIdentity()

        val providerToUse = getProviderToUse(vcClient)
        val testCard = TestData.TestCards[providerToUse]?.get("Visa-No3DS-1") ?: throw AssertionError("Test card should not be null")
        val input = CreditCardFundingSourceInput(
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
            TestData.VerifiedUser.fullName
        )
        val fundingSource = createCardFundingSource(
            vcClient,
            input,
            CreateCardFundingSourceOptions(supportedProviders = listOf(providerToUse))
        )
        fundingSource shouldNotBe null

        vcClient.createKeysIfAbsent()

        val sudo = createSudo(
            TestData.sudo
        )
        sudo.id shouldNotBe null

        val ownershipProof = getOwnershipProof(sudo)
        ownershipProof shouldNotBe null

        val provisionCardInput = ProvisionVirtualCardInput(
            ownershipProofs = listOf(ownershipProof),
            fundingSourceId = fundingSource.id,
            cardHolder = TestData.ProvisionCardInput.cardHolder,
            addressLine1 = TestData.ProvisionCardInput.addressLine1,
            city = TestData.ProvisionCardInput.city,
            state = TestData.ProvisionCardInput.state,
            postalCode = TestData.ProvisionCardInput.postalCode,
            country = TestData.ProvisionCardInput.country,
            currency = TestData.ProvisionCardInput.currency
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
    fun getVirtualCardShouldReturnNullForNonExistentId() = runBlocking {
        registerSignInAndEntitle()
        vcClient.createKeysIfAbsent()

        val retrievedCard = vcClient.getVirtualCard("NonExistentId")
        retrievedCard shouldBe null
    }

    @Test
    fun getVirtualCardsConfigShouldReturnVirtualCardsConfig() = runBlocking {
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
        virtualCardsConfig.maxFundingSourceVelocity.forEach { verifyVelocity(it) }
        virtualCardsConfig.maxFundingSourceFailureVelocity.forEach { verifyVelocity(it) }
        virtualCardsConfig.maxCardCreationVelocity.forEach { verifyVelocity(it) }
        virtualCardsConfig.maxTransactionVelocity.size shouldBeGreaterThanOrEqual 1
        virtualCardsConfig.maxTransactionAmount.size shouldBeGreaterThanOrEqual 1
        virtualCardsConfig.virtualCardCurrencies.size shouldBeGreaterThanOrEqual 1
        virtualCardsConfig.fundingSourceSupportInfo.size shouldBeGreaterThanOrEqual 1
    }

    @Test
    fun listVirtualCardsShouldReturnSingleCardListOutputResult() = runBlocking {
        registerSignInAndEntitle()
        verifyTestUserIdentity()

        val providerToUse = getProviderToUse(vcClient)
        val testCard = TestData.TestCards[providerToUse]?.get("Visa-No3DS-1") ?: throw AssertionError("Test card should not be null")
        val input = CreditCardFundingSourceInput(
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
            TestData.VerifiedUser.fullName
        )
        val fundingSource = createCardFundingSource(
            vcClient,
            input,
            CreateCardFundingSourceOptions(supportedProviders = listOf(providerToUse))
        )
        fundingSource shouldNotBe null

        vcClient.createKeysIfAbsent()

        val sudo = createSudo(
            TestData.sudo
        )
        sudo.id shouldNotBe null

        val ownershipProof = getOwnershipProof(sudo)
        ownershipProof shouldNotBe null

        val provisionCardInput = ProvisionVirtualCardInput(
            ownershipProofs = listOf(ownershipProof),
            fundingSourceId = fundingSource.id,
            cardHolder = TestData.ProvisionCardInput.cardHolder,
            addressLine1 = TestData.ProvisionCardInput.addressLine1,
            city = TestData.ProvisionCardInput.city,
            state = TestData.ProvisionCardInput.state,
            postalCode = TestData.ProvisionCardInput.postalCode,
            country = TestData.ProvisionCardInput.country,
            currency = TestData.ProvisionCardInput.currency
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
    fun listVirtualCardsShouldReturnMultipleCardListOutputResult() = runBlocking {
        registerSignInAndEntitle()
        verifyTestUserIdentity()

        val providerToUse = getProviderToUse(vcClient)
        val testCard = TestData.TestCards[providerToUse]?.get("Visa-No3DS-1") ?: throw AssertionError("Test card should not be null")
        val input = CreditCardFundingSourceInput(
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
            TestData.VerifiedUser.fullName
        )
        val fundingSource = createCardFundingSource(
            vcClient,
            input,
            CreateCardFundingSourceOptions(supportedProviders = listOf(providerToUse))
        )
        fundingSource shouldNotBe null

        vcClient.createKeysIfAbsent()

        val sudo = createSudo(
            TestData.sudo
        )
        sudo.id shouldNotBe null

        val ownershipProof = getOwnershipProof(sudo)
        ownershipProof shouldNotBe null

        val provisionCardInput = ProvisionVirtualCardInput(
            ownershipProofs = listOf(ownershipProof),
            fundingSourceId = fundingSource.id,
            cardHolder = TestData.ProvisionCardInput.cardHolder,
            addressLine1 = TestData.ProvisionCardInput.addressLine1,
            city = TestData.ProvisionCardInput.city,
            state = TestData.ProvisionCardInput.state,
            postalCode = TestData.ProvisionCardInput.postalCode,
            country = TestData.ProvisionCardInput.country,
            currency = TestData.ProvisionCardInput.currency
        )

        val card1 = provisionVirtualCard(vcClient, provisionCardInput)
        card1 shouldNotBe null
        Thread.sleep(1)
        val card2 = provisionVirtualCard(vcClient, provisionCardInput)
        card2 shouldNotBe null

        card2.createdAt.time shouldBeGreaterThan card1.createdAt.time

        // Since card2 is created after card1 it should be returned first in the list
        val expectedCards = arrayOf(card2, card1)

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
    }

    @Test
    fun updateVirtualCardShouldReturnUpdatedCardResult() = runBlocking {
        registerSignInAndEntitle()
        verifyTestUserIdentity()

        val providerToUse = getProviderToUse(vcClient)
        val testCard = TestData.TestCards[providerToUse]?.get("Visa-No3DS-1") ?: throw AssertionError("Test card should not be null")
        val input = CreditCardFundingSourceInput(
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
            TestData.VerifiedUser.fullName
        )
        val fundingSource = createCardFundingSource(
            vcClient,
            input,
            CreateCardFundingSourceOptions(supportedProviders = listOf(providerToUse))
        )
        fundingSource shouldNotBe null

        vcClient.createKeysIfAbsent()

        val sudo = createSudo(
            TestData.sudo
        )
        sudo.id shouldNotBe null

        val ownershipProof = getOwnershipProof(sudo)
        ownershipProof shouldNotBe null

        val provisionCardInput = ProvisionVirtualCardInput(
            ownershipProofs = listOf(ownershipProof),
            fundingSourceId = fundingSource.id,
            cardHolder = TestData.ProvisionCardInput.cardHolder,
            metadata = JsonValue.JsonString("Ted Bear"),
            addressLine1 = TestData.ProvisionCardInput.addressLine1,
            city = TestData.ProvisionCardInput.city,
            state = TestData.ProvisionCardInput.state,
            postalCode = TestData.ProvisionCardInput.postalCode,
            country = TestData.ProvisionCardInput.country,
            currency = TestData.ProvisionCardInput.currency
        )
        val card = provisionVirtualCard(vcClient, provisionCardInput)
        card shouldNotBe null

        val updateCardInput = UpdateVirtualCardInput(
            id = card.id,
            expectedCardVersion = card.version,
            cardHolder = "Not Unlimited Cards",
            metadata = JsonValue.JsonString("Bed Tear"),
            addressLine1 = "321 Somewhere St",
            city = "Olnem Park",
            state = "NY",
            postalCode = "52049",
            country = "US"
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
            else -> { fail("Unexpected SingleAPIResult") }
        }
    }

    @Test
    fun updateVirtualCardShouldReturnPartiallyUpdatedCardResult() = runBlocking {
        registerSignInAndEntitle()
        verifyTestUserIdentity()

        val providerToUse = getProviderToUse(vcClient)
        val testCard = TestData.TestCards[providerToUse]?.get("Visa-No3DS-1") ?: throw AssertionError("Test card should not be null")
        val input = CreditCardFundingSourceInput(
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
            TestData.VerifiedUser.fullName
        )
        val fundingSource = createCardFundingSource(
            vcClient,
            input,
            CreateCardFundingSourceOptions(supportedProviders = listOf(providerToUse))
        )
        fundingSource shouldNotBe null

        vcClient.createKeysIfAbsent()

        val sudo = createSudo(
            TestData.sudo
        )
        sudo.id shouldNotBe null

        val ownershipProof = getOwnershipProof(sudo)
        ownershipProof shouldNotBe null

        val provisionCardInput = ProvisionVirtualCardInput(
            ownershipProofs = listOf(ownershipProof),
            fundingSourceId = fundingSource.id,
            cardHolder = TestData.ProvisionCardInput.cardHolder,
            addressLine1 = TestData.ProvisionCardInput.addressLine1,
            city = TestData.ProvisionCardInput.city,
            state = TestData.ProvisionCardInput.state,
            postalCode = TestData.ProvisionCardInput.postalCode,
            country = TestData.ProvisionCardInput.country,
            currency = TestData.ProvisionCardInput.currency
        )
        val card = provisionVirtualCard(vcClient, provisionCardInput)
        card shouldNotBe null

        val updateCardInput = UpdateVirtualCardInput(
            id = card.id,
            expectedCardVersion = card.version,
            cardHolder = "Unlimited Cards",
            alias = "Bed Tear",
            null,
            addressLine1 = "123 Nowhere St",
            city = "Menlo Park",
            state = "CA",
            postalCode = "94025",
            country = "US"
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
            else -> { fail("Unexpected SingleAPIResult") }
        }
    }

    @Test
    @Ignore("Ignore until we have a fix or workaround for AWS AppSync's inability to serialize null inputs - PAY-1199 opened for tracking")
    fun updateVirtualCardWithNullBillingAddressInputShouldReturnUpdatedCardWithNullBillingAddress() = runBlocking {
        registerSignInAndEntitle()
        verifyTestUserIdentity()

        val providerToUse = getProviderToUse(vcClient)
        val testCard = TestData.TestCards[providerToUse]?.get("Visa-No3DS-1") ?: throw AssertionError("Test card should not be null")
        val input = CreditCardFundingSourceInput(
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
            TestData.VerifiedUser.fullName
        )
        val fundingSource = createCardFundingSource(
            vcClient,
            input,
            CreateCardFundingSourceOptions(supportedProviders = listOf(providerToUse))
        )
        fundingSource shouldNotBe null

        vcClient.createKeysIfAbsent()

        val sudo = createSudo(
            TestData.sudo
        )
        sudo.id shouldNotBe null

        val ownershipProof = getOwnershipProof(sudo)
        ownershipProof shouldNotBe null

        val provisionCardInput = ProvisionVirtualCardInput(
            ownershipProofs = listOf(ownershipProof),
            fundingSourceId = fundingSource.id,
            cardHolder = TestData.ProvisionCardInput.cardHolder,
            addressLine1 = TestData.ProvisionCardInput.addressLine1,
            city = TestData.ProvisionCardInput.city,
            state = TestData.ProvisionCardInput.state,
            postalCode = TestData.ProvisionCardInput.postalCode,
            country = TestData.ProvisionCardInput.country,
            currency = TestData.ProvisionCardInput.currency
        )
        val card = provisionVirtualCard(vcClient, provisionCardInput)
        card shouldNotBe null

        val updateCardInput = UpdateVirtualCardInput(
            id = card.id,
            expectedCardVersion = card.version,
            cardHolder = "",
            alias = "",
            billingAddress = null
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
            else -> { fail("Unexpected SingleAPIResult") }
        }
    }

    @Test
    fun updateVirtualCardShouldThrowWithNonExistentId() = runBlocking<Unit> {
        registerSignInAndEntitle()

        val updateCardInput = UpdateVirtualCardInput(
            id = "NonExistentId",
            cardHolder = "",
            alias = "",
            billingAddress = null
        )

        vcClient.createKeysIfAbsent()

        shouldThrow<SudoVirtualCardsClient.VirtualCardException.CardNotFoundException> {
            vcClient.updateVirtualCard(updateCardInput)
        }
    }

    @Test
    fun cancelVirtualCardShouldReturnClosedCardResult() = runBlocking {
        registerSignInAndEntitle()
        verifyTestUserIdentity()

        val providerToUse = getProviderToUse(vcClient)
        val testCard = TestData.TestCards[providerToUse]?.get("Visa-No3DS-1") ?: throw AssertionError("Test card should not be null")
        val input = CreditCardFundingSourceInput(
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
            TestData.VerifiedUser.fullName
        )
        val fundingSource = createCardFundingSource(
            vcClient,
            input,
            CreateCardFundingSourceOptions(supportedProviders = listOf(providerToUse))
        )
        fundingSource shouldNotBe null

        vcClient.createKeysIfAbsent()

        val sudo = createSudo(
            TestData.sudo
        )
        sudo.id shouldNotBe null

        val ownershipProof = getOwnershipProof(sudo)
        ownershipProof shouldNotBe null

        val provisionCardInput = ProvisionVirtualCardInput(
            ownershipProofs = listOf(ownershipProof),
            fundingSourceId = fundingSource.id,
            cardHolder = TestData.ProvisionCardInput.cardHolder,
            addressLine1 = TestData.ProvisionCardInput.addressLine1,
            city = TestData.ProvisionCardInput.city,
            state = TestData.ProvisionCardInput.state,
            postalCode = TestData.ProvisionCardInput.postalCode,
            country = TestData.ProvisionCardInput.country,
            currency = TestData.ProvisionCardInput.currency
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
                cancelledCard.result.owners.first().id shouldBe card.owners.first().id
                cancelledCard.result.owners.first().issuer shouldBe card.owners.first().issuer
                cancelledCard.result.cancelledAt shouldNotBe card.cancelledAt
                cancelledCard.result.activeTo.time shouldBeGreaterThan 0L
                cancelledCard.result.createdAt.time shouldBeGreaterThan 0L
                cancelledCard.result.updatedAt.time shouldBeGreaterThan 0L
                cancelledCard.result.expiry.mm.toInt() shouldBeGreaterThan 0
                cancelledCard.result.expiry.yyyy.toInt() shouldBeGreaterThan 0
                cancelledCard.result.version shouldBeGreaterThan 0
            }
            else -> { fail("Unexpected SingleAPIResult") }
        }
    }

    @Test
    fun cancelVirtualCardShouldThrowWithNonExistentId() = runBlocking<Unit> {
        registerSignInAndEntitle()

        vcClient.createKeysIfAbsent()

        shouldThrow<SudoVirtualCardsClient.VirtualCardException.CardNotFoundException> {
            vcClient.cancelVirtualCard("NonExistentId")
        }
    }

    @Test
    fun createKeysIfAbsentShouldCreateKeysOnInitialize() = runBlocking {
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
    fun createKeysIfAbsentShouldReturnExistingKeysOnSubsequentCalls() = runBlocking {
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
    fun getTransactionsShouldReturnNullForBogusId() = runBlocking {
        registerSignInAndEntitle()

        vcClient.createKeysIfAbsent()

        vcClient.getTransaction(UUID.randomUUID().toString()) shouldBe null
    }

    private val transactionSubscriber = object : TransactionSubscriber {
        override fun transactionChanged(transaction: Transaction) { }
        override fun connectionStatusChanged(state: TransactionSubscriber.ConnectionState) { }
    }

    @Test
    @Ignore
    fun subscribeUnsubscribeShouldNotFail() = runBlocking {
        registerSignInAndEntitle()
        verifyTestUserIdentity()

        vcClient.subscribeToTransactions("id", transactionSubscriber)
        vcClient.unsubscribeAll()

        vcClient.subscribeToTransactions("id", transactionSubscriber)
        vcClient.unsubscribeFromTransactions("id")

        vcClient.subscribeToTransactions("id") { }

        vcClient.close()
    }

    @Test
    @Ignore
    fun subscribeShouldThrowWhenNotAuthenticated() = runBlocking<Unit> {
        vcClient.unsubscribeFromTransactions("id")
        vcClient.unsubscribeAll()

        shouldThrow<SudoVirtualCardsClient.TransactionException.AuthenticationException> {
            vcClient.subscribeToTransactions("id", transactionSubscriber)
        }

        shouldThrow<SudoVirtualCardsClient.TransactionException.AuthenticationException> {
            vcClient.subscribeToTransactions("id") {}
        }
    }

    @Test
    fun resetShouldNotAffectOtherClients() = runBlocking {
        registerSignInAndEntitle()
        assumeTrue(userClient.isRegistered())

        verifyTestUserIdentity()

        val keyManager = KeyManagerFactory(context).createAndroidKeyManager("vc")
        keyManager.removeAllKeys()

        val vcClient = SudoVirtualCardsClient.builder()
            .setContext(context)
            .setSudoUserClient(userClient)
            .setKeyManager(keyManager)
            .build()

        val providerToUse = getProviderToUse(vcClient)
        val testCard = TestData.TestCards[providerToUse]?.get("Visa-No3DS-1") ?: throw AssertionError("Test card should not be null")
        val input = CreditCardFundingSourceInput(
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
            TestData.VerifiedUser.fullName
        )
        val fundingSource = createCardFundingSource(
            vcClient,
            input,
            CreateCardFundingSourceOptions(supportedProviders = listOf(providerToUse))
        )
        fundingSource shouldNotBe null

        vcClient.createKeysIfAbsent()

        val sudo = createSudo(
            TestData.sudo
        )
        sudo.id shouldNotBe null

        val ownershipProof = getOwnershipProof(sudo)
        ownershipProof shouldNotBe null

        val provisionCardInput = ProvisionVirtualCardInput(
            ownershipProofs = listOf(ownershipProof),
            fundingSourceId = fundingSource.id,
            cardHolder = TestData.ProvisionCardInput.cardHolder,
            addressLine1 = TestData.ProvisionCardInput.addressLine1,
            city = TestData.ProvisionCardInput.city,
            state = TestData.ProvisionCardInput.state,
            postalCode = TestData.ProvisionCardInput.postalCode,
            country = TestData.ProvisionCardInput.country,
            currency = TestData.ProvisionCardInput.currency
        )
        val card = provisionVirtualCard(vcClient, provisionCardInput)
        card shouldNotBe null

        keyManager.exportKeys().size shouldBe 5

        vcClient.reset()
        keyManager.exportKeys().size shouldBe 0
        assumeTrue(userClient.isRegistered())
    }
}
