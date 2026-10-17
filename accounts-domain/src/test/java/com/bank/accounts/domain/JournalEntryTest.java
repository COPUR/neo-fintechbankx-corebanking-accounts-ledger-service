package com.bank.accounts.domain;

import com.bank.accounts.domain.exception.UnbalancedEntryException;
import com.bank.shared.kernel.domain.Money;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Currency;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JournalEntryTest {

    private static final Currency AED = Currency.getInstance("AED");
    private static final Instant NOW = Instant.parse("2026-10-17T12:00:00Z");

    private static PostingLine debit(String account, String amount) {
        return PostingLine.debit(AccountId.of(account), Money.of(amount, AED));
    }

    private static PostingLine credit(String account, String amount) {
        return PostingLine.credit(AccountId.of(account), Money.of(amount, AED));
    }

    @Test
    void aBalancedEntryIsAccepted() {
        JournalEntry entry = JournalEntry.book(JournalEntryId.of("JE-1"), "PAY-1",
            List.of(debit("ACC-1", "70.00"), debit("ACC-2", "30.00"), credit("ACC-3", "100.00")), "transfer", NOW);

        assertThat(entry.totalDebits()).isEqualTo(Money.of("100.00", AED));
        assertThat(entry.totalCredits()).isEqualTo(Money.of("100.00", AED));
        assertThat(entry.currency()).isEqualTo(AED);
        assertThat(entry.accountIds()).containsExactly(AccountId.of("ACC-1"), AccountId.of("ACC-2"),
            AccountId.of("ACC-3"));
        assertThat(entry.reference()).isEqualTo("PAY-1");
    }

    @Test
    void debitsMustEqualCredits() {
        assertThatThrownBy(() -> JournalEntry.book(JournalEntryId.of("JE-1"), "PAY-1",
            List.of(debit("ACC-1", "100.00"), credit("ACC-2", "99.99")), "transfer", NOW))
            .isInstanceOf(UnbalancedEntryException.class)
            .hasMessageContaining("100.00")
            .hasMessageContaining("99.99");
    }

    @Test
    void anEntryNeedsAtLeastOneDebitAndOneCredit() {
        assertThatThrownBy(() -> JournalEntry.book(JournalEntryId.of("JE-1"), "PAY-1",
            List.of(debit("ACC-1", "10.00"), debit("ACC-2", "10.00")), "x", NOW))
            .isInstanceOf(UnbalancedEntryException.class);
        assertThatThrownBy(() -> JournalEntry.book(JournalEntryId.of("JE-1"), "PAY-1", List.of(), "x", NOW))
            .isInstanceOf(UnbalancedEntryException.class);
    }

    @Test
    void allLinesMustShareOneCurrency() {
        PostingLine usd = PostingLine.credit(AccountId.of("ACC-2"), Money.of("10.00", Currency.getInstance("USD")));

        assertThatThrownBy(() -> JournalEntry.book(JournalEntryId.of("JE-1"), "PAY-1",
            List.of(debit("ACC-1", "10.00"), usd), "x", NOW))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("currency");
    }

    @Test
    void aLineAmountMustBePositive() {
        assertThatThrownBy(() -> debit("ACC-1", "0.00")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> credit("ACC-1", "-5.00")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anAccountMayNotBeOnBothSidesOfOneEntry() {
        assertThatThrownBy(() -> JournalEntry.book(JournalEntryId.of("JE-1"), "PAY-1",
            List.of(debit("ACC-1", "10.00"), credit("ACC-1", "10.00")), "x", NOW))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("ACC-1");
    }

    @Test
    void theReferenceIsRequiredAndBounded() {
        List<PostingLine> lines = List.of(debit("ACC-1", "10.00"), credit("ACC-2", "10.00"));

        assertThatThrownBy(() -> JournalEntry.book(JournalEntryId.of("JE-1"), " ", lines, "x", NOW))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> JournalEntry.book(JournalEntryId.of("JE-1"), "R".repeat(129), lines, "x", NOW))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sameContentComparesLinesAndNarrativeButNotIdOrTime() {
        List<PostingLine> lines = List.of(debit("ACC-1", "10.00"), credit("ACC-2", "10.00"));
        JournalEntry first = JournalEntry.book(JournalEntryId.of("JE-1"), "PAY-1", lines, "x", NOW);
        JournalEntry replay = JournalEntry.book(JournalEntryId.of("JE-2"), "PAY-1", lines, "x", NOW.plusSeconds(5));
        JournalEntry changed = JournalEntry.book(JournalEntryId.of("JE-3"), "PAY-1",
            List.of(debit("ACC-1", "10.01"), credit("ACC-2", "10.01")), "x", NOW);

        assertThat(first.sameContentAs(replay)).isTrue();
        assertThat(first.sameContentAs(changed)).isFalse();
    }

    @Test
    void aStoredEntryIsRehydratedUnderTheSameRules() {
        List<PostingLine> lines = List.of(debit("ACC-1", "10.00"), credit("ACC-2", "10.00"));
        JournalEntry entry = JournalEntry.rehydrate(JournalEntryId.of("JE-1"), "PAY-1", lines, " note ", NOW);

        assertThat(entry.getId()).isEqualTo(JournalEntryId.of("JE-1"));
        assertThat(entry.lines()).isEqualTo(lines);
        assertThat(entry.narrative()).isEqualTo("note");
        assertThat(entry.bookedAt()).isEqualTo(NOW);
        assertThatThrownBy(() -> JournalEntry.rehydrate(JournalEntryId.of("JE-1"), "PAY-1", lines,
            "n".repeat(257), NOW)).isInstanceOf(IllegalArgumentException.class);
    }
}
