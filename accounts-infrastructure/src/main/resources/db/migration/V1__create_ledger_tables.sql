-- Accounts ledger (svc-acc-accounts-ledger), schema sc_acc_accounts_ledger.
-- Balances move only through journal entries; a posting line is never updated
-- or deleted, and every entry's debits equal its credits (checked in the
-- domain; the DB keeps the shape honest with the constraints below).

CREATE TABLE account (
    account_id        VARCHAR(64)    PRIMARY KEY,
    category          VARCHAR(16)    NOT NULL,
    holder_id         VARCHAR(64),
    account_type      VARCHAR(16)    NOT NULL,
    currency          VARCHAR(3)     NOT NULL,
    status            VARCHAR(16)    NOT NULL,
    islamic_compliant BOOLEAN        NOT NULL DEFAULT false,
    freeze_reason     VARCHAR(256),
    booked_balance    NUMERIC(19, 4) NOT NULL,
    -- Idempotency-Key of the open request; a replay returns this account.
    open_request_key  VARCHAR(128)   NOT NULL,
    opened_at         TIMESTAMPTZ    NOT NULL,
    updated_at        TIMESTAMPTZ    NOT NULL,
    version           BIGINT         NOT NULL,

    CONSTRAINT uq_account_open_request_key UNIQUE (open_request_key),
    CONSTRAINT ck_account_category CHECK (category IN ('CUSTOMER', 'INTERNAL')),
    CONSTRAINT ck_account_status CHECK (status IN ('ACTIVE', 'FROZEN', 'CLOSED')),
    CONSTRAINT ck_account_holder CHECK ((category = 'CUSTOMER') = (holder_id IS NOT NULL)),
    CONSTRAINT ck_account_internal_type CHECK ((category = 'INTERNAL') = (account_type = 'INTERNAL')),
    CONSTRAINT ck_account_customer_not_negative CHECK (category = 'INTERNAL' OR booked_balance >= 0)
);

CREATE INDEX ix_account_holder ON account (holder_id) WHERE holder_id IS NOT NULL;

CREATE TABLE journal_entry (
    journal_entry_id  VARCHAR(64)    PRIMARY KEY,
    -- The caller's business key (payment, mandate collection): one booking each.
    reference         VARCHAR(128)   NOT NULL,
    currency          VARCHAR(3)     NOT NULL,
    narrative         VARCHAR(256),
    booked_at         TIMESTAMPTZ    NOT NULL,

    CONSTRAINT uq_journal_entry_reference UNIQUE (reference)
);

CREATE TABLE posting_line (
    journal_entry_id  VARCHAR(64)    NOT NULL REFERENCES journal_entry (journal_entry_id),
    line_no           INTEGER        NOT NULL,
    account_id        VARCHAR(64)    NOT NULL REFERENCES account (account_id),
    side              VARCHAR(6)     NOT NULL,
    amount            NUMERIC(19, 4) NOT NULL,

    PRIMARY KEY (journal_entry_id, line_no),
    CONSTRAINT ck_posting_line_side CHECK (side IN ('DEBIT', 'CREDIT')),
    CONSTRAINT ck_posting_line_positive CHECK (amount > 0)
);

-- An account's postings in booking order (statement reads).
CREATE INDEX ix_posting_line_account ON posting_line (account_id, journal_entry_id);
