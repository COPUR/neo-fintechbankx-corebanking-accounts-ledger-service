package com.bank.accounts.infrastructure.web.dto;

import com.bank.accounts.domain.JournalEntry;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** A booked entry; replay is true when an earlier call with the same reference booked it. */
public record JournalEntryResponse(String journalEntryId, String reference, String currency, List<Line> lines,
                                   String narrative, Instant bookedAt, boolean replay) {

    public record Line(String accountId, String side, BigDecimal amount) {
    }

    public static JournalEntryResponse from(JournalEntry entry, boolean replay) {
        return new JournalEntryResponse(entry.getId().value(), entry.reference(),
            entry.currency().getCurrencyCode(),
            entry.lines().stream().map(line -> new Line(line.accountId().value(), line.side().name(),
                line.amount().getAmount())).toList(),
            entry.narrative(), entry.bookedAt(), replay);
    }
}
