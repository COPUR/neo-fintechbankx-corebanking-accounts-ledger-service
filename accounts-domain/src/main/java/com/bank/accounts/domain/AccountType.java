package com.bank.accounts.domain;

/**
 * Product type of a customer account, from AmanahFi's AccountType. The CBDC
 * and stablecoin wallets are not carried over: they belong to the Islamic
 * finance and digital-asset work, not to the ledger.
 */
public enum AccountType {
    CURRENT,
    SAVINGS,
    INVESTMENT,
    BUSINESS,
    /** The bank's own ledger accounts (clearing, suspense, cash); never held by a customer. */
    INTERNAL
}
