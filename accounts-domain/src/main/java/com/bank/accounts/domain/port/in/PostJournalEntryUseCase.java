package com.bank.accounts.domain.port.in;

public interface PostJournalEntryUseCase {

    /**
     * Books the entry and moves every account balance in one transaction, or
     * nothing. The same reference with the same lines is a replay; with other
     * lines it is refused (IdempotencyConflictException).
     */
    PostingResult post(PostJournalEntryCommand command);
}
