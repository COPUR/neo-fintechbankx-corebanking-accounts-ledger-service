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
hand: the purge removes published rows after `accounts.outbox.retention` (7 days).

```sql
-- Park a row the relay keeps failing on (only a payload error parks by itself, ADR-021 decision 4).
-- The relay counts it once as outbox_parked_events_total{exception="OperatorPark"}.
update sc_acc_accounts_ledger.outbox_event
set parked_at = now(), park_reason = '<ticket>: <reason>'
where event_id = '<event id>' and published_at is null and parked_at is null;

-- Replay: the relay picks the row up again in its original place, then the account's later rows.
-- A row parked for a payload error parks again unless its cause (topic config, record size) was fixed.
update sc_acc_accounts_ledger.outbox_event
set parked_at = null, park_reason = null, park_counted = false, last_error = null
where event_id = '<event id>' and parked_at is not null;

-- Discard: consumers never receive this event. Only with the consumers' squads (payments, lending)
-- agreed in the ticket, since a missing Debited or Credited event leaves their view of the
-- balance wrong.
update sc_acc_accounts_ledger.outbox_event
set published_at = now(), parked_at = null,
    park_reason = 'discarded <ticket>: ' || coalesce(park_reason, '')
where event_id = '<event id>' and parked_at is not null;
```

The account's ledger state (`account`, `journal_entry`, `posting_line`) is never changed by these
steps. Only publication changes.
