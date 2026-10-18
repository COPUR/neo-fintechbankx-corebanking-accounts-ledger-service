package com.bank.accounts.infrastructure.web.dto;

import com.bank.accounts.domain.AccountType;
import com.bank.accounts.domain.port.in.OpenAccountCommand;
import com.bank.shared.kernel.domain.CustomerId;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.Currency;

/** Body of POST /api/v1/accounts. customerId is required for every type but INTERNAL. */
public record OpenAccountRequest(
    @Size(max = 64) String customerId,
    @NotNull AccountType accountType,
    @NotNull @Pattern(regexp = "[A-Z]{3}", message = "currency must be an ISO 4217 code") String currency,
    Boolean islamicCompliant) {

    public OpenAccountCommand toCommand(String idempotencyKey) {
        CustomerId holder = customerId == null || customerId.isBlank() ? null : CustomerId.of(customerId);
        return new OpenAccountCommand(idempotencyKey, holder, accountType, Currency.getInstance(currency),
            Boolean.TRUE.equals(islamicCompliant));
    }
}
