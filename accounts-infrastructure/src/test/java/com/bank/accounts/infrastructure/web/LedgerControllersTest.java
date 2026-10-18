package com.bank.accounts.infrastructure.web;

import com.bank.accounts.domain.Account;
import com.bank.accounts.domain.AccountId;
import com.bank.accounts.domain.AccountStatus;
import com.bank.accounts.domain.AccountType;
import com.bank.accounts.domain.JournalEntry;
import com.bank.accounts.domain.JournalEntryId;
import com.bank.accounts.domain.PostingLine;
import com.bank.accounts.domain.exception.AccountNotFoundException;
import com.bank.accounts.domain.exception.AccountNotOperationalException;
import com.bank.accounts.domain.exception.IdempotencyConflictException;
import com.bank.accounts.domain.exception.InsufficientFundsException;
import com.bank.accounts.domain.exception.JournalEntryNotFoundException;
import com.bank.accounts.domain.exception.UnbalancedEntryException;
import com.bank.accounts.domain.port.in.Caller;
import com.bank.accounts.domain.port.in.FindAccountUseCase;
import com.bank.accounts.domain.port.in.FindJournalEntryUseCase;
import com.bank.accounts.domain.port.in.ManageAccountUseCase;
import com.bank.accounts.domain.port.in.OpenAccountCommand;
import com.bank.accounts.domain.port.in.OpenAccountUseCase;
import com.bank.accounts.domain.port.in.PostJournalEntryCommand;
import com.bank.accounts.domain.port.in.PostJournalEntryUseCase;
import com.bank.accounts.domain.port.in.PostingResult;
import com.bank.accounts.infrastructure.config.SecurityConfiguration;
import com.bank.accounts.infrastructure.config.ServiceCallerPolicy;
import com.bank.shared.kernel.domain.CustomerId;
import com.bank.shared.kernel.domain.Money;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.sql.SQLException;
import java.time.Instant;
import java.util.Currency;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Web slice with the real security rules, the caller taken from the token, and the error mapping. */
@WebMvcTest(controllers = {AccountController.class, JournalEntryController.class})
@ExtendWith(OutputCaptureExtension.class)
class LedgerControllersTest {

    @SpringBootConfiguration
    @Import({AccountController.class, JournalEntryController.class, ApiExceptionHandler.class,
        SecurityConfiguration.class, ServiceCallerPolicy.class, CorrelationIdFilter.class})
    static class Slice {
    }

    private static final Currency AED = Currency.getInstance("AED");
    private static final Instant NOW = Instant.parse("2026-10-17T12:00:00Z");
    private static final String ENTRY = """
        {"reference":"PAY-1","currency":"AED","narrative":"payment PAY-1","lines":[
          {"accountId":"ACC-1","side":"DEBIT","amount":40.00},
          {"accountId":"ACC-2","side":"CREDIT","amount":40.00}]}
        """;
    private static final String SERVICE_ENTRY = ENTRY.replace("\"PAY-1\"", "\"payment-PAY-1\"");

    @Autowired MockMvc mvc;
    @MockBean OpenAccountUseCase open;
    @MockBean FindAccountUseCase find;
    @MockBean ManageAccountUseCase manage;
    @MockBean PostJournalEntryUseCase postEntry;
    @MockBean FindJournalEntryUseCase findEntry;
    @MockBean JwtDecoder jwtDecoder;

    private static Account account() {
        Account account = Account.openCustomerAccount(AccountId.of("ACC-1"), CustomerId.of("CUST-1"),
            AccountType.CURRENT, AED, false, NOW);
        account.credit(Money.of("100.00", AED), JournalEntryId.of("JE-0"), NOW);
        return account;
    }

    private static JournalEntry entry() {
        return JournalEntry.book(JournalEntryId.of("JE-1"), "PAY-1", List.of(
            PostingLine.debit(AccountId.of("ACC-1"), Money.of("40.00", AED)),
            PostingLine.credit(AccountId.of("ACC-2"), Money.of("40.00", AED))), "payment PAY-1", NOW);
    }

    @Test
    void aServiceReadsTheAccountFieldsPaymentsAndMandatesUse() throws Exception {
        when(find.find(AccountId.of("ACC-1"), Caller.staff("service-account-svc-pay-initiation-settlement")))
            .thenReturn(account());

        mvc.perform(service(get("/api/v1/accounts/ACC-1"), "svc-pay-initiation-settlement"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accountId").value("ACC-1"))
            .andExpect(jsonPath("$.customerId").value("CUST-1"))
            .andExpect(jsonPath("$.status").value("ACTIVE"))
            .andExpect(jsonPath("$.currency").value("AED"))
            .andExpect(jsonPath("$.availableBalance").value(100.00))
            .andExpect(jsonPath("$.debitAllowed").value(true))
            .andExpect(jsonPath("$.creditAllowed").value(true));
    }

    @Test
    void aCustomerReadsAsTheirCustomerIdAndAnotherAccountIsTheFixed404() throws Exception {
        when(find.find(AccountId.of("ACC-1"), Caller.customer("CUST-1"))).thenReturn(account());
        when(find.find(AccountId.of("ACC-9"), Caller.customer("CUST-1")))
            .thenThrow(new AccountNotFoundException(AccountId.of("ACC-9")));

        mvc.perform(customer(get("/api/v1/accounts/ACC-1"), "CUST-1")).andExpect(status().isOk());
        mvc.perform(customer(get("/api/v1/accounts/ACC-9"), "CUST-1"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"))
            .andExpect(jsonPath("$.message").value("Account not found"));
    }

    @Test
    void aCustomerTokenWithoutACustomerIdIsRefused() throws Exception {
        mvc.perform(as(get("/api/v1/accounts/ACC-1"), "kc-1", "ROLE_CUSTOMER")).andExpect(status().isForbidden());
        verifyNoInteractions(find);
    }

    @Test
    void staffOpenAnAccountWithAnIdempotencyKey() throws Exception {
        when(open.open(argThat(command -> command != null && command.requestKey().equals("k-1")
            && command.holder().equals(CustomerId.of("CUST-1"))))).thenReturn(account());

        mvc.perform(as(post("/api/v1/accounts"), "banker-1", "ROLE_BANKER").header("x-idempotency-key", "k-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"customerId\":\"CUST-1\",\"accountType\":\"CURRENT\",\"currency\":\"AED\"}"))
            .andExpect(status().isCreated())
            .andExpect(header().string("Location", "/api/v1/accounts/ACC-1"))
            .andExpect(jsonPath("$.accountId").value("ACC-1"));
    }

    @Test
    void openingNeedsAKeyAndAStaffRole() throws Exception {
        String body = "{\"customerId\":\"CUST-1\",\"accountType\":\"CURRENT\",\"currency\":\"AED\"}";
        mvc.perform(as(post("/api/v1/accounts"), "banker-1", "ROLE_BANKER")
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("Missing required header x-idempotency-key"));
        mvc.perform(customer(post("/api/v1/accounts"), "CUST-1").header("x-idempotency-key", "k")
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isForbidden());
        verifyNoInteractions(open);
    }

    @Test
    void aReusedOpenKeyIsA409AndAnInvalidBodyA400() throws Exception {
        when(open.open(any(OpenAccountCommand.class))).thenThrow(new IdempotencyConflictException("reused"));

        mvc.perform(as(post("/api/v1/accounts"), "admin", "ROLE_ADMIN").header("x-idempotency-key", "k")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"customerId\":\"CUST-1\",\"accountType\":\"CURRENT\",\"currency\":\"AED\"}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
        mvc.perform(as(post("/api/v1/accounts"), "admin", "ROLE_ADMIN").header("x-idempotency-key", "k")
                .contentType(MediaType.APPLICATION_JSON).content("{\"accountType\":\"CURRENT\",\"currency\":\"aed\"}"))
            .andExpect(status().isBadRequest());
        mvc.perform(as(post("/api/v1/accounts"), "admin", "ROLE_ADMIN").header("x-idempotency-key", "k")
                .contentType(MediaType.APPLICATION_JSON).content("{\"accountType\":\"NOPE\",\"currency\":\"AED\"}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void staffFreezeUnfreezeAndClose() throws Exception {
        Account frozen = account();
        frozen.freeze("court order", NOW);
        when(manage.freeze(AccountId.of("ACC-1"), "court order")).thenReturn(frozen);
        when(manage.unfreeze(AccountId.of("ACC-1"))).thenReturn(account());
        when(manage.close(AccountId.of("ACC-1"))).thenThrow(new IllegalStateException("balance is not zero"));

        mvc.perform(as(post("/api/v1/accounts/ACC-1/freeze"), "banker", "ROLE_BANKER")
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"court order\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("FROZEN"))
            .andExpect(jsonPath("$.debitAllowed").value(false));
        mvc.perform(as(post("/api/v1/accounts/ACC-1/unfreeze"), "banker", "ROLE_BANKER"))
            .andExpect(status().isOk());
        mvc.perform(as(post("/api/v1/accounts/ACC-1/close"), "banker", "ROLE_BANKER"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("INVALID_ACCOUNT_STATE"));
        mvc.perform(service(post("/api/v1/accounts/ACC-1/unfreeze"), "svc-pay-initiation-settlement"))
            .andExpect(status().isForbidden());
    }

    @Test
    void aListedServicePostsAnEntryAndAReplayAnswers200() throws Exception {
        when(postEntry.post(argThat(command -> command != null && command.reference().equals("payment-PAY-1"))))
            .thenReturn(new PostingResult(entry(), false), new PostingResult(entry(), true));

        mvc.perform(service(post("/api/v1/journal-entries"), "svc-pay-initiation-settlement")
                .contentType(MediaType.APPLICATION_JSON).content(SERVICE_ENTRY))
            .andExpect(status().isCreated())
            .andExpect(header().string("Location", "/api/v1/journal-entries/JE-1"))
            .andExpect(jsonPath("$.replay").value(false))
            .andExpect(jsonPath("$.lines[0].side").value("DEBIT"));
        mvc.perform(service(post("/api/v1/journal-entries"), "svc-pay-initiation-settlement")
                .contentType(MediaType.APPLICATION_JSON).content(SERVICE_ENTRY))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.replay").value(true));
    }

    @Test
    void anUnlistedServiceABankerAndACustomerCannotPost() throws Exception {
        mvc.perform(service(post("/api/v1/journal-entries"), "svc-of-consent-authorization")
                .contentType(MediaType.APPLICATION_JSON).content(ENTRY))
            .andExpect(status().isForbidden());
        mvc.perform(as(post("/api/v1/journal-entries"), "banker", "ROLE_BANKER")
                .contentType(MediaType.APPLICATION_JSON).content(ENTRY))
            .andExpect(status().isForbidden());
        mvc.perform(customer(post("/api/v1/journal-entries"), "CUST-1")
                .contentType(MediaType.APPLICATION_JSON).content(ENTRY))
            .andExpect(status().isForbidden());
        verifyNoInteractions(postEntry);
    }

    @Test
    void aServicePostsOnlyUnderItsOwnPrefixWhileAnAdminIsNotScoped() throws Exception {
        when(postEntry.post(any(PostJournalEntryCommand.class))).thenReturn(new PostingResult(entry(), false));

        mvc.perform(service(post("/api/v1/journal-entries"), "svc-ln-loan-lifecycle")
                .contentType(MediaType.APPLICATION_JSON).content(SERVICE_ENTRY))
            .andExpect(status().isForbidden());
        mvc.perform(service(post("/api/v1/journal-entries"), "svc-pay-initiation-settlement")
                .contentType(MediaType.APPLICATION_JSON).content(ENTRY))
            .andExpect(status().isForbidden());
        verifyNoInteractions(postEntry);

        mvc.perform(service(post("/api/v1/journal-entries"), "svc-ln-loan-lifecycle")
                .contentType(MediaType.APPLICATION_JSON)
                .content(ENTRY.replace("\"PAY-1\"", "\" loan-disbursement:LN-9 \"")))
            .andExpect(status().isCreated());
        mvc.perform(as(post("/api/v1/journal-entries"), "admin", "ROLE_ADMIN")
                .contentType(MediaType.APPLICATION_JSON).content(ENTRY))
            .andExpect(status().isCreated());
        verify(postEntry).post(argThat(command -> command.reference().equals(" loan-disbursement:LN-9 ")));
        verify(postEntry).post(argThat(command -> command.reference().equals("PAY-1")));
    }

    @Test
    void anAmountFinerThanTheCurrencyIsRefusedNotRounded() throws Exception {
        mvc.perform(as(post("/api/v1/journal-entries"), "admin", "ROLE_ADMIN")
                .contentType(MediaType.APPLICATION_JSON).content(ENTRY.replace("40.00", "40.005")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        verifyNoInteractions(postEntry);
    }

    @Test
    void bookingRefusalsMapToStableCodes() throws Exception {
        when(postEntry.post(any(PostJournalEntryCommand.class)))
            .thenThrow(new InsufficientFundsException(AccountId.of("ACC-1"), Money.of("1", AED), Money.of("40", AED)))
            .thenThrow(new AccountNotOperationalException(AccountId.of("ACC-1"), AccountStatus.FROZEN))
            .thenThrow(new UnbalancedEntryException("Debits do not equal credits"))
            .thenThrow(new AccountNotFoundException(AccountId.of("ACC-2")))
            .thenThrow(new IdempotencyConflictException("other lines"))
            .thenThrow(new OptimisticLockingFailureException("race"))
            .thenThrow(new DataIntegrityViolationException("dup", new ConstraintViolationException("dup",
                new SQLException("duplicate key"), "uq_journal_entry_reference")))
            .thenThrow(new DataIntegrityViolationException("other", new SQLException("ck_something")));

        String[][] expected = {{"422", "INSUFFICIENT_FUNDS"}, {"422", "ACCOUNT_NOT_OPERATIONAL"},
            {"422", "UNBALANCED_ENTRY"}, {"404", "ACCOUNT_NOT_FOUND"}, {"409", "IDEMPOTENCY_KEY_REUSED"},
            {"409", "CONCURRENT_UPDATE"}, {"409", "DUPLICATE_REQUEST"}, {"500", "INTERNAL_ERROR"}};
        for (String[] answer : expected) {
            mvc.perform(as(post("/api/v1/journal-entries"), "admin", "ROLE_ADMIN")
                    .contentType(MediaType.APPLICATION_JSON).content(ENTRY))
                .andExpect(status().is(Integer.parseInt(answer[0])))
                .andExpect(jsonPath("$.code").value(answer[1]));
        }
    }

    @Test
    void anEntryIsReadByStaffAndServices() throws Exception {
        when(findEntry.find(JournalEntryId.of("JE-1"))).thenReturn(entry());
        when(findEntry.find(JournalEntryId.of("JE-X"))).thenThrow(new JournalEntryNotFoundException(JournalEntryId.of("JE-X")));

        mvc.perform(service(get("/api/v1/journal-entries/JE-1"), "svc-ln-loan-lifecycle"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.reference").value("PAY-1"));
        mvc.perform(as(get("/api/v1/journal-entries/JE-X"), "banker", "ROLE_BANKER"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("JOURNAL_ENTRY_NOT_FOUND"));
        mvc.perform(customer(get("/api/v1/journal-entries/JE-1"), "CUST-1")).andExpect(status().isForbidden());
        verify(findEntry).find(eq(JournalEntryId.of("JE-1")));
    }

    @Test
    void anEntryIsFoundByReferenceByStaffAndListedServicesOnly() throws Exception {
        when(findEntry.findByReference("loan-disbursement:LN-1")).thenReturn(entry());
        when(findEntry.findByReference("loan-disbursement:LN-9"))
            .thenThrow(JournalEntryNotFoundException.forReference("loan-disbursement:LN-9"));

        mvc.perform(service(get("/api/v1/journal-entries").param("reference", " loan-disbursement:LN-1 "),
                "svc-ln-loan-lifecycle"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.reference").value("PAY-1"))
            .andExpect(jsonPath("$.replay").value(false));
        mvc.perform(as(get("/api/v1/journal-entries").param("reference", "loan-disbursement:LN-9"), "banker", "ROLE_BANKER"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("JOURNAL_ENTRY_NOT_FOUND"))
            .andExpect(jsonPath("$.message").value("Journal entry not found"));
        mvc.perform(as(get("/api/v1/journal-entries"), "admin", "ROLE_ADMIN"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("Missing required parameter reference"));
        mvc.perform(service(get("/api/v1/journal-entries").param("reference", "PAY-1"), "svc-of-consent-authorization"))
            .andExpect(status().isForbidden());
        mvc.perform(customer(get("/api/v1/journal-entries").param("reference", "PAY-1"), "CUST-1"))
            .andExpect(status().isForbidden());
        verify(findEntry).findByReference(eq("loan-disbursement:LN-1"));
        verify(findEntry).findByReference(eq("loan-disbursement:LN-9"));
        verifyNoMoreInteractions(findEntry);
    }

    @Test
    void anotherServicesReferenceIsTheSameFixed404WithoutReadingTheJournal(CapturedOutput output) throws Exception {
        when(findEntry.findByReference("payment-PAY-1")).thenReturn(entry());

        mvc.perform(service(get("/api/v1/journal-entries").param("reference", "payment-PAY-1"), "svc-ln-loan-lifecycle")
                .header("x-fapi-interaction-id", "it-scope-1"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("JOURNAL_ENTRY_NOT_FOUND"))
            .andExpect(jsonPath("$.message").value("Journal entry not found"));
        verifyNoInteractions(findEntry);

        mvc.perform(service(get("/api/v1/journal-entries").param("reference", "payment-PAY-1"),
                "svc-pay-initiation-settlement").header("x-fapi-interaction-id", "it-scope-2"))
            .andExpect(status().isOk());
        mvc.perform(as(get("/api/v1/journal-entries").param("reference", "payment-PAY-1"), "banker", "ROLE_BANKER"))
            .andExpect(status().isOk());

        assertThat(output.getOut())
            .contains("outcome=OUT_OF_SCOPE client=svc-ln-loan-lifecycle subject=service-account-svc-ln-loan-lifecycle"
                + " reference=payment-PAY-1 interactionId=it-scope-1")
            .contains("outcome=FOUND client=svc-pay-initiation-settlement"
                + " subject=service-account-svc-pay-initiation-settlement reference=payment-PAY-1 interactionId=it-scope-2")
            .contains("outcome=FOUND client=- subject=banker reference=payment-PAY-1");
    }

    @Test
    void aBlankOrTooLongReferenceIsA400AtTheWebLayer(CapturedOutput output) throws Exception {
        mvc.perform(service(get("/api/v1/journal-entries").param("reference", "   "), "svc-ln-loan-lifecycle"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
            .andExpect(jsonPath("$.message").value("reference is required"));
        mvc.perform(as(get("/api/v1/journal-entries").param("reference", ""), "banker", "ROLE_BANKER"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(service(get("/api/v1/journal-entries").param("reference", "loan-" + "x".repeat(124)),
                "svc-ln-loan-lifecycle"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
            .andExpect(jsonPath("$.message").value("reference is longer than 128 characters"));
        verifyNoInteractions(findEntry);

        when(findEntry.findByReference("loan-" + "x".repeat(123)))
            .thenThrow(JournalEntryNotFoundException.forReference("loan-" + "x".repeat(123)));
        mvc.perform(service(get("/api/v1/journal-entries").param("reference", "loan-" + "x".repeat(123)),
                "svc-ln-loan-lifecycle"))
            .andExpect(status().isNotFound());
        assertThat(output.getOut()).contains("outcome=INVALID client=svc-ln-loan-lifecycle")
            .contains("reference=- ").contains("outcome=NOT_FOUND client=svc-ln-loan-lifecycle")
            .doesNotContain("x".repeat(124));
    }

    @Test
    void aControlCharacterInTheReferenceDoesNotForgeAnAuditLine(CapturedOutput output) throws Exception {
        when(findEntry.findByReference("loan-1\noutcome=FOUND"))
            .thenThrow(JournalEntryNotFoundException.forReference("loan-1\noutcome=FOUND"));

        mvc.perform(service(get("/api/v1/journal-entries").param("reference", "loan-1\noutcome=FOUND"),
                "svc-ln-loan-lifecycle"))
            .andExpect(status().isNotFound());
        assertThat(output.getOut()).contains("reference=loan-1_outcome=FOUND")
            .doesNotContain("loan-1\noutcome=FOUND");
    }

    @Test
    void malformedJsonIsA400WithoutTheParserMessage() throws Exception {
        mvc.perform(as(post("/api/v1/journal-entries"), "admin", "ROLE_ADMIN")
                .contentType(MediaType.APPLICATION_JSON).content("{not json"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value(
                "The request body is not valid JSON or a field has an unsupported value"));
    }

    @Test
    void anAnonymousCallIs401() throws Exception {
        mvc.perform(get("/api/v1/accounts/ACC-1")).andExpect(status().isUnauthorized());
    }

    private static MockHttpServletRequestBuilder customer(MockHttpServletRequestBuilder request, String customerId) {
        return request.with(jwt().jwt(j -> j.subject("kc-" + customerId).claim("customer_id", customerId))
            .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER")));
    }

    /** A client-credentials token: SERVICE realm role, azp the calling client id. */
    private static MockHttpServletRequestBuilder service(MockHttpServletRequestBuilder request, String clientId) {
        return request.with(jwt().jwt(j -> j.subject("service-account-" + clientId).claim("azp", clientId))
            .authorities(new SimpleGrantedAuthority("ROLE_SERVICE")));
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, String subject, String role) {
        return request.with(jwt().jwt(j -> j.subject(subject)).authorities(new SimpleGrantedAuthority(role)));
    }
}
