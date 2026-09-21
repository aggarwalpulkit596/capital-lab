# Operations, validation, and rollout

These are proposed operating requirements and implementation stages. They are not achieved availability, performance, or production-readiness claims.

## Failure behavior and runbooks

| Failure                                   | Automatic response                                                        | Operator action                                                         | What must stay true                            |
| ----------------------------------------- | ------------------------------------------------------------------------- | ----------------------------------------------------------------------- | ---------------------------------------------- |
| Store report missing beyond allowance     | Hold new advances for affected source/pools; retry/backfill               | Check source permissions, calendar, outage, and revision lineage        | Download time cannot replace coverage          |
| Risk/fraud assessment delayed or expired  | Hold affected new reservations/dispatch; continue reconciliation          | Resolve backlog or approve a documented conservative fallback policy    | No silent use of obsolete approval             |
| Duplicate or out-of-order notifications   | Persist/deduplicate, reload authoritative current facts where appropriate | Investigate uncorrelated observations                                   | No duplicate posting or state regression       |
| Provider timeout after possible execution | UNKNOWN; keep operation/key and required reservations; recover            | Resolve via provider evidence if automatic recovery exhausts its budget | No second payment or unsupported release       |
| Queue unavailable                         | Keep committed outbox rows; alert on age                                  | Restore queue/relay and replay safely                                   | No lost committed intent                       |
| Worker crash                              | Lease expires; another worker resumes same operation                      | Inspect repeated failures and dead-letter items                         | Lease expiration is not payment cancellation   |
| Database unavailable/failover             | Stop new financial writes and dispatch claims                             | Restore writer, verify fencing and uncertain bank operations            | No split-brain funding                         |
| Unmatched bank credit                     | Record cash and unapplied liability; hold ambiguous pool origination      | Identify settlement with evidence                                       | No guessed residual payout                     |
| Return after settlement                   | Record distinct return evidence, hold relevant new activity               | Review current allocations and recovery                                 | Preserve original cash history                 |
| Ledger-to-bank discrepancy                | Open break, block affected cash-dependent releases when material          | Reconcile facts and post approved corrections                           | Never edit a journal to hide a break           |
| Destination takeover suspected            | Block new dispatch to scope and preserve evidence                         | Verify identity and investigate pending operations                      | No automatic reroute of in-flight money        |
| Source schema changes                     | Quarantine unparseable batches; alert, retain raw evidence                | Update parser with fixtures and controlled replay                       | Bad input cannot silently become zero proceeds |

Replay dead-letter items only after fixing the cause and preserving original identities. Operations commands must pass domain authorization and invariants; support engineers do not repair money by ad hoc SQL updates.

## Observability and proposed targets

Carry request, advance, pool, payment, provider operation, journal, and reconciliation-case IDs through logs and traces. Avoid customer financial data and credentials in ordinary logs.

Initial internal targets, to be negotiated and load-tested:

- Quote/reservation API: p95 under 500 ms excluding external onboarding/provider work; funding decisions do not synchronously call stores.
- Durable webhook acknowledgment: p95 under 2 seconds; business processing happens asynchronously after persistence.
- Healthy-path outbox and payment-command delay: p95 under 60 seconds during supported operating windows. Provider availability and rail cutoffs remain separate.
- Page on unresolved bank outcome older than a provider-specific threshold; five minutes is a demo alert example, not a promise of resolution or grounds to retry under a new key.
- Daily account reconciliation completes by an agreed next-business-day control deadline; inspect material unmatched items continuously.
- Any unbalanced posting attempt, duplicate semantic posting attempt with a conflicting amount, or unauthorized cross-tenant access is an immediate engineering/security incident.

Track request-to-cash latency by rail, aged UNKNOWN orders, payout returns by reason, active reservations without workers, outstanding exposure, reporting lag, assessment lag, reconciliation backlog and dollar value, borrower/store/provider concentration, liquidity, and loss/recovery cohorts. Publish separate service availability and payout-timeliness metrics so a healthy HTTP endpoint cannot mask stuck money.

## Security, privacy, and responsibility boundaries

- Developer APIs enforce tenant-scoped authorization; operations tools use least-privilege roles and immutable actor audit events.
- Sensitive identity documents and bank tokens use restricted stores with encryption, rotation, access auditing, and defined retention. Raw bank account details are absent from ordinary event payloads.
- Require step-up verification for payout destination changes. Separate high-impact policy changes, beneficiary overrides, manual cash allocations, and write-offs from routine support access; use dual approval where the approved operating policy requires it.
- The contracted bank and program define KYC/KYB, sanctions, monitoring, account ownership, permitted activity, and regulatory responsibilities. Model status and evidence; do not invent a universal compliance checklist from a generic BaaS API.
- Log versions and reasons for every credit, fraud, and override decision. Collect device or related-party signals only where available and appropriately governed.
- Runtime generative AI has no authority to approve credit, alter journals, change beneficiaries, or execute a payout in this design.

## Availability and recovery

Use a single active financial writer, high-availability database replication, encrypted backups, point-in-time recovery, object-storage versioning, and tested restore procedures. Target no loss of acknowledged local financial commits during an ordinary failover with appropriately configured replication. Regional disaster RPO/RTO are explicit infrastructure and business decisions, not guaranteed by this document.

After a restore, freeze new dispatch. Reconcile operations in the recovery window against the provider before reenabling the writer. A restored database may forget a transfer the bank already executed; replaying with new keys would be unsafe. Fence old workers and preserve original provider keys. Resume using current evidence, verified ledger/control balances, and a documented incident timeline.

## Acceptance matrix for the future implementation

| Layer          | Required evidence before calling it implemented                                                                                                                  |
| -------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Ingestion      | Duplicate cumulative report does not double proceeds; revised report changes the correct lot; timezone/schema/missing-field fixtures; interruption-safe backfill |
| Risk           | Point-in-time inputs, separate hold ownership, expiry, limit reduction, settlement runoff, no repeated financing of a settled lot                                |
| Fraud          | Validated source evidence; false-positive launch scenarios; destination-change race; expired/missing assessment gating; scoped case resolution                   |
| Reservation    | Real PostgreSQL concurrency tests with independent transactions; borrower and shared funding limits respected; different-payload idempotency conflict            |
| Bank workflow  | Simulator executes a transfer then drops response; retry recovers same operation; callbacks duplicated/reordered; failed cancellation and later returns          |
| Ledger         | Every posting balances per currency; immutable history; exactly one journal effect per economic fact; reversal/replay/overflow tests                             |
| Reconciliation | One-to-many/many-to-one receipts, shortfall, overpayment, bank fees, late adjustments, duplicate allocation races, ambiguous receipt case                        |
| Operations     | Restore with an in-flight bank operation, worker fencing, key rotation, privilege tests, alert/runbook drill, traceable operator corrections                     |

No synthetic fraud/risk test establishes real-world predictive performance. Shadow results, outcome labels, loss limits, and review capacity must be validated separately.

## Incremental implementation plan

Implementation update: parts of stages 1 and 2 are now delivered in the [transactional payment slice](../transactional-payments.md): real PostgreSQL reservations, durable DB-polled intents, minimal balanced funding journals, an HTTP bank simulator, and recovery including a separate-JVM crash. This does not complete the broader stage-1 financial model or provider lifecycle, nor stages 3–6.

| Stage                             | Deliverable                                                                         | Exit evidence                                                               |
| --------------------------------- | ----------------------------------------------------------------------------------- | --------------------------------------------------------------------------- |
| 0 — completed prototype           | Pure Kotlin quote evaluator and synthetic fixture runner                            | Existing 23 tests and demo results; no bank integration                     |
| 1 — financial core                | PostgreSQL identities, pool runoff, reservations, idempotency, ledger, inbox/outbox | Concurrency, balanced postings, and replay tests                            |
| 2 — bank simulation               | One adapter and fault-injectable fake bank, dispatch/recovery worker                | Timeout-after-execution never produces two transfers                        |
| 3 — collection and reconciliation | Synthetic store settlement reports, cash receipts, allocation and residuals         | $1,000 happy path, partial settlement, return, and $700 shortfall scenarios |
| 4 — risk/fraud operations         | Versioned features, transparent rule policies, separate holds, review queue         | Material changes block stale approvals; cases clear only their own holds    |
| 5 — partner sandbox               | Implement actual contracted provider semantics and test account lifecycle           | Provider contract tests, reconciliation, security and operations review     |
| 6 — bounded production pilot      | Small approved exposure/participant limits and manual controls                      | Agreed loss, timeliness, reconciliation, and operational criteria           |

Stages are dependency milestones, not a promise to finish the complete platform before the application deadline. For an interview artifact, stages 0 plus this specification are already clearly distinguishable; stages 1–3 would be the next useful engineering evidence. Expand the Q5 answer only when new implementation and validation actually happen.

## Scaling decisions and triggers

Keep financial transactions together at first. Scale API replicas and independent workers; partition raw/event ingestion by source and developer; isolate expensive feature generation from payout processing. Use indexes on tenant/scope/state/next-attempt fields, bounded retries, backpressure, and per-source rate-limit budgets.

Extract source ingestion or analytics when throughput and independent ownership justify it. Extract a model-serving component when its lifecycle warrants it. Splitting the ledger from reservations requires a deliberate replacement for the local atomicity guarantee; a queue alone is insufficient.

Partition core data by legal entity/currency and, later, developer where constraints permit. Shared portfolio and cash budgets need safe quotas or a centralized allocator; naïvely sharding borrower rows does not solve global limit races. Allocate conservative budgets to shards before spending, and reconcile replenishment. Introduce more banks only after per-provider unknown-outcome and liquidity isolation work.

## Decisions needing external facts before production

| Decision                                     | Proposed assumption here                                        | Required owner/evidence                                  |
| -------------------------------------------- | --------------------------------------------------------------- | -------------------------------------------------------- |
| Legal product structure and recourse         | Advance against specifically identified store proceeds          | Legal/product agreement                                  |
| Collection route and repayment rights        | Attributable designated collection account                      | Bank, legal, store-account operations                    |
| Cash ownership and permitted transfers       | Separate owned funding cash from developer liabilities          | Finance/legal/bank account structure                     |
| Fee timing, refunds, and revenue recognition | Fee deducted upfront; recognition shown as separate policy step | Finance-approved posting rules and terms                 |
| Real provider semantics                      | Stable operation identity, retriable/observable outcomes        | Provider contract and sandbox evidence                   |
| Risk thresholds and maximum loss budget      | Transparent conservative rules; no calibrated model claim       | Risk owner and historical settlement outcomes            |
| Funding facility and liquidity               | Explicit owned cash budgets and operational buffer              | Treasury and actual funding agreements                   |
| Supported geography/currency/rails           | USD and one provider initially                                  | Product, bank capabilities, legal review                 |
| Retention and recovery targets               | Immutable evidence with scoped access and tested restore        | Security, legal, finance, infrastructure owners          |
| Store reports and revisions                  | Versioned estimates with final-report reconciliation            | Actual schemas, accessible permissions, source calendars |

These are dependencies to resolve with the relevant owners, not reasons to invent private RevenueCat behavior.

## Specification review performed

Checked all local document links and fenced code blocks across the eight architecture documents. Seven Mermaid diagrams are included; they were inspected as diagram source, not rendered in this review. Independently checked that the seven example posted journals balance, that the happy-path developer cash and fee total matches proceeds, that partial repayment creates no new origination headroom for the same earnings, and that a $700 collection against $800 principal leaves $100 outstanding. Reviewed reservation/dispatch lock order and clarified exclusion of an order's own reservation during revalidation.

These are document and arithmetic checks, not executed integration or concurrency tests. The acceptance matrix above remains future implementation work.

## Primary references used

References support specific external behaviors; the architecture and example accounting policy are our proposed design.

- [RevenueCat Early Payouts](https://www.revenuecat.com/early-payouts): public product description and named banking provider.
- [Apple report availability](https://developer.apple.com/help/app-store-connect/reference/reporting/sales-and-trends-reports-availability) and [report distinctions](https://developer.apple.com/help/app-store-connect/measure-app-performance/download-and-view-reports): reporting lag and estimates versus final proceeds.
- [Apple notification history](https://developer.apple.com/documentation/appstoreserverapi/get-notification-history): recovery of historical notifications; current state may need another API lookup.
- [Google RTDN](https://developer.android.com/google/play/billing/rtdn-reference) and [voided purchases](https://developers.google.com/android-publisher/voided-purchases): notification/full-state distinction and voided-purchase evidence.
- [Increase idempotency](https://www.increase.com/documentation/idempotency-keys), [transactions/transfers](https://www.increase.com/documentation/transactions-transfers), and [ACH lifecycle](https://increase.com/documentation/sending-ach-transfers): concrete examples of banking-integration semantics.
- [Stripe idempotency](https://docs.stripe.com/api/idempotent_requests) and [webhooks](https://docs.stripe.com/webhooks): provider-specific retry, retention, authentication, and event-delivery considerations.
- [AWS transactional outbox](https://docs.aws.amazon.com/en_en/prescriptive-guidance/latest/cloud-design-patterns/transactional-outbox.html): durable local state and event publication.
- [PostgreSQL locking](https://www.postgresql.org/docs/current/explicit-locking.html): database serialization primitives.
- [Modern Treasury ledger immutability](https://www.moderntreasury.com/journal/enforcing-immutability-in-your-double-entry-ledger): posted ledger history and correction design considerations.

## Local dashboard implementation update

The [interactive dashboard](../dashboard.md) now runs 20 curated scenarios across eligibility, illustrative monitoring, reservations, bank recovery, payment matching, and bounded final collection allocation. The current backend suite has 135 passing tests; see [validation](../validation.md). The production acceptance matrix above still includes unimplemented paths, including returns, partial/aggregated collections, and residual payouts. The Apache 2.0 project includes an attributed public-data replay and a CI workflow; the source is published on GitHub; the application remains a local demo.
