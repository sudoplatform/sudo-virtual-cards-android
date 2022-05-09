/*
 * Copyright © 2022 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards.types.transformers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.amazonaws.util.Base64
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.stub
import com.sudoplatform.sudokeymanager.AndroidSQLiteStore
import com.sudoplatform.sudokeymanager.KeyManager
import com.sudoplatform.sudokeymanager.KeyManagerInterface
import com.sudoplatform.sudouser.SudoUserClient
import com.sudoplatform.sudovirtualcards.BaseTests
import com.sudoplatform.sudovirtualcards.graphql.CancelCardMutation
import com.sudoplatform.sudovirtualcards.graphql.CardProvisionMutation
import com.sudoplatform.sudovirtualcards.graphql.GetCardQuery
import com.sudoplatform.sudovirtualcards.graphql.GetProvisionalCardQuery
import com.sudoplatform.sudovirtualcards.graphql.ListCardsQuery
import com.sudoplatform.sudovirtualcards.graphql.UpdateCardMutation
import com.sudoplatform.sudovirtualcards.keys.DefaultDeviceKeyManager
import com.sudoplatform.sudovirtualcards.keys.DefaultPublicKeyService
import com.sudoplatform.sudovirtualcards.graphql.type.CardState
import com.sudoplatform.sudovirtualcards.graphql.type.DeltaAction
import com.sudoplatform.sudovirtualcards.graphql.type.ProvisioningState
import com.sudoplatform.sudovirtualcards.types.CurrencyAmount
import com.sudoplatform.sudovirtualcards.types.Expiry
import com.sudoplatform.sudovirtualcards.types.ProvisionalVirtualCard
import com.sudoplatform.sudovirtualcards.types.VirtualCard
import io.kotlintest.matchers.numerics.shouldBeGreaterThan
import io.kotlintest.shouldBe
import io.kotlintest.shouldNotBe
import io.kotlintest.shouldThrow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import timber.log.Timber
import java.util.UUID

/**
 * Test the operation of [Unsealer] under Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class UnsealerTest : BaseTests() {

    private val keyRingServiceName = "sudo-virtual-cards"

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private val mockUserClient by before {
        mock<SudoUserClient>().stub {
            on { getSubject() } doReturn "mockSubject"
        }
    }

    private val keyManager by before {
        KeyManager(AndroidSQLiteStore(context))
    }

    private val deviceKeyManager by before {
        DefaultDeviceKeyManager(
            userClient = mockUserClient,
            keyRingServiceName = keyRingServiceName,
            keyManager = keyManager
        )
    }

    private val symmetricKeyId = "symmetricKey"
    private val publicKeyId = UUID.randomUUID().toString()

    private val unsealer by before {
        Unsealer(
            deviceKeyManager,
            publicKeyId,
            DefaultPublicKeyService.DEFAULT_ALGORITHM
        )
    }

    private fun seal(value: String): String {

        val encryptedSymmetricKeyBytes = keyManager.encryptWithPublicKey(
            publicKeyId,
            keyManager.getSymmetricKeyData(symmetricKeyId),
            KeyManagerInterface.PublicKeyEncryptionAlgorithm.RSA_ECB_OAEPSHA1
        )
        encryptedSymmetricKeyBytes.size shouldBe Unsealer.KEY_SIZE_AES

        val encryptedData = keyManager.encryptWithSymmetricKey(
            symmetricKeyId,
            value.toByteArray(),
            KeyManagerInterface.SymmetricEncryptionAlgorithm.AES_CBC_PKCS7_256
        )

        val data = ByteArray(encryptedSymmetricKeyBytes.size + encryptedData.size)
        encryptedSymmetricKeyBytes.copyInto(data)
        encryptedData.copyInto(data, Unsealer.KEY_SIZE_AES)

        return String(Base64.encode(data), Charsets.UTF_8)
    }

    @Before
    fun init() {
        Timber.plant(Timber.DebugTree())
        keyManager.removeAllKeys()
        keyManager.generateKeyPair(publicKeyId, true)
        keyManager.generateSymmetricKey(symmetricKeyId)
    }

    @After
    fun fini() = runBlocking {
        Timber.uprootAll()
    }

    @Test
    fun `unseal string`() {

        val clearData = "The owl and the pussycat went to sea in a beautiful pea green boat."

        val sealedData = seal(clearData)

        unsealer.unseal(sealedData) shouldBe clearData
    }

    @Test
    fun `unseal amount to CurrencyAmount`() {
        val clearCurrency = "USD"
        val clearAmount = "100"

        val sealedCurrency = seal(clearCurrency)
        val sealedAmount = seal(clearAmount)

        unsealer.unsealAmount(sealedCurrency, sealedAmount) shouldBe CurrencyAmount("USD", 100)
    }

    @Test
    fun `unseal CardProvisionMutation BillingAddress`() {

        val sealedBillingAddress = CardProvisionMutation.BillingAddress(
            "typename",
            seal("123 Nowhere St"),
            null,
            seal("Menlo Park"),
            seal("CA"),
            seal("94025"),
            seal("US")
        )

        val billingAddress = unsealer.unseal(sealedBillingAddress)
        billingAddress shouldNotBe null

        with(billingAddress!!) {
            addressLine1 shouldBe "123 Nowhere St"
            addressLine2 shouldBe null
            city shouldBe "Menlo Park"
            state shouldBe "CA"
            postalCode shouldBe "94025"
            country shouldBe "US"
        }

        unsealer.unseal(null as CardProvisionMutation.BillingAddress?) shouldBe null
    }

    @Test
    fun `unseal CardProvisionMutation Expiry`() {

        val sealedExpiry = CardProvisionMutation.Expiry(
            "typename",
            seal("12"),
            seal("2020")
        )

        val expiry = unsealer.unseal(sealedExpiry)
        expiry shouldNotBe null

        with(expiry) {
            mm shouldBe "12"
            yyyy shouldBe "2020"
        }
    }

    @Test
    fun `unseal GetProvisionalCardQuery BillingAddress`() {

        val sealedBillingAddress = GetProvisionalCardQuery.BillingAddress(
            "typename",
            seal("333 Ravenswood Ave"),
            null,
            seal("Menlo Park"),
            seal("CA"),
            seal("94025"),
            seal("US")
        )

        val billingAddress = unsealer.unseal(sealedBillingAddress)
        billingAddress shouldNotBe null

        with(billingAddress!!) {
            addressLine1 shouldBe "333 Ravenswood Ave"
            addressLine2 shouldBe null
            city shouldBe "Menlo Park"
            state shouldBe "CA"
            postalCode shouldBe "94025"
            country shouldBe "US"
        }

        unsealer.unseal(null as GetProvisionalCardQuery.BillingAddress?) shouldBe null
    }

    @Test
    fun `unseal GetProvisionalCardQuery Expiry`() {

        val sealedExpiry = GetProvisionalCardQuery.Expiry(
            "typename",
            seal("12"),
            seal("2020")
        )

        val expiry = unsealer.unseal(sealedExpiry)
        expiry shouldNotBe null

        with(expiry) {
            mm shouldBe "12"
            yyyy shouldBe "2020"
        }
    }

    @Test
    fun `unseal GetCardQuery BillingAddress`() {

        val sealedBillingAddress = GetCardQuery.BillingAddress(
            "typename",
            seal("333 Ravenswood Ave"),
            null,
            seal("Menlo Park"),
            seal("CA"),
            seal("94025"),
            seal("US")
        )

        val billingAddress = unsealer.unseal(sealedBillingAddress)
        billingAddress shouldNotBe null

        with(billingAddress!!) {
            addressLine1 shouldBe "333 Ravenswood Ave"
            addressLine2 shouldBe null
            city shouldBe "Menlo Park"
            state shouldBe "CA"
            postalCode shouldBe "94025"
            country shouldBe "US"
        }

        unsealer.unseal(null as GetCardQuery.BillingAddress?) shouldBe null
    }

    @Test
    fun `unseal GetCardQuery Expiry`() {

        val sealedExpiry = GetCardQuery.Expiry(
            "typename",
            seal("12"),
            seal("2020")
        )

        val expiry = unsealer.unseal(sealedExpiry)
        expiry shouldNotBe null

        with(expiry) {
            mm shouldBe "12"
            yyyy shouldBe "2020"
        }
    }

    @Test
    fun `unseal ListCardsQuery BillingAddress`() {

        val sealedBillingAddress = ListCardsQuery.BillingAddress(
            "typename",
            seal("333 Ravenswood Ave"),
            null,
            seal("Menlo Park"),
            seal("CA"),
            seal("94025"),
            seal("US")
        )

        val billingAddress = unsealer.unseal(sealedBillingAddress)
        billingAddress shouldNotBe null

        with(billingAddress!!) {
            addressLine1 shouldBe "333 Ravenswood Ave"
            addressLine2 shouldBe null
            city shouldBe "Menlo Park"
            state shouldBe "CA"
            postalCode shouldBe "94025"
            country shouldBe "US"
        }

        unsealer.unseal(null as ListCardsQuery.BillingAddress?) shouldBe null
    }

    @Test
    fun `unseal ListCardsQuery Expiry`() {

        val sealedExpiry = ListCardsQuery.Expiry(
            "typename",
            seal("12"),
            seal("2020")
        )

        val expiry = unsealer.unseal(sealedExpiry)
        expiry shouldNotBe null

        with(expiry) {
            mm shouldBe "12"
            yyyy shouldBe "2020"
        }
    }

    @Test
    fun `unseal UpdateCardMutation BillingAddress`() {

        val sealedBillingAddress = UpdateCardMutation.BillingAddress(
            "typename",
            seal("333 Ravenswood Ave"),
            null,
            seal("Menlo Park"),
            seal("CA"),
            seal("94025"),
            seal("US")
        )

        val billingAddress = unsealer.unseal(sealedBillingAddress)
        billingAddress shouldNotBe null

        with(billingAddress!!) {
            addressLine1 shouldBe "333 Ravenswood Ave"
            addressLine2 shouldBe null
            city shouldBe "Menlo Park"
            state shouldBe "CA"
            postalCode shouldBe "94025"
            country shouldBe "US"
        }

        unsealer.unseal(null as UpdateCardMutation.BillingAddress?) shouldBe null
    }

    @Test
    fun `unseal UpdateCardMutation Expiry`() {

        val sealedExpiry = UpdateCardMutation.Expiry(
            "typename",
            seal("12"),
            seal("2020")
        )

        val expiry = unsealer.unseal(sealedExpiry)
        expiry shouldNotBe null

        with(expiry) {
            mm shouldBe "12"
            yyyy shouldBe "2020"
        }
    }

    @Test
    fun `unseal CancelCardMutation BillingAddress`() {

        val sealedBillingAddress = CancelCardMutation.BillingAddress(
            "typename",
            seal("333 Ravenswood Ave"),
            null,
            seal("Menlo Park"),
            seal("CA"),
            seal("94025"),
            seal("US")
        )

        val billingAddress = unsealer.unseal(sealedBillingAddress)
        billingAddress shouldNotBe null

        with(billingAddress!!) {
            addressLine1 shouldBe "333 Ravenswood Ave"
            addressLine2 shouldBe null
            city shouldBe "Menlo Park"
            state shouldBe "CA"
            postalCode shouldBe "94025"
            country shouldBe "US"
        }

        unsealer.unseal(null as CancelCardMutation.BillingAddress?) shouldBe null
    }

    @Test
    fun `unseal CancelCardMutation Expiry`() {

        val sealedExpiry = CancelCardMutation.Expiry(
            "typename",
            seal("12"),
            seal("2020")
        )

        val expiry = unsealer.unseal(sealedExpiry)
        expiry shouldNotBe null

        with(expiry) {
            mm shouldBe "12"
            yyyy shouldBe "2020"
        }
    }

    @Test
    fun `unseal should throw if data too short`() {
        val shortData = String(Base64.encode("hello".toByteArray()), Charsets.UTF_8)
        shouldThrow<Unsealer.UnsealerException.SealedDataTooShortException> {
            unsealer.unseal(shortData)
        }
    }

    @Test
    fun `unseal GetProvisionalCardQuery result`() {

        val sealedBillingAddress = GetProvisionalCardQuery.BillingAddress(
            "typename",
            seal("333 Ravenswood Ave"),
            seal("Building 201"),
            seal("Menlo Park"),
            seal("CA"),
            seal("94025"),
            seal("US")
        )

        val sealedProvisionalCard = GetProvisionalCardQuery.Card(
            "typename",
            "id",
            "owner",
            1,
            1.0,
            1.0,
            DefaultPublicKeyService.DEFAULT_ALGORITHM,
            publicKeyId,
            "keyRingId",
            listOf(GetProvisionalCardQuery.Owner("typename", "id", "issuer")),
            "fundingSourceId",
            "currency",
            CardState.ISSUED,
            1.0,
            null,
            "last4",
            seal("cardHolder"),
            seal("alias"),
            seal("pan"),
            seal("csc"),
            sealedBillingAddress,
            GetProvisionalCardQuery.Expiry(
                "typename",
                seal("01"),
                seal("2021")
            ),
            null
        )

        val provisionalCard = VirtualCardTransformer.toEntityFromGetProvisionalCardQueryResult(deviceKeyManager, sealedProvisionalCard)

        with(provisionalCard) {
            id shouldBe "id"
            owner shouldBe "owner"
            version shouldBe 1
            createdAt.time shouldBeGreaterThan 0L
            updatedAt.time shouldBeGreaterThan 0L
            owners.isEmpty() shouldBe false
            owners.first().id shouldBe "id"
            owners.first().issuer shouldBe "issuer"
            fundingSourceId shouldBe "fundingSourceId"
            currency shouldBe "currency"
            state shouldBe VirtualCard.State.ISSUED
            activeTo.time shouldBeGreaterThan 0L
            cancelledAt shouldBe null
            last4 shouldBe "last4"
            cardHolder shouldBe "cardHolder"
            alias shouldBe "alias"
            cardNumber shouldBe "pan"
            securityCode shouldBe "csc"
            billingAddress shouldNotBe null
            expiry shouldBe Expiry("01", "2021")
        }

        with(provisionalCard.billingAddress!!) {
            addressLine1 shouldBe "333 Ravenswood Ave"
            addressLine2 shouldBe "Building 201"
            city shouldBe "Menlo Park"
            state shouldBe "CA"
            postalCode shouldBe "94025"
            country shouldBe "US"
        }
    }

    @Test
    fun `unseal CardProvisionMutation result`() {

        val sealedBillingAddress = CardProvisionMutation.BillingAddress(
            "typename",
            seal("333 Ravenswood Ave"),
            null,
            seal("Menlo Park"),
            seal("CA"),
            seal("94025"),
            seal("US")
        )

        val sealedProvisionalCard = CardProvisionMutation.Card(
            "typename",
            "id",
            "owner",
            1,
            1.0,
            1.0,
            DefaultPublicKeyService.DEFAULT_ALGORITHM,
            publicKeyId,
            "keyRingId",
            listOf(CardProvisionMutation.Owner("typename", "id", "issuer")),
            "fundingSourceId",
            "currency",
            CardState.ISSUED,
            1.0,
            null,
            "last4",
            seal("cardHolder"),
            seal("alias"),
            seal("pan"),
            seal("csc"),
            sealedBillingAddress,
            CardProvisionMutation.Expiry(
                "typename",
                seal("01"),
                seal("2021")
            ),
            null,
            null
        )

        val sealedCardProvision = CardProvisionMutation.CardProvision(
            "typename",
            "id",
            "owner",
            1,
            1.0,
            1.0,
            "clientRefId",
            ProvisioningState.COMPLETED,
            listOf(sealedProvisionalCard),
            DeltaAction.DELETE
        )

        val provisionResult = VirtualCardTransformer.toEntityFromCardProvisionMutationResult(deviceKeyManager, sealedCardProvision)

        with(provisionResult) {
            id shouldBe "id"
            owner shouldBe "owner"
            version shouldBe 1
            createdAt.time shouldBeGreaterThan 0L
            updatedAt.time shouldBeGreaterThan 0L
            clientRefId shouldBe "clientRefId"
            provisioningState shouldBe ProvisionalVirtualCard.ProvisioningState.COMPLETED
            card shouldNotBe null
        }
        with(provisionResult.card!!) {
            owners.isEmpty() shouldBe false
            owners.first().id shouldBe "id"
            owners.first().issuer shouldBe "issuer"
            fundingSourceId shouldBe "fundingSourceId"
            currency shouldBe "currency"
            state shouldBe VirtualCard.State.ISSUED
            activeTo.time shouldBeGreaterThan 0L
            cancelledAt shouldBe null
            last4 shouldBe "last4"
            cardHolder shouldBe "cardHolder"
            alias shouldBe "alias"
            cardNumber shouldBe "pan"
            securityCode shouldBe "csc"
            billingAddress shouldNotBe null
            expiry shouldBe Expiry("01", "2021")
        }
        with(provisionResult.card!!.billingAddress!!) {
            addressLine1 shouldBe "333 Ravenswood Ave"
            addressLine2 shouldBe null
            city shouldBe "Menlo Park"
            state shouldBe "CA"
            postalCode shouldBe "94025"
            country shouldBe "US"
        }
    }

    @Test
    fun `unseal GetCardQuery result`() {

        val sealedBillingAddress = GetCardQuery.BillingAddress(
            "typename",
            seal("333 Ravenswood Ave"),
            seal("Building 201"),
            seal("Menlo Park"),
            seal("CA"),
            seal("94025"),
            seal("US")
        )

        val sealedCard = GetCardQuery.GetCard(
            "typename",
            "id",
            "owner",
            1,
            1.0,
            1.0,
            DefaultPublicKeyService.DEFAULT_ALGORITHM,
            publicKeyId,
            "keyRingId",
            listOf(GetCardQuery.Owner("typename", "id", "issuer")),
            "fundingSourceId",
            "currency",
            CardState.ISSUED,
            1.0,
            null,
            "last4",
            seal("cardHolder"),
            seal("alias"),
            seal("pan"),
            seal("csc"),
            sealedBillingAddress,
            GetCardQuery.Expiry(
                "typename",
                seal("01"),
                seal("2021")
            ),
            null,
            null
        )

        val card = VirtualCardTransformer.toEntityFromGetCardQueryResult(deviceKeyManager, sealedCard)

        with(card) {
            id shouldBe "id"
            owner shouldBe "owner"
            version shouldBe 1
            createdAt.time shouldBeGreaterThan 0L
            updatedAt.time shouldBeGreaterThan 0L
            owners.isEmpty() shouldBe false
            owners.first().id shouldBe "id"
            owners.first().issuer shouldBe "issuer"
            fundingSourceId shouldBe "fundingSourceId"
            currency shouldBe "currency"
            state shouldBe VirtualCard.State.ISSUED
            activeTo.time shouldBeGreaterThan 0L
            cancelledAt shouldBe null
            last4 shouldBe "last4"
            cardHolder shouldBe "cardHolder"
            alias shouldBe "alias"
            cardNumber shouldBe "pan"
            securityCode shouldBe "csc"
            billingAddress shouldNotBe null
            expiry shouldBe Expiry("01", "2021")
        }

        with(card.billingAddress!!) {
            addressLine1 shouldBe "333 Ravenswood Ave"
            addressLine2 shouldBe "Building 201"
            city shouldBe "Menlo Park"
            state shouldBe "CA"
            postalCode shouldBe "94025"
            country shouldBe "US"
        }
    }

    @Test
    fun `unseal ListCardsQuery result`() {

        val sealedBillingAddress = ListCardsQuery.BillingAddress(
            "typename",
            seal("333 Ravenswood Ave"),
            seal("Building 201"),
            seal("Menlo Park"),
            seal("CA"),
            seal("94025"),
            seal("US")
        )

        val sealedCard = ListCardsQuery.Item(
            "typename",
            "id",
            "owner",
            1,
            1.0,
            1.0,
            DefaultPublicKeyService.DEFAULT_ALGORITHM,
            publicKeyId,
            "keyRingId",
            listOf(ListCardsQuery.Owner("typename", "id", "issuer")),
            "fundingSourceId",
            "currency",
            CardState.ISSUED,
            1.0,
            null,
            "last4",
            seal("cardHolder"),
            seal("alias"),
            seal("pan"),
            seal("csc"),
            sealedBillingAddress,
            ListCardsQuery.Expiry(
                "typename",
                seal("01"),
                seal("2021")
            ),
            null,
            null
        )
        val sealedCards = arrayListOf(sealedCard)

        val card = VirtualCardTransformer.toEntityFromListCardsQueryResult(deviceKeyManager, sealedCards)

        with(card[0]) {
            id shouldBe "id"
            owner shouldBe "owner"
            version shouldBe 1
            createdAt.time shouldBeGreaterThan 0L
            updatedAt.time shouldBeGreaterThan 0L
            owners.isEmpty() shouldBe false
            owners.first().id shouldBe "id"
            owners.first().issuer shouldBe "issuer"
            fundingSourceId shouldBe "fundingSourceId"
            currency shouldBe "currency"
            state shouldBe VirtualCard.State.ISSUED
            activeTo.time shouldBeGreaterThan 0L
            cancelledAt shouldBe null
            last4 shouldBe "last4"
            cardHolder shouldBe "cardHolder"
            alias shouldBe "alias"
            cardNumber shouldBe "pan"
            securityCode shouldBe "csc"
            billingAddress shouldNotBe null
            expiry shouldBe Expiry("01", "2021")
        }

        with(card[0].billingAddress!!) {
            addressLine1 shouldBe "333 Ravenswood Ave"
            addressLine2 shouldBe "Building 201"
            city shouldBe "Menlo Park"
            state shouldBe "CA"
            postalCode shouldBe "94025"
            country shouldBe "US"
        }
    }

    @Test
    fun `unseal UpdateCardMutation result`() {

        val sealedBillingAddress = UpdateCardMutation.BillingAddress(
            "typename",
            seal("333 Ravenswood Ave"),
            null,
            seal("Menlo Park"),
            seal("CA"),
            seal("94025"),
            seal("US")
        )

        val sealedUpdatedCard = UpdateCardMutation.UpdateCard(
            "typename",
            "id",
            "owner",
            1,
            1.0,
            1.0,
            DefaultPublicKeyService.DEFAULT_ALGORITHM,
            publicKeyId,
            "keyRingId",
            listOf(UpdateCardMutation.Owner("typename", "id", "issuer")),
            "fundingSourceId",
            "currency",
            CardState.ISSUED,
            1.0,
            null,
            "last4",
            seal("cardHolder"),
            seal("alias"),
            seal("pan"),
            seal("csc"),
            sealedBillingAddress,
            UpdateCardMutation.Expiry(
                "typename",
                seal("01"),
                seal("2021")
            ),
            null,
            null
        )

        val updatedCard = VirtualCardTransformer.toEntityFromUpdateCardMutationResult(deviceKeyManager, sealedUpdatedCard)

        with(updatedCard) {
            owners.isEmpty() shouldBe false
            owners.first().id shouldBe "id"
            owners.first().issuer shouldBe "issuer"
            fundingSourceId shouldBe "fundingSourceId"
            currency shouldBe "currency"
            state shouldBe VirtualCard.State.ISSUED
            activeTo.time shouldBeGreaterThan 0L
            cancelledAt shouldBe null
            last4 shouldBe "last4"
            cardHolder shouldBe "cardHolder"
            alias shouldBe "alias"
            cardNumber shouldBe "pan"
            securityCode shouldBe "csc"
            billingAddress shouldNotBe null
            expiry shouldBe Expiry("01", "2021")
        }

        with(updatedCard.billingAddress!!) {
            addressLine1 shouldBe "333 Ravenswood Ave"
            addressLine2 shouldBe null
            city shouldBe "Menlo Park"
            state shouldBe "CA"
            postalCode shouldBe "94025"
            country shouldBe "US"
        }
    }

    @Test
    fun `unseal CancelCardMutation result`() {

        val sealedBillingAddress = CancelCardMutation.BillingAddress(
            "typename",
            seal("333 Ravenswood Ave"),
            null,
            seal("Menlo Park"),
            seal("CA"),
            seal("94025"),
            seal("US")
        )

        val sealedCancelledCard = CancelCardMutation.CancelCard(
            "typename",
            "id",
            "owner",
            1,
            1.0,
            1.0,
            DefaultPublicKeyService.DEFAULT_ALGORITHM,
            publicKeyId,
            "keyRingId",
            listOf(CancelCardMutation.Owner("typename", "id", "issuer")),
            "fundingSourceId",
            "currency",
            CardState.CLOSED,
            1.0,
            null,
            "last4",
            seal("cardHolder"),
            seal("alias"),
            seal("pan"),
            seal("csc"),
            sealedBillingAddress,
            CancelCardMutation.Expiry(
                "typename",
                seal("01"),
                seal("2021")
            ),
            null,
            null
        )

        val cancelledCard = VirtualCardTransformer.toEntityFromCancelCardMutationResult(deviceKeyManager, sealedCancelledCard)

        with(cancelledCard) {
            owners.isEmpty() shouldBe false
            owners.first().id shouldBe "id"
            owners.first().issuer shouldBe "issuer"
            fundingSourceId shouldBe "fundingSourceId"
            currency shouldBe "currency"
            state shouldBe VirtualCard.State.CLOSED
            activeTo.time shouldBeGreaterThan 0L
            cancelledAt shouldBe null
            last4 shouldBe "last4"
            cardHolder shouldBe "cardHolder"
            alias shouldBe "alias"
            cardNumber shouldBe "pan"
            securityCode shouldBe "csc"
            billingAddress shouldNotBe null
            expiry shouldBe Expiry("01", "2021")
        }

        with(cancelledCard.billingAddress!!) {
            addressLine1 shouldBe "333 Ravenswood Ave"
            addressLine2 shouldBe null
            city shouldBe "Menlo Park"
            state shouldBe "CA"
            postalCode shouldBe "94025"
            country shouldBe "US"
        }
    }
}
