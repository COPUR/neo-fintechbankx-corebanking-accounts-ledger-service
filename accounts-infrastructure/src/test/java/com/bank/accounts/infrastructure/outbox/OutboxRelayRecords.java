package com.bank.accounts.infrastructure.outbox;

/** Test access to the record the relay builds for a row, for tests outside this package. */
public final class OutboxRelayRecords {

    private OutboxRelayRecords() {
    }

    public static String topicOf(OutboxEventJpaEntity row) {
        return OutboxRelay.toRecord(row).topic();
    }
}
