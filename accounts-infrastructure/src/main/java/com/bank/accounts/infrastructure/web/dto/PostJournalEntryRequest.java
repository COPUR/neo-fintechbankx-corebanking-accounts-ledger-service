package com.bank.accounts.infrastructure.web.dto;

import com.bank.accounts.domain.AccountId;
import com.bank.accounts.domain.PostingLine;
import com.bank.accounts.domain.PostingSide;
import com.bank.accounts.domain.port.in.PostJournalEntryCommand;
import com.bank.shared.kernel.domain.Money;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.List;

/**
 * Body of POST /api/v1/journal-entries. The reference is the caller's
 * business key (payment id, mandate collection id); a retry sends the same
 * reference and lines and gets the first booking back.
 */
public record PostJournalEntryRequest(
    @NotBlank @Size(max = 128) String reference,
    @NotNull @Pattern(regexp = "[A-Z]{3}", message = "currency must be an ISO 4217 code") String currency,
    @NotEmpty @Size(max = 50) List<@Valid Line> lines,
    @Size(max = 256) String narrative) {

    public record Line(@NotBlank @Size(max = 64) String accountId, @NotNull PostingSide side,
                       @NotNull BigDecimal amount) {
    }

    public PostJournalEntryCommand toCommand() {
        Currency entryCurrency = Currency.getInstance(currency);
        List<PostingLine> postingLines = lines.stream()
            .map(line -> new PostingLine(AccountId.of(line.accountId()), line.side(),
                exact(line.amount(), entryCurrency)))
            .toList();
        return new PostJournalEntryCommand(reference, postingLines, narrative);
    }

    /** Money rounds to the currency's minor unit; an amount finer than that is refused, not rounded. */
    private static Money exact(BigDecimal amount, Currency currency) {
        if (amount.stripTrailingZeros().scale() > currency.getDefaultFractionDigits()) {
            throw new IllegalArgumentException("Amount " + amount.toPlainString() + " has more decimals than "
                + currency.getCurrencyCode() + " allows");
        }
        return Money.of(amount, currency);
    }
}
