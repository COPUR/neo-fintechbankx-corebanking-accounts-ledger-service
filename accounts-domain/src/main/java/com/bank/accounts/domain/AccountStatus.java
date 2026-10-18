package com.bank.accounts.domain;

/** Only an ACTIVE account takes debits and credits. */
public enum AccountStatus {
    ACTIVE,
    FROZEN,
    CLOSED
}
