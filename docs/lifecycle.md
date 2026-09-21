# Post-disbursement lifecycle and the tenant API

Quoting, reserving, and dispatching an advance was the original slice. This document covers what the lab added afterwards: what happens once the cash has left, and the API through which all of it is reachable.

All money remains synthetic. None of this is RevenueCat's implementation, and no threshold here is a calibrated credit decision.

## Why this half matters

An advance against app-store receivables is not finished when the money lands. Store proceeds arrive later, partially, and aggregated across pools. Refunds and chargebacks shrink the receivable after we have already funded against it. Banks return transfers days after settling them. Each of those is a separate fact arriving at a separate time, and each one has to be recordable without editing what was already posted.

## Store remittances: partial and aggregated

`capital/settlement/RemittanceService.kt`

A store pays once for many pools, so a remittance carries a line per pool. Per pool the waterfall is fixed and ordered, so the same remittance always allocates the same way:

1. **Open recovery obligations**, because that principal is already known not to be arriving through store proceeds.
2. **Outstanding advance principal.**
3. **Residual**, which becomes payable to the developer.

| Situation                              | Outcome                                                                   |
| -------------------------------------- | ------------------------------------------------------------------------- |
| Line covers less than outstanding      | Principal reduced, pool stays open, no residual                           |
| Line exceeds outstanding               | Principal cleared, remainder becomes developer residual                   |
| Line marked final                      | Pool closes; reaching zero principal early does **not** close it          |
| Line names an unknown pool             | Cash held as unapplied and reported, never silently absorbed              |
| Lines do not total the received amount | Nothing is posted; the difference is unexplained                          |
| Same remittance id replayed            | Original outcome returned; a different amount under that id is a conflict |

Pools are locked in id order so concurrent remittances cannot deadlock.

## Refunds and chargebacks: reclassification, not forgiveness

`capital/settlement/ProceedsRevisionService.kt`

This is the characteristic risk of advancing against subscription receivables. A refund reduces expected proceeds after funding, so the receivable shrinks while the advance does not.

Reducing a limit never retrieves money already advanced. What the revision can do is state honestly that some principal is no longer expected to arrive through store proceeds. That principal is **reclassified**: it moves out of the pool's outstanding balance into a recovery obligation collectible from the developer.

The developer's total outstanding is deliberately **unchanged**. They still owe exactly the same amount; only the route by which we expect repayment has changed. The register (expected store proceeds) and the subledger (our cash, assets, and obligations) stay separate — updating expected proceeds is a register change and posts nothing on its own.

## Bank returns: appending, never editing

`capital/settlement/AdvanceReturnService.kt`

A return is a genuinely later fact, not a correction. The funding journal stated truthfully that cash left on that day, so it is never edited. A second balanced journal is appended with `kind='REVERSAL'`, and the schema permits exactly one journal of each kind per advance, which makes a double reversal a constraint violation rather than a silent second credit.

A return is **refused** once store proceeds have already repaid the principal: the money was collected from another source, and reversing would drive the pool below zero. That conflict needs a human.

## Residual release

`capital/settlement/ResidualPayoutService.kt`

Open recovery is netted first across every pool, because paying residual while the same developer owes reclassified principal would hand back money we are simultaneously collecting. Release is a **posting**; instructing the outbound transfer is a separate dispatch, so a bookkeeping release is never mistaken for settled cash.

## Automatic payouts

`capital/automation/PayoutAutomation.kt`

RevenueCat describes both automatic and on-demand payouts; only on-demand existed before. A policy sets mode, a per-cycle minimum (below which capacity is left alone rather than sending dust), an optional maximum, and a `DAILY` or `WEEKLY` cadence.

Two properties make a cycle safe to run repeatedly, which matters because a scheduler will:

- A cycle is **unique per developer and period**, so a second run in the same period does nothing.
- Each reservation uses a derived idempotency key (`auto:pool:cycle-date`), so even if the cycle row were lost, replaying it would return the original advance rather than fund a second one.

The scheduler never overrides policy. It asks for available capacity and requests at most that, so holds, limits, destination changes, and stale reports block an automatic payout exactly as they block a manual one. Skipped pools are recorded with their reason rather than omitted.

## Portfolio

`capital/portfolio/PortfolioView.kt`

Single-pool views cannot show concentration, total headroom, or how much residual waits behind an open recovery. This reads committed state only and takes no locks: a number here can be stale the moment it is returned and **must never be used to authorize funding**. Reservation re-checks capacity under lock for exactly that reason.

`largestPoolShareBasisPoints` is reported as an observation. Nothing in the lab acts on it.

## The tenant API

`capital/api/` — the contract is [`src/main/resources/api/openapi.json`](../src/main/resources/api/openapi.json), served at `/v1/openapi.json`, so the served document and the checked-in one cannot drift.

Two boundaries are enforced at the edge rather than left to callers:

- **Tenancy.** Every route resolves its developer from the API key, never from a path or body field. A key asking for another developer's pool gets `404`, not `403`, so it cannot learn the pool exists.
- **Provenance.** Facts originating outside the developer — a store remitting money, a refund, a bank return — require `OPERATOR` scope. A developer must not be able to assert that a store paid them, because that would clear their own advance.

Scopes are ordered `READ < WRITE < OPERATOR`. Keys are stored only as SHA-256 digests, so a leaked row cannot be replayed and a key cannot be read back after issue.

| Route                             | Scope      | Purpose                                         |
| --------------------------------- | ---------- | ----------------------------------------------- |
| `GET /v1/portfolio`               | READ       | Aggregate position across pools                 |
| `GET /v1/pools/{id}/availability` | READ       | Quotable principal with its evidence            |
| `POST /v1/advances`               | WRITE      | Reserve and dispatch (Idempotency-Key required) |
| `GET /v1/advances/{id}`           | READ       | Inspect one advance                             |
| `GET`/`PUT /v1/payout-policy`     | READ/WRITE | Automatic payout configuration                  |
| `POST /v1/payout-cycles`          | WRITE      | Run this period's cycle                         |
| `POST /v1/residual-payouts`       | WRITE      | Release residual, net of recovery               |
| `POST /v1/remittances`            | OPERATOR   | Apply money received from a store               |
| `POST /v1/proceeds-revisions`     | OPERATOR   | Refund or chargeback revision                   |
| `POST /v1/advances/{id}/return`   | OPERATOR   | Record a bank return                            |

This is a local demonstration surface. It has no rate limiting, key rotation, request signing, replay window, or pooled connections, and it binds to loopback only. See [SECURITY.md](../SECURITY.md).

## What is still not implemented

Multi-currency pools and ledgers; real store report ingestion (expected coverage remains a supplied input); outbound dispatch of a residual release; returns of a residual payout; revenue recognition of deferred fees; aggregated remittances spanning more than one developer; and any calibrated underwriting or fraud model. See the [engineering reference](engineering-reference.md).
