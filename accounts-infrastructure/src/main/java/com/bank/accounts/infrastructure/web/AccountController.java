package com.bank.accounts.infrastructure.web;

import com.bank.accounts.domain.Account;
import com.bank.accounts.domain.AccountId;
import com.bank.accounts.domain.port.in.FindAccountUseCase;
import com.bank.accounts.domain.port.in.ManageAccountUseCase;
import com.bank.accounts.domain.port.in.OpenAccountUseCase;
import com.bank.accounts.infrastructure.web.dto.AccountResponse;
import com.bank.accounts.infrastructure.web.dto.FreezeRequest;
import com.bank.accounts.infrastructure.web.dto.OpenAccountRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * Accounts API. GET is the read payments and mandates make before moving
 * money; customers read only their own accounts, and another customer's
 * account answers the same 404 as an unknown one. Opening and state changes
 * are staff operations; open-finance onboarding (dynamiconboarding) will
 * open accounts through its own TPP endpoint in a later round.
 */
@RestController
@RequestMapping("/api/v1/accounts")
public class AccountController {

    static final String IDEMPOTENCY_KEY_HEADER = "x-idempotency-key";

    private final OpenAccountUseCase openAccount;
    private final FindAccountUseCase findAccount;
    private final ManageAccountUseCase manageAccount;

    public AccountController(OpenAccountUseCase openAccount, FindAccountUseCase findAccount,
                             ManageAccountUseCase manageAccount) {
        this.openAccount = openAccount;
        this.findAccount = findAccount;
        this.manageAccount = manageAccount;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('BANKER', 'ADMIN')")
    public ResponseEntity<AccountResponse> open(@RequestHeader(IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
                                                @Valid @RequestBody OpenAccountRequest request) {
        Account account = openAccount.open(request.toCommand(idempotencyKey));
        return ResponseEntity.created(URI.create("/api/v1/accounts/" + account.getId().value()))
            .body(AccountResponse.from(account));
    }

    @GetMapping("/{accountId}")
    @PreAuthorize("hasAnyRole('CUSTOMER', 'BANKER', 'ADMIN', 'SERVICE')")
    public AccountResponse get(@PathVariable String accountId, Authentication authentication) {
        return AccountResponse.from(findAccount.find(AccountId.of(accountId), Callers.of(authentication)));
    }

    @PostMapping("/{accountId}/freeze")
    @PreAuthorize("hasAnyRole('BANKER', 'ADMIN')")
    public AccountResponse freeze(@PathVariable String accountId, @Valid @RequestBody FreezeRequest request) {
        return AccountResponse.from(manageAccount.freeze(AccountId.of(accountId), request.reason()));
    }

    @PostMapping("/{accountId}/unfreeze")
    @PreAuthorize("hasAnyRole('BANKER', 'ADMIN')")
    public AccountResponse unfreeze(@PathVariable String accountId) {
        return AccountResponse.from(manageAccount.unfreeze(AccountId.of(accountId)));
    }

    @PostMapping("/{accountId}/close")
    @PreAuthorize("hasAnyRole('BANKER', 'ADMIN')")
    public ResponseEntity<AccountResponse> close(@PathVariable String accountId) {
        return ResponseEntity.status(HttpStatus.OK).body(AccountResponse.from(manageAccount.close(AccountId.of(accountId))));
    }
}
