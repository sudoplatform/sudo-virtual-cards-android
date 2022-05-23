/*
 * Copyright © 2022 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards.types.transformers

import com.amazonaws.util.Base64
import com.google.gson.Gson
import com.sudoplatform.sudovirtualcards.graphql.CancelCardMutation
import com.sudoplatform.sudovirtualcards.graphql.CardProvisionMutation
import com.sudoplatform.sudovirtualcards.graphql.GetCardQuery
import com.sudoplatform.sudovirtualcards.graphql.GetProvisionalCardQuery
import com.sudoplatform.sudovirtualcards.graphql.ListCardsQuery
import com.sudoplatform.sudovirtualcards.graphql.UpdateCardMutation
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
     * Transform the input type [BillingAddress] into the corresponding GraphQL type [AddressInput]
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
     * Transform the input type [JsonValue] into the corresponding GraphQL type [SealedAttributeInput]
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
     * Transform the results of the [CardProvisionMutation].
     *
     * @param deviceKeyManager [DeviceKeyManager] Used to retrieve keys to unseal data.
     * @param result [CardProvisionMutation.CardProvision] The GraphQL mutation results.
     * @return The [ProvisionalVirtualCard] entity type.
     */
    fun toEntityFromCardProvisionMutationResult(
        deviceKeyManager: DeviceKeyManager,
        result: CardProvisionMutation.CardProvision
    ): ProvisionalVirtualCard {
        return ProvisionalVirtualCard(
            id = result.id(),
            clientRefId = result.clientRefId(),
            owner = result.owner(),
            version = result.version(),
            provisioningState = result.provisioningState().toProvisionalCardState(),
            card = result.card()?.firstOrNull()?.toVirtualCard(deviceKeyManager),
            createdAt = result.createdAtEpochMs().toDate(),
            updatedAt = result.updatedAtEpochMs().toDate()
        )
    }

    /**
     * Transform the results of the [GetProvisionalCardQuery].
     *
     * @param deviceKeyManager [DeviceKeyManager] Used to retrieve keys to unseal data.
     * @param result [GetProvisionalCardQuery.Card] The GraphQL query results.
     * @return The [VirtualCard] entity type.
     */
    fun toEntityFromGetProvisionalCardQueryResult(deviceKeyManager: DeviceKeyManager, result: GetProvisionalCardQuery.Card): VirtualCard {
        val keyInfo = KeyInfo(result.keyId(), KeyType.PRIVATE_KEY, result.algorithm())
        val unsealer = Unsealer(deviceKeyManager, keyInfo)
        return VirtualCard(
            id = result.id(),
            owners = result.owners().toProvOwners(),
            owner = result.owner(),
            version = result.version(),
            fundingSourceId = result.fundingSourceId(),
            state = result.state().toState(),
            cardHolder = unsealer.unseal(result.cardHolder()),
            alias = result.alias()?.let { unsealer.unseal(it) },
            metadata = result.metadata()?.let {
                val symmetricKeyInfo = KeyInfo(it.keyId(), KeyType.SYMMETRIC_KEY, it.algorithm())
                val metadataUnsealer = Unsealer(deviceKeyManager, symmetricKeyInfo)
                metadataUnsealer.unseal(it)
            },
            last4 = result.last4(),
            cardNumber = unsealer.unseal(result.pan()),
            securityCode = unsealer.unseal(result.csc()),
            billingAddress = unsealer.unseal(result.billingAddress()),
            expiry = unsealer.unseal(result.expiry()),
            currency = result.currency(),
            activeTo = result.activeToEpochMs().toDate(),
            cancelledAt = result.cancelledAtEpochMs()?.toDate(),
            createdAt = result.createdAtEpochMs().toDate(),
            updatedAt = result.updatedAtEpochMs().toDate()
        )
    }

    /**
     * Transform the results of the [GetProvisionalCardQuery].
     *
     * @param deviceKeyManager [DeviceKeyManager] Used to retrieve keys to unseal data.
     * @param result [GetProvisionalCardQuery.GetProvisionalCard] The GraphQL query results.
     * @return The [ProvisionalVirtualCard] entity type.
     */
    fun toEntityFromGetProvisionalCardQueryResult(
        deviceKeyManager: DeviceKeyManager,
        result: GetProvisionalCardQuery.GetProvisionalCard
    ): ProvisionalVirtualCard {
        return ProvisionalVirtualCard(
            id = result.id(),
            clientRefId = result.clientRefId(),
            owner = result.owner(),
            version = result.version(),
            provisioningState = result.provisioningState().toProvisionalCardState(),
            card = result.card()?.firstOrNull()?.let { toEntityFromGetProvisionalCardQueryResult(deviceKeyManager, it) },
            createdAt = result.createdAtEpochMs().toDate(),
            updatedAt = result.updatedAtEpochMs().toDate()
        )
    }

    /**
     * Transform the results of the [GetCardQuery].
     *
     * @param deviceKeyManager [DeviceKeyManager] Used to retrieve keys to unseal data.
     * @param result [GetCardQuery.GetCard] The GraphQL query results.
     * @return The [VirtualCard] entity type.
     */
    fun toEntityFromGetCardQueryResult(deviceKeyManager: DeviceKeyManager, result: GetCardQuery.GetCard): VirtualCard {
        val keyInfo = KeyInfo(result.keyId(), KeyType.PRIVATE_KEY, result.algorithm())
        val unsealer = Unsealer(deviceKeyManager, keyInfo)
        return VirtualCard(
            id = result.id(),
            owners = result.owners().toGetCardOwners(),
            owner = result.owner(),
            version = result.version(),
            fundingSourceId = result.fundingSourceId(),
            state = result.state().toState(),
            cardHolder = unsealer.unseal(result.cardHolder()),
            alias = result.alias()?.let { unsealer.unseal(it) },
            metadata = result.metadata()?.let {
                val symmetricKeyInfo = KeyInfo(it.keyId(), KeyType.SYMMETRIC_KEY, it.algorithm())
                val metadataUnsealer = Unsealer(deviceKeyManager, symmetricKeyInfo)
                metadataUnsealer.unseal(it)
            },
            last4 = result.last4(),
            cardNumber = unsealer.unseal(result.pan()),
            securityCode = unsealer.unseal(result.csc()),
            billingAddress = unsealer.unseal(result.billingAddress()),
            expiry = unsealer.unseal(result.expiry()),
            currency = result.currency(),
            activeTo = result.activeToEpochMs().toDate(),
            cancelledAt = result.cancelledAtEpochMs()?.toDate(),
            createdAt = result.createdAtEpochMs().toDate(),
            updatedAt = result.updatedAtEpochMs().toDate(),
            lastTransaction = result.lastTransaction()?.toTransaction(deviceKeyManager)
        )
    }

    /**
     * Transform the results of the [ListCardsQuery.Item].
     *
     * @param deviceKeyManager [DeviceKeyManager] Used to retrieve keys to unseal data.
     * @param result [ListCardsQuery.Item] The GraphQL query result.
     * @return The [VirtualCard] entity type.
     */
    fun toEntityFromListCardsQueryResult(deviceKeyManager: DeviceKeyManager, result: ListCardsQuery.Item): VirtualCard {
        val keyInfo = KeyInfo(result.keyId(), KeyType.PRIVATE_KEY, result.algorithm())
        val unsealer = Unsealer(deviceKeyManager, keyInfo)
        return VirtualCard(
            id = result.id(),
            owners = result.owners().toListCardsOwners(),
            owner = result.owner(),
            version = result.version(),
            fundingSourceId = result.fundingSourceId(),
            state = result.state().toState(),
            cardHolder = unsealer.unseal(result.cardHolder()),
            alias = result.alias()?.let { unsealer.unseal(it) },
            metadata = result.metadata()?.let {
                val symmetricKeyInfo = KeyInfo(it.keyId(), KeyType.SYMMETRIC_KEY, it.algorithm())
                val metadataUnsealer = Unsealer(deviceKeyManager, symmetricKeyInfo)
                metadataUnsealer.unseal(it)
            },
            last4 = result.last4(),
            cardNumber = unsealer.unseal(result.pan()),
            securityCode = unsealer.unseal(result.csc()),
            billingAddress = unsealer.unseal(result.billingAddress()),
            expiry = unsealer.unseal(result.expiry()),
            currency = result.currency(),
            activeTo = result.activeToEpochMs().toDate(),
            cancelledAt = result.cancelledAtEpochMs()?.toDate(),
            createdAt = result.createdAtEpochMs().toDate(),
            updatedAt = result.updatedAtEpochMs().toDate(),
            lastTransaction = result.lastTransaction()?.toTransaction(deviceKeyManager)
        )
    }

    /**
     * Transform the results of the [ListCardsQuery.Item] into a [PartialVirtualCard].
     *
     * @param result [ListCardsQuery.Item] The GraphQL query result.
     * @return The [PartialVirtualCard] entity type.
     */
    fun toPartialVirtualCardFromListCardsQueryResult(result: ListCardsQuery.Item): PartialVirtualCard {
        return PartialVirtualCard(
            id = result.id(),
            owners = result.owners().toListCardsOwners(),
            owner = result.owner(),
            version = result.version(),
            fundingSourceId = result.fundingSourceId(),
            state = result.state().toState(),
            last4 = result.last4(),
            currency = result.currency(),
            activeTo = result.activeToEpochMs().toDate(),
            cancelledAt = result.cancelledAtEpochMs()?.toDate(),
            createdAt = result.createdAtEpochMs().toDate(),
            updatedAt = result.updatedAtEpochMs().toDate()
        )
    }

    /**
     * Transform the results of the [UpdateCardMutation].
     *
     * @param deviceKeyManager [DeviceKeyManager] Used to retrieve keys to unseal data.
     * @param result [UpdateCardMutation.UpdateCard] The GraphQL mutation results.
     * @return The [VirtualCard] entity type.
     */
    fun toEntityFromUpdateCardMutationResult(deviceKeyManager: DeviceKeyManager, result: UpdateCardMutation.UpdateCard): VirtualCard {
        val keyInfo = KeyInfo(result.keyId(), KeyType.PRIVATE_KEY, result.algorithm())
        val unsealer = Unsealer(deviceKeyManager, keyInfo)
        return VirtualCard(
            id = result.id(),
            owners = result.owners().toUpdateCardOwners(),
            owner = result.owner(),
            version = result.version(),
            fundingSourceId = result.fundingSourceId(),
            state = result.state().toState(),
            cardHolder = unsealer.unseal(result.cardHolder()),
            alias = result.alias()?.let { unsealer.unseal(it) },
            metadata = result.metadata()?.let {
                val symmetricKeyInfo = KeyInfo(it.keyId(), KeyType.SYMMETRIC_KEY, it.algorithm())
                val metadataUnsealer = Unsealer(deviceKeyManager, symmetricKeyInfo)
                metadataUnsealer.unseal(it)
            },
            last4 = result.last4(),
            cardNumber = unsealer.unseal(result.pan()),
            securityCode = unsealer.unseal(result.csc()),
            billingAddress = unsealer.unseal(result.billingAddress()),
            expiry = unsealer.unseal(result.expiry()),
            currency = result.currency(),
            activeTo = result.activeToEpochMs().toDate(),
            cancelledAt = result.cancelledAtEpochMs()?.toDate(),
            createdAt = result.createdAtEpochMs().toDate(),
            updatedAt = result.updatedAtEpochMs().toDate(),
            lastTransaction = result.lastTransaction()?.toTransaction(deviceKeyManager)
        )
    }

    /**
     * Transform the results of the [UpdateCardMutation.UpdateCard] into a [PartialVirtualCard].
     *
     * @param result [UpdateCardMutation.UpdateCard] The GraphQL query result.
     * @return The [PartialVirtualCard] entity type.
     */
    fun toPartialEntityFromUpdateCardMutationResult(result: UpdateCardMutation.UpdateCard): PartialVirtualCard {
        return PartialVirtualCard(
            id = result.id(),
            owners = result.owners().toUpdateCardOwners(),
            owner = result.owner(),
            version = result.version(),
            fundingSourceId = result.fundingSourceId(),
            state = result.state().toState(),
            last4 = result.last4(),
            currency = result.currency(),
            activeTo = result.activeToEpochMs().toDate(),
            cancelledAt = result.cancelledAtEpochMs()?.toDate(),
            createdAt = result.createdAtEpochMs().toDate(),
            updatedAt = result.updatedAtEpochMs().toDate()
        )
    }

    /**
     * Transform the results of the [CancelCardMutation].
     *
     * @param deviceKeyManager [DeviceKeyManager] Used to retrieve keys to unseal data.
     * @param result [CancelCardMutation.CancelCard] The GraphQL mutation results.
     * @return The [VirtualCard] entity type.
     */
    fun toEntityFromCancelCardMutationResult(deviceKeyManager: DeviceKeyManager, result: CancelCardMutation.CancelCard): VirtualCard {
        val keyInfo = KeyInfo(result.keyId(), KeyType.PRIVATE_KEY, result.algorithm())
        val unsealer = Unsealer(deviceKeyManager, keyInfo)
        return VirtualCard(
            id = result.id(),
            owners = result.owners().toCancelCardOwners(),
            owner = result.owner(),
            version = result.version(),
            fundingSourceId = result.fundingSourceId(),
            state = result.state().toState(),
            cardHolder = unsealer.unseal(result.cardHolder()),
            alias = result.alias()?.let { unsealer.unseal(it) },
            metadata = result.metadata()?.let {
                val symmetricKeyInfo = KeyInfo(it.keyId(), KeyType.SYMMETRIC_KEY, it.algorithm())
                val metadataUnsealer = Unsealer(deviceKeyManager, symmetricKeyInfo)
                metadataUnsealer.unseal(it)
            },
            last4 = result.last4(),
            cardNumber = unsealer.unseal(result.pan()),
            securityCode = unsealer.unseal(result.csc()),
            billingAddress = unsealer.unseal(result.billingAddress()),
            expiry = unsealer.unseal(result.expiry()),
            currency = result.currency(),
            activeTo = result.activeToEpochMs().toDate(),
            cancelledAt = result.cancelledAtEpochMs()?.toDate(),
            createdAt = result.createdAtEpochMs().toDate(),
            updatedAt = result.updatedAtEpochMs().toDate(),
            lastTransaction = result.lastTransaction()?.toTransaction(deviceKeyManager)
        )
    }

    /**
     * Transform the results of the [CancelCardMutation.CancelCard] into a [PartialVirtualCard].
     *
     * @param result [CancelCardMutation.CancelCard] The GraphQL query result.
     * @return The [PartialVirtualCard] entity type.
     */
    fun toPartialVirtualCardFromCancelCardMutationResult(result: CancelCardMutation.CancelCard): PartialVirtualCard {
        return PartialVirtualCard(
            id = result.id(),
            owners = result.owners().toCancelCardOwners(),
            owner = result.owner(),
            version = result.version(),
            fundingSourceId = result.fundingSourceId(),
            state = result.state().toState(),
            last4 = result.last4(),
            currency = result.currency(),
            activeTo = result.activeToEpochMs().toDate(),
            cancelledAt = result.cancelledAtEpochMs()?.toDate(),
            createdAt = result.createdAtEpochMs().toDate(),
            updatedAt = result.updatedAtEpochMs().toDate()
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

    private fun CardProvisionMutation.Card.toVirtualCard(deviceKeyManager: DeviceKeyManager): VirtualCard {
        val keyInfo = KeyInfo(keyId(), KeyType.PRIVATE_KEY, algorithm())
        val unsealer = Unsealer(deviceKeyManager, keyInfo)
        return VirtualCard(
            id = id(),
            owners = owners().toOwners(),
            owner = owner(),
            version = version(),
            fundingSourceId = fundingSourceId(),
            state = state().toCardState(),
            cardHolder = unsealer.unseal(cardHolder()),
            alias = alias()?.let { unsealer.unseal(it) },
            metadata = metadata()?.let {
                val symmetricKeyInfo = KeyInfo(it.keyId(), KeyType.SYMMETRIC_KEY, it.algorithm())
                val metadataUnsealer = Unsealer(deviceKeyManager, symmetricKeyInfo)
                metadataUnsealer.unseal(it)
            },
            last4 = last4(),
            cardNumber = unsealer.unseal(pan()),
            securityCode = unsealer.unseal(csc()),
            billingAddress = unsealer.unseal(billingAddress()),
            expiry = unsealer.unseal(expiry()),
            currency = currency(),
            activeTo = activeToEpochMs().toDate(),
            cancelledAt = cancelledAtEpochMs().toDate(),
            createdAt = createdAtEpochMs().toDate(),
            updatedAt = updatedAtEpochMs().toDate(),
            lastTransaction = lastTransaction()?.toTransaction(deviceKeyManager)
        )
    }

    private fun CardState.toCardState(): CardStateEntity {
        return when (this) {
            CardState.CLOSED -> CardStateEntity.CLOSED
            CardState.FAILED -> CardStateEntity.FAILED
            CardState.ISSUED -> CardStateEntity.ISSUED
            CardState.SUSPENDED -> CardStateEntity.SUSPENDED
        }
    }

    private fun List<CardProvisionMutation.Owner>.toOwners(): List<Owner> {
        return this.map {
            it.toOwner()
        }
    }

    private fun CardProvisionMutation.Owner.toOwner(): Owner {
        return Owner(id = id(), issuer = issuer())
    }

    private fun List<GetProvisionalCardQuery.Owner>.toProvOwners(): List<Owner> {
        return this.map {
            it.toOwner()
        }
    }

    private fun GetProvisionalCardQuery.Owner.toOwner(): Owner {
        return Owner(id = id(), issuer = issuer())
    }

    private fun List<GetCardQuery.Owner>.toGetCardOwners(): List<Owner> {
        return this.map {
            it.toOwner()
        }
    }

    private fun GetCardQuery.Owner.toOwner(): Owner {
        return Owner(id = id(), issuer = issuer())
    }

    private fun List<ListCardsQuery.Owner>.toListCardsOwners(): List<Owner> {
        return this.map {
            it.toOwner()
        }
    }

    private fun ListCardsQuery.Owner.toOwner(): Owner {
        return Owner(id = id(), issuer = issuer())
    }

    private fun List<UpdateCardMutation.Owner>.toUpdateCardOwners(): List<Owner> {
        return this.map {
            it.toOwner()
        }
    }

    private fun UpdateCardMutation.Owner.toOwner(): Owner {
        return Owner(id = id(), issuer = issuer())
    }

    private fun List<CancelCardMutation.Owner>.toCancelCardOwners(): List<Owner> {
        return this.map {
            it.toOwner()
        }
    }

    private fun CancelCardMutation.Owner.toOwner(): Owner {
        return Owner(id = id(), issuer = issuer())
    }

    private fun CardProvisionMutation.LastTransaction.toTransaction(deviceKeyManager: DeviceKeyManager): Transaction {
        val keyInfo = KeyInfo(keyId(), KeyType.PRIVATE_KEY, algorithm())
        val unsealer = Unsealer(deviceKeyManager, keyInfo)
        return Transaction(
            id = id(),
            owner = owner(),
            version = version(),
            cardId = cardId(),
            sequenceId = sequenceId(),
            type = type().toTransactionType(),
            billedAmount = unsealer.unsealAmount(billedAmount().currency(), billedAmount().amount()),
            transactedAmount = unsealer.unsealAmount(transactedAmount().currency(), transactedAmount().amount()),
            description = unsealer.unseal(description()),
            declineReason = declineReason()?.let { unsealer.unseal(it).toDeclineReason() },
            transactedAt = unsealer.unseal(transactedAtEpochMs()).toDouble().toDate(),
            createdAt = createdAtEpochMs().toDate(),
            updatedAt = updatedAtEpochMs().toDate()
        )
    }

    private fun GetCardQuery.LastTransaction.toTransaction(deviceKeyManager: DeviceKeyManager): Transaction {
        val keyInfo = KeyInfo(keyId(), KeyType.PRIVATE_KEY, algorithm())
        val unsealer = Unsealer(deviceKeyManager, keyInfo)
        return Transaction(
            id = id(),
            owner = owner(),
            version = version(),
            cardId = cardId(),
            sequenceId = sequenceId(),
            type = type().toTransactionType(),
            billedAmount = unsealer.unsealAmount(billedAmount().currency(), billedAmount().amount()),
            transactedAmount = unsealer.unsealAmount(transactedAmount().currency(), transactedAmount().amount()),
            description = unsealer.unseal(description()),
            declineReason = declineReason()?.let { unsealer.unseal(it).toDeclineReason() },
            transactedAt = unsealer.unseal(transactedAtEpochMs()).toDouble().toDate(),
            createdAt = createdAtEpochMs().toDate(),
            updatedAt = updatedAtEpochMs().toDate()
        )
    }

    private fun ListCardsQuery.LastTransaction.toTransaction(deviceKeyManager: DeviceKeyManager): Transaction {
        val keyInfo = KeyInfo(keyId(), KeyType.PRIVATE_KEY, algorithm())
        val unsealer = Unsealer(deviceKeyManager, keyInfo)
        return Transaction(
            id = id(),
            owner = owner(),
            version = version(),
            cardId = cardId(),
            sequenceId = sequenceId(),
            type = type().toTransactionType(),
            billedAmount = unsealer.unsealAmount(billedAmount().currency(), billedAmount().amount()),
            transactedAmount = unsealer.unsealAmount(transactedAmount().currency(), transactedAmount().amount()),
            description = unsealer.unseal(description()),
            declineReason = declineReason()?.let { unsealer.unseal(it).toDeclineReason() },
            transactedAt = unsealer.unseal(transactedAtEpochMs()).toDouble().toDate(),
            createdAt = createdAtEpochMs().toDate(),
            updatedAt = updatedAtEpochMs().toDate()
        )
    }

    private fun UpdateCardMutation.LastTransaction.toTransaction(deviceKeyManager: DeviceKeyManager): Transaction {
        val keyInfo = KeyInfo(keyId(), KeyType.PRIVATE_KEY, algorithm())
        val unsealer = Unsealer(deviceKeyManager, keyInfo)
        return Transaction(
            id = id(),
            owner = owner(),
            version = version(),
            cardId = cardId(),
            sequenceId = sequenceId(),
            type = type().toTransactionType(),
            billedAmount = unsealer.unsealAmount(billedAmount().currency(), billedAmount().amount()),
            transactedAmount = unsealer.unsealAmount(transactedAmount().currency(), transactedAmount().amount()),
            description = unsealer.unseal(description()),
            declineReason = declineReason()?.let { unsealer.unseal(it).toDeclineReason() },
            transactedAt = unsealer.unseal(transactedAtEpochMs()).toDouble().toDate(),
            createdAt = createdAtEpochMs().toDate(),
            updatedAt = updatedAtEpochMs().toDate()
        )
    }

    private fun CancelCardMutation.LastTransaction.toTransaction(deviceKeyManager: DeviceKeyManager): Transaction {
        val keyInfo = KeyInfo(keyId(), KeyType.PRIVATE_KEY, algorithm())
        val unsealer = Unsealer(deviceKeyManager, keyInfo)
        return Transaction(
            id = id(),
            owner = owner(),
            version = version(),
            cardId = cardId(),
            sequenceId = sequenceId(),
            type = type().toTransactionType(),
            billedAmount = unsealer.unsealAmount(billedAmount().currency(), billedAmount().amount()),
            transactedAmount = unsealer.unsealAmount(transactedAmount().currency(), transactedAmount().amount()),
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
