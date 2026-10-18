package com.bank.accounts.infrastructure.web;

import com.bank.accounts.domain.JournalEntry;
import com.bank.accounts.domain.JournalEntryId;
import com.bank.accounts.domain.exception.JournalEntryNotFoundException;
import com.bank.accounts.domain.port.in.FindJournalEntryUseCase;
import com.bank.accounts.domain.port.in.PostJournalEntryUseCase;
import com.bank.accounts.domain.port.in.PostingResult;
import com.bank.accounts.infrastructure.config.ServiceCallerPolicy;
import com.bank.accounts.infrastructure.web.dto.JournalEntryResponse;
import com.bank.accounts.infrastructure.web.dto.PostJournalEntryRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.Objects;
import java.util.Set;

/**
 * Journal API: the only way balances move. Posting is for the services on
 * SERVICE_CALLERS (payments, mandates, bulk, loan) and admins; a replay of
 * the same reference answers 200 with the first booking, a new one 201.
 * A service posts and looks up only the references with its own prefixes
 * (ServiceCallerPolicy); staff are not scoped.
 */
@RestController
@RequestMapping("/api/v1/journal-entries")
public class JournalEntryController {

    /** Audit trail of lookups by reference; one line per call, no balances or customer data. */
    private static final Logger audit = LoggerFactory.getLogger("audit.accounts.journal-entry-lookup");
    private static final Set<String> UNSCOPED_ROLES = Set.of("ROLE_BANKER", "ROLE_ADMIN");

    private final PostJournalEntryUseCase postEntry;
    private final FindJournalEntryUseCase findEntry;
    private final ServiceCallerPolicy serviceCallers;

    public JournalEntryController(PostJournalEntryUseCase postEntry, FindJournalEntryUseCase findEntry,
                                  ServiceCallerPolicy serviceCallers) {
        this.postEntry = postEntry;
        this.findEntry = findEntry;
        this.serviceCallers = serviceCallers;
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN') or (" + Callers.LISTED_SERVICE
        + " and @serviceCallers.ownsReference(authentication, #request.reference()))")
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
     * nobody booked is a 404, and so is one outside the calling service's prefixes,
     * without reading the journal. Every call writes one audit line.
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('BANKER', 'ADMIN') or " + Callers.LISTED_SERVICE)
    public ResponseEntity<JournalEntryResponse> findByReference(@RequestParam String reference,
                                                                Authentication authentication) {
        String trimmed = reference.trim();
        if (trimmed.isEmpty() || trimmed.length() > JournalEntry.MAX_REFERENCE_LENGTH) {
            audit(authentication, "INVALID", null);
            throw new IllegalArgumentException(trimmed.isEmpty() ? "reference is required"
                : "reference is longer than " + JournalEntry.MAX_REFERENCE_LENGTH + " characters");
        }
        if (!unscoped(authentication) && !serviceCallers.ownsReference(authentication, trimmed)) {
            audit(authentication, "OUT_OF_SCOPE", trimmed);
            throw JournalEntryNotFoundException.forReference(trimmed);
        }
        JournalEntry entry;
        try {
            entry = findEntry.findByReference(trimmed);
        } catch (JournalEntryNotFoundException ex) {
            audit(authentication, "NOT_FOUND", trimmed);
            throw ex;
        }
        audit(authentication, "FOUND", trimmed);
        return ResponseEntity.ok(JournalEntryResponse.from(entry, false));
    }

    @GetMapping("/{journalEntryId}")
    @PreAuthorize("hasAnyRole('BANKER', 'ADMIN', 'SERVICE')")
    public ResponseEntity<JournalEntryResponse> get(@PathVariable String journalEntryId) {
        return ResponseEntity.status(HttpStatus.OK)
            .body(JournalEntryResponse.from(findEntry.find(JournalEntryId.of(journalEntryId)), false));
    }

    private static boolean unscoped(Authentication authentication) {
        return authentication.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .anyMatch(UNSCOPED_ROLES::contains);
    }

    /** Reference is logged only once validated (bounded, then stripped of control characters). */
    private static void audit(Authentication authentication, String outcome, String reference) {
        audit.info("Journal entry lookup by reference: outcome={} client={} subject={} reference={} interactionId={}",
            outcome, Callers.client(authentication), authentication.getName(),
            reference == null ? "-" : reference.replaceAll("\\p{Cntrl}", "_"),
            Objects.requireNonNullElse(MDC.get(CorrelationIdFilter.MDC_KEY), "-"));
    }
}
