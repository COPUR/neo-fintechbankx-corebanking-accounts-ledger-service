package com.bank.accounts.domain.port.in;

import com.bank.accounts.domain.JournalEntry;

/** The booked entry, and whether it was booked by an earlier call with the same reference. */
public record PostingResult(JournalEntry entry, boolean replay) {
}
