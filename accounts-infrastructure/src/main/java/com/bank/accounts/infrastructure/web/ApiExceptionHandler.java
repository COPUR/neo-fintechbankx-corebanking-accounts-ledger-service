package com.bank.accounts.infrastructure.web;

import com.bank.accounts.domain.exception.AccountNotFoundException;
import com.bank.accounts.domain.exception.AccountNotOperationalException;
import com.bank.accounts.domain.exception.IdempotencyConflictException;
import com.bank.accounts.domain.exception.InsufficientFundsException;
import com.bank.accounts.domain.exception.JournalEntryNotFoundException;
import com.bank.accounts.domain.exception.UnbalancedEntryException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.List;

/**
 * Maps domain exceptions to the ErrorResponse of accounts-ledger.yaml.
 * Balances stay in the logs; the client gets a stable code. An unknown
 * account and one the caller may not see get the same fixed 404 (ADR-025).
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    static final List<String> DUPLICATE_CONSTRAINTS = List.of("uq_account_open_request_key",
        "uq_journal_entry_reference");
    static final String ACCOUNT_NOT_FOUND_MESSAGE = "Account not found";

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(AccountNotFoundException.class)
    ResponseEntity<ErrorResponse> accountNotFound(AccountNotFoundException ex) {
        return error(HttpStatus.NOT_FOUND, "ACCOUNT_NOT_FOUND", ACCOUNT_NOT_FOUND_MESSAGE);
    }

    @ExceptionHandler(JournalEntryNotFoundException.class)
    ResponseEntity<ErrorResponse> entryNotFound(JournalEntryNotFoundException ex) {
        return error(HttpStatus.NOT_FOUND, "JOURNAL_ENTRY_NOT_FOUND", "Journal entry not found");
    }

    @ExceptionHandler(InsufficientFundsException.class)
    ResponseEntity<ErrorResponse> insufficientFunds(InsufficientFundsException ex) {
        log.info("Posting refused: {}", ex.getMessage());
        return error(HttpStatus.UNPROCESSABLE_ENTITY, "INSUFFICIENT_FUNDS",
            "Account " + ex.accountId() + " does not have enough available funds");
    }

    @ExceptionHandler(AccountNotOperationalException.class)
    ResponseEntity<ErrorResponse> notOperational(AccountNotOperationalException ex) {
        return error(HttpStatus.UNPROCESSABLE_ENTITY, "ACCOUNT_NOT_OPERATIONAL",
            "Account " + ex.accountId() + " is " + ex.status() + " and takes no postings");
    }

    @ExceptionHandler(UnbalancedEntryException.class)
    ResponseEntity<ErrorResponse> unbalanced(UnbalancedEntryException ex) {
        return error(HttpStatus.UNPROCESSABLE_ENTITY, "UNBALANCED_ENTRY", ex.getMessage());
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    ResponseEntity<ErrorResponse> idempotencyConflict(IdempotencyConflictException ex) {
        return error(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED", ex.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<ErrorResponse> invalidTransition(IllegalStateException ex) {
        return error(HttpStatus.CONFLICT, "INVALID_ACCOUNT_STATE", ex.getMessage());
    }

    @ExceptionHandler({OptimisticLockingFailureException.class, PessimisticLockingFailureException.class})
    ResponseEntity<ErrorResponse> concurrentUpdate(RuntimeException ex) {
        return error(HttpStatus.CONFLICT, "CONCURRENT_UPDATE", "The account was changed by another request; retry");
    }

    /**
     * Two concurrent first requests with the same Idempotency-Key or
     * reference: the unique index lets one through and the other is a
     * retryable 409 (the retry then gets the replay). Any other constraint
     * violation is a defect.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ErrorResponse> dataIntegrity(DataIntegrityViolationException ex) {
        if (DUPLICATE_CONSTRAINTS.stream().anyMatch(constraint -> violates(ex, constraint))) {
            return error(HttpStatus.CONFLICT, "DUPLICATE_REQUEST",
                "A request with this key is already being processed; retry");
        }
        log.error("Ledger write violated a database constraint", ex);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "The change could not be stored");
    }

    static boolean violates(Throwable ex, String constraint) {
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (cause instanceof org.hibernate.exception.ConstraintViolationException violation
                && constraint.equalsIgnoreCase(violation.getConstraintName())) {
                return true;
            }
            if (cause.getMessage() != null && cause.getMessage().contains(constraint)) {
                return true;
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return false;
    }

    /** Malformed JSON, or a value that does not fit the field. The parser's message is not echoed. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ErrorResponse> unreadableBody(HttpMessageNotReadableException ex) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST",
            "The request body is not valid JSON or a field has an unsupported value");
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    ResponseEntity<ErrorResponse> missingParameter(MissingServletRequestParameterException ex) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Missing required parameter " + ex.getParameterName());
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    ResponseEntity<ErrorResponse> missingHeader(MissingRequestHeaderException ex) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Missing required header " + ex.getHeaderName());
    }

    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentNotValidException.class})
    ResponseEntity<ErrorResponse> badRequest(Exception ex) {
        String message = ex instanceof MethodArgumentNotValidException invalid
            ? invalid.getBindingResult().getAllErrors().stream()
                .map(e -> e.getDefaultMessage()).findFirst().orElse("Invalid request")
            : ex.getMessage();
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message);
    }

    private static ResponseEntity<ErrorResponse> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status)
            .body(new ErrorResponse(code, message, MDC.get(CorrelationIdFilter.MDC_KEY), Instant.now()));
    }

    public record ErrorResponse(String code, String message, String interactionId, Instant timestamp) {
    }
}
