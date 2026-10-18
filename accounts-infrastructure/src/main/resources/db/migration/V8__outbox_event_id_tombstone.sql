-- Used ids outlive the purge (DevSecOps on bb0728b). The purge deletes published rows, and the
-- service role could then insert the same event_id again, park it and have it discarded: an audit
-- calling a delivered event never published.
--   - outbox_event_tombstone: one row per event id that must never be reused. It is written when
--     the relay records sent_while_parked_at (AFTER UPDATE; the row is still live, so its key holds
--     the id meanwhile) and BEFORE each DELETE, so even a delete and a re-insert of the same id in
--     one statement (writable CTE, MERGE) finds the tombstone. The function is SECURITY DEFINER,
--     owned by the schema owner: the runtime role only reads the table, so it cannot forge or
--     pre-empt a tombstone.
--   - outbox_discard_guard refuses inserting an outbox row whose id has a tombstone, as it does for
--     an audited (discarded) id. It first waits for a purge of the same id still in flight.
--   - Existing records are copied in under a lock that holds off the relay's mark until this
--     migration commits.
--   - The column comment now says what V7 made true: write-once, never cleared.
-- outbox_discard_guard is replaced (V7 body plus the tombstone check); its triggers are unchanged.

SET LOCAL lock_timeout = '5s';
LOCK TABLE outbox_event IN SHARE ROW EXCLUSIVE MODE;

CREATE TABLE outbox_event_tombstone (
    event_id    UUID        PRIMARY KEY,
    recorded_at TIMESTAMPTZ NOT NULL,
    cause       TEXT        NOT NULL CHECK (cause IN ('SENT_WHILE_PARKED', 'PURGED', 'PURGED_DISCARDED'))
);

COMMENT ON TABLE outbox_event_tombstone IS
    'Event ids that must never be reused. SENT_WHILE_PARKED: the relay delivered the row to Kafka while it was parked. PURGED: the purge deleted a row the relay published, so Kafka has it. PURGED_DISCARDED: the purge deleted a discarded row (never sent; its outbox_discard_audit row says so). Never purged; written only by the outbox_event_tombstone_write triggers, read-only for the runtime role. outbox_discard_guard refuses an outbox row with such an id.';

INSERT INTO outbox_event_tombstone (event_id, recorded_at, cause)
SELECT event_id, sent_while_parked_at, 'SENT_WHILE_PARKED' FROM outbox_event WHERE sent_while_parked_at IS NOT NULL
ON CONFLICT (event_id) DO NOTHING;

COMMENT ON COLUMN outbox_event.sent_while_parked_at IS
    'When the relay delivered this row to Kafka although an operator had parked it during the send. Set only by the service role, once, on a parked unpublished row; write-once, never cleared, kept by a replay (V7). While set, the row cannot be discarded; its id is also kept in outbox_event_tombstone (V8).';

CREATE OR REPLACE FUNCTION outbox_discard_guard() RETURNS trigger
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
        IF NEW.sent_while_parked_at IS NOT NULL THEN
            RAISE EXCEPTION 'sent_while_parked_at is set only by the relay, on a parked row'
                USING ERRCODE = 'check_violation';
        END IF;
        -- Wait for a purge (or mark) of this id that is still in flight, so the checks below see
        -- the tombstone it writes; the unique key alone would let the insert in once it commits.
        EXECUTE format('SELECT 1 FROM %I.outbox_event WHERE event_id = $1 FOR KEY SHARE', TG_TABLE_SCHEMA)
            USING NEW.event_id;
        -- The audit row is keyed by event id: a discarded id must not come back as a new event.
        EXECUTE format('SELECT 1 FROM %I.outbox_discard_audit WHERE event_id = $1', TG_TABLE_SCHEMA)
            USING NEW.event_id;
        GET DIAGNOSTICS audited = ROW_COUNT;
        IF audited > 0 THEN
            RAISE EXCEPTION 'outbox event id % was discarded and cannot be reused', NEW.event_id
                USING ERRCODE = 'unique_violation';
        END IF;
        -- Nor may an id that was delivered or purged (outbox_event_tombstone, V8) come back.
        EXECUTE format('SELECT 1 FROM %I.outbox_event_tombstone WHERE event_id = $1', TG_TABLE_SCHEMA)
            USING NEW.event_id;
        GET DIAGNOSTICS audited = ROW_COUNT;
        IF audited > 0 THEN
            RAISE EXCEPTION 'outbox event id % was already sent to Kafka and cannot be reused', NEW.event_id
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

    -- The event id is the consumers' de-duplication key and the audit's key: it never changes.
    IF NEW.event_id IS DISTINCT FROM OLD.event_id THEN
        RAISE EXCEPTION 'outbox event %: the event id cannot change', OLD.event_id
            USING ERRCODE = 'check_violation';
    END IF;

    -- The relay's record that Kafka has a row an operator parked during its send. Only the
    -- service role sets it, once, on a parked unpublished row; nobody clears it. A replay leaves
    -- it set and the relay then publishes the row normally; while the row is unpublished it can
    -- never be discarded, however often it is parked again.
    IF NEW.sent_while_parked_at IS DISTINCT FROM OLD.sent_while_parked_at THEN
        IF OLD.sent_while_parked_at IS NOT NULL THEN
            RAISE EXCEPTION 'outbox event %: sent_while_parked_at is write-once and cannot be cleared or changed',
                OLD.event_id USING ERRCODE = 'check_violation';
        END IF;
        IF session_user <> runtime_role OR OLD.parked_at IS NULL OR NEW.parked_at IS NULL
                OR OLD.published_at IS NOT NULL THEN
            RAISE EXCEPTION 'outbox event %: sent_while_parked_at is set only by the relay, once, on a parked row',
                OLD.event_id USING ERRCODE = 'insufficient_privilege';
        END IF;
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
            IF NEW.sent_while_parked_at IS NOT NULL THEN
                RAISE EXCEPTION 'outbox event % was already sent to Kafka; replay it instead of discarding it',
                    OLD.event_id USING ERRCODE = 'check_violation';
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


-- Runs as the schema owner, so the runtime role needs no write privilege on the tombstone. A
-- trigger function cannot be called directly; EXECUTE is revoked from PUBLIC all the same.
CREATE OR REPLACE FUNCTION outbox_event_tombstone_write() RETURNS trigger
    LANGUAGE plpgsql
    SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
    audited integer;
BEGIN
    IF TG_OP = 'DELETE' THEN
        -- BEFORE DELETE, after trg_outbox_discard_guard (triggers fire in name order), which lets
        -- only published rows be deleted: sent by the relay, or discarded with an audit row.
        EXECUTE format('SELECT 1 FROM %I.outbox_discard_audit WHERE event_id = $1', TG_TABLE_SCHEMA)
            USING OLD.event_id;
        GET DIAGNOSTICS audited = ROW_COUNT;
        EXECUTE format('INSERT INTO %I.outbox_event_tombstone (event_id, recorded_at, cause)'
                       ' VALUES ($1, now(), $2) ON CONFLICT (event_id) DO NOTHING', TG_TABLE_SCHEMA)
            USING OLD.event_id, CASE WHEN audited > 0 THEN 'PURGED_DISCARDED' ELSE 'PURGED' END;
        RETURN OLD;
    END IF;
    IF OLD.sent_while_parked_at IS NULL AND NEW.sent_while_parked_at IS NOT NULL THEN
        EXECUTE format('INSERT INTO %I.outbox_event_tombstone (event_id, recorded_at, cause)'
                       ' VALUES ($1, $2, ''SENT_WHILE_PARKED'') ON CONFLICT (event_id) DO NOTHING', TG_TABLE_SCHEMA)
            USING NEW.event_id, NEW.sent_while_parked_at;
    END IF;
    RETURN NEW;
END
$$;

REVOKE ALL ON FUNCTION outbox_event_tombstone_write() FROM PUBLIC;

-- Before the row goes: an AFTER DELETE trigger fires at the end of the statement, too late for an
-- insert of the same id later in that statement.
CREATE TRIGGER trg_outbox_event_tombstone
    BEFORE DELETE ON outbox_event
    FOR EACH ROW EXECUTE FUNCTION outbox_event_tombstone_write();

-- After the guard allowed the relay's mark; the live row's key holds the id until the commit.
CREATE TRIGGER trg_outbox_event_tombstone_mark
    AFTER UPDATE ON outbox_event
    FOR EACH ROW EXECUTE FUNCTION outbox_event_tombstone_write();


DO $$
DECLARE
    runtime_role text := '${runtime_role}';
BEGIN
    EXECUTE 'REVOKE ALL ON TABLE outbox_event_tombstone FROM PUBLIC';
    IF runtime_role = current_user THEN
        RAISE NOTICE 'runtime role % is the schema owner (single-user run): privileges not separated', runtime_role;
        RETURN;
    END IF;
    EXECUTE format('GRANT SELECT ON TABLE outbox_event_tombstone TO %I', runtime_role);
END
$$;
