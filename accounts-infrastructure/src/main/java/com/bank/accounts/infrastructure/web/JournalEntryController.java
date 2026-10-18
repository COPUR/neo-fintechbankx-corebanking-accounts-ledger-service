package com.bank.accounts.infrastructure.web;

import com.bank.accounts.domain.JournalEntryId;
import com.bank.accounts.domain.port.in.FindJournalEntryUseCase;
import com.bank.accounts.domain.port.in.PostJournalEntryUseCase;
import com.bank.accounts.domain.port.in.PostingResult;
import com.bank.accounts.infrastructure.web.dto.JournalEntryResponse;
import com.bank.accounts.infrastructure.web.dto.PostJournalEntryRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * Journal API: the only way balances move. Posting is for the services on
 * SERVICE_CALLERS (payments, mandates, bulk, loan) and admins; a replay of
 * the same reference answers 200 with the first booking, a new one 201.
 */
@RestController
@RequestMapping("/api/v1/journal-entries")
public class JournalEntryController {

    private final PostJournalEntryUseCase postEntry;
    private final FindJournalEntryUseCase findEntry;

    public JournalEntryController(PostJournalEntryUseCase postEntry, FindJournalEntryUseCase findEntry) {
        this.postEntry = postEntry;
        this.findEntry = findEntry;
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN') or " + Callers.LISTED_SERVICE)
    public ResponseEntity<JournalEntryResponse> post(@Valid @RequestBody PostJournalEntryRequest request) {
        PostingResult result = postEntry.post(request.toCommand());
        JournalEntryResponse body = JournalEntryResponse.from(result.entry(), result.replay());
        if (result.replay()) {
            return ResponseEntity.ok(body);
        }
        return ResponseEntity.created(URI.create("/api/v1/journal-entries/" + body.journalEntryId())).body(body);
    }

    /**
     * The entry booked under a business reference, for reconciliation (for example
     * loan-disbursement:&lt;loanId&gt;): staff, and the services allowed to post. A reference
     * nobody booked is a 404.
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('BANKER', 'ADMIN') or " + Callers.LISTED_SERVICE)
    public ResponseEntity<JournalEntryResponse> findByReference(@RequestParam String reference) {
        return ResponseEntity.ok(JournalEntryResponse.from(findEntry.findByReference(reference), false));
    }

    @GetMapping("/{journalEntryId}")
    @PreAuthorize("hasAnyRole('BANKER', 'ADMIN', 'SERVICE')")
    public ResponseEntity<JournalEntryResponse> get(@PathVariable String journalEntryId) {
        return ResponseEntity.status(HttpStatus.OK)
            .body(JournalEntryResponse.from(findEntry.find(JournalEntryId.of(journalEntryId)), false));
    }
}
