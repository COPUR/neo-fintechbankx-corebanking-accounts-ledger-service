package com.bank.accounts.domain;

import com.bank.accounts.domain.exception.UnbalancedEntryException;
import com.bank.shared.kernel.domain.Entity;
import com.bank.shared.kernel.domain.Money;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * A booked double entry: at least one debit and one credit line in one
 * currency, debits equal to credits. Entries are immutable; a correction is a
 * new entry. The reference is the caller's business key (a payment or
 * mandate collection id) and is unique: posting the same reference again
 * with the same lines returns the first entry instead of booking twice.
 */
public final class JournalEntry implements Entity<JournalEntryId> {

    public static final int MAX_REFERENCE_LENGTH = 128;
    public static final int MAX_NARRATIVE_LENGTH = 256;

    private final JournalEntryId id;
    private final String reference;
    private final List<PostingLine> lines;
    private final String narrative;
    private final Instant bookedAt;

    private JournalEntry(JournalEntryId id, String reference, List<PostingLine> lines, String narrative,
                         Instant bookedAt) {
        this.id = Objects.requireNonNull(id, "journalEntryId");
        this.reference = requireReference(reference);
        this.lines = List.copyOf(Objects.requireNonNull(lines, "lines"));
        this.narrative = narrative == null || narrative.isBlank() ? null : narrative.trim();
        if (this.narrative != null && this.narrative.length() > MAX_NARRATIVE_LENGTH) {
            throw new IllegalArgumentException("narrative is longer than " + MAX_NARRATIVE_LENGTH + " characters");
        }
        this.bookedAt = Objects.requireNonNull(bookedAt, "bookedAt");
        validate(this.lines);
    }

    /** Validates and books a new entry. */
    public static JournalEntry book(JournalEntryId id, String reference, List<PostingLine> lines, String narrative,
                                    Instant now) {
        return new JournalEntry(id, reference, lines, narrative, now);
    }

    /** Restores a stored entry; the same rules apply. */
    public static JournalEntry rehydrate(JournalEntryId id, String reference, List<PostingLine> lines,
                                         String narrative, Instant bookedAt) {
        return new JournalEntry(id, reference, lines, narrative, bookedAt);
    }

    private static String requireReference(String reference) {
        if (reference == null || reference.isBlank()) {
            throw new IllegalArgumentException("reference is required");
        }
        String trimmed = reference.trim();
        if (trimmed.length() > MAX_REFERENCE_LENGTH) {
            throw new IllegalArgumentException("reference is longer than " + MAX_REFERENCE_LENGTH + " characters");
        }
        return trimmed;
    }

    private static void validate(List<PostingLine> lines) {
        if (lines.stream().noneMatch(line -> line.side() == PostingSide.DEBIT)
            || lines.stream().noneMatch(line -> line.side() == PostingSide.CREDIT)) {
            throw new UnbalancedEntryException("An entry needs at least one debit and one credit line");
        }
        Currency currency = lines.get(0).amount().getCurrency();
        if (lines.stream().anyMatch(line -> !line.amount().getCurrency().equals(currency))) {
            throw new IllegalArgumentException("All lines of an entry must share one currency");
        }
        Set<AccountId> debited = new HashSet<>();
        Set<AccountId> credited = new HashSet<>();
        for (PostingLine line : lines) {
            (line.side() == PostingSide.DEBIT ? debited : credited).add(line.accountId());
        }
        debited.retainAll(credited);
        if (!debited.isEmpty()) {
            throw new IllegalArgumentException("An account cannot be on both sides of one entry: " + debited);
        }
        Money debits = total(lines, PostingSide.DEBIT, currency);
        Money credits = total(lines, PostingSide.CREDIT, currency);
        if (!debits.equals(credits)) {
            throw new UnbalancedEntryException("Debits " + debits + " do not equal credits " + credits);
        }
    }

    private static Money total(List<PostingLine> lines, PostingSide side, Currency currency) {
        return lines.stream().filter(line -> line.side() == side).map(PostingLine::amount)
            .reduce(Money.zero(currency), Money::add);
    }

    /** True when a replay of the same reference asks for exactly this booking. */
    public boolean sameContentAs(JournalEntry other) {
        return reference.equals(other.reference) && lines.equals(other.lines)
            && Objects.equals(narrative, other.narrative);
    }

    /** Every account the entry touches, in line order, each once. */
    public List<AccountId> accountIds() {
        return new ArrayList<>(lines.stream().map(PostingLine::accountId)
            .collect(LinkedHashSet<AccountId>::new, Set::add, Set::addAll));
    }

    @Override
    public JournalEntryId getId() {
        return id;
    }

    public String reference() {
        return reference;
    }

    public List<PostingLine> lines() {
        return lines;
    }

    public String narrative() {
        return narrative;
    }

    public Instant bookedAt() {
        return bookedAt;
    }

    public Currency currency() {
        return lines.get(0).amount().getCurrency();
    }

    public Money totalDebits() {
        return total(lines, PostingSide.DEBIT, currency());
    }

    public Money totalCredits() {
        return total(lines, PostingSide.CREDIT, currency());
    }
}
