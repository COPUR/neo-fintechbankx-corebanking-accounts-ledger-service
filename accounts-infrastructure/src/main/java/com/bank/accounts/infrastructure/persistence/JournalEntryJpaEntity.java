package com.bank.accounts.infrastructure.persistence;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Row of sc_acc_accounts_ledger.journal_entry with its posting lines (V1). Insert-only. */
@Entity
@Table(name = "journal_entry")
public class JournalEntryJpaEntity {

    @Id
    @Column(name = "journal_entry_id", length = 64)
    private String journalEntryId;

    @Column(name = "reference", nullable = false, length = 128, updatable = false)
    private String reference;

    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    private String currency;

    @Column(name = "narrative", length = 256, updatable = false)
    private String narrative;

    @Column(name = "booked_at", nullable = false, updatable = false)
    private Instant bookedAt;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "posting_line", joinColumns = @JoinColumn(name = "journal_entry_id"))
    @OrderColumn(name = "line_no")
    private List<PostingLineEmbeddable> lines = new ArrayList<>();

    protected JournalEntryJpaEntity() {
    }

    JournalEntryJpaEntity(String journalEntryId, String reference, String currency, String narrative,
                          Instant bookedAt, List<PostingLineEmbeddable> lines) {
        this.journalEntryId = journalEntryId;
        this.reference = reference;
        this.currency = currency;
        this.narrative = narrative;
        this.bookedAt = bookedAt;
        this.lines = new ArrayList<>(lines);
    }

    public String getJournalEntryId() { return journalEntryId; }
    public String getReference() { return reference; }
    public String getCurrency() { return currency; }
    public String getNarrative() { return narrative; }
    public Instant getBookedAt() { return bookedAt; }
    public List<PostingLineEmbeddable> getLines() { return lines; }
}
