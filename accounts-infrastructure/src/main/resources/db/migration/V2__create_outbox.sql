-- Transactional outbox for evt.acc.account.v1 (ADR-019: one topic per
-- aggregate, keyed by the account id, eventType header). Rows are written in
-- the same transaction as the account change and relayed by OutboxRelay,
-- which computes the topic. Same final shape as the payments outbox after its
-- V2 and V4-V7 (ADR-021 decision 4: only payload errors park a row; a parked
-- row holds back the later rows of its aggregate until it is replayed).

CREATE TABLE outbox_event (
    event_id          UUID          PRIMARY KEY,
    created_seq       BIGINT        GENERATED ALWAYS AS IDENTITY,
    aggregate_type    VARCHAR(64)   NOT NULL,
    aggregate_id      VARCHAR(64)   NOT NULL,
    aggregate_version BIGINT        NOT NULL,
    event_type        VARCHAR(128)  NOT NULL,
    payload           JSONB         NOT NULL,
    correlation_id    VARCHAR(128)  NOT NULL,
    occurred_at       TIMESTAMPTZ   NOT NULL,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    published_at      TIMESTAMPTZ,
    attempts          INTEGER       NOT NULL DEFAULT 0,
    last_error        VARCHAR(512),
    parked_at         TIMESTAMPTZ,
    park_reason       VARCHAR(512),
    park_counted      BOOLEAN       NOT NULL DEFAULT false,
    -- W3C traceparent of the request that produced the event, sent as a Kafka header.
    traceparent       VARCHAR(55),

    CONSTRAINT uq_outbox_created_seq UNIQUE (created_seq)
);

CREATE INDEX ix_outbox_unpublished ON outbox_event (created_seq) WHERE published_at IS NULL AND parked_at IS NULL;
CREATE INDEX ix_outbox_parked ON outbox_event (parked_at) WHERE parked_at IS NOT NULL;
CREATE INDEX ix_outbox_published_at ON outbox_event (published_at) WHERE published_at IS NOT NULL;
CREATE INDEX ix_outbox_park_uncounted ON outbox_event (created_seq) WHERE parked_at IS NOT NULL AND NOT park_counted;
CREATE INDEX ix_outbox_parked_aggregate ON outbox_event (aggregate_id) WHERE parked_at IS NOT NULL;

COMMENT ON TABLE outbox_event IS 'Pending and recently published account events; purged after accounts.outbox.retention.';
COMMENT ON COLUMN outbox_event.parked_at IS
    'Set when the relay parked the row for a payload error, or an operator parked it. The relay skips the row and holds back the later rows of the same aggregate_id until it is replayed (parked_at cleared).';
COMMENT ON COLUMN outbox_event.park_reason IS
    'Why the row is parked: "payload error: <exception>" from the relay, or the operator''s recorded reason.';
COMMENT ON COLUMN outbox_event.park_counted IS
    'True once the park was counted in outbox_parked_events_total; reset with parked_at on a replay.';
