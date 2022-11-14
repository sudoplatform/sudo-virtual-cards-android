package com.sudoplatform.sudovirtualcards.types

/**
 * Representation of an enumeration depicting the type of [CreditCardFundingSource], in the Sudo
 * Platform Virtual Cards SDK.
 */
enum class CardType {
    /** Credit card funding source. */
    CREDIT,
    /** Debit card funding source. */
    DEBIT,
    /** Prepaid card funding source. */
    PREPAID,
    /** Other card funding source. */
    OTHER,
    /** Unknown card type. Please check you have the correct (latest) version of this SDK. */
    UNKNOWN
}
