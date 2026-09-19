# Interactive Capital Lab

The local dashboard runs the Kotlin financial engine against PostgreSQL. It is a teaching and interview walkthrough, using synthetic money and an independently committed bank simulator.

## Start

```sh
docker compose up -d --wait postgres
./gradlew dashboard
```

Open **http://127.0.0.1:8080**. JDK 17 and Docker are required. The interface uses bundled HTML, CSS, and JavaScript, with no frontend package install or external CDN. `LAB_HTTP_PORT` changes the port. `LAB_JDBC_URL`, `LAB_DB_USER`, and `LAB_DB_PASSWORD` select a dedicated demo database.

Use Ctrl-C to stop the server, then `docker compose stop postgres` to stop the database without deleting its volume. Each run creates isolated schemas. The run's URL supports refreshing and reopening it within the same server session. The server keeps the step cursor in memory; after server restart, create a new run. PostgreSQL records and automatically saved JSON under `build/dashboard-runs/` remain available for inspection.

## A five-minute walkthrough

1. Select **Paid, but the response vanished** and click **Start scenario**.
2. Advance through assessment and reservation. Explain $200 principal, $5 fee, and $195 bank cash. A quote alone moves no money.
3. Lose the HTTP response after the bank commits. Inspect **Bank & outbox** and **Reconciliation**: one external payment, unknown local state, reserved capacity, and an unposted bank debit.
4. Apply the risk hold. Recovery queries the original operation, records the confirmed payment once, and never resubmits during the hold.
5. Inspect **Ledger** and replay. Balanced entries persist; payment and journal counts do not grow.
6. Optionally run **Unknown outcome + hold** to show the other branch: lookup says not found, so the reservation stays until the hold clears.

Use **Export run** to save the full event history, inputs, reasons, bank observations, journals, and reconciliation results. Exported evidence describes observed demo behavior, not production guarantees.

## Scenario coverage

The 20 guided scenarios cover normal advances; response loss before/after execution; query-only recovery during a hold; retrying a stable key; worker interruption; bank rejection; holds before dispatch; destination changes; stale reporting; reduced proceeds; velocity/cancellation signals; insufficient funding cash; concurrent distinct and duplicate requests; altered bank statements; final collection allocation; shortfall; unmatched receipt; and public-data replay.

The UI's worker interruption is an in-process failure after the bank call; a separate integration test halts an actual child JVM. The dashboard is a curated scenario library, not an interface to every low-level unit-test branch. The full suite also checks generation fencing, transaction rollback, immutable journals, report boundaries, rounding, and malformed inputs.

## Components and boundaries

| Component              | Executable behavior                                                                                                           | Scope limit                                                                              |
| ---------------------- | ----------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------- |
| Underwriting           | Capacity, borrower limits, lifetime funding, stale coverage                                                                   | Illustrative rules; no calibrated default model                                          |
| Risk and fraud review  | Cancellation ratio ≥10%; revenue ≥3× previous seven observed days triggers hold                                               | Review signals, not fraud labels; no device/KYC model                                    |
| Money movement         | PostgreSQL reservations, DB outbox, immutable identity, leased dispatch and recovery                                          | USD synthetic bank only                                                                  |
| Partner integration    | Real loopback HTTP; separate bank transactions; lost responses and lookup                                                     | No real provider authentication or bank account                                          |
| Funding ledger         | Balanced immutable new funding journals                                                                                       | Opening balances are fixtures; fee remains deferred                                      |
| Payment reconciliation | Match identity, currency, amount, and funding cash posting; expose exceptions                                                 | Bank simulator combines execution and settlement                                         |
| Collection allocation  | One final report and confirmed synthetic receipt per pool; principal first, residual payable, shortfall, duplicate protection | No partial/aggregated receipts, residual bank payout, cash sweep, or full collections GL |
| Public replay          | 305 real observed retail days with attributed source and deterministic transformation                                         | GBP retail data mapped to synthetic USD; no app-store/default/fraud labels               |

Collection cash is distinct from funding cash. Allocating a receipt reduces outstanding principal, closes the pool, and leaves lifetime-funded principal unchanged. A residual remains a developer liability and is not spent as company funding cash. A mismatched receipt is recorded as external evidence and remains unapplied; this bounded slice does not yet book a full unapplied-cash ledger.

## API and code

- `GET /api/catalog`: guided scenario definitions.
- `GET /api/dataset`: bundled source metadata and daily aggregates.
- `POST /api/runs`: create isolated run with `scenario`, plus `datasetDate` for public replay.
- `GET /api/runs`: list this server session's run IDs.
- `GET /api/runs/{id}`: inspect a run.
- `POST /api/runs/{id}/step`: execute `expectedStep`. Repeating an earlier step returns current state without advancing again.

Mutations require `Content-Type: application/json` and `X-Capital-Lab: local-demo`, plus a matching Origin when present. There is no arbitrary SQL, shell-command, external-URL, or production-provider endpoint exposed through the dashboard.

Read [ScenarioLab.kt](../src/main/kotlin/capital/dashboard/ScenarioLab.kt), [Monitoring.kt](../src/main/kotlin/capital/risk/Monitoring.kt), [CollectionService.kt](../src/main/kotlin/capital/collections/CollectionService.kt), and [DashboardServer.kt](../src/main/kotlin/capital/dashboard/DashboardServer.kt).

## Public release preparation

Code and original documentation use [Apache 2.0](../LICENSE); public data retain [separate attribution](../data/README.md). Contribution and local-deployment notes are included. See the [source repository](https://github.com/aggarwalpulkit596/capital-lab) and [CI checks](https://github.com/aggarwalpulkit596/capital-lab/actions/workflows/check.yml). The dashboard itself remains a local application. Personal application drafts and session notes are excluded from the public repository.

## Role-based workspace

The home view is now **Developer payouts**: review $200 principal, the $5 fixed fee, and $195 net cash, then request the payout. **Finance operations** presents payouts, fees withheld, risk review, ledger, and reconciliation for one selected account or scenario. **Scenario workbench** retains the existing guided scenarios. Previous server sessions are available through run history as read-only evidence archives. See [engineering boundaries](engineering-reference.md) and [operations](runbook.md).
