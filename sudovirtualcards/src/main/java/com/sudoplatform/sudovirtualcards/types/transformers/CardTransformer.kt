/*
 * Copyright © 2020 Anonyome Labs, Inc. All rights reserved.
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
import com.sudoplatform.sudovirtualcards.graphql.type.CardFilterInput
import com.sudoplatform.sudovirtualcards.graphql.type.CardState
import com.sudoplatform.sudovirtualcards.graphql.type.IDFilterInput
import com.sudoplatform.sudovirtualcards.graphql.type.ProvisioningState
import com.sudoplatform.sudovirtualcards.keys.DeviceKeyManager
import com.sudoplatform.sudovirtualcards.types.BillingAddress
import com.sudoplatform.sudovirtualcards.types.Card
import com.sudoplatform.sudovirtualcards.types.Owner
import com.sudoplatform.sudovirtualcards.types.ProvisionalCard
import com.sudoplatform.sudovirtualcards.types.inputs.filters.CardFilter

/**
 * Transformer responsible for transforming the [Card] and [ProvisionalCard] GraphQL data
 * types to the entity type that is exposed to users.
 *
 * @since 2020-05-26
 */
internal object CardTransformer {

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
     * @param result The GraphQL mutation results.
     * @return The [ProvisionalCard] entity type.
     */
    fun toEntityFromCardProvisionMutationResult(
        deviceKeyManager: DeviceKeyManager,
        result: CardProvisionMutation.CardProvision
    ): ProvisionalCard {
        return ProvisionalCard(
            id = result.id(),
            clientRefId = result.clientRefId(),
            owner = result.owner(),
            version = result.version(),
            state = result.provisioningState().toProvisionalCardState(),
            card = result.card()?.firstOrNull()?.toCard(deviceKeyManager),
            createdAt = result.createdAtEpochMs().toDate(),
            updatedAt = result.updatedAtEpochMs().toDate()
        )
    }

    /**
     * Transform the results of the [GetProvisionalCardQuery].
     *
     * @param cardResult The GraphQL query results.
     * @return The [Card] entity type.
     */
    fun toCardFromGetProvisionalCardQueryResult(deviceKeyManager: DeviceKeyManager, cardResult: GetProvisionalCardQuery.Card): Card {

        val unsealer = Unsealer(deviceKeyManager, cardResult.keyId(), cardResult.algorithm())

        return Card(
            id = cardResult.id(),
            owners = cardResult.owners().toProvOwners(),
            owner = cardResult.owner(),
            version = cardResult.version(),
            fundingSourceId = cardResult.fundingSourceId(),
            state = cardResult.state().toState(),
            cardHolder = unsealer.unseal(cardResult.cardHolder()),
            alias = unsealer.unseal(cardResult.alias()),
            last4 = cardResult.last4(),
            cardNumber = unsealer.unseal(cardResult.pan()),
            securityCode = unsealer.unseal(cardResult.csc()),
            billingAddress = unsealer.unseal(cardResult.billingAddress()),
            expirationMonth = unsealer.unseal(cardResult.expiry().mm()).toInt(),
            expirationYear = unsealer.unseal(cardResult.expiry().yyyy()).toInt(),
            currency = cardResult.currency(),
            activeTo = cardResult.activeToEpochMs().toDate(),
            cancelledAt = cardResult.cancelledAtEpochMs()?.toDate(),
            createdAt = cardResult.createdAtEpochMs().toDate(),
            updatedAt = cardResult.updatedAtEpochMs().toDate()
        )
    }

    /**
     * Transform the results of the [GetProvisionalCardQuery].
     *
     * @param result The GraphQL query results.
     * @return The [ProvisionalCard] entity type.
     */
    fun toProvisionalCardFromGetProvisionalCardQueryResult(
        deviceKeyManager: DeviceKeyManager,
        result: GetProvisionalCardQuery.GetProvisionalCard
    ): ProvisionalCard {
        return ProvisionalCard(
            id = result.id(),
            clientRefId = result.clientRefId(),
            owner = result.owner(),
            version = result.version(),
            state = result.provisioningState().toProvisionalCardState(),
            card = result.card()?.firstOrNull()?.let { toCardFromGetProvisionalCardQueryResult(deviceKeyManager, it) },
            createdAt = result.createdAtEpochMs().toDate(),
            updatedAt = result.updatedAtEpochMs().toDate()
        )
    }

    /**
     * Transform the results of the [GetCardQuery].
     *
     * @param result The GraphQL query results.
     * @return The [Card] entity type.
     */
    fun toEntityFromGetCardQueryResult(deviceKeyManager: DeviceKeyManager, result: GetCardQuery.GetCard): Card {

        val unsealer = Unsealer(deviceKeyManager, result.keyId(), result.algorithm())

        return Card(
            id = result.id(),
            owners = result.owners().toGetCardOwners(),
            owner = result.owner(),
            version = result.version(),
            fundingSourceId = result.fundingSourceId(),
            state = result.state().toState(),
            cardHolder = unsealer.unseal(result.cardHolder()),
            alias = unsealer.unseal(result.alias()),
            last4 = result.last4(),
            cardNumber = unsealer.unseal(result.pan()),
            securityCode = unsealer.unseal(result.csc()),
            billingAddress = unsealer.unseal(result.billingAddress()),
            expirationMonth = unsealer.unseal(result.expiry().mm()).toInt(),
            expirationYear = unsealer.unseal(result.expiry().yyyy()).toInt(),
            currency = result.currency(),
            activeTo = result.activeToEpochMs().toDate(),
            cancelledAt = result.cancelledAtEpochMs()?.toDate(),
            createdAt = result.createdAtEpochMs().toDate(),
            updatedAt = result.updatedAtEpochMs().toDate()
        )
    }

    /**
     * Transform the results of the [ListCardsQuery].
     *
     * @param cardResult The GraphQL query results.
     * @return The list of [Card]s entity type.
     */
    fun toEntityFromListCardsQueryResult(deviceKeyManager: DeviceKeyManager, cardResult: List<ListCardsQuery.Item>): List<Card> {

        return cardResult.map { card ->
            val unsealer = Unsealer(deviceKeyManager, card.keyId(), card.algorithm())
            Card(
                id = card.id(),
                owners = card.owners().toListCardsOwners(),
                owner = card.owner(),
                version = card.version(),
                fundingSourceId = card.fundingSourceId(),
                state = card.state().toState(),
                cardHolder = unsealer.unseal(card.cardHolder()),
                alias = unsealer.unseal(card.alias()),
                last4 = card.last4(),
                cardNumber = unsealer.unseal(card.pan()),
                securityCode = unsealer.unseal(card.csc()),
                billingAddress = unsealer.unseal(card.billingAddress()),
                expirationMonth = unsealer.unseal(card.expiry().mm()).toInt(),
                expirationYear = unsealer.unseal(card.expiry().yyyy()).toInt(),
                currency = card.currency(),
                activeTo = card.activeToEpochMs().toDate(),
                cancelledAt = card.cancelledAtEpochMs()?.toDate(),
                createdAt = card.createdAtEpochMs().toDate(),
                updatedAt = card.updatedAtEpochMs().toDate()
            )
        }.toList()
    }

    /**
     * Transform the results of the [UpdateCardMutation].
     *
     * @param result The GraphQL mutation results.
     * @return The [Card] entity type.
     */
    fun toEntityFromUpdateCardMutationResult(deviceKeyManager: DeviceKeyManager, result: UpdateCardMutation.UpdateCard): Card {

        val unsealer = Unsealer(deviceKeyManager, result.keyId(), result.algorithm())

        return Card(
            id = result.id(),
            owners = result.owners().toUpdateCardOwners(),
            owner = result.owner(),
            version = result.version(),
            fundingSourceId = result.fundingSourceId(),
            state = result.state().toState(),
            cardHolder = unsealer.unseal(result.cardHolder()),
            alias = unsealer.unseal(result.alias()),
            last4 = result.last4(),
            cardNumber = unsealer.unseal(result.pan()),
            securityCode = unsealer.unseal(result.csc()),
            billingAddress = unsealer.unseal(result.billingAddress()),
            expirationMonth = unsealer.unseal(result.expiry().mm()).toInt(),
            expirationYear = unsealer.unseal(result.expiry().yyyy()).toInt(),
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
     * @param result The GraphQL mutation results.
     * @return The [Card] entity type.
     */
    fun toEntityFromCancelCardMutationResult(deviceKeyManager: DeviceKeyManager, result: CancelCardMutation.CancelCard): Card {

        val unsealer = Unsealer(deviceKeyManager, result.keyId(), result.algorithm())

        return Card(
            id = result.id(),
            owners = result.owners().toCancelCardOwners(),
            owner = result.owner(),
            version = result.version(),
            fundingSourceId = result.fundingSourceId(),
            state = result.state().toState(),
            cardHolder = unsealer.unseal(result.cardHolder()),
            alias = unsealer.unseal(result.alias()),
            last4 = result.last4(),
            cardNumber = unsealer.unseal(result.pan()),
            securityCode = unsealer.unseal(result.csc()),
            billingAddress = unsealer.unseal(result.billingAddress()),
            expirationMonth = unsealer.unseal(result.expiry().mm()).toInt(),
            expirationYear = unsealer.unseal(result.expiry().yyyy()).toInt(),
            currency = result.currency(),
            activeTo = result.activeToEpochMs().toDate(),
            cancelledAt = result.cancelledAtEpochMs()?.toDate(),
            createdAt = result.createdAtEpochMs().toDate(),
            updatedAt = result.updatedAtEpochMs().toDate()
        )
    }

    private fun CardState.toState(): Card.State {
        return when (this) {
            CardState.ISSUED -> Card.State.ISSUED
            CardState.SUSPENDED -> Card.State.SUSPENDED
            CardState.CLOSED -> Card.State.CLOSED
            CardState.FAILED -> Card.State.FAILED
        }
    }

    private fun ProvisioningState.toProvisionalCardState(): ProvisionalCard.State {
        return when (this) {
            ProvisioningState.PROVISIONING -> ProvisionalCard.State.PROVISIONING
            ProvisioningState.FAILED -> ProvisionalCard.State.FAILED
            ProvisioningState.COMPLETED -> ProvisionalCard.State.COMPLETED
        }
    }

    private fun CardProvisionMutation.Card.toCard(deviceKeyManager: DeviceKeyManager): Card {

        val unsealer = Unsealer(deviceKeyManager, keyId(), algorithm())

        return Card(
            id = id(),
            owners = owners().toOwners(),
            owner = owner(),
            version = version(),
            fundingSourceId = fundingSourceId(),
            state = state().toCardState(),
            cardHolder = unsealer.unseal(cardHolder()),
            alias = unsealer.unseal(alias()),
            last4 = last4(),
            cardNumber = unsealer.unseal(pan()),
            securityCode = unsealer.unseal(csc()),
            billingAddress = unsealer.unseal(billingAddress()),
            expirationMonth = unsealer.unseal(expiry().mm()).toInt(),
            expirationYear = unsealer.unseal(expiry().yyyy()).toInt(),
            currency = currency(),
            activeTo = activeToEpochMs().toDate(),
            cancelledAt = cancelledAtEpochMs().toDate(),
            createdAt = createdAtEpochMs().toDate(),
            updatedAt = updatedAtEpochMs().toDate()
        )
    }

    private fun CardState.toCardState(): Card.State {
        return when (this) {
            CardState.CLOSED -> Card.State.CLOSED
            CardState.FAILED -> Card.State.FAILED
            CardState.ISSUED -> Card.State.ISSUED
            CardState.SUSPENDED -> Card.State.SUSPENDED
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

    /**
     * Convert from the API definition of the card filter to the GraphQL definition.
     *
     * @param filter The API definition of the card filter, can be null.
     * @return The GraphQL definition of a card filter, can be null.
     */
    fun toGraphQLFilter(filter: CardFilter?): CardFilterInput? {
        if (filter == null || filter.propertyFilters.isEmpty()) {
            return null
        }
        val builder = CardFilterInput.builder()
        for (field in filter.propertyFilters) {
            when (field.property) {
                CardFilter.Property.STATE -> builder.state(field.toFilterInput())
            }
        }
        return builder.build()
    }

    private fun CardFilter.PropertyFilter.toFilterInput(): IDFilterInput {
        val builder = IDFilterInput.builder()
        when (comparison) {
            CardFilter.ComparisonOperator.EQUAL -> builder.eq(value.first)
            CardFilter.ComparisonOperator.NOT_EQUAL -> builder.ne(value.first)
            CardFilter.ComparisonOperator.LESS_THAN_EQUAL -> builder.le(value.first)
            CardFilter.ComparisonOperator.LESS_THAN -> builder.lt(value.first)
            CardFilter.ComparisonOperator.GREATER_THAN_EQUAL -> builder.ge(value.first)
            CardFilter.ComparisonOperator.GREATER_THAN -> builder.gt(value.first)
            CardFilter.ComparisonOperator.CONTAINS -> builder.contains(value.first)
            CardFilter.ComparisonOperator.NOT_CONTAINS -> builder.notContains(value.first)
            CardFilter.ComparisonOperator.BEGINS_WITH -> builder.beginsWith(value.first)
            CardFilter.ComparisonOperator.BETWEEN -> builder.between(listOf(value.first, value.second))
        }
        return builder.build()
    }
}
