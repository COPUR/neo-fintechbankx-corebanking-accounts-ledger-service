-- The relay's "sent while parked" record gets its own column (DevSecOps on f11fdce). V5 kept it
-- in last_error, which operators and the service role may clear, so clearing it re-enabled a
-- false discard. sent_while_parked_at is set only by the service role, on a parked unpublished
-- row, in the same UPDATE that finds the row parked after its send
-- (SpringDataOutboxRepository.markSent), and cleared only by a replay that unparks the row in the
-- same statement. outbox_discard_guard refuses a discard request while it is set, and
-- outbox_discard_audit_derive refuses the audit row; the relay's discard batch skips such rows.
-- Both functions are replaced in place; the triggers are unchanged.

SET LOCAL lock_timeout = '5s';

ALTER TABLE outbox_event ADD COLUMN sent_while_parked_at TIMESTAMPTZ;

COMMENT ON COLUMN outbox_event.sent_while_parked_at IS
    'When the relay delivered this row to Kafka although an operator had parked it during the send; set only by the service role, cleared only by a replay. While set, the row cannot be discarded.';

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

    -- The event id is the consumers' de-duplication key and the audit's key: it never changes.
    IF NEW.event_id IS DISTINCT FROM OLD.event_id THEN
        RAISE EXCEPTION 'outbox event %: the event id cannot change', OLD.event_id
            USING ERRCODE = 'check_violation';
    END IF;

    -- The relay's record that Kafka has a row an operator parked during its send. Only the
    -- service role sets it, on a parked unpublished row; it is cleared only by a replay that
    -- unparks the row in the same statement. While set, the row cannot be discarded.
    IF NEW.sent_while_parked_at IS DISTINCT FROM OLD.sent_while_parked_at THEN
        IF NEW.sent_while_parked_at IS NOT NULL THEN
            IF session_user <> runtime_role OR OLD.sent_while_parked_at IS NOT NULL
                    OR OLD.parked_at IS NULL OR NEW.parked_at IS NULL OR OLD.published_at IS NOT NULL THEN
                RAISE EXCEPTION 'outbox event %: sent_while_parked_at is set only by the relay, once, on a parked row',
                    OLD.event_id USING ERRCODE = 'insufficient_privilege';
            END IF;
        ELSIF NOT (OLD.parked_at IS NOT NULL AND NEW.parked_at IS NULL) THEN
            RAISE EXCEPTION 'outbox event %: sent_while_parked_at is cleared only by a replay that unparks the row',
                OLD.event_id USING ERRCODE = 'check_violation';
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

CREATE OR REPLACE FUNCTION outbox_discard_audit_derive() RETURNS trigger
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
                   ' AND sent_while_parked_at IS NULL'
                   ' FOR UPDATE', TG_TABLE_SCHEMA)
        INTO requested USING NEW.event_id;
    GET DIAGNOSTICS found_rows = ROW_COUNT;  -- EXECUTE does not set FOUND
    IF found_rows = 0 THEN
        RAISE EXCEPTION 'outbox event % has no pending discard request (or was already sent)', NEW.event_id
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
