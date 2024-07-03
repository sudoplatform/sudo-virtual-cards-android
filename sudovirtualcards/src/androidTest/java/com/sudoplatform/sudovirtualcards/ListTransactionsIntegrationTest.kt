/*
 * Copyright © 2023 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sudoplatform.sudokeymanager.KeyManagerFactory
import com.sudoplatform.sudovirtualcards.simulator.types.inputs.SimulateAuthorizationInput
import com.sudoplatform.sudovirtualcards.types.CurrencyAmount
import com.sudoplatform.sudovirtualcards.types.ListAPIResult
import com.sudoplatform.sudovirtualcards.types.Transaction
import com.sudoplatform.sudovirtualcards.types.TransactionType
import com.sudoplatform.sudovirtualcards.types.inputs.CreditCardFundingSourceInput
import com.sudoplatform.sudovirtualcards.types.inputs.ProvisionVirtualCardInput
import com.sudoplatform.sudovirtualcards.util.CreateCardFundingSourceOptions
import io.kotlintest.matchers.collections.shouldHaveSize
import io.kotlintest.matchers.numerics.shouldBeGreaterThan
import io.kotlintest.shouldBe
import io.kotlintest.shouldNotBe
import kotlinx.coroutines.runBlocking
import org.awaitility.Duration
import org.awaitility.kotlin.await
import org.awaitility.kotlin.has
import org.awaitility.kotlin.untilCallTo
import org.awaitility.kotlin.withPollInterval
import org.junit.After
import org.junit.Assert
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import timber.log.Timber
import java.util.concurrent.TimeUnit
import java.util.logging.Logger

@RunWith(AndroidJUnit4::class)
class ListTransactionsIntegrationTest : BaseIntegrationTest() {
    private val verbose = false

    private lateinit var vcClient: SudoVirtualCardsClient

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
    fun listTransactionsByCardIdShouldReturnSingleTransactionListOutputResult() = runBlocking {
        assumeTrue(isTransactionSimulatorAvailable())
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
            TestData.VerifiedUser.fullName,
        )
        val fundingSource = createCardFundingSource(
            vcClient,
            input,
            CreateCardFundingSourceOptions(supportedProviders = listOf(providerToUse)),
        )
        fundingSource shouldNotBe null

        vcClient.createKeysIfAbsent()

        val sudo = createSudo(
            TestData.sudo,
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
            currency = TestData.ProvisionCardInput.currency,
        )
        val card = provisionVirtualCard(vcClient, provisionCardInput)
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

        val transactions = await.atMost(Duration.TEN_SECONDS.multiply(4)) withPollInterval Duration.TWO_HUNDRED_MILLISECONDS untilCallTo {
            runBlocking {
                vcClient.listTransactionsByCardId(card.id)
            }
        } has { (this as ListAPIResult.Success<Transaction>).result.items.size == 1 }

        when (transactions) {
            is ListAPIResult.Success -> {
                transactions.result.items shouldHaveSize 1

                with(transactions.result.items[0]) {
                    id.isBlank() shouldBe false
                    owner shouldBe card.owner
                    version shouldBe 1
                    cardId shouldBe card.id
                    sequenceId.isBlank() shouldBe false
                    type shouldBe TransactionType.PENDING
                    billedAmount shouldBe CurrencyAmount("USD", originalAmount)
                    transactedAmount shouldBe CurrencyAmount("USD", originalAmount)
                    description shouldBe merchant.name
                    declineReason shouldBe null
                }
            }
            else -> {
                Assert.fail("Unexpected ListAPIResult")
            }
        }
    }

    @Test
    fun listTransactionsByCardIdShouldReturnMultipleTransactionListOutputResult() = runBlocking {
        assumeTrue(isTransactionSimulatorAvailable())
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
            TestData.VerifiedUser.fullName,
        )
        val fundingSource = createCardFundingSource(
            vcClient,
            input,
            CreateCardFundingSourceOptions(supportedProviders = listOf(providerToUse)),
        )
        fundingSource shouldNotBe null

        vcClient.createKeysIfAbsent()

        val sudo = createSudo(
            TestData.sudo,
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
            currency = TestData.ProvisionCardInput.currency,
        )
        val card = provisionVirtualCard(vcClient, provisionCardInput)
        card shouldNotBe null

        simulateTransactions(card)

        // List the transactions and wait until there are no PENDING transactions
        val transactions = await.atMost(Duration.TEN_SECONDS.multiply(4)) withPollInterval Duration.TWO_HUNDRED_MILLISECONDS untilCallTo {
            runBlocking {
                vcClient.listTransactionsByCardId(card.id)
            }
        } has { (this as ListAPIResult.Success<Transaction>).result.items.size == 2 }

        when (transactions) {
            is ListAPIResult.Success -> {
                transactions.result.items shouldHaveSize 2
            }
            else -> {
                Assert.fail("Unexpected ListAPIResult")
            }
        }
    }

    @Test
    fun listTransactionsByCardIdShouldRespectLimit() = runBlocking {
        assumeTrue(isTransactionSimulatorAvailable())
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
            TestData.VerifiedUser.fullName,
        )
        val fundingSource = createCardFundingSource(
            vcClient,
            input,
            CreateCardFundingSourceOptions(supportedProviders = listOf(providerToUse)),
        )
        fundingSource shouldNotBe null

        vcClient.createKeysIfAbsent()

        val sudo = createSudo(
            TestData.sudo,
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
            currency = TestData.ProvisionCardInput.currency,
        )
        val card = provisionVirtualCard(vcClient, provisionCardInput)
        card shouldNotBe null

        simulateTransactions(card)

        // SimulateTransactions creates two transactions, one at a time. So this transactionsWithLimit call can return
        // a single transaction and no nextToken, but in order to verify that it has worked, we need to wait for a single
        // transaction and a valid nextToken.
        val transactionsWithLimit = await.atMost(
            Duration(
                12,
                TimeUnit.SECONDS,
            ),
        ) withPollInterval Duration.TWO_HUNDRED_MILLISECONDS untilCallTo {
            runBlocking {
                vcClient.listTransactionsByCardId(
                    cardId = card.id,
                    limit = 1,
                )
            }
        } has {
            (this as ListAPIResult.Success<Transaction>).result.items.size == 1 &&
                (this.result.nextToken != null)
        }

        when (transactionsWithLimit) {
            is ListAPIResult.Success -> {
                transactionsWithLimit.result.items shouldHaveSize 1
                transactionsWithLimit.result.nextToken shouldNotBe null
            }
            else -> {
                Assert.fail("Unexpected ListAPIResult - listTransactionsByCardId did not result in more than 1 transaction")
            }
        }
        val pagedTransactions = vcClient.listTransactionsByCardId(
            cardId = card.id,
            nextToken = (transactionsWithLimit as ListAPIResult.Success<Transaction>).result.nextToken,
        )
        when (pagedTransactions) {
            is ListAPIResult.Success -> {
                pagedTransactions.result.items shouldHaveSize 1
                pagedTransactions.result.nextToken shouldBe null
            }
            else -> {
                Assert.fail("Unexpected ListAPIResult - paged listTransactionsByCardId did not return additional transaction")
            }
        }
    }

    @Test
    fun listTransactionsByCardIdShouldReturnEmptyList() = runBlocking {
        assumeTrue(isTransactionSimulatorAvailable())
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
            TestData.VerifiedUser.fullName,
        )
        val fundingSource = createCardFundingSource(
            vcClient,
            input,
            CreateCardFundingSourceOptions(supportedProviders = listOf(providerToUse)),
        )
        fundingSource shouldNotBe null

        vcClient.createKeysIfAbsent()

        val sudo = createSudo(
            TestData.sudo,
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
            currency = TestData.ProvisionCardInput.currency,
        )
        val card = provisionVirtualCard(vcClient, provisionCardInput)
        card shouldNotBe null

        when (val initialTransactions = vcClient.listTransactionsByCardId(card.id)) {
            is ListAPIResult.Success -> {
                initialTransactions.result.items.isEmpty() shouldBe true
                initialTransactions.result.nextToken shouldBe null
            }
            else -> {
                Assert.fail("Unexpected ListAPIResult")
            }
        }

        var transactions = vcClient.listTransactionsByCardId(
            cardId = card.id,
        )
        when (transactions) {
            is ListAPIResult.Success -> {
                transactions.result.items.isEmpty() shouldBe true
                transactions.result.nextToken shouldBe null
            }
            else -> {
                Assert.fail("Unexpected ListAPIResult")
            }
        }

        transactions = vcClient.listTransactionsByCardId(
            cardId = card.id,
        )
        when (transactions) {
            is ListAPIResult.Success -> {
                transactions.result.items.isEmpty() shouldBe true
                transactions.result.nextToken shouldBe null
            }
            else -> {
                Assert.fail("Unexpected ListAPIResult")
            }
        }
    }

    @Test
    fun listTransactionsByCardIdAndTypeShouldReturnSingleTransactionListOutputResult() = runBlocking {
        assumeTrue(isTransactionSimulatorAvailable())
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
            TestData.VerifiedUser.fullName,
        )
        val fundingSource = createCardFundingSource(
            vcClient,
            input,
            CreateCardFundingSourceOptions(supportedProviders = listOf(providerToUse)),
        )
        fundingSource shouldNotBe null

        vcClient.createKeysIfAbsent()

        val sudo = createSudo(
            TestData.sudo,
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
            currency = TestData.ProvisionCardInput.currency,
        )
        val card = provisionVirtualCard(vcClient, provisionCardInput)
        card shouldNotBe null

        simulateTransactions(card)

        // List only COMPLETE transactions
        val completeTransactions = await.atMost(
            Duration.TEN_SECONDS.multiply(4),
        ) withPollInterval Duration.TWO_HUNDRED_MILLISECONDS untilCallTo {
            runBlocking {
                vcClient.listTransactionsByCardIdAndType(card.id, TransactionType.COMPLETE)
            }
        } has { (this as ListAPIResult.Success<Transaction>).result.items.size == 1 }

        when (completeTransactions) {
            is ListAPIResult.Success -> {
                completeTransactions.result.items shouldHaveSize 1
            }
            else -> {
                Assert.fail("Unexpected ListAPIResult")
            }
        }

        // List only REFUND transactions
        val refundTransactions = await.atMost(
            Duration.TEN_SECONDS.multiply(4),
        ) withPollInterval Duration.TWO_HUNDRED_MILLISECONDS untilCallTo {
            runBlocking {
                vcClient.listTransactionsByCardIdAndType(card.id, TransactionType.REFUND)
            }
        } has { (this as ListAPIResult.Success<Transaction>).result.items.size == 1 }

        when (refundTransactions) {
            is ListAPIResult.Success -> {
                refundTransactions.result.items shouldHaveSize 1
            }
            else -> {
                Assert.fail("Unexpected ListAPIResult")
            }
        }
    }

    @Test
    fun listTransactionsByCardIdAndTypeShouldReturnMultipleTransactionListOutputResult() = runBlocking {
        assumeTrue(isTransactionSimulatorAvailable())
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
            TestData.VerifiedUser.fullName,
        )
        val fundingSource = createCardFundingSource(
            vcClient,
            input,
            CreateCardFundingSourceOptions(supportedProviders = listOf(providerToUse)),
        )
        fundingSource shouldNotBe null

        vcClient.createKeysIfAbsent()

        val sudo = createSudo(
            TestData.sudo,
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
            currency = TestData.ProvisionCardInput.currency,
        )
        val card = provisionVirtualCard(vcClient, provisionCardInput)
        card shouldNotBe null

        simulateTransactions(card)

        simulateTransactions(card)

        val transactions = await.atMost(Duration.TEN_SECONDS.multiply(4)) withPollInterval Duration.TWO_HUNDRED_MILLISECONDS untilCallTo {
            runBlocking {
                vcClient.listTransactionsByCardIdAndType(card.id, TransactionType.COMPLETE)
            }
        } has { (this as ListAPIResult.Success<Transaction>).result.items.size == 2 }

        when (transactions) {
            is ListAPIResult.Success -> {
                transactions.result.items shouldHaveSize 2
            }
            else -> {
                Assert.fail("Unexpected ListAPIResult")
            }
        }
    }

    @Test
    fun listTransactionsByCardIdAndTypeShouldRespectLimit() = runBlocking {
        assumeTrue(isTransactionSimulatorAvailable())
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
            TestData.VerifiedUser.fullName,
        )
        val fundingSource = createCardFundingSource(
            vcClient,
            input,
            CreateCardFundingSourceOptions(supportedProviders = listOf(providerToUse)),
        )
        fundingSource shouldNotBe null

        vcClient.createKeysIfAbsent()

        val sudo = createSudo(
            TestData.sudo,
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
            currency = TestData.ProvisionCardInput.currency,
        )
        val card = provisionVirtualCard(vcClient, provisionCardInput)
        card shouldNotBe null

        simulateTransactions(card)

        val merchant = vcSimulatorClient.getSimulatorMerchants().first()
        val originalAmount = 80
        val authInput = SimulateAuthorizationInput(
            cardNumber = card.cardNumber,
            amount = originalAmount,
            merchantId = merchant.id,
            securityCode = card.securityCode,
            expirationMonth = card.expiry.mm.toInt(),
            expirationYear = card.expiry.yyyy.toInt(),
        )
        vcSimulatorClient.simulateAuthorization(authInput)

        val transactions = await.atMost(Duration.TEN_SECONDS.multiply(4)) withPollInterval Duration.TWO_HUNDRED_MILLISECONDS untilCallTo {
            runBlocking {
                vcClient.listTransactionsByCardIdAndType(card.id, TransactionType.PENDING, limit = 1)
            }
        } has { (this as ListAPIResult.Success<Transaction>).result.items.size == 1 }

        when (transactions) {
            is ListAPIResult.Success -> {
                transactions.result.items shouldHaveSize 1
            }
            else -> {
                Assert.fail("Unexpected ListAPIResult")
            }
        }
    }

    @Test
    fun listTransactionsByCardIdAndTypeShouldReturnEmptyList() = runBlocking {
        assumeTrue(isTransactionSimulatorAvailable())
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
            TestData.VerifiedUser.fullName,
        )
        val fundingSource = createCardFundingSource(
            vcClient,
            input,
            CreateCardFundingSourceOptions(supportedProviders = listOf(providerToUse)),
        )
        fundingSource shouldNotBe null

        vcClient.createKeysIfAbsent()

        val sudo = createSudo(
            TestData.sudo,
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
            currency = TestData.ProvisionCardInput.currency,
        )
        val card = provisionVirtualCard(vcClient, provisionCardInput)
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

        when (
            val transactions = vcClient.listTransactionsByCardIdAndType(
                cardId = card.id,
                transactionType = TransactionType.REFUND,
            )
        ) {
            is ListAPIResult.Success -> {
                transactions.result.items.isEmpty() shouldBe true
                transactions.result.nextToken shouldBe null
            }
            else -> {
                Assert.fail("Unexpected ListAPIResult")
            }
        }
    }

    @Test
    fun listTransactionsShouldReturnSingleTransactionListOutputResult() = runBlocking {
        assumeTrue(isTransactionSimulatorAvailable())
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
            TestData.VerifiedUser.fullName,
        )
        val fundingSource = createCardFundingSource(
            vcClient,
            input,
            CreateCardFundingSourceOptions(supportedProviders = listOf(providerToUse)),
        )
        fundingSource shouldNotBe null

        vcClient.createKeysIfAbsent()

        val sudo = createSudo(
            TestData.sudo,
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
            currency = TestData.ProvisionCardInput.currency,
        )
        val card = provisionVirtualCard(vcClient, provisionCardInput)
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

        val transactions = await.atMost(Duration.TEN_SECONDS.multiply(4)) withPollInterval Duration.TWO_HUNDRED_MILLISECONDS untilCallTo {
            runBlocking {
                vcClient.listTransactions()
            }
        } has { (this as ListAPIResult.Success<Transaction>).result.items.size == 1 }

        when (transactions) {
            is ListAPIResult.Success -> {
                transactions.result.items shouldHaveSize 1

                with(transactions.result.items[0]) {
                    id.isBlank() shouldBe false
                    owner shouldBe card.owner
                    version shouldBe 1
                    cardId shouldBe card.id
                    sequenceId.isBlank() shouldBe false
                    type shouldBe TransactionType.PENDING
                    billedAmount shouldBe CurrencyAmount("USD", originalAmount)
                    transactedAmount shouldBe CurrencyAmount("USD", originalAmount)
                    description shouldBe merchant.name
                    declineReason shouldBe null
                }
            }
            else -> {
                Assert.fail("Unexpected ListAPIResult")
            }
        }
    }

    @Test
    fun listTransactionsShouldReturnMultipleTransactionListOutputResult() = runBlocking {
        assumeTrue(isTransactionSimulatorAvailable())

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
            TestData.VerifiedUser.fullName,
        )
        val fundingSource = createCardFundingSource(
            vcClient,
            input,
            CreateCardFundingSourceOptions(supportedProviders = listOf(providerToUse)),
        )
        fundingSource shouldNotBe null

        vcClient.createKeysIfAbsent()

        val sudo = createSudo(
            TestData.sudo,
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
            currency = TestData.ProvisionCardInput.currency,
        )
        val card = provisionVirtualCard(vcClient, provisionCardInput)
        card shouldNotBe null

        simulateTransactions(card)

        // List the transactions and wait until there are no PENDING transactions
        val transactions = await.atMost(Duration.TEN_SECONDS.multiply(4)) withPollInterval Duration.TWO_HUNDRED_MILLISECONDS untilCallTo {
            runBlocking {
                vcClient.listTransactions()
            }
        } has { (this as ListAPIResult.Success<Transaction>).result.items.size == 2 }

        when (transactions) {
            is ListAPIResult.Success -> {
                transactions.result.items shouldHaveSize 2
            }
            else -> {
                Assert.fail("Unexpected ListAPIResult")
            }
        }
    }

    @Test
    fun listTransactionsShouldRespectLimit() = runBlocking {
        assumeTrue(isTransactionSimulatorAvailable())
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
            TestData.VerifiedUser.fullName,
        )
        val fundingSource = createCardFundingSource(
            vcClient,
            input,
            CreateCardFundingSourceOptions(supportedProviders = listOf(providerToUse)),
        )
        fundingSource shouldNotBe null

        vcClient.createKeysIfAbsent()

        val sudo = createSudo(
            TestData.sudo,
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
            currency = TestData.ProvisionCardInput.currency,
        )
        val card = provisionVirtualCard(vcClient, provisionCardInput)
        card shouldNotBe null

        simulateTransactions(card)

        val transactionsWithLimit = await.atMost(
            Duration(
                12,
                TimeUnit.SECONDS,
            ),
        ) withPollInterval Duration.TWO_HUNDRED_MILLISECONDS untilCallTo {
            runBlocking {
                vcClient.listTransactions(
                    limit = 1,
                )
            }
        } has {
            (this as ListAPIResult.Success<Transaction>).result.items.size == 1 &&
                (this.result.nextToken != null)
        }

        when (transactionsWithLimit) {
            is ListAPIResult.Success -> {
                transactionsWithLimit.result.items shouldHaveSize 1
                transactionsWithLimit.result.nextToken shouldNotBe null
            }
            else -> {
                Assert.fail("Unexpected ListAPIResult - listTransactions did not result in more than one transaction")
            }
        }
        val pagedTransactions = vcClient.listTransactions(
            nextToken = (transactionsWithLimit as ListAPIResult.Success<Transaction>).result.nextToken,
        )
        when (pagedTransactions) {
            is ListAPIResult.Success -> {
                pagedTransactions.result.items shouldHaveSize 1
                pagedTransactions.result.nextToken shouldBe null
            }
            else -> {
                Assert.fail("Unexpected ListAPIResult - paged listTransactions did not return additional transaction")
            }
        }
    }

    @Test
    fun listTransactionsShouldReturnEmptyList() = runBlocking {
        assumeTrue(isTransactionSimulatorAvailable())
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
            TestData.VerifiedUser.fullName,
        )
        val fundingSource = createCardFundingSource(
            vcClient,
            input,
            CreateCardFundingSourceOptions(supportedProviders = listOf(providerToUse)),
        )
        fundingSource shouldNotBe null

        vcClient.createKeysIfAbsent()

        val sudo = createSudo(
            TestData.sudo,
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
            currency = TestData.ProvisionCardInput.currency,
        )
        val card = provisionVirtualCard(vcClient, provisionCardInput)
        card shouldNotBe null

        when (val initialTransactions = vcClient.listTransactions()) {
            is ListAPIResult.Success -> {
                initialTransactions.result.items.isEmpty() shouldBe true
                initialTransactions.result.nextToken shouldBe null
            }
            else -> {
                Assert.fail("Unexpected ListAPIResult")
            }
        }

        var transactions = vcClient.listTransactions()
        when (transactions) {
            is ListAPIResult.Success -> {
                transactions.result.items.isEmpty() shouldBe true
                transactions.result.nextToken shouldBe null
            }
            else -> {
                Assert.fail("Unexpected ListAPIResult")
            }
        }

        transactions = vcClient.listTransactions()
        when (transactions) {
            is ListAPIResult.Success -> {
                transactions.result.items.isEmpty() shouldBe true
                transactions.result.nextToken shouldBe null
            }
            else -> {
                Assert.fail("Unexpected ListAPIResult")
            }
        }
    }
}
