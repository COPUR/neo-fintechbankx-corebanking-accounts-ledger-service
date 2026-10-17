package com.bank.accounts.domain.exception;

/** A journal entry whose debits and credits do not balance. */
public class UnbalancedEntryException extends RuntimeException {

    public UnbalancedEntryException(String message) {
        super(message);
    }
}
