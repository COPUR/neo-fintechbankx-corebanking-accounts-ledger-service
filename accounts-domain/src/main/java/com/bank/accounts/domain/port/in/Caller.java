package com.bank.accounts.domain.port.in;

import com.bank.shared.kernel.domain.CustomerId;

import java.util.Objects;

/**
 * Who is calling a use case. A customer sees only accounts they hold; staff
 * (banker, admin) and services see every account.
 */
public record Caller(String principal, boolean staff) {

    public Caller {
        if (principal == null || principal.isBlank()) {
            throw new IllegalArgumentException("Caller principal is required");
        }
    }

    public static Caller customer(String customerId) {
        return new Caller(customerId, false);
    }

    public static Caller staff(String principal) {
        return new Caller(principal, true);
    }

    public boolean mayActFor(CustomerId customerId) {
        return staff || (customerId != null && Objects.equals(principal, customerId.getValue()));
    }
}
