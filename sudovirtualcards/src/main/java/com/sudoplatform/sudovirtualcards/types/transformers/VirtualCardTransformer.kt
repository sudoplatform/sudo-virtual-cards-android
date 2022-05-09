/*
 * Copyright © 2022 Anonyome Labs, Inc. All rights reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package com.sudoplatform.sudovirtualcards.types.transformers

import com.sudoplatform.sudovirtualcards.graphql.CancelCardMutation
import com.sudoplatform.sudovirtualcards.graphql.CardProvisionMutation
import com.sudoplatform.sudovirtualcards.graphql.GetCardQuery
import com.sudoplatform.sudovirtualcards.graphql.GetProvisionalCardQuery
import com.sudoplatform.sudovirtualcards.graphql.ListCardsQuery
import com.sudoplatform.sudovirtualcards.graphql.UpdateCardMutation
import com.sudoplatform.sudovirtualcards.graphql.type.AddressInput
import com.sudoplatform.sudovirtualcards.graphql.type.CardState
import com.sudoplatform.sudovirtualcards.graphql.type.ProvisioningState
import com.sudoplatform.sudovirtualcards.keys.DeviceKeyManager
import com.sudoplatform.sudovirtualcards.types.BillingAddress
import com.sudoplatform.sudovirtualcards.types.DeclineReason
import com.sudoplatform.sudovirtualcards.types.Transaction.TransactionType
import com.sudoplatform.sudovirtualcards.graphql.type.TransactionType as TransactionTypeEntity
import com.sudoplatform.sudovirtualcards.types.VirtualCard
import com.sudoplatform.sudovirtualcards.types.Owner
import com.sudoplatform.sudovirtualcards.types.ProvisionalVirtualCard
import com.sudoplatform.sudovirtualcards.types.Transaction

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
        val unsealer = Unsealer(deviceKeyManager, result.keyId(), result.algorithm())
        return VirtualCard(
            id = result.id(),
            owners = result.owners().toProvOwners(),
            owner = result.owner(),
            version = result.version(),
            fundingSourceId = result.fundingSourceId(),
            state = result.state().toState(),
            cardHolder = unsealer.unseal(result.cardHolder()),
            alias = result.alias()?.let { unsealer.unseal(it) },
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
        val unsealer = Unsealer(deviceKeyManager, result.keyId(), result.algorithm())
        return VirtualCard(
            id = result.id(),
            owners = result.owners().toGetCardOwners(),
            owner = result.owner(),
            version = result.version(),
            fundingSourceId = result.fundingSourceId(),
            state = result.state().toState(),
            cardHolder = unsealer.unseal(result.cardHolder()),
            alias = result.alias()?.let { unsealer.unseal(it) },
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
     * Transform the results of the [ListCardsQuery].
     *
     * @param deviceKeyManager [DeviceKeyManager] Used to retrieve keys to unseal data.
     * @param result [List<ListCardsQuery.Item>] The GraphQL query results.
     * @return The list of [VirtualCard]s entity type.
     */
    fun toEntityFromListCardsQueryResult(deviceKeyManager: DeviceKeyManager, result: List<ListCardsQuery.Item>): List<VirtualCard> {
        return result.map { card ->
            val unsealer = Unsealer(deviceKeyManager, card.keyId(), card.algorithm())
            VirtualCard(
                id = card.id(),
                owners = card.owners().toListCardsOwners(),
                owner = card.owner(),
                version = card.version(),
                fundingSourceId = card.fundingSourceId(),
                state = card.state().toState(),
                cardHolder = unsealer.unseal(card.cardHolder()),
                alias = card.alias()?.let { unsealer.unseal(it) },
                last4 = card.last4(),
                cardNumber = unsealer.unseal(card.pan()),
                securityCode = unsealer.unseal(card.csc()),
                billingAddress = unsealer.unseal(card.billingAddress()),
                expiry = unsealer.unseal(card.expiry()),
                currency = card.currency(),
                activeTo = card.activeToEpochMs().toDate(),
                cancelledAt = card.cancelledAtEpochMs()?.toDate(),
                createdAt = card.createdAtEpochMs().toDate(),
                updatedAt = card.updatedAtEpochMs().toDate(),
                lastTransaction = card.lastTransaction()?.toTransaction(deviceKeyManager)
            )
        }.toList()
    }

    /**
     * Transform the results of the [UpdateCardMutation].
     *
     * @param deviceKeyManager [DeviceKeyManager] Used to retrieve keys to unseal data.
     * @param result [UpdateCardMutation.UpdateCard] The GraphQL mutation results.
     * @return The [VirtualCard] entity type.
     */
    fun toEntityFromUpdateCardMutationResult(deviceKeyManager: DeviceKeyManager, result: UpdateCardMutation.UpdateCard): VirtualCard {
        val unsealer = Unsealer(deviceKeyManager, result.keyId(), result.algorithm())
        return VirtualCard(
            id = result.id(),
            owners = result.owners().toUpdateCardOwners(),
            owner = result.owner(),
            version = result.version(),
            fundingSourceId = result.fundingSourceId(),
            state = result.state().toState(),
            cardHolder = unsealer.unseal(result.cardHolder()),
            alias = result.alias()?.let { unsealer.unseal(it) },
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
     * Transform the results of the [CancelCardMutation].
     *
     * @param deviceKeyManager [DeviceKeyManager] Used to retrieve keys to unseal data.
     * @param result [CancelCardMutation.CancelCard] The GraphQL mutation results.
     * @return The [VirtualCard] entity type.
     */
    fun toEntityFromCancelCardMutationResult(deviceKeyManager: DeviceKeyManager, result: CancelCardMutation.CancelCard): VirtualCard {
        val unsealer = Unsealer(deviceKeyManager, result.keyId(), result.algorithm())
        return VirtualCard(
            id = result.id(),
            owners = result.owners().toCancelCardOwners(),
            owner = result.owner(),
            version = result.version(),
            fundingSourceId = result.fundingSourceId(),
            state = result.state().toState(),
            cardHolder = unsealer.unseal(result.cardHolder()),
            alias = result.alias()?.let { unsealer.unseal(it) },
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

    private fun CardState.toState(): VirtualCard.State {
        return when (this) {
            CardState.ISSUED -> VirtualCard.State.ISSUED
            CardState.SUSPENDED -> VirtualCard.State.SUSPENDED
            CardState.CLOSED -> VirtualCard.State.CLOSED
            CardState.FAILED -> VirtualCard.State.FAILED
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
        val unsealer = Unsealer(deviceKeyManager, keyId(), algorithm())
        return VirtualCard(
            id = id(),
            owners = owners().toOwners(),
            owner = owner(),
            version = version(),
            fundingSourceId = fundingSourceId(),
            state = state().toCardState(),
            cardHolder = unsealer.unseal(cardHolder()),
            alias = alias()?.let { unsealer.unseal(it) },
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

    private fun CardState.toCardState(): VirtualCard.State {
        return when (this) {
            CardState.CLOSED -> VirtualCard.State.CLOSED
            CardState.FAILED -> VirtualCard.State.FAILED
            CardState.ISSUED -> VirtualCard.State.ISSUED
            CardState.SUSPENDED -> VirtualCard.State.SUSPENDED
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
        val unsealer = Unsealer(deviceKeyManager, keyId(), algorithm())
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
        val unsealer = Unsealer(deviceKeyManager, keyId(), algorithm())
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
        val unsealer = Unsealer(deviceKeyManager, keyId(), algorithm())
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
        val unsealer = Unsealer(deviceKeyManager, keyId(), algorithm())
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
        val unsealer = Unsealer(deviceKeyManager, keyId(), algorithm())
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

    private fun TransactionTypeEntity.toTransactionType(): TransactionType {
        for (txnType in TransactionType.values()) {
            if (txnType.name == this.name) {
                return txnType
            }
        }
        return TransactionType.UNKNOWN
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
