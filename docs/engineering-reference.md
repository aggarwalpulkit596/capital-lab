# Engineering reference: implemented boundaries

Capital Lab is a local reference implementation for financial workflows. Its strongest guarantees are the ones exercised against PostgreSQL and the independent HTTP bank simulator. It is not a deployable lending platform.

## Code ownership and configuration

| Concern          | Code                                 | Responsibility                                                                                                                          |
| ---------------- | ------------------------------------ | --------------------------------------------------------------------------------------------------------------------------------------- |
| Commercial terms | `capital/policy/FinancialTerms.kt`   | One versioned 80% advance / 2.5% fee policy. Shared by quoting, transactional capacity checks, origination limits, and fee calculation. |
| Eligibility      | `capital/Eligibility.kt`             | Pure evaluation of source coverage, exposure, limits, and exact cents arithmetic.                                                       |
| Monitoring       | `capital/risk`                       | Separately versioned, illustrative cancellation and velocity thresholds. Explanations use the actual configured values.                 |
| Runtime settings | `capital/config/RuntimeConfig.kt`    | Validated database, HTTP, recovery, and transport bounds. Environment parsing happens at the application entry point.                   |
| Synthetic data   | `capital/simulation`                 | Named immutable fixture recipes and a controllable clock; no fictional account IDs in financial services.                               |
| Payments         | `capital/payments/AdvanceService.kt` | Atomic reservations, frozen commands, idempotency, outbox claims, fencing, recovery, and funding journals.                              |
| Collections      | `capital/collections`                | Idempotent allocation of one final receipt per pool.                                                                                    |
| Reconciliation   | `capital/reconciliation`             | Read-only comparison of command, bank, and ledger evidence.                                                                             |
| Ingestion        | `capital/ingestion`                  | Public-data replay and explicit synthetic currency conversion.                                                                          |
| Dashboard        | `capital/dashboard`                  | HTTP boundary, scenario orchestration, progress/observations, telemetry, and read-only evidence archives.                               |

Financial rates are reviewed policy definitions, not environment toggles. The mathematical basis-point denominator and supported USD currency are domain constraints. Scenario balances, fictional IDs, and deliberately injected faults belong to the simulation. Naming every number as a global constant would not establish these boundaries.

The quote records a policy version and its actual values. The reservation persists decision evidence and freezes principal, fee, cash, currency, destination, and bank identity. A retry under a different policy returns the original command. Later authorization checks may block an unexecuted transfer; they never rewrite its frozen fee. A database test exercises non-default terms across quote → reservation → bank → ledger to detect hidden default assumptions.

## User-facing fee model

The developer sees the available principal, fixed percentage fee, and exact cash received before requesting a payout. For the default account: $200 principal − $5 fee = $195 paid. A single request runs the existing reservation and bank workflow. Refreshing or retrying uses the same run/payment identity.

Finance sees one isolated account or scenario at a time, with payouts, fees withheld, risk signals, immutable postings, and reconciliation exceptions. Fees withheld sum recorded settled advances only. Seeded historical exposure is excluded because there is no corresponding historical fee evidence. The funding journal credits deferred fees; revenue recognition is not implemented. No additional fee is charged when the final store receipt is allocated.

The engineering workbench remains available for faults, concurrency, source replay, and raw evidence. It is deliberately separate from the developer's payout task. Finance views currently inspect evidence; they are not an authenticated operational approval system.

## Durability and operational bounds

- SQL installation records resource identity and SHA-256 inside an advisory-locked transaction. Reapplying identical SQL is a no-op. Editing an applied migration fails; evolve the schema by adding another migration resource. Existing unversioned schemas are not automatically adopted.
- JDBC has connect, socket, statement, and lock timeouts. HTTP workers, queued work, request bytes, and active runs are bounded. Queue rejection can close a connection; this is not a production overload gateway.
- `/health/live` checks the HTTP process. `/health/ready` probes database connectivity. It does not certify every historical schema or a real bank dependency.
- `/api/metrics` returns in-memory request counts, server-error counts, and aggregate duration. Structured request logs carry an ID, method, route template, status, and duration, without bodies or credentials. This is a local diagnostic surface, not an SLO or distributed tracing system.
- Evidence checkpoints use a forced temporary file and atomic replacement. A slower writer cannot overwrite a later step. Filesystem evidence and financial database commits are not one transaction; directory-entry durability across power loss is not guaranteed.
- After restart, saved runs are read-only archives. Stepping an archive returns `409 ARCHIVED_READ_ONLY`; the server does not auto-resume bank work from JSON. Database state remains the financial authority.

## What remains before real deployment

Authentication and tenant authorization; pooled connections and measured throughput; independent bank credentials and contract tests; automated workers and multi-instance lifecycle; returns and reversals; partial/aggregated collections and residual disbursement; full opening ledger and revenue recognition; calibrated underwriting and fraud evaluation; migrations for existing deployments; backup/restore, disaster recovery, reconciliation operations, and production observability.

See [HLD and LLD](architecture/README.md) for the broader target design, [runbook](runbook.md) for local operation, and [validation](validation.md) for executed evidence.
