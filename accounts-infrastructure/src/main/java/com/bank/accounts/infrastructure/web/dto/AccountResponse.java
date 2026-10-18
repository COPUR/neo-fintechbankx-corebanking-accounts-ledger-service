package com.bank.accounts.infrastructure.web.dto;

import com.bank.accounts.domain.Account;
import com.bank.shared.kernel.domain.CustomerId;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * The account view of GET /api/v1/accounts/{accountId}. Its first fields are
 * the contract the payments and mandates adapters already read
 * (CoreBankingAccountHttpAdapter, AccountsServiceHttpAdapter): accountId,
 * customerId (the holder), status, currency, availableBalance, debitAllowed,
 * creditAllowed. Amounts are JSON numbers there, so they are here too.
 */
public record AccountResponse(String accountId, String customerId, String category, String accountType,
                              String status, String currency, BigDecimal availableBalance,
                              BigDecimal bookedBalance, boolean debitAllowed, boolean creditAllowed,
                              boolean islamicCompliant, Instant openedAt, Instant updatedAt) {

    public static AccountResponse from(Account account) {
        return new AccountResponse(account.getId().value(), account.holder().map(CustomerId::getValue).orElse(null),
            account.category().name(), account.type().name(), account.status().name(),
            account.currency().getCurrencyCode(), account.availableBalance().getAmount(),
            account.bookedBalance().getAmount(), account.debitAllowed(), account.creditAllowed(),
            account.islamicCompliant(), account.openedAt(), account.updatedAt());
    }
}
