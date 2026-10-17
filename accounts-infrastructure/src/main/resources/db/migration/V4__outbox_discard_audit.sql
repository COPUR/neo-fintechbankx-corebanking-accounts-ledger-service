-- Operator discards of parked outbox rows (docs/RUNBOOK.md, Outbox). An operator
-- requests one in SQL (UPDATE: discard_requested_at, discard_reason); the relay,
-- under its lock, inserts its outbox_discard_audit row, and that insert is the discard:
-- the audit triggers lock and copy the requested row and mark it published (never sent),
-- so an audit row exists exactly when its discard happened. The relay counts it in
-- outbox_discarded_events_total. The audit row outlives the outbox purge
-- (accounts.outbox.retention) and is insert-only for the runtime role, like the journal.
--
-- The service's own role (${runtime_role}) can neither forge nor steer a discard:
--   - trg_outbox_discard_guard (every INSERT, UPDATE and DELETE of outbox_event) refuses an
--     INSERT carrying a request or reusing a discarded event id; lets only other logins request,
--     change or withdraw one, and stamps who (session_user) and when (now()) itself; while a
--     request is pending, freezes every column the audit copies and the event id; lets a parked
--     row be marked published only through its matching audit row; refuses the service role
--     unparking a row (only an operator's replay does, and the relay then sends the row), and
--     every other login publishing one (only the relay publishes); refuses any change to a
--     published row; and refuses deleting a row that was never published (the purge deletes
--     published rows only). A parked row therefore leaves the outbox unsent only as an audited
--     discard, whichever login and however many statements try.
--   - trg_outbox_discard_audit_derive locks the requested outbox row (FOR UPDATE, so a
--     concurrent withdraw or re-request either commits first and is seen, or waits and then
--     finds the row published) and fills every audit column from it; it refuses an audit
--     row without a pending request. trg_outbox_discard_audit_mark marks that row published.
-- The audit copies the event envelope: account, holder and journal entry ids, amounts and
-- balances, no names or contact data.

-- Fail fast instead of queueing behind the service's own transactions.
SET LOCAL lock_timeout = '5s';

ALTER TABLE outbox_event
    ADD COLUMN discard_requested_at TIMESTAMPTZ,
    ADD COLUMN discard_reason       VARCHAR(512),
    ADD COLUMN discard_requested_by VARCHAR(128);

-- The columns are new, so no row can fail the check; it is added in the same short
-- ACCESS EXCLUSIVE window as the columns, which lock_timeout bounds.
ALTER TABLE outbox_event
    ADD CONSTRAINT ck_outbox_discard_reason
        CHECK (discard_requested_at IS NULL OR length(btrim(coalesce(discard_reason, ''))) > 0);

COMMENT ON COLUMN outbox_event.discard_requested_at IS
    'Set by an operator (UPDATE) to discard a parked row; the relay audits it in outbox_discard_audit and marks it published.';
COMMENT ON COLUMN outbox_event.discard_requested_by IS
    'Database login that requested the discard; set by trg_outbox_discard_guard from session_user, never from the statement.';

CREATE FUNCTION outbox_discard_guard() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    runtime_role constant text := '${runtime_role}';
    is_runtime boolean;
    request_changed boolean;
    frozen_changed boolean;
    pending boolean;
    audited integer;
BEGIN
    -- The service's role, unless this is a single-user run where it also owns the table.
    is_runtime := session_user = runtime_role
        AND runtime_role <> (SELECT pg_get_userbyid(c.relowner) FROM pg_class c WHERE c.oid = TG_RELID);

    IF TG_OP = 'INSERT' THEN
        IF NEW.discard_requested_at IS NOT NULL OR NEW.discard_reason IS NOT NULL
                OR NEW.discard_requested_by IS NOT NULL THEN
            RAISE EXCEPTION 'outbox discard can only be requested by UPDATE of a parked row'
                USING ERRCODE = 'check_violation';
        END IF;
        -- The audit row is keyed by event id: a discarded id must not come back as a new event.
        EXECUTE format('SELECT 1 FROM %I.outbox_discard_audit WHERE event_id = $1', TG_TABLE_SCHEMA)
            USING NEW.event_id;
        GET DIAGNOSTICS audited = ROW_COUNT;
        IF audited > 0 THEN
            RAISE EXCEPTION 'outbox event id % was discarded and cannot be reused', NEW.event_id
                USING ERRCODE = 'unique_violation';
        END IF;
        RETURN NEW;
    END IF;

    IF TG_OP = 'DELETE' THEN
        IF OLD.published_at IS NULL THEN
            RAISE EXCEPTION 'outbox event % was never published and cannot be deleted', OLD.event_id
                USING ERRCODE = 'check_violation';
        END IF;
        RETURN OLD;
    END IF;

    pending := OLD.discard_requested_at IS NOT NULL AND OLD.published_at IS NULL;
    request_changed := NEW.discard_requested_at IS DISTINCT FROM OLD.discard_requested_at
        OR NEW.discard_reason IS DISTINCT FROM OLD.discard_reason
        OR NEW.discard_requested_by IS DISTINCT FROM OLD.discard_requested_by;

    IF request_changed THEN
        IF is_runtime THEN
            RAISE EXCEPTION 'the service role cannot request, change or withdraw an outbox discard'
                USING ERRCODE = 'insufficient_privilege';
        END IF;
        IF OLD.published_at IS NOT NULL THEN
            RAISE EXCEPTION 'outbox event % is already published', OLD.event_id
                USING ERRCODE = 'check_violation';
        END IF;
        IF NEW.discard_requested_at IS NULL THEN
            NEW.discard_reason := NULL;
            NEW.discard_requested_by := NULL;
        ELSE
            IF NEW.parked_at IS NULL THEN
                RAISE EXCEPTION 'only a parked outbox event can be discarded' USING ERRCODE = 'check_violation';
            END IF;
            NEW.discard_requested_at := now();
            NEW.discard_requested_by := session_user;
        END IF;
    END IF;

    -- A published row (sent, or discarded with its audit row) is final until the purge deletes it.
    IF OLD.published_at IS NOT NULL AND NEW IS DISTINCT FROM OLD THEN
        RAISE EXCEPTION 'outbox event % is published; a published row cannot change', OLD.event_id
            USING ERRCODE = 'check_violation';
    END IF;

    frozen_changed := NEW.event_id IS DISTINCT FROM OLD.event_id
        OR NEW.aggregate_type IS DISTINCT FROM OLD.aggregate_type
        OR NEW.aggregate_id IS DISTINCT FROM OLD.aggregate_id
        OR NEW.aggregate_version IS DISTINCT FROM OLD.aggregate_version
        OR NEW.event_type IS DISTINCT FROM OLD.event_type
        OR NEW.payload IS DISTINCT FROM OLD.payload
        OR NEW.occurred_at IS DISTINCT FROM OLD.occurred_at
        OR NEW.park_reason IS DISTINCT FROM OLD.park_reason;

    IF OLD.published_at IS NULL AND NEW.published_at IS NOT NULL AND OLD.parked_at IS NOT NULL THEN
        -- A parked row is never sent, so it leaves the outbox only as an audited discard: the
        -- mark made by trg_outbox_discard_audit_mark (published_at set, parked_at cleared,
        -- nothing else), for a pending request whose audit row names this very request.
        EXECUTE format('SELECT 1 FROM %I.outbox_discard_audit WHERE event_id = $1 AND requested_by = $2'
                       ' AND requested_at = $3 AND discard_reason = $4', TG_TABLE_SCHEMA)
            USING OLD.event_id, OLD.discard_requested_by, OLD.discard_requested_at, OLD.discard_reason;
        GET DIAGNOSTICS audited = ROW_COUNT;  -- EXECUTE does not set FOUND
        IF NOT pending OR audited = 0 OR request_changed OR frozen_changed OR NEW.parked_at IS NOT NULL THEN
            RAISE EXCEPTION 'outbox event % is parked; it leaves the outbox only as an audited discard', OLD.event_id
                USING ERRCODE = 'check_violation';
        END IF;
    ELSIF OLD.published_at IS NULL AND NEW.published_at IS NOT NULL AND session_user <> runtime_role THEN
        -- Only the relay (the service role) publishes, and only rows that are not parked.
        RAISE EXCEPTION 'outbox event %: only the service role publishes outbox events', OLD.event_id
            USING ERRCODE = 'insufficient_privilege';
    ELSIF is_runtime AND OLD.parked_at IS NOT NULL AND NEW.parked_at IS NULL THEN
        -- The relay never unparks; a replay is an operator's decision (docs/RUNBOOK.md).
        RAISE EXCEPTION 'outbox event %: the service role cannot unpark an outbox event', OLD.event_id
            USING ERRCODE = 'insufficient_privilege';
    ELSIF pending AND NEW.discard_requested_at IS NOT NULL THEN
        -- Still pending after this statement: what the audit will copy cannot change, and a
        -- replay (parked_at cleared) must withdraw the request in the same statement.
        IF frozen_changed OR NEW.parked_at IS DISTINCT FROM OLD.parked_at THEN
            RAISE EXCEPTION 'outbox event % has a pending discard request; withdraw it before changing the row',
                OLD.event_id USING ERRCODE = 'check_violation';
        END IF;
    END IF;
    -- Otherwise no request was pending, or another login withdrew it in this statement
    -- (refused above for the service role).
    RETURN NEW;
END
$$;

CREATE TRIGGER trg_outbox_discard_guard
    BEFORE INSERT OR UPDATE OR DELETE ON outbox_event
    FOR EACH ROW
    EXECUTE FUNCTION outbox_discard_guard();

CREATE INDEX ix_outbox_discard_requested ON outbox_event (created_seq)
    WHERE discard_requested_at IS NOT NULL AND published_at IS NULL;

CREATE TABLE outbox_discard_audit (
    event_id          UUID          PRIMARY KEY,
    aggregate_type    VARCHAR(64)   NOT NULL,
    aggregate_id      VARCHAR(64)   NOT NULL,
    aggregate_version BIGINT        NOT NULL,
    event_type        VARCHAR(128)  NOT NULL,
    payload           JSONB         NOT NULL,
    occurred_at       TIMESTAMPTZ   NOT NULL,
    parked_at         TIMESTAMPTZ   NOT NULL,
    park_reason       VARCHAR(512),
    discard_reason    VARCHAR(512)  NOT NULL,
    requested_by      VARCHAR(128)  NOT NULL,
    requested_at      TIMESTAMPTZ   NOT NULL,
    discarded_at      TIMESTAMPTZ   NOT NULL
);

COMMENT ON TABLE outbox_discard_audit IS
    'Account events an operator discarded (never published); kept after the outbox purge. Insert-only for the runtime role; every column is derived from the requested outbox row.';

-- Whatever the INSERT supplies besides event_id is replaced from the outbox row, which must be
-- parked, unpublished and carry a discard request; anything else is refused.
CREATE FUNCTION outbox_discard_audit_derive() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    requested record;
    found_rows integer;
BEGIN
    -- FOR UPDATE: a concurrent withdraw or re-request commits first and is seen here, or waits
    -- for this transaction and then finds the row published.
    EXECUTE format('SELECT * FROM %I.outbox_event WHERE event_id = $1'
                   ' AND discard_requested_at IS NOT NULL AND parked_at IS NOT NULL AND published_at IS NULL'
                   ' FOR UPDATE', TG_TABLE_SCHEMA)
        INTO requested USING NEW.event_id;
    GET DIAGNOSTICS found_rows = ROW_COUNT;  -- EXECUTE does not set FOUND
    IF found_rows = 0 THEN
        RAISE EXCEPTION 'outbox event % has no pending discard request', NEW.event_id
            USING ERRCODE = 'check_violation';
    END IF;
    NEW.aggregate_type    := requested.aggregate_type;
    NEW.aggregate_id      := requested.aggregate_id;
    NEW.aggregate_version := requested.aggregate_version;
    NEW.event_type        := requested.event_type;
    NEW.payload           := requested.payload;
    NEW.occurred_at       := requested.occurred_at;
    NEW.parked_at         := requested.parked_at;
    NEW.park_reason       := requested.park_reason;
    NEW.discard_reason    := requested.discard_reason;
    NEW.requested_by      := requested.discard_requested_by;
    NEW.requested_at      := requested.discard_requested_at;
    NEW.discarded_at      := now();
    RETURN NEW;
END
$$;

CREATE TRIGGER trg_outbox_discard_audit_derive
    BEFORE INSERT ON outbox_discard_audit
    FOR EACH ROW
    EXECUTE FUNCTION outbox_discard_audit_derive();

-- The audit row is the discard: the row it copied leaves the outbox in the same statement.
CREATE FUNCTION outbox_discard_audit_mark() RETURNS trigger
    LANGUAGE plpgsql
    SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    marked integer;
BEGIN
    EXECUTE format('UPDATE %I.outbox_event SET published_at = $2, parked_at = NULL WHERE event_id = $1',
                   TG_TABLE_SCHEMA)
        USING NEW.event_id, NEW.discarded_at;
    GET DIAGNOSTICS marked = ROW_COUNT;
    IF marked <> 1 THEN
        RAISE EXCEPTION 'outbox event % could not be marked discarded', NEW.event_id
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END
$$;

CREATE TRIGGER trg_outbox_discard_audit_mark
    AFTER INSERT ON outbox_discard_audit
    FOR EACH ROW
    EXECUTE FUNCTION outbox_discard_audit_mark();

DO $$
DECLARE
    runtime_role text := '${runtime_role}';
BEGIN
    IF runtime_role = current_user THEN
        RAISE NOTICE 'runtime role % is the schema owner (single-user run): privileges not separated', runtime_role;
        RETURN;
    END IF;
    EXECUTE format('REVOKE ALL ON TABLE outbox_discard_audit FROM PUBLIC');
    EXECUTE format('GRANT SELECT, INSERT ON TABLE outbox_discard_audit TO %I', runtime_role);
END
$$;
