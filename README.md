# neo-fintechbankx-corebanking-accounts-ledger-service

Bu repository, FinTechBankX DDD/EDA dönüşümünde **svc-acc-accounts-ledger** servisinin (bağlam kodu `acc`) kaynak kodunu, kontratlarını ve operasyonel guardrail'lerini içerir. Müşteri ve iç hesapları, bakiyeleri ve bunları hareket ettiren çift taraflı yevmiye kayıtlarını (double-entry journal) bu servis tutar; başka hiçbir servis bakiye saklamaz.

## Sorumluluk ve Sahiplik
| Alan | Değer |
|---|---|
| Organizasyon Modeli | Spotify Model (Tribe/Squad) |
| Tribe | Lending and Money Movement Tribe |
| Squad | Payment Orchestration Squad |
| Repo Kümesi (Capability) | core_banking |
| Service ID | svc-acc-accounts-ledger |
| Bounded Context | acc (accounts ledger) |
| Veritabanı | `db_acc_accounts_ledger_<env>`, şema `sc_acc_accounts_ledger` |
| Event topic | `evt.acc.account.v1` |
| Mimari Yaklaşım | DDD + Hexagonal + Event-Driven |

## Kapsam
### In Scope
- Hesap açma, dondurma, çözme, kapatma (`CURRENT`, `SAVINGS`, `INVESTMENT`, `BUSINESS`, iç hesaplar için `INTERNAL`; İslami uyumluluk bayrağı).
- Çift taraflı yevmiye kaydı: borç toplamı alacak toplamına eşit, tek para birimi, pozitif tutarlar, müşteri hesabı sıfırın altına inmez (alan modeli ve veritabanı CHECK'i), iç hesaplar inebilir.
- Ödemelerin, talimatların, toplu ödemelerin ve kredilerin okuduğu hesap portu (`GET /api/v1/accounts/{accountId}`).

### Out of Scope (bu turda yok)
- Monolitteki dynamiconboarding (TPP DPoP uç noktası, KYC çözme, yaptırım taraması): sonraki tur.
- Bloke/rezervasyon (hold) ve hesap ekstresi: `availableBalance` şimdilik `bookedBalance`'a eşit.
- CBDC ve stablecoin cüzdanları taşınmadı.

## Run, test and deploy

| What | Command / path |
|---|---|
| Full gate (unit, integration, ArchUnit, Money rule, coverage) | `./gradlew check` (integration tests need `TEST_DB_URL` and `TEST_DB_USERNAME` (plus `TEST_DB_PASSWORD` unless the database trusts local clients) or Docker; with `CI=true` they fail without them) |
| Integration tests against a local PostgreSQL 16 | set `TEST_DB_URL` (for example `jdbc:postgresql://localhost:5432/db_acc_accounts_ledger_test`), `TEST_DB_USERNAME` (`accounts_test`) and, unless the database trusts local clients, `TEST_DB_PASSWORD` (the owner role's password, from your own store), then `./gradlew :accounts-bootstrap:test`; the owner role needs `CREATEROLE` (the tests create the runtime role `accounts_runtime_it`) |
| Run locally without TLS | `SPRING_PROFILES_ACTIVE=local` (relaxes the startup TLS assertion for a local database and Kafka; never set in a deployment) |
| Database migrations | `accounts-infrastructure/src/main/resources/db/migration` (V1 ledger tables, V2 outbox, V3 runtime-role grants: journal rows are insert-only, V4 outbox discard audit: insert-only) |
| Kubernetes | `deploy/helm/accounts-ledger-service` (image by digest only: `image.repository` plus `image.digest`, as the cicd-templates helm-deploy job passes them); `templates/_fbx_helpers.tpl` is the cicd-templates `fbx.guard` copied byte for byte and checked by `scripts/ci/verify-vendored-guard.sh` |
| AWS infrastructure | `deploy/terraform` |
| Consumer contract | [docs/CONSUMER_CONTRACT.md](docs/CONSUMER_CONTRACT.md) |
| Operations (outbox relay switch, metrics, park, replay, discard) | [docs/RUNBOOK.md](docs/RUNBOOK.md) |

Module layout (service guardrails, ADR-028/029): `accounts-domain` holds the `Account` aggregate, the `JournalEntry` entity, `domain.exception`, `domain.port.in` (`OpenAccountUseCase`, `FindAccountUseCase`, `ManageAccountUseCase`, `PostJournalEntryUseCase`, `FindJournalEntryUseCase`) and `domain.port.out` (`AccountRepository`, `JournalEntryRepository`, `AccountEventPort`). `accounts-application` holds `AccountsLedgerService`; `accounts-infrastructure` holds the JPA adapters, the transactional outbox and relay, the web adapters and configuration; `accounts-bootstrap` is the Spring Boot application. Everything lives under `com.bank.accounts` (plus `com.bank.shared.kernel`).

Posting takes pessimistic locks on every account of an entry in ascending id order, then checks the reference: the same reference with the same lines is a replay (200 with the first entry), with other lines a 409.

## Contracts and events

| | |
|---|---|
| API | `api/openapi/accounts-ledger.yaml` |
| Events | `api/asyncapi/svc-acc-accounts-ledger.yaml` (AsyncAPI 3.0; `AccountEventContractTest` checks it against the code). Catalog PR pending. |

```yaml
published_events:
  # One topic per aggregate (ADR-019): key = account id, the eventType record header names the event.
  - topic: evt.acc.account.v1
    eventTypes:
      - CoreBanking.Account.Opened.v1
      - CoreBanking.Account.Frozen.v1     # the freeze reason is not published
      - CoreBanking.Account.Unfrozen.v1
      - CoreBanking.Account.Closed.v1
      - CoreBanking.Account.Debited.v1    # journalEntryId, amount, balanceAfter
      - CoreBanking.Account.Credited.v1
consumed_events: []   # none, so the service owns no DLQ
```

## Identity and mesh

- Tokens must carry `aud` containing `svc-acc-accounts-ledger`.
- `GET /api/v1/accounts/{id}`: `CUSTOMER` (own accounts only; any other id answers the same fixed 404 as an unknown one), `BANKER`, `ADMIN`, `SERVICE`.
- Opening, freezing, unfreezing and closing: `BANKER`, `ADMIN`.
- `POST /api/v1/journal-entries`: `ADMIN`, or a `SERVICE` token whose `azp` is on `SERVICE_CALLERS` (default `svc-pay-initiation-settlement,svc-pay-recurring-mandates,svc-pay-bulk-orchestration,svc-ln-loan-lifecycle`).
- The chart ships no Istio policies, no NetworkPolicy by default and no ServiceMonitor; the mesh and observability repos own them.

## Dokümantasyon ve Referanslar
- [Enterprise Architecture Hub](https://github.com/COPUR/fintechbankx-governance-architecture-enablement-enterprise-architecture)
- [Service Data Ownership Matrix](https://github.com/COPUR/fintechbankx-governance-architecture-enablement-enterprise-architecture/blob/main/docs/enterprisearchitecture/implementation-development/SERVICE_DATA_OWNERSHIP_MATRIX.md)

## Güvenlik ve Uyumluluk Notları
- Gerçek secret değerleri repo veya `.env` içinde tutulmaz; CI'daki geçici veritabanı `POSTGRES_HOST_AUTH_METHOD=trust` ile çalışır, parola secret'ı gerekmez.
- CI pipeline, anonimlik, secret ve local-path sızıntısı kontrollerini bloklayıcı olarak çalıştırır.
- Özel repo işleri `runs-on: ${{ vars.CI_RUNNER || 'ubuntu-latest' }}` ile fbx-ci runner'ında koşar.

## Katkı
- Katkı süreci için `CONTRIBUTING.md` izlenmelidir.
