/*
 * Copyright © 2023 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards.types.transformers

import com.amazonaws.util.Base64
import com.google.gson.Gson
import com.sudoplatform.sudovirtualcards.SudoVirtualCardsClient
import com.sudoplatform.sudovirtualcards.graphql.fragment.ProvisionalCard as ProvisionalCardFragment
import com.sudoplatform.sudovirtualcards.graphql.fragment.SealedCard
import com.sudoplatform.sudovirtualcards.graphql.fragment.SealedCardWithLastTransaction
import com.sudoplatform.sudovirtualcards.graphql.fragment.SealedTransaction
import com.sudoplatform.sudovirtualcards.graphql.type.AddressInput
import com.sudoplatform.sudovirtualcards.graphql.type.CardState
import com.sudoplatform.sudovirtualcards.graphql.type.ProvisioningState
import com.sudoplatform.sudovirtualcards.graphql.type.SealedAttributeInput
import com.sudoplatform.sudovirtualcards.graphql.type.TransactionType
import com.sudoplatform.sudovirtualcards.keys.DeviceKeyManager
import com.sudoplatform.sudovirtualcards.types.BillingAddress
import com.sudoplatform.sudovirtualcards.types.CardState as CardStateEntity
import com.sudoplatform.sudovirtualcards.types.DeclineReason
import com.sudoplatform.sudovirtualcards.types.JsonValue
import com.sudoplatform.sudovirtualcards.types.Owner
import com.sudoplatform.sudovirtualcards.types.PartialVirtualCard
import com.sudoplatform.sudovirtualcards.types.ProvisionalVirtualCard
import com.sudoplatform.sudovirtualcards.types.SymmetricKeyEncryptionAlgorithm
import com.sudoplatform.sudovirtualcards.types.VirtualCard
import com.sudoplatform.sudovirtualcards.types.Transaction
import com.sudoplatform.sudovirtualcards.types.TransactionType as TransactionTypeEntity

/**
 * Transformer responsible for transforming the [VirtualCard] and [ProvisionalVirtualCard] GraphQL data
 * types to the entity type that is exposed to users.
 */
internal object VirtualCardTransformer {

    /**
     * Transform the input type [BillingAddress] into the corresponding GraphQL type [AddressInput].
     */
    fun BillingAddress?.toAddressInput(): AddressInput? {
        if (this == null) {
            return null
        }
        return AddressInput.builder()
            .addressLine1(addressLine1)
            .addressLine2(addressLine2)
            .city(city)
            .state(state)
            .postalCode(postalCode)
            .country(country)
            .build()
    }

    /**
     * Transform the input type [JsonValue] into the corresponding GraphQL type [SealedAttributeInput].
     */
    fun JsonValue<Any>?.toMetadataInput(deviceKeyManager: DeviceKeyManager): SealedAttributeInput? {
        if (this == null) {
            return null
        }
        var symmetricKeyId = deviceKeyManager.getCurrentSymmetricKeyId()
        if (symmetricKeyId == null) {
            symmetricKeyId = deviceKeyManager.generateNewCurrentSymmetricKey()
        }
        val serializedMetadata = Gson().toJson(this.unwrap()).toByteArray(Charsets.UTF_8)
        val encryptedMetadata = deviceKeyManager.encryptWithSymmetricKeyId(symmetricKeyId, serializedMetadata)
        val base64EncodedEncryptedMetadata = String(Base64.encode(encryptedMetadata), Charsets.UTF_8)
        return SealedAttributeInput.builder()
            .algorithm(SymmetricKeyEncryptionAlgorithm.AES_CBC_PKCS7PADDING.toString())
            .base64EncodedSealedData(base64EncodedEncryptedMetadata)
            .keyId(symmetricKeyId)
            .plainTextType("json-string")
            .build()
    }

    /**
     * Transform the [ProvisionalCardFragment] GraphQL type to its entity type.
     *
     * @param deviceKeyManager [DeviceKeyManager] Used to retrieve keys to unseal data.
     * @param provisionalCard [ProvisionalCardFragment] The GraphQL type.
     * @return The [ProvisionalVirtualCard] entity type.
     */
    fun toEntity(
        deviceKeyManager: DeviceKeyManager,
        provisionalCard: ProvisionalCardFragment
    ): ProvisionalVirtualCard {
        return ProvisionalVirtualCard(
            id = provisionalCard.id(),
            clientRefId = provisionalCard.clientRefId(),
            owner = provisionalCard.owner(),
            version = provisionalCard.version(),
            provisioningState = provisionalCard.provisioningState().toProvisionalCardState(),
            card = provisionalCard.card()?.firstOrNull()?.let { toEntity(deviceKeyManager, it.fragments().sealedCard()) },
            createdAt = provisionalCard.createdAtEpochMs().toDate(),
            updatedAt = provisionalCard.updatedAtEpochMs().toDate()
        )
    }

    /**
     * Transform the [SealedCardWithLastTransaction] GraphQL type to its entity type.
     *
     * @param deviceKeyManager [DeviceKeyManager] Used to retrieve keys to unseal data.
     * @param sealedCardWithLastTransaction [SealedCardWithLastTransaction] The GraphQL type.
     * @return The [VirtualCard] entity type.
     */
    fun toEntity(
        deviceKeyManager: DeviceKeyManager,
        sealedCardWithLastTransaction: SealedCardWithLastTransaction
    ): VirtualCard {
        val sealedCard = sealedCardWithLastTransaction.fragments().sealedCard()
            ?: throw SudoVirtualCardsClient.VirtualCardException.FailedException(
                "unexpected null SealedCard in SealedCardWithLastTransaction"
            )
        return toEntity(
            deviceKeyManager,
            sealedCard,
            sealedCardWithLastTransaction.lastTransaction()?.fragments()?.sealedTransaction()
        )
    }

    /**
     * Transform the [ProvisionalCardFragment] GraphQL type to its entity type.
     *
     * @param deviceKeyManager [DeviceKeyManager] Used to retrieve keys to unseal data.
     * @param sealedCard [SealedCard] The sealed card GraphQL type.
     * @param sealedLastTransaction [SealedTransaction] The seal last transaction GraphQL type.
     * @return The [VirtualCard] entity type.
     */
    fun toEntity(
        deviceKeyManager: DeviceKeyManager,
        sealedCard: SealedCard,
        sealedLastTransaction: SealedTransaction? = null
    ): VirtualCard {
        val keyInfo = KeyInfo(sealedCard.keyId(), KeyType.PRIVATE_KEY, sealedCard.algorithm())
        val unsealer = Unsealer(deviceKeyManager, keyInfo)

        val owners = sealedCard.owners().toOwners()
        return VirtualCard(
            id = sealedCard.id(),
            owner = sealedCard.owner(),
            owners = owners,
            version = sealedCard.version(),
            fundingSourceId = sealedCard.fundingSourceId(),
            state = sealedCard.state().toState(),
            cardHolder = unsealer.unseal(sealedCard.cardHolder()),
            alias = sealedCard.alias()?.let { unsealer.unseal(it) },
            metadata = sealedCard.metadata()?.let {
                val sealedAttribute = it.fragments().sealedAttribute()
                val symmetricKeyInfo = KeyInfo(sealedAttribute.keyId(), KeyType.SYMMETRIC_KEY, sealedAttribute.algorithm())
                val metadataUnsealer = Unsealer(deviceKeyManager, symmetricKeyInfo)
                metadataUnsealer.unseal(it)
            },
            last4 = sealedCard.last4(),
            cardNumber = unsealer.unseal(sealedCard.pan()),
            securityCode = unsealer.unseal(sealedCard.csc()),
            billingAddress = unsealer.unseal(sealedCard.billingAddress()),
            expiry = unsealer.unseal(sealedCard.expiry()),
            currency = sealedCard.currency(),
            activeTo = sealedCard.activeToEpochMs().toDate(),
            cancelledAt = sealedCard.cancelledAtEpochMs()?.toDate(),
            createdAt = sealedCard.createdAtEpochMs().toDate(),
            updatedAt = sealedCard.updatedAtEpochMs().toDate(),
            lastTransaction = sealedLastTransaction?.toTransaction(deviceKeyManager)
        )
    }

    /**
     * Transform a [SealedCardWithLastTransaction] into a [PartialVirtualCard].
     *
     * @param sealedCardWithLastTransaction [SealedCardWithLastTransaction] The GraphQL type.
     * @return The [PartialVirtualCard] entity type.
     */
    fun toPartialEntity(sealedCardWithLastTransaction: SealedCardWithLastTransaction): PartialVirtualCard {
        val sealedCard = sealedCardWithLastTransaction.fragments().sealedCard()
            ?: throw SudoVirtualCardsClient.VirtualCardException.FailedException(
                "unexpected null SealedCard in SealedCardWithLastTransaction"
            )
        return toPartialEntity(sealedCard)
    }

    /**
     * Transform a [SealedCard] into a [PartialVirtualCard].
     *
     * @param sealedCard [SealedCard] The GraphQL type.
     * @return The [PartialVirtualCard] entity type.
     */
    private fun toPartialEntity(sealedCard: SealedCard): PartialVirtualCard {
        return PartialVirtualCard(
            id = sealedCard.id(),
            owners = sealedCard.owners().toOwners(),
            owner = sealedCard.owner(),
            version = sealedCard.version(),
            fundingSourceId = sealedCard.fundingSourceId(),
            state = sealedCard.state().toState(),
            last4 = sealedCard.last4(),
            currency = sealedCard.currency(),
            activeTo = sealedCard.activeToEpochMs().toDate(),
            cancelledAt = sealedCard.cancelledAtEpochMs()?.toDate(),
            createdAt = sealedCard.createdAtEpochMs().toDate(),
            updatedAt = sealedCard.updatedAtEpochMs().toDate()
        )
    }

    private fun CardState.toState(): CardStateEntity {
        return when (this) {
            CardState.ISSUED -> CardStateEntity.ISSUED
            CardState.SUSPENDED -> CardStateEntity.SUSPENDED
            CardState.CLOSED -> CardStateEntity.CLOSED
            CardState.FAILED -> CardStateEntity.FAILED
        }
    }

    private fun ProvisioningState.toProvisionalCardState(): ProvisionalVirtualCard.ProvisioningState {
        return when (this) {
            ProvisioningState.PROVISIONING -> ProvisionalVirtualCard.ProvisioningState.PROVISIONING
            ProvisioningState.FAILED -> ProvisionalVirtualCard.ProvisioningState.FAILED
            ProvisioningState.COMPLETED -> ProvisionalVirtualCard.ProvisioningState.COMPLETED
        }
    }

    private fun SealedCard.Owner.toOwner(): Owner {
        return Owner(id = fragments().owner().id(), issuer = fragments().owner().issuer())
    }

    private fun List<SealedCard.Owner>.toOwners(): List<Owner> {
        return this.map {
            it.toOwner()
        }
    }

    private fun SealedTransaction.toTransaction(deviceKeyManager: DeviceKeyManager): Transaction {
        val keyInfo = KeyInfo(keyId(), KeyType.PRIVATE_KEY, algorithm())
        val unsealer = Unsealer(deviceKeyManager, keyInfo)
        return Transaction(
            id = id(),
            owner = owner(),
            version = version(),
            cardId = cardId(),
            sequenceId = sequenceId(),
            type = type().toTransactionType(),
            billedAmount = unsealer.unsealAmount(billedAmount().fragments().sealedCurrencyAmountAttribute()),
            transactedAmount = unsealer.unsealAmount(transactedAmount().fragments().sealedCurrencyAmountAttribute()),
            description = unsealer.unseal(description()),
            declineReason = declineReason()?.let { unsealer.unseal(it).toDeclineReason() },
            transactedAt = unsealer.unseal(transactedAtEpochMs()).toDouble().toDate(),
            createdAt = createdAtEpochMs().toDate(),
            updatedAt = updatedAtEpochMs().toDate()
        )
    }

    private fun TransactionType.toTransactionType(): TransactionTypeEntity {
        for (txnType in TransactionTypeEntity.values()) {
            if (txnType.name == this.name) {
                return txnType
            }
        }
        return TransactionTypeEntity.UNKNOWN
    }

    private fun String.toDeclineReason(): DeclineReason {
        for (value in DeclineReason.values()) {
            if (value.name == this) {
                return value
            }
        }
        return DeclineReason.UNKNOWN
    }
}
