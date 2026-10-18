package com.bank.accounts.domain.exception;

/** A reference or idempotency key was reused for a different request. */
public class IdempotencyConflictException extends RuntimeException {

    public IdempotencyConflictException(String message) {
        super(message);
    }
}
