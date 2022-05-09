/*
 * Copyright © 2022 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sudoplatform.sudoprofiles.Sudo
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
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import timber.log.Timber
import java.util.logging.Logger
import com.sudoplatform.sudokeymanager.KeyManagerFactory
import com.sudoplatform.sudovirtualcards.simulator.types.inputs.SimulateAuthorizationInput
import com.sudoplatform.sudovirtualcards.types.CurrencyAmount
import com.sudoplatform.sudovirtualcards.types.FundingSource
import com.sudoplatform.sudovirtualcards.types.ProviderCompletionData
import com.sudoplatform.sudovirtualcards.types.ProvisionalFundingSource
import com.sudoplatform.sudovirtualcards.types.ProvisionalVirtualCard
import com.sudoplatform.sudovirtualcards.types.Transaction
import com.sudoplatform.sudovirtualcards.types.VirtualCard
import com.sudoplatform.sudovirtualcards.types.inputs.CompleteFundingSourceInput
import com.sudoplatform.sudovirtualcards.types.inputs.CreditCardFundingSourceInput
import com.sudoplatform.sudovirtualcards.types.inputs.FundingSourceType
import com.sudoplatform.sudovirtualcards.types.inputs.ProvisionVirtualCardInput
import com.sudoplatform.sudovirtualcards.types.inputs.SetupFundingSourceInput
import com.sudoplatform.sudovirtualcards.types.inputs.UpdateVirtualCardInput
import io.kotlintest.matchers.collections.shouldHaveSize
import org.awaitility.Duration.ONE_HUNDRED_MILLISECONDS
import org.awaitility.Duration.TEN_SECONDS
import org.awaitility.Duration.TWO_HUNDRED_MILLISECONDS
import org.junit.Ignore
import java.util.UUID
import org.awaitility.kotlin.await
import org.awaitility.kotlin.has
import org.awaitility.kotlin.untilCallTo
import org.awaitility.kotlin.withPollInterval

/**
 * Test the operation of the [SudoVirtualCardsClient].
 */
@RunWith(AndroidJUnit4::class)
class SudoVirtualCardsClientIntegrationTest : BaseIntegrationTest() {

    private val verbose = false

    private lateinit var vcClient: SudoVirtualCardsClient

    private val sudosToDelete = mutableListOf<Sudo>()

    @Before
    fun init() {
        Timber.plant(Timber.DebugTree())

        if (verbose) {
            Logger.getLogger("com.amazonaws").level = java.util.logging.Level.FINEST
            Logger.getLogger("org.apache.http").level = java.util.logging.Level.FINEST
        }

        // Remove all keys from the Android Keystore so we can start with a clean slate.
        KeyManagerFactory(context).createAndroidKeyManager().removeAllKeys()
        sudoClient.generateEncryptionKey()
        vcClient = SudoVirtualCardsClient.builder()
            .setContext(context)
            .setSudoUserClient(userClient)
            .setSudoProfilesClient(sudoClient)
            .build()
    }

    @After
    fun fini() = runBlocking {
        if (userClient.isRegistered()) {
            sudosToDelete.forEach {
                try {
                    sudoClient.deleteSudo(it)
                } catch (e: Throwable) {
                    Timber.e(e)
                }
            }
            deregister()
        }
        vcClient.reset()
        sudoClient.reset()
        userClient.reset()

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
                .setSudoProfilesClient(sudoClient)
                .build()
        }

        // SudoUserClient not provided
        shouldThrow<NullPointerException> {
            SudoVirtualCardsClient.builder()
                .setContext(context)
                .setSudoProfilesClient(sudoClient)
                .build()
        }

        // SudoProfilesClient not provided
        shouldThrow<NullPointerException> {
            SudoVirtualCardsClient.builder()
                .setContext(context)
                .setSudoUserClient(userClient)
                .build()
        }
    }

    @Test
    fun shouldNotThrowIfTheRequiredItemsAreProvidedToBuilder() {
        SudoVirtualCardsClient.builder()
            .setContext(context)
            .setSudoUserClient(userClient)
            .setSudoProfilesClient(sudoClient)
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

        val setupInput = SetupFundingSourceInput("USD", FundingSourceType.CREDIT_CARD)
        val provisionalFundingSource = vcClient.setupFundingSource(setupInput)

        with(provisionalFundingSource) {
            id shouldNotBe null
            owner shouldBe userClient.getSubject()
            version shouldBe 1
            state shouldBe ProvisionalFundingSource.ProvisioningState.PROVISIONING
            stateReason shouldBe ProvisionalFundingSource.StateReason.PROCESSING
            provisioningData.provider shouldBe "stripe"
            provisioningData.version shouldBe 1
            provisioningData.clientSecret shouldNotBe null
            provisioningData.intent shouldNotBe null
        }
    }

    @Test
    fun setupFundingSourceShouldThrowWithUnsupportedCurrency() = runBlocking<Unit> {
        registerSignInAndEntitle()
        verifyTestUserIdentity()

        val setupInput = SetupFundingSourceInput("AUD", FundingSourceType.CREDIT_CARD)
        shouldThrow<SudoVirtualCardsClient.FundingSourceException.UnsupportedCurrencyException> {
            vcClient.setupFundingSource(setupInput)
        }
    }

    @Test
    fun completeFundingSourceShouldReturnFundingSourceResult() = runBlocking {
        registerSignInAndEntitle()
        verifyTestUserIdentity()

        val input = CreditCardFundingSourceInput(
            TestData.Visa.creditCardNumber,
            expirationMonth(),
            expirationYear(),
            TestData.Visa.securityCode,
            TestData.VerifiedUser.addressLine1,
            TestData.VerifiedUser.addressLine2,
            TestData.VerifiedUser.city,
            TestData.VerifiedUser.state,
            TestData.VerifiedUser.postalCode,
            TestData.VerifiedUser.country
        )

        val fundingSource = createFundingSource(vcClient, input)

        with(fundingSource) {
            id shouldNotBe null
            owner shouldBe userClient.getSubject()
            version shouldBe 1
            createdAt.time shouldBeGreaterThan 0L
            updatedAt.time shouldBeGreaterThan 0L
            state shouldBe FundingSource.State.ACTIVE
            currency shouldBe "USD"
            last4 shouldBe "4242"
            network shouldBe FundingSource.CreditCardNetwork.VISA
        }
    }

    @Test
    fun completeFundingSourceShouldThrowWithProvisionalFundingSourceNotFound() = runBlocking<Unit> {
        registerSignInAndEntitle()
        verifyTestUserIdentity()

        val input = CompleteFundingSourceInput(
            UUID.randomUUID().toString(),
            ProviderCompletionData("stripe", 1, "paymentMethod"),
            null
        )
        shouldThrow<SudoVirtualCardsClient.FundingSourceException.ProvisionalFundingSourceNotFoundException> {
            vcClient.completeFundingSource(input)
        }
    }

    @Test
    fun completeFundingSourceShouldThrowWithCompletionDataInvalid() = runBlocking<Unit> {
        registerSignInAndEntitle()
        verifyTestUserIdentity()

        val setupInput = SetupFundingSourceInput("USD", FundingSourceType.CREDIT_CARD)
        val provisionalFundingSource = vcClient.setupFundingSource(setupInput)

        val input = CompleteFundingSourceInput(
            provisionalFundingSource.id,
            ProviderCompletionData("stripe", 1, "paymentMethod"),
            null
        )
        shouldThrow<SudoVirtualCardsClient.FundingSourceException> {
            vcClient.completeFundingSource(input)
        }
    }

    @Test
    fun getFundingSourceShouldReturnFundingSourceResult() = runBlocking {
        registerSignInAndEntitle()
        verifyTestUserIdentity()

        val input = CreditCardFundingSourceInput(
            TestData.Visa.creditCardNumber,
            expirationMonth(),
            expirationYear(),
            TestData.Visa.securityCode,
            TestData.VerifiedUser.addressLine1,
            TestData.VerifiedUser.addressLine2,
            TestData.VerifiedUser.city,
            TestData.VerifiedUser.state,
            TestData.VerifiedUser.postalCode,
            TestData.VerifiedUser.country
        )
        val fundingSource = createFundingSource(vcClient, input)
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

        val input = CreditCardFundingSourceInput(
            TestData.Visa.creditCardNumber,
            expirationMonth(),
            expirationYear(),
            TestData.Visa.securityCode,
            TestData.VerifiedUser.addressLine1,
            TestData.VerifiedUser.addressLine2,
            TestData.VerifiedUser.city,
            TestData.VerifiedUser.state,
            TestData.VerifiedUser.postalCode,
            TestData.VerifiedUser.country
        )
        val fundingSource = createFundingSource(vcClient, input)
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

        val input1 = CreditCardFundingSourceInput(
            TestData.Visa.creditCardNumber,
            expirationMonth(),
            expirationYear(),
            TestData.Visa.securityCode,
            TestData.VerifiedUser.addressLine1,
            TestData.VerifiedUser.addressLine2,
            TestData.VerifiedUser.city,
            TestData.VerifiedUser.state,
            TestData.VerifiedUser.postalCode,
            TestData.VerifiedUser.country
        )
        val fundingSource1 = createFundingSource(vcClient, input1)
        fundingSource1 shouldNotBe null

        val input2 = CreditCardFundingSourceInput(
            TestData.Mastercard.creditCardNumber,
            expirationMonth(),
            expirationYear(),
            TestData.Mastercard.securityCode,
            TestData.VerifiedUser.addressLine1,
            TestData.VerifiedUser.addressLine2,
            TestData.VerifiedUser.city,
            TestData.VerifiedUser.state,
            TestData.VerifiedUser.postalCode,
            TestData.VerifiedUser.country
        )
        val fundingSource2 = createFundingSource(vcClient, input2)
        fundingSource2 shouldNotBe null

        val listFundingSources = vcClient.listFundingSources()
        listFundingSources.items.isEmpty() shouldBe false
        listFundingSources.items.size shouldBe 2
        listFundingSources.nextToken shouldBe null

        val fundingSources = listFundingSources.items
        fundingSources[0].id shouldBe fundingSource1.id
        fundingSources[0].owner shouldBe fundingSource1.owner
        fundingSources[0].version shouldBe fundingSource1.version
        fundingSources[0].state shouldBe fundingSource1.state
        fundingSources[0].currency shouldBe fundingSource1.currency
        fundingSources[0].last4 shouldBe fundingSource1.last4
        fundingSources[0].network shouldBe fundingSource1.network

        fundingSources[1].id shouldBe fundingSource2.id
        fundingSources[1].owner shouldBe fundingSource2.owner
        fundingSources[1].version shouldBe fundingSource2.version
        fundingSources[1].state shouldBe fundingSource2.state
        fundingSources[1].currency shouldBe fundingSource2.currency
        fundingSources[1].last4 shouldBe fundingSource2.last4
        fundingSources[1].network shouldBe fundingSource2.network
    }

    @Test
    fun cancelFundingSourceShouldReturnInactiveFundingSourceResult() = runBlocking {
        registerSignInAndEntitle()
        verifyTestUserIdentity()

        val input = CreditCardFundingSourceInput(
            TestData.Visa.creditCardNumber,
            expirationMonth(),
            expirationYear(),
            TestData.Visa.securityCode,
            TestData.VerifiedUser.addressLine1,
            TestData.VerifiedUser.addressLine2,
            TestData.VerifiedUser.city,
            TestData.VerifiedUser.state,
            TestData.VerifiedUser.postalCode,
            TestData.VerifiedUser.country
        )
        val fundingSource = createFundingSource(vcClient, input)
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

        val input = CreditCardFundingSourceInput(
            TestData.Visa.creditCardNumber,
            expirationMonth(),
            expirationYear(),
            TestData.Visa.securityCode,
            TestData.VerifiedUser.addressLine1,
            TestData.VerifiedUser.addressLine2,
            TestData.VerifiedUser.city,
            TestData.VerifiedUser.state,
            TestData.VerifiedUser.postalCode,
            TestData.VerifiedUser.country
        )
        val fundingSource = createFundingSource(vcClient, input)
        fundingSource shouldNotBe null

        val sudo = createSudo(
            Sudo("Mr", "Theodore", "Bear", "Shopping", null, null)
        )
        sudo.id shouldNotBe null

        val ownershipProof = getOwnershipProof(sudo)
        ownershipProof shouldNotBe null

        val provisionCardInput = ProvisionVirtualCardInput(
            ownershipProofs = listOf(ownershipProof),
            fundingSourceId = fundingSource.id,
            cardHolder = "Unlimited Cards",
            alias = "Ted Bear",
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
                    card.alias shouldBe "Ted Bear"
                    card.fundingSourceId shouldBe fundingSource.id
                    card.state shouldBe VirtualCard.State.ISSUED
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
    fun getVirtualCardShouldReturnProvisionedCardResult() = runBlocking {
        registerSignInAndEntitle()
        verifyTestUserIdentity()

        val input = CreditCardFundingSourceInput(
            TestData.Visa.creditCardNumber,
            expirationMonth(),
            expirationYear(),
            TestData.Visa.securityCode,
            TestData.VerifiedUser.addressLine1,
            TestData.VerifiedUser.addressLine2,
            TestData.VerifiedUser.city,
            TestData.VerifiedUser.state,
            TestData.VerifiedUser.postalCode,
            TestData.VerifiedUser.country
        )
        val fundingSource = createFundingSource(vcClient, input)
        fundingSource shouldNotBe null

        val card = provisionVirtualCard(vcClient, fundingSource.id)
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

        val retrievedCard = vcClient.getVirtualCard("NonExistentId")
        retrievedCard shouldBe null
    }

    @Test
    fun listVirtualCardsShouldReturnSingleCardListOutputResult() = runBlocking {
        registerSignInAndEntitle()
        verifyTestUserIdentity()

        val input = CreditCardFundingSourceInput(
            TestData.Visa.creditCardNumber,
            expirationMonth(),
            expirationYear(),
            TestData.Visa.securityCode,
            TestData.VerifiedUser.addressLine1,
            TestData.VerifiedUser.addressLine2,
            TestData.VerifiedUser.city,
            TestData.VerifiedUser.state,
            TestData.VerifiedUser.postalCode,
            TestData.VerifiedUser.country
        )
        val fundingSource = createFundingSource(vcClient, input)
        fundingSource shouldNotBe null

        val card = provisionVirtualCard(vcClient, fundingSource.id)
        card shouldNotBe null

        val listCards = vcClient.listVirtualCards()
        listCards.items.isEmpty() shouldBe false
        listCards.items.size shouldBe 1
        listCards.nextToken shouldBe null

        val cards = listCards.items
        cards[0].id shouldBe card.id
        cards[0].cardNumber shouldBe card.cardNumber
        cards[0].last4 shouldBe card.last4
        cards[0].cardHolder shouldBe card.cardHolder
        cards[0].alias shouldBe card.alias
        cards[0].fundingSourceId shouldBe card.fundingSourceId
        cards[0].state shouldBe card.state
        cards[0].billingAddress shouldBe card.billingAddress
        cards[0].currency shouldBe card.currency
        cards[0].owner shouldBe card.owner
        cards[0].owners.first().id shouldBe card.owners.first().id
        cards[0].owners.first().issuer shouldBe card.owners.first().issuer
        cards[0].cancelledAt shouldBe card.cancelledAt
        cards[0].activeTo.time shouldBeGreaterThan 0L
        cards[0].createdAt.time shouldBeGreaterThan 0L
        cards[0].updatedAt.time shouldBeGreaterThan 0L
        cards[0].expiry.mm.toInt() shouldBeGreaterThan 0
        cards[0].expiry.yyyy.toInt() shouldBeGreaterThan 0
        cards[0].securityCode shouldBe card.securityCode
        cards[0].version shouldBeGreaterThan 0
    }

    @Test
    fun listVirtualCardsShouldReturnMultipleCardListOutputResult() = runBlocking {
        registerSignInAndEntitle()
        verifyTestUserIdentity()

        val input = CreditCardFundingSourceInput(
            TestData.Visa.creditCardNumber,
            expirationMonth(),
            expirationYear(),
            TestData.Visa.securityCode,
            TestData.VerifiedUser.addressLine1,
            TestData.VerifiedUser.addressLine2,
            TestData.VerifiedUser.city,
            TestData.VerifiedUser.state,
            TestData.VerifiedUser.postalCode,
            TestData.VerifiedUser.country
        )
        val fundingSource = createFundingSource(vcClient, input)
        fundingSource shouldNotBe null

        val card1 = provisionVirtualCard(vcClient, fundingSource.id)
        card1 shouldNotBe null
        Thread.sleep(1)
        val card2 = provisionVirtualCard(vcClient, fundingSource.id)
        card2 shouldNotBe null

        card2.createdAt.time shouldBeGreaterThan card1.createdAt.time

        // Since card2 is created after card1 it should be returned first in the list
        val expectedCards = arrayOf(card2, card1)

        val listCards = vcClient.listVirtualCards()
        listCards.items.isEmpty() shouldBe false
        listCards.items.size shouldBe 2
        listCards.nextToken shouldBe null

        val actualCards = listCards.items
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

    @Test
    fun updateVirtualCardShouldReturnUpdatedCardResult() = runBlocking {
        registerSignInAndEntitle()
        verifyTestUserIdentity()

        val input = CreditCardFundingSourceInput(
            TestData.Visa.creditCardNumber,
            expirationMonth(),
            expirationYear(),
            TestData.Visa.securityCode,
            TestData.VerifiedUser.addressLine1,
            TestData.VerifiedUser.addressLine2,
            TestData.VerifiedUser.city,
            TestData.VerifiedUser.state,
            TestData.VerifiedUser.postalCode,
            TestData.VerifiedUser.country
        )
        val fundingSource = createFundingSource(vcClient, input)
        fundingSource shouldNotBe null

        val card = provisionVirtualCard(vcClient, fundingSource.id)
        card shouldNotBe null

        val updateCardInput = UpdateVirtualCardInput(
            id = card.id,
            expectedCardVersion = card.version,
            cardHolder = "Not Unlimited Cards",
            alias = "Bed Tear",
            addressLine1 = "321 Somewhere St",
            city = "Olnem Park",
            state = "NY",
            postalCode = "52049",
            country = "US"
        )
        val updatedCard = vcClient.updateVirtualCard(updateCardInput)

        updatedCard.id shouldBe card.id
        updatedCard.cardHolder shouldNotBe card.cardHolder
        updatedCard.alias shouldNotBe card.alias
        updatedCard.billingAddress shouldNotBe card.billingAddress
    }

    @Test
    fun updateVirtualCardShouldReturnPartiallyUpdatedCardResult() = runBlocking {
        registerSignInAndEntitle()
        verifyTestUserIdentity()

        val input = CreditCardFundingSourceInput(
            TestData.Visa.creditCardNumber,
            expirationMonth(),
            expirationYear(),
            TestData.Visa.securityCode,
            TestData.VerifiedUser.addressLine1,
            TestData.VerifiedUser.addressLine2,
            TestData.VerifiedUser.city,
            TestData.VerifiedUser.state,
            TestData.VerifiedUser.postalCode,
            TestData.VerifiedUser.country
        )
        val fundingSource = createFundingSource(vcClient, input)
        fundingSource shouldNotBe null

        val card = provisionVirtualCard(vcClient, fundingSource.id)
        card shouldNotBe null

        val updateCardInput = UpdateVirtualCardInput(
            id = card.id,
            expectedCardVersion = card.version,
            cardHolder = "Unlimited Cards",
            alias = "Bed Tear",
            addressLine1 = "123 Nowhere St",
            city = "Menlo Park",
            state = "CA",
            postalCode = "94025",
            country = "US"
        )
        val updatedCard = vcClient.updateVirtualCard(updateCardInput)

        updatedCard.id shouldBe card.id
        updatedCard.cardHolder shouldBe card.cardHolder
        updatedCard.alias shouldNotBe card.alias
        updatedCard.billingAddress shouldBe card.billingAddress
    }

    @Test
    @Ignore("Ignore until we have a fix or workaround for AWS AppSync's inability to serialize null inputs - PAY-1199 opened for tracking")
    fun updateVirtualCardWithNullBillingAddressInputShouldReturnUpdatedCardWithNullBillingAddress() = runBlocking {
        registerSignInAndEntitle()
        verifyTestUserIdentity()

        val input = CreditCardFundingSourceInput(
            TestData.Visa.creditCardNumber,
            expirationMonth(),
            expirationYear(),
            TestData.Visa.securityCode,
            TestData.VerifiedUser.addressLine1,
            TestData.VerifiedUser.addressLine2,
            TestData.VerifiedUser.city,
            TestData.VerifiedUser.state,
            TestData.VerifiedUser.postalCode,
            TestData.VerifiedUser.country
        )
        val fundingSource = createFundingSource(vcClient, input)
        fundingSource shouldNotBe null

        val card = provisionVirtualCard(vcClient, fundingSource.id)
        card shouldNotBe null

        // given
        val updateCardInput = UpdateVirtualCardInput(
            id = card.id,
            expectedCardVersion = card.version,
            cardHolder = "",
            alias = "",
            billingAddress = null
        )
        // when
        val updatedCard = vcClient.updateVirtualCard(updateCardInput)

        // then
        updatedCard.id shouldBe card.id
        updatedCard.cardHolder shouldNotBe card.cardHolder
        updatedCard.alias shouldNotBe card.alias
        updatedCard.billingAddress shouldNotBe card.billingAddress
        updatedCard.billingAddress shouldBe null
    }

    @Test
    fun updateVirtualCardShouldThrowWithNonExistentId() = runBlocking<Unit> {
        registerSignInAndEntitle()

        // given
        val updateCardInput = UpdateVirtualCardInput(
            id = "NonExistentId",
            cardHolder = "",
            alias = "",
            billingAddress = null
        )

        // when
        shouldThrow<SudoVirtualCardsClient.VirtualCardException.CardNotFoundException> {
            vcClient.updateVirtualCard(updateCardInput)
        }
    }

    @Test
    fun cancelVirtualCardShouldReturnClosedCardResult() = runBlocking {
        registerSignInAndEntitle()
        verifyTestUserIdentity()

        val input = CreditCardFundingSourceInput(
            TestData.Visa.creditCardNumber,
            expirationMonth(),
            expirationYear(),
            TestData.Visa.securityCode,
            TestData.VerifiedUser.addressLine1,
            TestData.VerifiedUser.addressLine2,
            TestData.VerifiedUser.city,
            TestData.VerifiedUser.state,
            TestData.VerifiedUser.postalCode,
            TestData.VerifiedUser.country
        )
        val fundingSource = createFundingSource(vcClient, input)
        fundingSource shouldNotBe null

        val card = provisionVirtualCard(vcClient, fundingSource.id)
        card shouldNotBe null

        // when
        val cancelledCard = vcClient.cancelVirtualCard(card.id)

        // then
        cancelledCard.id shouldBe card.id
        cancelledCard.cardNumber shouldBe card.cardNumber
        cancelledCard.last4 shouldBe card.last4
        cancelledCard.cardHolder shouldBe card.cardHolder
        cancelledCard.alias shouldBe card.alias
        cancelledCard.fundingSourceId shouldBe card.fundingSourceId
        cancelledCard.state shouldBe VirtualCard.State.CLOSED
        cancelledCard.billingAddress shouldBe card.billingAddress
        cancelledCard.currency shouldBe card.currency
        cancelledCard.owner shouldBe card.owner
        cancelledCard.owners.first().id shouldBe card.owners.first().id
        cancelledCard.owners.first().issuer shouldBe card.owners.first().issuer
        cancelledCard.cancelledAt shouldNotBe card.cancelledAt
        cancelledCard.activeTo.time shouldBeGreaterThan 0L
        cancelledCard.createdAt.time shouldBeGreaterThan 0L
        cancelledCard.updatedAt.time shouldBeGreaterThan 0L
        cancelledCard.expiry.mm.toInt() shouldBeGreaterThan 0
        cancelledCard.expiry.yyyy.toInt() shouldBeGreaterThan 0
        cancelledCard.version shouldBeGreaterThan 0
    }

    @Test
    fun cancelVirtualCardShouldThrowWithNonExistentId() = runBlocking<Unit> {
        registerSignInAndEntitle()

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

        vcClient.getTransaction(UUID.randomUUID().toString()) shouldBe null
    }

    @Test
    fun listTransactionsByCardIdShouldReturnSingleTransactionListOutputResult() = runBlocking {
        registerSignInAndEntitle()
        verifyTestUserIdentity()

        val input = CreditCardFundingSourceInput(
            TestData.Visa.creditCardNumber,
            expirationMonth(),
            expirationYear(),
            TestData.Visa.securityCode,
            TestData.VerifiedUser.addressLine1,
            TestData.VerifiedUser.addressLine2,
            TestData.VerifiedUser.city,
            TestData.VerifiedUser.state,
            TestData.VerifiedUser.postalCode,
            TestData.VerifiedUser.country
        )
        val fundingSource = createFundingSource(vcClient, input)
        fundingSource shouldNotBe null

        val card = provisionVirtualCard(vcClient, fundingSource.id)
        card shouldNotBe null

        val merchant = vcSimulatorClient.getSimulatorMerchants().first()
        val originalAmount = 75
        val authInput = SimulateAuthorizationInput(
            cardNumber = card.cardNumber,
            amount = originalAmount,
            merchantId = merchant.id,
            securityCode = card.securityCode,
            expirationMonth = card.expiry.mm.toInt(),
            expirationYear = card.expiry.yyyy.toInt(),
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

        val transactions = mutableListOf<Transaction>()
        withTimeout(40_000L) {
            while (transactions.isEmpty()) {
                delay(500L)
                transactions.addAll(listTransactionsByCardId(vcClient, card.id))
            }
        }

        transactions.size shouldBe 1
        with(transactions[0]) {
            id.isBlank() shouldBe false
            owner shouldBe card.owner
            version shouldBe 1
            cardId shouldBe card.id
            sequenceId.isBlank() shouldBe false
            type shouldBe Transaction.TransactionType.PENDING
            billedAmount shouldBe CurrencyAmount("USD", originalAmount)
            transactedAmount shouldBe CurrencyAmount("USD", originalAmount)
            description shouldBe merchant.name
            declineReason shouldBe null
        }
    }

    @Test
    fun listTransactionsByCardIdShouldReturnMultipleTransactionListOutputResult() = runBlocking {
        registerSignInAndEntitle()
        verifyTestUserIdentity()

        val input = CreditCardFundingSourceInput(
            TestData.Visa.creditCardNumber,
            expirationMonth(),
            expirationYear(),
            TestData.Visa.securityCode,
            TestData.VerifiedUser.addressLine1,
            TestData.VerifiedUser.addressLine2,
            TestData.VerifiedUser.city,
            TestData.VerifiedUser.state,
            TestData.VerifiedUser.postalCode,
            TestData.VerifiedUser.country
        )
        val fundingSource = createFundingSource(vcClient, input)
        fundingSource shouldNotBe null

        val card = provisionVirtualCard(vcClient, fundingSource.id)
        card shouldNotBe null

        simulateTransactions(card)

        // List the transactions and wait until there are no PENDING transactions
        val transactions = await.atMost(TEN_SECONDS.multiply(4)) withPollInterval TWO_HUNDRED_MILLISECONDS untilCallTo {
            runBlocking {
                vcClient.listTransactionsByCardId(card.id)
            }
        } has { items.size == 2 }

        with(transactions) {
            items shouldHaveSize 2
        }
    }

    @Test
    fun listTransactionsByCardIdShouldRespectLimit() = runBlocking {
        registerSignInAndEntitle()
        verifyTestUserIdentity()

        val input = CreditCardFundingSourceInput(
            TestData.Visa.creditCardNumber,
            expirationMonth(),
            expirationYear(),
            TestData.Visa.securityCode,
            TestData.VerifiedUser.addressLine1,
            TestData.VerifiedUser.addressLine2,
            TestData.VerifiedUser.city,
            TestData.VerifiedUser.state,
            TestData.VerifiedUser.postalCode,
            TestData.VerifiedUser.country
        )
        val fundingSource = createFundingSource(vcClient, input)
        fundingSource shouldNotBe null

        val card = provisionVirtualCard(vcClient, fundingSource.id)
        card shouldNotBe null

        simulateTransactions(card)

        val transactionsWithLimit = await.atMost(TEN_SECONDS) withPollInterval ONE_HUNDRED_MILLISECONDS untilCallTo {
            runBlocking {
                vcClient.listTransactionsByCardId(
                    cardId = card.id,
                    limit = 1
                )
            }
        } has { items.size == 1 }

        with(transactionsWithLimit) {
            items shouldHaveSize 1
            nextToken shouldBe null
        }
    }

    @Test
    fun listTransactionsByCardIdShouldReturnEmptyList() = runBlocking {
        registerSignInAndEntitle()
        verifyTestUserIdentity()

        val input = CreditCardFundingSourceInput(
            TestData.Visa.creditCardNumber,
            expirationMonth(),
            expirationYear(),
            TestData.Visa.securityCode,
            TestData.VerifiedUser.addressLine1,
            TestData.VerifiedUser.addressLine2,
            TestData.VerifiedUser.city,
            TestData.VerifiedUser.state,
            TestData.VerifiedUser.postalCode,
            TestData.VerifiedUser.country
        )
        val fundingSource = createFundingSource(vcClient, input)
        fundingSource shouldNotBe null

        val card = provisionVirtualCard(vcClient, fundingSource.id)
        card shouldNotBe null

        with(vcClient.listTransactionsByCardId(card.id)) {
            items.isEmpty() shouldBe true
            nextToken shouldBe null
        }

        var transactions = vcClient.listTransactionsByCardId(
            cardId = card.id
        )
        with(transactions) {
            items.isEmpty() shouldBe true
            nextToken shouldBe null
        }

        transactions = vcClient.listTransactionsByCardId(
            cardId = card.id
        )
        with(transactions) {
            items.isEmpty() shouldBe true
            nextToken shouldBe null
        }
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
            .setSudoProfilesClient(sudoClient)
            .setKeyManager(keyManager)
            .build()

        val input = CreditCardFundingSourceInput(
            TestData.Visa.creditCardNumber,
            expirationMonth(),
            expirationYear(),
            TestData.Visa.securityCode,
            TestData.VerifiedUser.addressLine1,
            TestData.VerifiedUser.addressLine2,
            TestData.VerifiedUser.city,
            TestData.VerifiedUser.state,
            TestData.VerifiedUser.postalCode,
            TestData.VerifiedUser.country
        )
        val fundingSource = createFundingSource(vcClient, input)
        fundingSource shouldNotBe null

        val card = provisionVirtualCard(vcClient, fundingSource.id)
        card shouldNotBe null

        keyManager.exportKeys().size shouldBe 3

        vcClient.reset()
        keyManager.exportKeys().size shouldBe 0
        assumeTrue(userClient.isRegistered())
    }
}
