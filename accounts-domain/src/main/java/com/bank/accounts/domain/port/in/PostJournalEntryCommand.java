package com.bank.accounts.domain.port.in;

import com.bank.accounts.domain.PostingLine;

import java.util.List;
import java.util.Objects;

/** Books one double entry under the caller's business reference. */
public record PostJournalEntryCommand(String reference, List<PostingLine> lines, String narrative) {

    public PostJournalEntryCommand {
        Objects.requireNonNull(lines, "lines");
        lines = List.copyOf(lines);
    }
}
