package com.bank.accounts.application;

import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

/** Runs the callback and counts the transactions opened. */
final class RecordingTransactions implements TransactionOperations {

    private int opened;

    @Override
    public <T> T execute(TransactionCallback<T> action) throws TransactionException {
        opened++;
        return action.doInTransaction(new SimpleTransactionStatus());
    }

    int opened() {
        return opened;
    }
}
