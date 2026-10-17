package com.bank.accounts.domain;

/**
 * CUSTOMER accounts have a holder and never go below zero. INTERNAL accounts
 * are the bank's side of a double entry (clearing, settlement, cash) and may.
 */
public enum AccountCategory {
    CUSTOMER,
    INTERNAL
}
