# Accounts port: contract for consumers

Who reads this: the payments (svc-pay-initiation-settlement), mandates (svc-pay-recurring-mandates), bulk (svc-pay-bulk-orchestration) and loan (svc-ln-loan-lifecycle) teams, which today run an in-memory accounts adapter (`ACCOUNTS_ADAPTER=in-memory`). The full schemas are in [accounts-ledger.yaml](../api/openapi/accounts-ledger.yaml) and [svc-acc-accounts-ledger.yaml](../api/asyncapi/svc-acc-accounts-ledger.yaml).

## Reading an account

`GET /api/v1/accounts/{accountId}` with a client-credentials token (`aud` contains `svc-acc-accounts-ledger`, role `SERVICE`). The response is the shape the payments `CoreBankingAccountHttpAdapter` and the mandates `AccountsServiceHttpAdapter` already read, so swapping the in-memory adapter for the HTTP one needs configuration only:

| Field | Meaning |
|---|---|
| `accountId` | `ACC-<uuid>` |
| `customerId` | holder; absent on internal accounts |
| `category` | `CUSTOMER` or `INTERNAL` |
| `accountType` | `CURRENT`, `SAVINGS`, `INVESTMENT`, `BUSINESS`, `INTERNAL` |
| `status` | `ACTIVE`, `FROZEN`, `CLOSED` |
| `currency` | ISO 4217 |
| `availableBalance`, `bookedBalance` | JSON numbers in the currency's minor units precision; equal until holds exist |
| `debitAllowed`, `creditAllowed` | true only while `ACTIVE` |
| `islamicCompliant` | boolean |
| `openedAt`, `updatedAt` | ISO 8601 instants |

An unknown id answers 404 `ACCOUNT_NOT_FOUND`.

## Moving money

`POST /api/v1/journal-entries` from a client whose `azp` is on `SERVICE_CALLERS`:

```json
{"reference":"PAY-123","currency":"AED","narrative":"payment PAY-123","lines":[
  {"accountId":"ACC-...","side":"DEBIT","amount":40.00},
  {"accountId":"ACC-...","side":"CREDIT","amount":40.00}]}
```

- `reference` is your business key (payment id, mandate collection id). Retrying with the same reference and the same lines answers 200 with the first booking (`replay: true`); the same reference with other lines answers 409 `IDEMPOTENCY_KEY_REUSED`.
- 422 `INSUFFICIENT_FUNDS`: a customer account would go below zero. 422 `ACCOUNT_NOT_OPERATIONAL`: frozen or closed. 422 `UNBALANCED_ENTRY`: debits differ from credits.
- 409 `CONCURRENT_UPDATE` or `DUPLICATE_REQUEST`: retry with the same reference.
- Money into or out of the bank goes against an internal account (for example a clearing account) that an admin opens with `accountType: INTERNAL`.

## Events

Topic `evt.acc.account.v1`, key = account id, header `eventType`: `CoreBanking.Account.Opened.v1`, `Frozen.v1`, `Unfrozen.v1`, `Closed.v1`, `Debited.v1`, `Credited.v1`. Debited and Credited carry `journalEntryId`, `amount` and `balanceAfter`. Consumers declare the types they handle and skip the rest (ADR-019).

## Not there yet

Holds and reservations, statements, the dynamiconboarding TPP endpoint and an open-finance read of balances come in later rounds.
