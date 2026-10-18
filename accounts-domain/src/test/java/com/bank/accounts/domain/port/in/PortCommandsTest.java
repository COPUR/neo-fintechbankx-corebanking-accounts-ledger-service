package com.bank.accounts.domain.port.in;

import com.bank.accounts.domain.AccountId;
import com.bank.accounts.domain.AccountStatus;
import com.bank.accounts.domain.AccountType;
import com.bank.accounts.domain.JournalEntryId;
import com.bank.accounts.domain.PostingLine;
import com.bank.accounts.domain.exception.AccountNotFoundException;
import com.bank.accounts.domain.exception.AccountNotOperationalException;
import com.bank.accounts.domain.exception.InsufficientFundsException;
import com.bank.accounts.domain.exception.JournalEntryNotFoundException;
import com.bank.shared.kernel.domain.CustomerId;
import com.bank.shared.kernel.domain.Money;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Currency;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PortCommandsTest {

    private static final Currency AED = Currency.getInstance("AED");

    @Test
    void aCustomerActsOnlyForThemselvesAndStaffForAnyone() {
        assertThat(Caller.customer("C1").mayActFor(CustomerId.of("C1"))).isTrue();
        assertThat(Caller.customer("C1").mayActFor(CustomerId.of("C2"))).isFalse();
        assertThat(Caller.customer("C1").mayActFor(null)).isFalse();
        assertThat(Caller.staff("svc").mayActFor(CustomerId.of("C2"))).isTrue();
        assertThatThrownBy(() -> Caller.customer(" ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void openAccountCommandTiesTheInternalTypeToHavingNoHolder() {
        OpenAccountCommand internal = new OpenAccountCommand(" k1 ", null, AccountType.INTERNAL, AED, false);
        assertThat(internal.internal()).isTrue();
        assertThat(internal.requestKey()).isEqualTo("k1");

        assertThatThrownBy(() -> new OpenAccountCommand("k", CustomerId.of("C"), AccountType.INTERNAL, AED, false))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OpenAccountCommand("k", null, AccountType.CURRENT, AED, false))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OpenAccountCommand("k", null, AccountType.INTERNAL, AED, true))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OpenAccountCommand(" ", CustomerId.of("C"), AccountType.CURRENT, AED, false))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OpenAccountCommand("k".repeat(129), CustomerId.of("C"), AccountType.CURRENT,
            AED, false)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void postCommandCopiesItsLines() {
        List<PostingLine> lines = new ArrayList<>(List.of(PostingLine.debit(AccountId.of("A"), Money.of("1", AED))));
        PostJournalEntryCommand command = new PostJournalEntryCommand("R", lines, null);
        lines.clear();

        assertThat(command.lines()).hasSize(1);
    }

    @Test
    void exceptionsCarryTheirSubject() {
        assertThat(new AccountNotFoundException(AccountId.of("A1")).accountId()).isEqualTo(AccountId.of("A1"));
        AccountNotOperationalException frozen = new AccountNotOperationalException(AccountId.of("A1"),
            AccountStatus.FROZEN);
        assertThat(frozen.accountId()).isEqualTo(AccountId.of("A1"));
        assertThat(frozen.status()).isEqualTo(AccountStatus.FROZEN);
        assertThat(new InsufficientFundsException(AccountId.of("A1"), Money.zero(AED), Money.of("1", AED))
            .accountId()).isEqualTo(AccountId.of("A1"));
        assertThat(new JournalEntryNotFoundException(JournalEntryId.of("JE-1"))).hasMessageContaining("JE-1");
    }
}
