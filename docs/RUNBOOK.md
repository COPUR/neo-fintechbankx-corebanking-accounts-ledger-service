# Accounts ledger runbook

Service `svc-acc-accounts-ledger`, namespace `corebanking`, squad label `corebanking`, chart
`deploy/helm/accounts-ledger-service`. Platform alert runbooks live in
COPUR/fintechbankx-platform-observability-sre-operations `docs/runbooks/`; this file holds the
ledger's own procedures they point to.

## Outbox

Every account change (open, freeze, unfreeze, close, each posting line) writes its event to
`sc_acc_accounts_ledger.outbox_event` in the same transaction. `OutboxRelay` publishes the rows to
`evt.acc.account.v1` in `created_seq` order, keyed by the account id, with the `eventType`,
`eventId` and `correlationId` headers (ADR-019). Delivery is at least once: a row whose send
succeeded but whose published mark did not commit is sent again, and consumers de-duplicate on
`eventId`.

### Relay switch

- The relay runs only with Helm `config.OUTBOX_RELAY_ENABLED: "true"` (property
  `accounts.outbox.relay.enabled`). The chart default is `"false"`.
- Turn it on in an environment only after Platform has applied the topic `evt.acc.account.v1`, its
  ACLs and the MSK policy for the service's role. Otherwise every send fails and the relay backs off.
- With the relay off:
  - rows accumulate in `outbox_event` and nothing purges them (the purge runs with the relay);
  - the service exports no outbox gauges, so OutboxRelayStalled, OutboxBacklogGrowing and
    OutboxParkedSignalMissing stay quiet for the ledger. That is on purpose: a relay that is off by
    decision must not page the squad.
- When the relay is first turned on it publishes the whole backlog, oldest first, so consumers
  receive every event written since the deployment.
- Exactly one replica publishes at a time. It holds the session-level Postgres advisory lock
  `0x6163635F6F7574` ("acc_out"). The other replicas skip their runs.

### Metrics

Exported only while the relay is on:

| Metric | Meaning |
|---|---|
| `outbox_oldest_pending_age_seconds` | Age of the oldest unpublished, unparked row. OutboxRelayStalled pages above 900 s for 5 min. |
| `outbox_pending_events` | Unpublished, unparked rows. OutboxBacklogGrowing warns above 100 while growing. |
| `outbox_parked_rows` | Rows parked right now. |
| `outbox_send_failures_total{exception}` | Failed sends by exception class. The relay backs off from 1 s, doubling, to at most 5 min. Nothing is marked. |
| `outbox_parked_events_total{exception}` | Parks: a payload error class, or `OperatorPark`. OutboxEventsParked fires on any increase. |
| `outbox_discarded_events_total` | Parked rows discarded at an operator's request (audited in `outbox_discard_audit`). No platform alert yet. |
| `outbox_discard_pending_age_seconds` | Age of the oldest discard request the relay has not carried out. It should fall back to 0 within one relay run; if it climbs, a session holds the row locked (the relay skips locked rows), the relay is off, or the row was sent while parked (see below). No platform alert yet. |
| `outbox_sent_while_parked_total` | Rows an operator parked while the relay was sending them: Kafka has the event, the row stays parked with `sent_while_parked_at` set. Replay them. |

### Diagnose

Run the queries as the schema owner (the migration role), from the DBA bastion, under the incident
ticket. The runtime role can do the same, but its credentials stay with the pods.

```sql
-- Oldest pending rows, and what blocks them
select event_id, aggregate_id, event_type, occurred_at, attempts, last_error
from sc_acc_accounts_ledger.outbox_event
where published_at is null and parked_at is null
order by created_seq limit 20;

-- Parked rows: each one holds back every later row of its account
select event_id, aggregate_id, event_type, parked_at, park_reason, last_error
from sc_acc_accounts_ledger.outbox_event
where parked_at is not null
order by created_seq;
```

- **Age is climbing and `outbox_send_failures_total` is rising.** Kafka, the network, the ACLs or the
  IAM policy is failing. The `exception` label and the warning log
  `Outbox relay send failed (<class>, retryable=...)` say which. Fix the cause; the relay resumes on
  its own after its backoff.
- **Age is climbing with no send failures.** Check that the relay is on in this environment (see
  Relay switch above). Then check who holds the relay lock:
  `select l.pid, a.client_addr, a.backend_start, a.state from pg_locks l join pg_stat_activity a using (pid)
  where l.locktype = 'advisory' and l.classid = 6382435 and l.objid = 1601140084 and l.granted;`
  Restarting that pod (`kubectl -n corebanking delete pod <pod>`) ends its session and releases the
  lock.
- **Age is climbing for one account only.** A parked row of that account holds back its later rows.
  Publishing them out of order would let a consumer replay Debited and Credited in the wrong booking
  order, so the relay waits. Replay or discard the parked row (below); a restart does not help.

### Operator park, replay and discard

Each of these changes publication for one account. The corebanking squad decides on them, and the
change ticket records the event id, the reason and who approved it. Never delete outbox rows by
hand: the purge removes published rows after `accounts.outbox.retention` (7 days). The database
refuses deleting a row that was never published, for every login.

```sql
-- Park a row the relay keeps failing on (only a payload error parks by itself, ADR-021 decision 4).
-- The relay counts it once as outbox_parked_events_total{exception="OperatorPark"}. If the relay is
-- sending the row at that moment, the send completes, the row stays parked, and the relay's mark
-- sets sent_while_parked_at (and a "sent while parked" note in last_error) instead of published_at.
update sc_acc_accounts_ledger.outbox_event
set parked_at = now(), park_reason = '<ticket>: <reason>'
where event_id = '<event id>' and published_at is null and parked_at is null;

-- Replay: the relay picks the row up again in its original place, then the account's later rows.
-- A row parked for a payload error parks again unless its cause (topic config, record size) was fixed.
update sc_acc_accounts_ledger.outbox_event
set parked_at = null, park_reason = null, park_counted = false, last_error = null
where event_id = '<event id>' and parked_at is not null;

-- Before a discard, check sent_while_parked_at: set means the relay's mark found the row parked
-- after it had delivered it to Kafka. Replay that row (consumers de-duplicate on eventId). The
-- database refuses discarding a row with the column set, because the audit would call a delivered
-- event never published. The column is write-once: only the relay sets it, nobody clears it, and
-- a replay keeps it (the relay then publishes the row as usual). It is only as good as the mark:
-- if the relay stops between the send and its mark, the row carries no record, so before a
-- discard also check with the consumers' squads that they never received the event.
-- Discard: consumers never receive this event. Only with the consumers' squads (payments, lending)
-- agreed in the ticket, since a missing Debited or Credited event leaves their view of the
-- balance wrong. This only requests it (a reason is required); the relay carries it out on its
-- next run (below).
update sc_acc_accounts_ledger.outbox_event
set discard_requested_at = now(), discard_reason = '<ticket>: <who agreed, why>'
where event_id = '<event id>' and parked_at is not null and published_at is null;
```

On its next run, the replica that holds the relay lock carries out every requested discard in
one transaction. It inserts the row's `sc_acc_accounts_ledger.outbox_discard_audit` row, and that
insert is the discard: an audit row exists exactly when its discard happened.

- The audit row copies the outbox row, with the requesting database login and time. Both always
  come from the database, never from a value a statement supplies:
  - The trigger `trg_outbox_discard_guard` stamps `session_user` and `now()` on the UPDATE that
    requests the discard. A backdated `discard_requested_at` is overwritten.
  - The same trigger refuses an INSERT that already carries a discard request.
  - The same trigger refuses the service's own runtime role when it tries to request, change or
    withdraw a discard.
  - While a request is pending, the same trigger freezes everything the audit copies (event id,
    aggregate, event type, payload, occurred_at, park reason) and the park itself.
  - A parked row leaves the outbox unsent only as an audited discard, however many statements
    try. The same trigger marks a parked row published only through its matching audit row,
    refuses the service's runtime role unparking a row (the relay never unparks; a replay is an
    operator's step), and refuses every login except the runtime role publishing a row. A published
    row cannot change at all until the purge deletes it.
  - An event id never changes, and a discarded event id cannot be inserted again after the purge.
  - A discard cannot be requested, or carried out, for a row with `sent_while_parked_at` set. Only
    the runtime role sets it (in the relay's mark, on a parked row); it is write-once, so a replay
    followed by a new park still cannot be discarded.
  - The trigger `trg_outbox_discard_audit_derive` locks the requested outbox row and fills every
    audit column from it. It refuses an audit row for an event that has no pending request. A
    withdrawal or a new request that is still in flight makes a direct insert wait; the audit then
    names the request as it stands after that commit, or is refused if it was withdrawn. The relay
    skips a request that an open transaction holds locked and takes it on a later run, so an
    operator's open transaction never stalls the relay.
  - The requester is therefore whoever logged in. Operators must use personal database logins, not
    the shared owner or runtime credentials.

Operator logins get only what these steps need (Platform grants them per environment):

```sql
grant usage on schema sc_acc_accounts_ledger to <operator>;
grant select on sc_acc_accounts_ledger.outbox_event, sc_acc_accounts_ledger.outbox_discard_audit to <operator>;
grant update (parked_at, park_reason, park_counted, last_error, discard_requested_at, discard_reason)
    on sc_acc_accounts_ledger.outbox_event to <operator>;
```

No `published_at` or `sent_while_parked_at`, no INSERT or DELETE, nothing on the audit table but
SELECT. The guard refuses an operator publishing a row or writing `sent_while_parked_at` anyway;
the column grant keeps that true without it.
- The trigger `trg_outbox_discard_audit_mark` then marks the row published without sending it and
  clears `parked_at`, so the account's later events go out.
- It counts the discard in `outbox_discarded_events_total` and logs a warning.

The audit row stays after the outbox purge. The runtime role may only read and insert audit rows,
never update or delete them. The audit copies the event envelope: account, holder and journal
entry ids, amounts and balances, but no names or contact data.

To withdraw a request before the relay runs, add `discard_requested_at = null` to the replay
statement. To keep the row parked, set only `discard_requested_at = null`. The trigger clears the
reason and the requester. While the request is pending, the replay statement alone is refused.
Once the relay has run, the discard is done: the row is published and can no longer be withdrawn,
changed or replayed.

```sql
-- Discards, newest first
select event_id, aggregate_id, event_type, park_reason, discard_reason, requested_by, requested_at, discarded_at
from sc_acc_accounts_ledger.outbox_discard_audit
order by discarded_at desc;
```

The account's ledger state (`account`, `journal_entry`, `posting_line`) is never changed by these
steps. Only publication changes.
