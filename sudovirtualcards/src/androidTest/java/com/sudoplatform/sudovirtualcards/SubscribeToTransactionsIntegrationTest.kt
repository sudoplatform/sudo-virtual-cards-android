/*
 * Copyright © 2024 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sudoplatform.sudokeymanager.KeyManagerFactory
import com.sudoplatform.sudovirtualcards.simulator.types.inputs.SimulateAuthorizationInput
import com.sudoplatform.sudovirtualcards.simulator.types.inputs.SimulateDebitInput
import com.sudoplatform.sudovirtualcards.simulator.types.outputs.SimulateAuthorizationResponse
import com.sudoplatform.sudovirtualcards.simulator.types.outputs.SimulateDebitResponse
import com.sudoplatform.sudovirtualcards.subscription.TransactionSubscriber
import com.sudoplatform.sudovirtualcards.subscription.TransactionSubscriber.ChangeType
import com.sudoplatform.sudovirtualcards.types.CurrencyAmount
import com.sudoplatform.sudovirtualcards.types.Transaction
import com.sudoplatform.sudovirtualcards.types.TransactionType
import com.sudoplatform.sudovirtualcards.types.VirtualCard
import com.sudoplatform.sudovirtualcards.util.CreateCardFundingSourceOptions
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
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SubscribeToTransactionsIntegrationTest : BaseIntegrationTest() {

    // MARK: - Supplementary

    class TransactionSubscriberMock : TransactionSubscriber {

        var transactionChangedCallCount = 0
        var transactionChangedParameterList: MutableList<Pair<Transaction, ChangeType>> = mutableListOf()

        override fun transactionChanged(transaction: Transaction, changeType: ChangeType) {
            transactionChangedCallCount += 1
            transactionChangedParameterList.add(Pair(transaction, changeType))
        }
    }

    // MARK: - Properties

    private lateinit var vcClient: SudoVirtualCardsClient

    // MARK: -Lifecycle

    @Before
    fun init() {
        KeyManagerFactory(context).createAndroidKeyManager().removeAllKeys()
        sudoClient.generateEncryptionKey()
        vcClient = SudoVirtualCardsClient.builder()
            .setContext(context)
            .setSudoUserClient(userClient)
            .build()
    }

    @After
    fun fini(): Unit = runBlocking {
        if (userClient.isRegistered()) {
            deregister()
        }
        vcClient.reset()
        sudoClient.reset()
        userClient.reset()
        sudoClient.generateEncryptionKey()
    }

    // MARK: - Tests

    @Test
    fun subscribeToTransactions_withAuthorizationTransaction_willNotifySubscriberWithUpsertChangeType(): Unit = runBlocking {
        // given
        val card = setupVirtualCard()
        val mockTransactionSubscriber = TransactionSubscriberMock()
        vcClient.subscribeToTransactions("test-id", mockTransactionSubscriber)

        // when
        val authorizationResponse = simulateAuthorization(card, 1000)
        await.atMost(Duration.TEN_SECONDS) withPollInterval Duration.TWO_HUNDRED_MILLISECONDS untilCallTo {
            mockTransactionSubscriber.transactionChangedCallCount
        } has { this == 1 }

        simulateDebit(authorizationResponse.id, 400)
        await.atMost(Duration.TEN_SECONDS) withPollInterval Duration.TWO_HUNDRED_MILLISECONDS untilCallTo {
            mockTransactionSubscriber.transactionChangedCallCount
        } has { this == 3 }

        simulateDebit(authorizationResponse.id, 600)
        await.atMost(Duration.TEN_SECONDS) withPollInterval Duration.TWO_HUNDRED_MILLISECONDS untilCallTo {
            mockTransactionSubscriber.transactionChangedCallCount
        } has { this == 5 }

        // then
        mockTransactionSubscriber.transactionChangedParameterList.map { it.first.type } shouldBe listOf(
            TransactionType.PENDING, // Pending transaction of $10 created
            TransactionType.PENDING, // Pending transaction updated to $6 after first debit of $4
            TransactionType.COMPLETE, // First debit transaction of $4 created
            TransactionType.PENDING, // Pending transaction deleted after second debit of $6
            TransactionType.COMPLETE, // Second debit transaction of $6 created
        )
        mockTransactionSubscriber.transactionChangedParameterList.map { it.first.transactedAmount } shouldBe listOf(
            CurrencyAmount("USD", 1000),
            CurrencyAmount("USD", 600),
            CurrencyAmount("USD", 400),
            CurrencyAmount("USD", 600),
            CurrencyAmount("USD", 600),
        )
        mockTransactionSubscriber.transactionChangedParameterList.map { it.second } shouldBe listOf(
            ChangeType.UPSERTED,
            ChangeType.UPSERTED,
            ChangeType.UPSERTED,
            ChangeType.DELETED,
            ChangeType.UPSERTED,
        )
    }

    // MARK: - Helpers

    private suspend fun setupVirtualCard(): VirtualCard {
        assumeTrue(isTransactionSimulatorAvailable())
        registerSignInAndEntitle()
        verifyTestUserIdentity()

        val providerToUse = getProviderToUse(vcClient)
        val testCard = TestData.TestCards[providerToUse]?.get("Visa-No3DS-1") ?: throw AssertionError("Test card should not be null")
        val fundingSourceInput = testCard.toFundingSourceInput(expirationMonth(), expirationYear())
        val fundingSourceOptions = CreateCardFundingSourceOptions(supportedProviders = listOf(providerToUse))
        val fundingSource = createCardFundingSource(vcClient, fundingSourceInput, fundingSourceOptions)
        fundingSource shouldNotBe null

        vcClient.createKeysIfAbsent()

        val sudo = createSudo(TestData.sudo)
        sudo.id shouldNotBe null

        val ownershipProof = getOwnershipProof(sudo)
        ownershipProof shouldNotBe null

        val provisionCardInput = TestData.ProvisionCardInput.toVirtualCardInput(listOf(ownershipProof), fundingSource.id)
        val card = provisionVirtualCard(vcClient, provisionCardInput)
        card shouldNotBe null
        return card
    }

    private suspend fun simulateAuthorization(card: VirtualCard, amount: Int): SimulateAuthorizationResponse {
        val merchant = vcSimulatorClient.getSimulatorMerchants().first()
        val authInput = SimulateAuthorizationInput(
            cardNumber = card.cardNumber,
            amount = amount,
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
        return authResponse
    }

    private suspend fun simulateDebit(authorizationId: String, amount: Int): SimulateDebitResponse {
        val debitInput = SimulateDebitInput(authorizationId, amount)
        return vcSimulatorClient.simulateDebit(debitInput)
    }
}
