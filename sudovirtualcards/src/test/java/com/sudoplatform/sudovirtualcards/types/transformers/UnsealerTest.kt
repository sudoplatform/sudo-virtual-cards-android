/*
 * Copyright © 2023 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards.types.transformers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.amazonaws.util.Base64
import com.google.gson.Gson
import com.sudoplatform.sudokeymanager.AndroidSQLiteStore
import com.sudoplatform.sudokeymanager.KeyManager
import com.sudoplatform.sudokeymanager.KeyManagerInterface
import com.sudoplatform.sudovirtualcards.BaseTests
import com.sudoplatform.sudovirtualcards.graphql.ProvisionVirtualCardMutation
import com.sudoplatform.sudovirtualcards.graphql.fragment.BankAccountFundingSource
import com.sudoplatform.sudovirtualcards.graphql.fragment.ProvisionalCard
import com.sudoplatform.sudovirtualcards.graphql.fragment.SealedAddressAttribute
import com.sudoplatform.sudovirtualcards.graphql.fragment.SealedAttribute
import com.sudoplatform.sudovirtualcards.graphql.fragment.SealedCard
import com.sudoplatform.sudovirtualcards.graphql.fragment.SealedCardWithLastTransaction
import com.sudoplatform.sudovirtualcards.graphql.fragment.SealedCurrencyAmountAttribute
import com.sudoplatform.sudovirtualcards.graphql.fragment.SealedExpiryAttribute
import com.sudoplatform.sudovirtualcards.graphql.fragment.SealedMarkupAttribute
import com.sudoplatform.sudovirtualcards.graphql.fragment.SealedTransaction
import com.sudoplatform.sudovirtualcards.graphql.fragment.SealedTransactionDetailChargeAttribute
import com.sudoplatform.sudovirtualcards.graphql.type.CardState
import com.sudoplatform.sudovirtualcards.graphql.type.ProvisioningState
import com.sudoplatform.sudovirtualcards.graphql.type.TransactionType
import com.sudoplatform.sudovirtualcards.keys.DefaultDeviceKeyManager
import com.sudoplatform.sudovirtualcards.keys.DefaultPublicKeyService
import com.sudoplatform.sudovirtualcards.types.CurrencyAmount
import com.sudoplatform.sudovirtualcards.types.Expiry
import com.sudoplatform.sudovirtualcards.types.InstitutionLogo
import com.sudoplatform.sudovirtualcards.types.JsonValue
import com.sudoplatform.sudovirtualcards.types.ProvisionalVirtualCard
import com.sudoplatform.sudovirtualcards.types.SymmetricKeyEncryptionAlgorithm
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
import com.sudoplatform.sudovirtualcards.graphql.fragment.Owner as OwnerFragment
import com.sudoplatform.sudovirtualcards.types.CardState as CardStateEntity

/**
 * Test the operation of [Unsealer] under Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class UnsealerTest : BaseTests() {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private val keyManager by before {
        KeyManager(AndroidSQLiteStore(context))
    }

    private val deviceKeyManager by before {
        DefaultDeviceKeyManager(
            keyManager = keyManager,
        )
    }

    private val symmetricKeyId = "symmetricKey"
    private val publicKeyId = UUID.randomUUID().toString()
    private val keyInfo = KeyInfo(publicKeyId, KeyType.PRIVATE_KEY, DefaultPublicKeyService.DEFAULT_ALGORITHM)
    private val keyInfo2 = KeyInfo(symmetricKeyId, KeyType.SYMMETRIC_KEY, DefaultPublicKeyService.DEFAULT_ALGORITHM)

    private val unsealer by before {
        Unsealer(
            deviceKeyManager,
            keyInfo,
        )
    }

    private val metadataUnsealer by before {
        Unsealer(
            deviceKeyManager,
            keyInfo2,
        )
    }

    private fun seal(value: String): String {
        val encryptedSymmetricKeyBytes = keyManager.encryptWithPublicKey(
            publicKeyId,
            keyManager.getSymmetricKeyData(symmetricKeyId)
                ?: throw AssertionError("missing symmetric key"),
            KeyManagerInterface.PublicKeyEncryptionAlgorithm.RSA_ECB_OAEPSHA1,
        )
        encryptedSymmetricKeyBytes.size shouldBe Unsealer.KEY_SIZE_AES

        val encryptedData = keyManager.encryptWithSymmetricKey(
            symmetricKeyId,
            value.toByteArray(),
            KeyManagerInterface.SymmetricEncryptionAlgorithm.AES_CBC_PKCS7_256,
        )

        val data = ByteArray(encryptedSymmetricKeyBytes.size + encryptedData.size)
        encryptedSymmetricKeyBytes.copyInto(data)
        encryptedData.copyInto(data, Unsealer.KEY_SIZE_AES)

        return String(Base64.encode(data), Charsets.UTF_8)
    }

    private fun sealMetadata(value: JsonValue<Any>): String {
        val serializedMetadata = Gson().toJson(value.unwrap()).toByteArray(Charsets.UTF_8)
        val encryptedMetadata = deviceKeyManager.encryptWithSymmetricKeyId(symmetricKeyId, serializedMetadata)
        return String(Base64.encode(encryptedMetadata), Charsets.UTF_8)
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

        unsealer.unsealAmount(
            SealedCurrencyAmountAttribute(
                "SealedCurrencyAmount",
                sealedCurrency,
                sealedAmount,
            ),
        ) shouldBe CurrencyAmount("USD", 100)
    }

    @Test
    fun `unseal SealedCard Metadata should throw for unsupported algorithm`() {
        val sealedMetadata = SealedCard.Metadata(
            "Metadata",
            SealedCard.Metadata.Fragments(
                SealedAttribute(
                    "SealedAttribute",
                    symmetricKeyId,
                    "unsupported algorithm",
                    "json-string",
                    sealMetadata(JsonValue.JsonString("metadata")),
                ),
            ),
        )

        shouldThrow<Unsealer.UnsealerException.UnsupportedAlgorithmException> {
            metadataUnsealer.unseal(sealedMetadata)
        }
    }

    @Test
    fun `unseal SealedCard Metadata`() {
        val sealedMetadata = SealedCard.Metadata(
            "Metadata",
            SealedCard.Metadata.Fragments(
                SealedAttribute(
                    "SealedAttribute",
                    symmetricKeyId,
                    SymmetricKeyEncryptionAlgorithm.AES_CBC_PKCS7PADDING.toString(),
                    "json-string",
                    sealMetadata(JsonValue.JsonString("metadata")),
                ),
            ),
        )

        val metadata = metadataUnsealer.unseal(sealedMetadata)
        metadata shouldNotBe null

        metadata shouldBe JsonValue.JsonString("metadata")
        metadata.unwrap() shouldBe "metadata"
    }

    @Test
    fun `unseal SealedCard BillingAddress`() {
        val sealedBillingAddress = SealedCard.BillingAddress(
            "BillingAddress",
            SealedCard.BillingAddress.Fragments(
                SealedAddressAttribute(
                    "SealedAddressAttribute",
                    seal("123 Nowhere St"),
                    null,
                    seal("Menlo Park"),
                    seal("CA"),
                    seal("94025"),
                    seal("US"),
                ),
            ),
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

        unsealer.unseal(null as SealedCard.BillingAddress?) shouldBe null
    }

    @Test
    fun `unseal SealedCard Expiry`() {
        val sealedExpiry = SealedCard.Expiry(
            "Expiry",
            SealedCard.Expiry.Fragments(
                SealedExpiryAttribute(
                    "SealedExpiryAttribute",
                    seal("12"),
                    seal("2020"),
                ),
            ),
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
        val sealedBillingAddress = SealedCard.BillingAddress(
            "BillingAddress",
            SealedCard.BillingAddress.Fragments(
                SealedAddressAttribute(
                    "SealedAddressAttribute",
                    seal("333 Ravenswood Ave"),
                    seal("Building 201"),
                    seal("Menlo Park"),
                    seal("CA"),
                    seal("94025"),
                    seal("US"),
                ),
            ),
        )

        val sealedCard = SealedCard(
            "SealedCard",
            "id",
            "owner",
            1,
            1.0,
            1.0,
            DefaultPublicKeyService.DEFAULT_ALGORITHM,
            publicKeyId,
            "keyRingId",
            listOf(
                SealedCard.Owner(
                    "Owner",
                    SealedCard.Owner.Fragments(OwnerFragment("Owner", "id", "issuer")),
                ),
            ),
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
            SealedCard.Expiry(
                "Expiry",
                SealedCard.Expiry.Fragments(
                    SealedExpiryAttribute(
                        "SealedExpiryAttribute",
                        seal("01"),
                        seal("2021"),
                    ),
                ),
            ),
            null,
        )

        val card = VirtualCardTransformer.toEntity(deviceKeyManager, sealedCard)

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
            state shouldBe CardStateEntity.ISSUED
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
    fun `unseal ProvisionVirtualCardMutation result`() {
        val sealedBillingAddress = SealedCard.BillingAddress(
            "BillingAddress",
            SealedCard.BillingAddress.Fragments(
                SealedAddressAttribute(
                    "SealedAddressAttribute",
                    seal("333 Ravenswood Ave"),
                    null,
                    seal("Menlo Park"),
                    seal("CA"),
                    seal("94025"),
                    seal("US"),
                ),
            ),
        )

        val sealedCard = ProvisionalCard.Card(
            "Card",
            ProvisionalCard.Card.Fragments(
                SealedCard(
                    "SealedCard",
                    "id",
                    "owner",
                    1,
                    1.0,
                    1.0,
                    DefaultPublicKeyService.DEFAULT_ALGORITHM,
                    publicKeyId,
                    "keyRingId",
                    listOf(
                        SealedCard.Owner(
                            "Owner",
                            SealedCard.Owner.Fragments(OwnerFragment("Owner", "id", "issuer")),
                        ),
                    ),
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
                    SealedCard.Expiry(
                        "Expiry",
                        SealedCard.Expiry.Fragments(
                            SealedExpiryAttribute(
                                "SealedExpiryAttribute",
                                seal("01"),
                                seal("2021"),
                            ),
                        ),
                    ),
                    null,
                ),
            ),
        )

        val sealedCardProvision = ProvisionVirtualCardMutation.CardProvision(
            "CardProvision",
            ProvisionVirtualCardMutation.CardProvision.Fragments(
                ProvisionalCard(
                    "ProvisionalCard",
                    "id",
                    "owner",
                    1,
                    1.0,
                    1.0,
                    "clientRefId",
                    ProvisioningState.COMPLETED,
                    listOf(sealedCard),
                ),
            ),
        )

        val provisionalCard = VirtualCardTransformer.toEntity(deviceKeyManager, sealedCardProvision.fragments().provisionalCard())

        with(provisionalCard) {
            id shouldBe "id"
            owner shouldBe "owner"
            version shouldBe 1
            createdAt.time shouldBeGreaterThan 0L
            updatedAt.time shouldBeGreaterThan 0L
            clientRefId shouldBe "clientRefId"
            provisioningState shouldBe ProvisionalVirtualCard.ProvisioningState.COMPLETED
            card shouldNotBe null
        }
        with(provisionalCard.card!!) {
            owners.isEmpty() shouldBe false
            owners.first().id shouldBe "id"
            owners.first().issuer shouldBe "issuer"
            fundingSourceId shouldBe "fundingSourceId"
            currency shouldBe "currency"
            state shouldBe CardStateEntity.ISSUED
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
        with(provisionalCard.card!!.billingAddress!!) {
            addressLine1 shouldBe "333 Ravenswood Ave"
            addressLine2 shouldBe null
            city shouldBe "Menlo Park"
            state shouldBe "CA"
            postalCode shouldBe "94025"
            country shouldBe "US"
        }
    }

    @Test
    fun `unseal SealedCard`() {
        val sealedBillingAddress = SealedCard.BillingAddress(
            "BillingAddress",
            SealedCard.BillingAddress.Fragments(
                SealedAddressAttribute(
                    "SealedAddressAttribute",
                    seal("333 Ravenswood Ave"),
                    seal("Building 201"),
                    seal("Menlo Park"),
                    seal("CA"),
                    seal("94025"),
                    seal("US"),
                ),
            ),
        )

        val sealedTransaction = SealedTransaction(
            "SealedTransaction",
            "id",
            "owner",
            1,
            1.0,
            2.0,
            3.0,
            DefaultPublicKeyService.DEFAULT_ALGORITHM,
            publicKeyId,
            "cardId",
            "sequenceId",
            TransactionType.COMPLETE,
            seal("4.0"),
            seal("5.0"),
            SealedTransaction.BilledAmount(
                "BilledAmount",
                SealedTransaction.BilledAmount.Fragments(
                    SealedCurrencyAmountAttribute(
                        "SealedCurrencyAmountAttribute",
                        seal("USD"),
                        seal("300"),
                    ),
                ),
            ),
            SealedTransaction.TransactedAmount(
                "TransactedAmount",
                SealedTransaction.TransactedAmount.Fragments(
                    SealedCurrencyAmountAttribute(
                        "SealedCurrencyAmountAttribute",
                        seal("AUD"),
                        seal("400"),
                    ),
                ),
            ),
            seal("description"),
            null,
            listOf(
                SealedTransaction.Detail(
                    "Detail",
                    SealedTransaction.Detail.Fragments(
                        SealedTransactionDetailChargeAttribute(
                            "SealedTransactionDetailChargeAttribute",
                            SealedTransactionDetailChargeAttribute.VirtualCardAmount(
                                "VirtualCardAmount",
                                SealedTransactionDetailChargeAttribute.VirtualCardAmount.Fragments(
                                    SealedCurrencyAmountAttribute(
                                        "SealedCurrencyAmountAttribute",
                                        seal("USD"),
                                        seal("1"),
                                    ),
                                ),
                            ),
                            SealedTransactionDetailChargeAttribute.Markup(
                                "Markup",
                                SealedTransactionDetailChargeAttribute.Markup.Fragments(
                                    SealedMarkupAttribute(
                                        "SealedMarkupAttribute",
                                        seal("1.0"),
                                        seal("2.0"),
                                        seal("3.0"),
                                    ),
                                ),
                            ),
                            SealedTransactionDetailChargeAttribute.MarkupAmount(
                                "MarkupAmount",
                                SealedTransactionDetailChargeAttribute.MarkupAmount.Fragments(
                                    SealedCurrencyAmountAttribute(
                                        "SealedCurrencyAmountAttribute",
                                        seal("USD"),
                                        seal("4"),
                                    ),
                                ),
                            ),
                            SealedTransactionDetailChargeAttribute.FundingSourceAmount(
                                "FundingSourceAmount",
                                SealedTransactionDetailChargeAttribute.FundingSourceAmount.Fragments(
                                    SealedCurrencyAmountAttribute(
                                        "SealedCurrencyAmountAttribute",
                                        seal("USD"),
                                        seal("2"),
                                    ),
                                ),
                            ),
                            "fundingSourceId",
                            seal("description"),
                            seal("CLEARED"),
                        ),
                    ),
                ),
            ),
        )

        val sealedCard = SealedCardWithLastTransaction(
            "SealedCard",
            SealedCardWithLastTransaction.LastTransaction(
                "LastTransaction",
                SealedCardWithLastTransaction.LastTransaction.Fragments(sealedTransaction),
            ),
            SealedCardWithLastTransaction.Fragments(
                SealedCard(
                    "SealedCard",
                    "id",
                    "owner",
                    1,
                    1.0,
                    1.0,
                    DefaultPublicKeyService.DEFAULT_ALGORITHM,
                    publicKeyId,
                    "keyRingId",
                    listOf(
                        SealedCard.Owner(
                            "Owner",
                            SealedCard.Owner.Fragments(OwnerFragment("Owner", "id", "issuer")),
                        ),
                    ),
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
                    SealedCard.Expiry(
                        "Expiry",
                        SealedCard.Expiry.Fragments(
                            SealedExpiryAttribute(
                                "SealedExpiryAttribute",
                                seal("01"),
                                seal("2021"),
                            ),
                        ),
                    ),
                    null,
                ),
            ),
        )

        val card = VirtualCardTransformer.toEntity(deviceKeyManager, sealedCard)

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
            state shouldBe CardStateEntity.ISSUED
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

        with(card.expiry!!) {
            mm shouldBe "01"
            yyyy shouldBe "2021"
        }
    }

    @Test
    fun `unseal BankAccountFundingSource InstitutionName`() {
        val sealedInstitutionName = BankAccountFundingSource.InstitutionName(
            "InstitutionName",
            BankAccountFundingSource.InstitutionName.Fragments(
                SealedAttribute(
                    "InstitutionName",
                    "keyId",
                    "algorithm",
                    "string",
                    seal("FooBar Institution"),
                ),
            ),
        )
        unsealer.unseal(sealedInstitutionName) shouldBe "FooBar Institution"
    }

    @Test
    fun `unseal BankAccountFundingSource InstitutionName should throw if invalid plainTextType`() {
        val sealedInstitutionName = BankAccountFundingSource.InstitutionName(
            "InstitutionName",
            BankAccountFundingSource.InstitutionName.Fragments(
                SealedAttribute(
                    "InstitutionName",
                    "keyId",
                    "algorithm",
                    "invalid",
                    seal("FooBar Institution"),
                ),
            ),
        )
        shouldThrow<Unsealer.UnsealerException.UnsupportedDataTypeException> {
            unsealer.unseal(sealedInstitutionName)
        }
    }

    @Test
    fun `unseal BankAccountFundingSource InstitutionLogo`() {
        val logo = "{type: 'image/png', data: 'FooBar Institution'}"
        val sealedInstitutionLogo = BankAccountFundingSource.InstitutionLogo(
            "InstitutionLogo",
            BankAccountFundingSource.InstitutionLogo.Fragments(
                SealedAttribute(
                    "InstitutionLogo",
                    "keyId",
                    "algorithm",
                    "json-string",
                    seal(logo),
                ),
            ),
        )
        unsealer.unseal(sealedInstitutionLogo) shouldBe InstitutionLogo("image/png", "FooBar Institution")
    }

    @Test
    fun `unseal BankAccountFundingSource InstitutionLogo should throw if invalid plainTextType`() {
        val sealedInstitutionLogo = BankAccountFundingSource.InstitutionLogo(
            "InstitutionLogo",
            BankAccountFundingSource.InstitutionLogo.Fragments(
                SealedAttribute(
                    "InstitutionLogo",
                    "keyId",
                    "algorithm",
                    "invalid",
                    seal("{type: 'image/png', data: 'FooBar Institution'}"),
                ),
            ),
        )
        shouldThrow<Unsealer.UnsealerException.UnsupportedDataTypeException> {
            unsealer.unseal(sealedInstitutionLogo)
        }
    }

    // TODO: modify this test to return null if unexpected structure. For the purposes of investigation,
    // we are expecting an UnsealerException.
    @Test
    fun `unseal BankAccountFundingSource InstitutionLogo should return null if unexpected structure`() {
        val sealedInstitutionLogo = BankAccountFundingSource.InstitutionLogo(
            "InstitutionLogo",
            BankAccountFundingSource.InstitutionLogo.Fragments(
                SealedAttribute(
                    "InstitutionLogo",
                    "keyId",
                    "algorithm",
                    "json-string",
                    seal("{'invalid': 'Invalid Institution Logo'}"),
                ),
            ),
        )
        shouldThrow<Unsealer.UnsealerException.UnexpectedFormatException> {
            unsealer.unseal(sealedInstitutionLogo)
        }
    }
}
