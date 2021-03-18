/*
 * Copyright © 2020 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sudoplatform.sudoprofiles.Sudo
import com.sudoplatform.sudovirtualcards.subscription.TransactionSubscriber
import com.sudoplatform.sudovirtualcards.types.Card
import com.sudoplatform.sudovirtualcards.types.FundingSource
import com.sudoplatform.sudovirtualcards.types.ProvisionalCard
import com.sudoplatform.sudovirtualcards.types.Transaction
import com.sudoplatform.sudovirtualcards.types.inputs.CreditCardFundingSourceInput
import com.sudoplatform.sudovirtualcards.types.inputs.ProvisionCardInput
import com.sudoplatform.sudovirtualcards.types.inputs.UpdateCardInput
import com.sudoplatform.sudovirtualcards.types.inputs.filters.filterCardsBy
import com.sudoplatform.sudovirtualcards.types.inputs.filters.filterTransactionsBy
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
import java.util.Calendar
import java.util.UUID
import java.util.logging.Logger
import com.sudoplatform.sudokeymanager.KeyManagerFactory

/**
 * Test the operation of the [SudoVirtualCardsClient].
 *
 * @since 2020-05-22
 */
@RunWith(AndroidJUnit4::class)
class SudoVirtualCardsClientIntegrationTest : BaseIntegrationTest() {

    private val verbose = false

    private lateinit var vcClient: SudoVirtualCardsClient

    private val sudosToDelete = mutableListOf<Sudo>()

    /** Returns the next calendar month. */
    private fun expirationMonth(): Int {
        val calendar = Calendar.getInstance()
        calendar.add(Calendar.MONTH, 1)
        return calendar.get(Calendar.MONTH)
    }

    /** Returns the next calendar year. */
    private fun expirationYear(): Int {
        val calendar = Calendar.getInstance()
        calendar.add(Calendar.YEAR, 1)
        return calendar.get(Calendar.YEAR)
    }

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
    fun fini() = runBlocking<Unit> {
        if (clientConfigFilesPresent()) {
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
        }

        Timber.uprootAll()
    }

    @Test
    fun shouldThrowIfRequiredItemsNotProvidedToBuilder() {

        // Can only run if client config files are present
        assumeTrue(clientConfigFilesPresent())

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

        // Can only run if client config files are present
        assumeTrue(clientConfigFilesPresent())

        SudoVirtualCardsClient.builder()
            .setContext(context)
            .setSudoUserClient(userClient)
            .setSudoProfilesClient(sudoClient)
            .build()
    }

    @Test
    fun shouldBeAbleToRegisterAndDeregister() = runBlocking<Unit> {

        // Can only run if client config files are present
        assumeTrue(clientConfigFilesPresent())

        // given
        userClient.isRegistered() shouldBe false

        // when
        register()

        // then
        userClient.isRegistered() shouldBe true

        // when
        signIn()

        // then
        userClient.isSignedIn() shouldBe true

        // when
        deregister()

        // then
        userClient.isRegistered() shouldBe false
    }

    @Test
    fun createFundingSourceShouldReturnFundingSourceResult() = runBlocking<Unit> {
        // Can only run if client config files are present
        assumeTrue(clientConfigFilesPresent())

        signInAndRegister()
        verifyTestUserIdentity()

        // given
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

        // when
        val fundingSource = vcClient.createFundingSource(input)

        // then
        fundingSource.id.isBlank() shouldBe false
        fundingSource.owner.isBlank() shouldBe false
        fundingSource.version shouldBe 1
        fundingSource.state shouldBe FundingSource.State.ACTIVE
        fundingSource.currency shouldBe "USD"
        fundingSource.last4 shouldBe "4242"
        fundingSource.network shouldBe FundingSource.CreditCardNetwork.VISA
    }

    @Test
    fun getFundingSourceShouldReturnFundingSourceResult() = runBlocking<Unit> {
        // Can only run if client config files are present
        assumeTrue(clientConfigFilesPresent())

        signInAndRegister()
        verifyTestUserIdentity()

        // given
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

        // when
        val fundingSource = vcClient.createFundingSource(input)

        // then
        fundingSource shouldNotBe null

        // when
        val retrievedFundingSource = vcClient.getFundingSource(fundingSource.id)
            ?: throw AssertionError("should not be null")

        // then
        retrievedFundingSource.id shouldBe fundingSource.id
        retrievedFundingSource.owner shouldBe fundingSource.owner
        retrievedFundingSource.version shouldBe fundingSource.version
        retrievedFundingSource.state shouldBe fundingSource.state
        retrievedFundingSource.currency shouldBe fundingSource.currency
        retrievedFundingSource.last4 shouldBe fundingSource.last4
        retrievedFundingSource.network shouldBe fundingSource.network
    }

    @Test
    fun getFundingSourceShouldReturnNullForNonExistentId() = runBlocking<Unit> {
        // Can only run if client config files are present
        assumeTrue(clientConfigFilesPresent())

        signInAndRegister()

        // when
        val retrievedFundingSource = vcClient.getFundingSource("NonExistentId")

        // then
        retrievedFundingSource shouldBe null
    }

    @Test
    fun listFundingSourcesShouldReturnSingleFundingSourceListOutputResult() = runBlocking<Unit> {
        // Can only run if client config files are present
        assumeTrue(clientConfigFilesPresent())

        signInAndRegister()
        verifyTestUserIdentity()

        // given
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

        // when
        val fundingSource = vcClient.createFundingSource(input)

        // then
        fundingSource shouldNotBe null

        // when
        val listFundingSources = vcClient.listFundingSources()

        // then
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
    fun listFundingSourcesShouldReturnMultipleFundingSourceListOutputResult() = runBlocking<Unit> {
        // Can only run if client config files are present
        assumeTrue(clientConfigFilesPresent())

        signInAndRegister()
        verifyTestUserIdentity()

        // given
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

        // when
        val fundingSource1 = vcClient.createFundingSource(input1)

        // then
        fundingSource1 shouldNotBe null

        // when
        val fundingSource2 = vcClient.createFundingSource(input2)

        // then
        fundingSource2 shouldNotBe null

        // when
        val listFundingSources = vcClient.listFundingSources()

        // then
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
    fun cancelFundingSourceShouldReturnInactiveFundingSourceResult() = runBlocking<Unit> {
        // Can only run if client config files are present
        assumeTrue(clientConfigFilesPresent())

        signInAndRegister()
        verifyTestUserIdentity()

        // given
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

        // when
        val fundingSource = vcClient.createFundingSource(input)

        // then
        fundingSource shouldNotBe null

        // when
        val cancelledFundingSource = vcClient.cancelFundingSource(fundingSource.id)

        // then
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
        // Can only run if client config files are present
        assumeTrue(clientConfigFilesPresent())

        signInAndRegister()

        // when
        shouldThrow<SudoVirtualCardsClient.FundingSourceException.FundingSourceNotFoundException> {
            vcClient.cancelFundingSource("NonExistentId")
        }
    }

    @Test
    fun provisionCardShouldReturnPendingCard() = runBlocking<Unit> {

        // Can only run if client config files are present
        assumeTrue(clientConfigFilesPresent())

        signInAndRegister()
        verifyTestUserIdentity()

        val fundingSourceInput = CreditCardFundingSourceInput(
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

        val fundingSource = vcClient.createFundingSource(fundingSourceInput)
        fundingSource shouldNotBe null

        val sudo = createSudo(
            Sudo("Mr", "Theodore", "Bear", "Shopping", null, null)
        )
        sudo.id shouldNotBe null

        val provisionCardInput = ProvisionCardInput(
            sudoId = sudo.id!!,
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
        val provisionalCard1 = vcClient.provisionCard(provisionCardInput)

        provisionalCard1.state shouldBe ProvisionalCard.State.PROVISIONING
        provisionalCard1.id.isBlank() shouldBe false
        provisionalCard1.clientRefId.isBlank() shouldBe false
        provisionalCard1.owner shouldBe userClient.getSubject()
        provisionalCard1.version shouldBeGreaterThan 0
        provisionalCard1.card shouldBe null
        provisionalCard1.createdAt.time shouldBeGreaterThan 0L
        provisionalCard1.updatedAt.time shouldBeGreaterThan 0L

        withTimeout(20_000L) {

            var state = ProvisionalCard.State.PROVISIONING

            while (state == ProvisionalCard.State.PROVISIONING) {

                val provisionalCard2 = vcClient.getProvisionalCard(provisionalCard1.id)
                provisionalCard2 shouldNotBe null
                provisionalCard2?.state shouldNotBe ProvisionalCard.State.FAILED
                provisionalCard2?.state shouldNotBe ProvisionalCard.State.UNKNOWN
                provisionalCard2?.id shouldBe provisionalCard1.id
                provisionalCard2?.clientRefId shouldBe provisionalCard1.clientRefId
                provisionalCard2?.owner shouldBe provisionalCard1.owner
                provisionalCard2?.version ?: 0 shouldBeGreaterThanOrEqual provisionalCard1.version
                provisionalCard2?.createdAt?.time ?: 0L shouldBe provisionalCard1.createdAt
                provisionalCard2?.updatedAt?.time ?: 0L shouldBeGreaterThan 0L

                if (provisionalCard2?.state == ProvisionalCard.State.COMPLETED) {
                    provisionalCard2.card shouldNotBe null
                    val card = provisionalCard2.card ?: throw AssertionError("should not be null")
                    card.cardNumber.isBlank() shouldBe false
                    card.last4 shouldBe card.cardNumber.substring(card.cardNumber.length - 4)
                    card.cardHolder shouldBe "Unlimited Cards"
                    card.alias shouldBe "Ted Bear"
                    card.fundingSourceId shouldBe fundingSource.id
                    card.state shouldBe Card.State.ISSUED
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
                    card.expirationMonth shouldBeGreaterThan 0
                    card.expirationYear shouldBeGreaterThan 2019
                    card.securityCode.isBlank() shouldBe false
                    card.version shouldBeGreaterThan 0
                } else {
                    provisionalCard2?.card shouldBe null
                    delay(2_000L)
                }

                state = provisionalCard2?.state ?: ProvisionalCard.State.PROVISIONING
            }
        }
    }

    @Test
    fun getCardShouldReturnProvisionedCardResult() = runBlocking<Unit> {
        // Can only run if client config files are present
        assumeTrue(clientConfigFilesPresent())

        signInAndRegister()
        verifyTestUserIdentity()

        val fundingSourceInput = CreditCardFundingSourceInput(
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

        val fundingSource = vcClient.createFundingSource(fundingSourceInput)
        fundingSource shouldNotBe null

        val sudo = createSudo(
            Sudo("Mr", "Theodore", "Bear", "Shopping", null, null)
        )
        sudo.id shouldNotBe null

        val provisionCardInput = ProvisionCardInput(
            sudoId = sudo.id!!,
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
        val card = provisionCard(provisionCardInput, vcClient)

        // when
        val retrievedCard = vcClient.getCard(card.id) ?: throw AssertionError("should not be null")

        // then
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
        retrievedCard.expirationMonth shouldBe card.expirationMonth
        retrievedCard.expirationYear shouldBe card.expirationYear
        retrievedCard.securityCode shouldBe card.securityCode
        retrievedCard.version shouldBe card.version
    }

    @Test
    fun getCardShouldReturnNullForNonExistentId() = runBlocking<Unit> {
        // Can only run if client config files are present
        assumeTrue(clientConfigFilesPresent())

        signInAndRegister()

        // when
        val retrievedCard = vcClient.getCard("NonExistentId")

        // then
        retrievedCard shouldBe null
    }

    @Test
    fun listCardsShouldReturnSingleCardListOutputResult() = runBlocking<Unit> {
        // Can only run if client config files are present
        assumeTrue(clientConfigFilesPresent())

        signInAndRegister()
        verifyTestUserIdentity()

        val fundingSourceInput = CreditCardFundingSourceInput(
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

        val fundingSource = vcClient.createFundingSource(fundingSourceInput)
        fundingSource shouldNotBe null

        val sudo = createSudo(
            Sudo("Mr", "Theodore", "Bear", "Shopping", null, null)
        )
        sudo.id shouldNotBe null

        val provisionCardInput = ProvisionCardInput(
            sudoId = sudo.id!!,
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
        val card = provisionCard(provisionCardInput, vcClient)

        // when
        val listCards = vcClient.listCards()

        // then
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
        cards[0].expirationMonth shouldBe card.expirationMonth
        cards[0].expirationYear shouldBe card.expirationYear
        cards[0].securityCode shouldBe card.securityCode
        cards[0].version shouldBeGreaterThan 0
    }

    @Test
    fun listCardsShouldReturnMultipleCardListOutputResult() = runBlocking<Unit> {
        // Can only run if client config files are present
        assumeTrue(clientConfigFilesPresent())

        signInAndRegister()
        verifyTestUserIdentity()

        val fundingSourceInput = CreditCardFundingSourceInput(
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

        val fundingSource = vcClient.createFundingSource(fundingSourceInput)
        fundingSource shouldNotBe null

        val sudo = createSudo(
            Sudo("Mr", "Theodore", "Bear", "Shopping", null, null)
        )
        sudo.id shouldNotBe null

        val provisionCardInput = ProvisionCardInput(
            sudoId = sudo.id!!,
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

        val card1 = provisionCard(provisionCardInput, vcClient)
        Thread.sleep(1)
        val card2 = provisionCard(provisionCardInput, vcClient)

        card2.createdAt.time shouldBeGreaterThan card1.createdAt.time

        // Since card2 is created after card1 it should be returned first in the list
        val expectedCards = arrayOf(card2, card1)

        // when
        val listCards = vcClient.listCards()

        // then
        listCards.items.isEmpty() shouldBe false
        listCards.items.size shouldBe 2
        listCards.nextToken shouldBe null

        val actualCards = listCards.items
        for (i in 0 until expectedCards.size) {
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
            actualCards[i].expirationMonth shouldBe expectedCards[i].expirationMonth
            actualCards[i].expirationYear shouldBe expectedCards[i].expirationYear
            actualCards[i].securityCode shouldBe expectedCards[i].securityCode
            actualCards[i].version shouldBeGreaterThan 0
        }
    }

    @Test
    fun listCardsWithNotEqualToIssuedStateFilterShouldReturnEmptyListOutputResult() = runBlocking<Unit> {
        // Can only run if client config files are present
        assumeTrue(clientConfigFilesPresent())

        signInAndRegister()
        verifyTestUserIdentity()

        val fundingSourceInput = CreditCardFundingSourceInput(
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

        val fundingSource = vcClient.createFundingSource(fundingSourceInput)
        fundingSource shouldNotBe null

        val sudo = createSudo(
            Sudo("Mr", "Theodore", "Bear", "Shopping", null, null)
        )
        sudo.id shouldNotBe null

        val provisionCardInput = ProvisionCardInput(
            sudoId = sudo.id!!,
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
        provisionCard(provisionCardInput, vcClient)

        // when
        val listCards = vcClient.listCards(
            filter = {
                filterCardsBy {
                    state notEqualTo "ISSUED"
                }
            }
        )

        // then
        listCards.items.isEmpty() shouldBe true
        listCards.items.size shouldBe 0
        listCards.nextToken shouldBe null
    }

    @Test
    fun listCardsWithEqualToIssuedStateFilterShouldReturnListOutputResult() = runBlocking<Unit> {
        // Can only run if client config files are present
        assumeTrue(clientConfigFilesPresent())

        signInAndRegister()
        verifyTestUserIdentity()

        val fundingSourceInput = CreditCardFundingSourceInput(
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

        val fundingSource = vcClient.createFundingSource(fundingSourceInput)
        fundingSource shouldNotBe null

        val sudo = createSudo(
            Sudo("Mr", "Theodore", "Bear", "Shopping", null, null)
        )
        sudo.id shouldNotBe null

        val provisionCardInput = ProvisionCardInput(
            sudoId = sudo.id!!,
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
        provisionCard(provisionCardInput, vcClient)

        // when
        val listCards = vcClient.listCards(
            filter = {
                filterCardsBy {
                    state equalTo "ISSUED"
                }
            }
        )

        // then
        listCards.items.isEmpty() shouldBe false
        listCards.items.size shouldBe 1
        listCards.nextToken shouldBe null
    }

    @Test
    fun updateCardShouldReturnUpdatedCardResult() = runBlocking<Unit> {
        // Can only run if client config files are present
        assumeTrue(clientConfigFilesPresent())

        signInAndRegister()
        verifyTestUserIdentity()

        val fundingSourceInput = CreditCardFundingSourceInput(
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

        val fundingSource = vcClient.createFundingSource(fundingSourceInput)
        fundingSource shouldNotBe null

        val sudo = createSudo(
            Sudo("Mr", "Theodore", "Bear", "Shopping", null, null)
        )
        sudo.id shouldNotBe null

        val provisionCardInput = ProvisionCardInput(
            sudoId = sudo.id!!,
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
        val card = provisionCard(provisionCardInput, vcClient)

        // given
        val updateCardInput = UpdateCardInput(
            id = card.id,
            cardHolder = "Not Unlimited Cards",
            alias = "Bed Tear",
            addressLine1 = "321 Somewhere St",
            city = "Olnem Park",
            state = "NY",
            postalCode = "52049",
            country = "US"
        )
        // when
        val updatedCard = vcClient.updateCard(updateCardInput)

        // then
        updatedCard.id shouldBe card.id
        updatedCard.cardHolder shouldNotBe card.cardHolder
        updatedCard.alias shouldNotBe card.alias
        updatedCard.billingAddress shouldNotBe card.billingAddress
    }

    @Test
    fun updateCardShouldReturnPartiallyUpdatedCardResult() = runBlocking<Unit> {
        // Can only run if client config files are present
        assumeTrue(clientConfigFilesPresent())

        signInAndRegister()
        verifyTestUserIdentity()

        val fundingSourceInput = CreditCardFundingSourceInput(
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

        val fundingSource = vcClient.createFundingSource(fundingSourceInput)
        fundingSource shouldNotBe null

        val sudo = createSudo(
            Sudo("Mr", "Theodore", "Bear", "Shopping", null, null)
        )
        sudo.id shouldNotBe null

        val provisionCardInput = ProvisionCardInput(
            sudoId = sudo.id!!,
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
        val card = provisionCard(provisionCardInput, vcClient)

        // given
        val updateCardInput = UpdateCardInput(
            id = card.id,
            cardHolder = "Unlimited Cards",
            alias = "Bed Tear",
            addressLine1 = "123 Nowhere St",
            city = "Menlo Park",
            state = "CA",
            postalCode = "94025",
            country = "US"
        )
        // when
        val updatedCard = vcClient.updateCard(updateCardInput)

        // then
        updatedCard.id shouldBe card.id
        updatedCard.cardHolder shouldBe card.cardHolder
        updatedCard.alias shouldNotBe card.alias
        updatedCard.billingAddress shouldBe card.billingAddress
    }

    @Test
    fun updateCardWithNullBillingAddressInputShouldReturnUpdatedCardWithNullBillingAddress() = runBlocking<Unit> {
        // Can only run if client config files are present
        assumeTrue(clientConfigFilesPresent())

        signInAndRegister()
        verifyTestUserIdentity()

        val fundingSourceInput = CreditCardFundingSourceInput(
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

        val fundingSource = vcClient.createFundingSource(fundingSourceInput)
        fundingSource shouldNotBe null

        val sudo = createSudo(
            Sudo("Mr", "Theodore", "Bear", "Shopping", null, null)
        )
        sudo.id shouldNotBe null

        val provisionCardInput = ProvisionCardInput(
            sudoId = sudo.id!!,
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
        val card = provisionCard(provisionCardInput, vcClient)

        // given
        val updateCardInput = UpdateCardInput(
            id = card.id,
            cardHolder = "",
            alias = "",
            billingAddress = null
        )
        // when
        val updatedCard = vcClient.updateCard(updateCardInput)

        // then
        updatedCard.id shouldBe card.id
        updatedCard.cardHolder shouldNotBe card.cardHolder
        updatedCard.alias shouldNotBe card.alias
        updatedCard.billingAddress shouldNotBe card.billingAddress
        updatedCard.billingAddress shouldBe null
    }

    @Test
    fun updateCardShouldThrowWithNonExistentId() = runBlocking<Unit> {
        // Can only run if client config files are present
        assumeTrue(clientConfigFilesPresent())

        signInAndRegister()

        // given
        val updateCardInput = UpdateCardInput(
            id = "NonExistentId",
            cardHolder = "",
            alias = "",
            billingAddress = null
        )

        // when
        shouldThrow<SudoVirtualCardsClient.CardException.CardNotFoundException> {
            vcClient.updateCard(updateCardInput)
        }
    }

    @Test
    fun cancelCardShouldReturnClosedCardResult() = runBlocking<Unit> {
        // Can only run if client config files are present
        assumeTrue(clientConfigFilesPresent())

        signInAndRegister()
        verifyTestUserIdentity()

        val fundingSourceInput = CreditCardFundingSourceInput(
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

        val fundingSource = vcClient.createFundingSource(fundingSourceInput)
        fundingSource shouldNotBe null

        val sudo = createSudo(
            Sudo("Mr", "Theodore", "Bear", "Shopping", null, null)
        )
        sudo.id shouldNotBe null

        val provisionCardInput = ProvisionCardInput(
            sudoId = sudo.id!!,
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
        val card = provisionCard(provisionCardInput, vcClient)

        // when
        val cancelledCard = vcClient.cancelCard(card.id)

        // then
        cancelledCard.id shouldBe card.id
        cancelledCard.cardNumber shouldBe card.cardNumber
        cancelledCard.last4 shouldBe card.last4
        cancelledCard.cardHolder shouldBe card.cardHolder
        cancelledCard.alias shouldBe card.alias
        cancelledCard.fundingSourceId shouldBe card.fundingSourceId
        cancelledCard.state shouldBe Card.State.CLOSED
        cancelledCard.billingAddress shouldBe card.billingAddress
        cancelledCard.currency shouldBe card.currency
        cancelledCard.owner shouldBe card.owner
        cancelledCard.owners.first().id shouldBe card.owners.first().id
        cancelledCard.owners.first().issuer shouldBe card.owners.first().issuer
        cancelledCard.cancelledAt shouldNotBe card.cancelledAt
        cancelledCard.activeTo.time shouldBeGreaterThan 0L
        cancelledCard.createdAt.time shouldBeGreaterThan 0L
        cancelledCard.updatedAt.time shouldBeGreaterThan 0L
        cancelledCard.expirationMonth shouldBe card.expirationMonth
        cancelledCard.expirationYear shouldBe card.expirationYear
        cancelledCard.version shouldBeGreaterThan 0
    }

    @Test
    fun cancelCardShouldThrowWithNonExistentId() = runBlocking<Unit> {
        // Can only run if client config files are present
        assumeTrue(clientConfigFilesPresent())

        signInAndRegister()

        // when
        shouldThrow<SudoVirtualCardsClient.CardException.CardNotFoundException> {
            vcClient.cancelCard("NonExistentId")
        }
    }

    @Test
    fun getTransactionsShouldReturnNullForBogusId() = runBlocking<Unit> {
        // Can only run if client config files are present
        assumeTrue(clientConfigFilesPresent())

        signInAndRegister()

        vcClient.getTransaction(UUID.randomUUID().toString()) shouldBe null
    }

    @Test
    fun listTransactionsShouldReturnEmptyList() = runBlocking<Unit> {
        // Can only run if client config files are present
        assumeTrue(clientConfigFilesPresent())

        signInAndRegister()

        with(vcClient.listTransactions()) {
            items.isEmpty() shouldBe true
            nextToken shouldBe null
        }

        var transactions = vcClient.listTransactions {
            filterTransactionsBy {
                cardId beginsWith "42"
                sequenceId contains "42"
            }
        }
        with(transactions) {
            items.isEmpty() shouldBe true
            nextToken shouldBe null
        }

        transactions = vcClient.listTransactions {
            filterTransactionsBy {
                cardId between ("42" to "43")
                sequenceId between ("0" to "9999")
            }
        }
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
    fun subscribeUnsubscribeShouldNotFail() = runBlocking<Unit> {
        // Can only run if client config files are present
        assumeTrue(clientConfigFilesPresent())

        signInAndRegister()
        verifyTestUserIdentity()

        vcClient.subscribeToTransactions("id", transactionSubscriber)
        vcClient.unsubscribeAll()

        vcClient.subscribeToTransactions("id", transactionSubscriber)
        vcClient.unsubscribeFromTransactions("id")

        vcClient.subscribeToTransactions("id") { }

        vcClient.close()
    }

    @Test
    fun subscribeShouldThrowWhenNotAuthenticated() = runBlocking<Unit> {
        // Can only run if client config files are present
        assumeTrue(clientConfigFilesPresent())

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
    fun resetShouldNotAffectOtherClients() = runBlocking<Unit> {
        assumeTrue(clientConfigFilesPresent())

        signInAndRegister()
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

        val fundingSourceInput = CreditCardFundingSourceInput(
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

        val fundingSource = vcClient.createFundingSource(fundingSourceInput)
        fundingSource shouldNotBe null

        val sudo = createSudo(
            Sudo("Mr", "Theodore", "Bear", "Shopping", null, null)
        )
        sudo.id shouldNotBe null

        val provisionCardInput = ProvisionCardInput(
            sudoId = sudo.id!!,
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
        provisionCard(provisionCardInput, vcClient)

        keyManager.exportKeys().size shouldBe 3

        vcClient.reset()
        keyManager.exportKeys().size shouldBe 0
        assumeTrue(userClient.isRegistered())
    }
}
