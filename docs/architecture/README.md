# Early Payouts architecture specification

Status: reference design, 19 September 2026. This is a learning and interview artifact, not a description of RevenueCat's private architecture. The Kotlin calculator, [reservation/bank-simulator slice](../transactional-payments.md), and [interactive dashboard with illustrative monitoring, payment reconciliation, and bounded final collection allocation](../dashboard.md) are implemented. Complete production workflows, real integrations, calibrated models, and operating targets below remain proposed. Consult the dashboard's scope table for current implementation boundaries.

## Reading order

| Document | Questions answered |
| --- | --- |
| [HLD](HLD.md) | Where does each component live? How do requests, events, and cash move? What do we build first? |
| [Underwriting and risk monitoring](underwriting-risk.md) | Who qualifies, how are limits determined, and what changes after approval? |
| [Fraud detection](fraud.md) | Which abuse signals can we actually observe, and how do they affect payouts? |
| [Money movement, ledger, and reconciliation](payments-ledger-reconciliation.md) | How do we reserve capacity, pay safely, account for it, and reconcile settlement? |
| [Partner bank integrations](bank-integrations.md) | What does the adapter promise? How are timeouts, returns, and account changes handled? |
| [Data models and contracts](contracts-data.md) | What are the entities, invariants, APIs, events, and Kotlin module boundaries? |
| [Operations and implementation roadmap](operations-rollout.md) | How do we deploy, observe, secure, validate, and evolve the system? What remains unknown? |

HLD means high-level design: responsibilities, deployment boundaries, and end-to-end flow. LLD means low-level design: states, interfaces, records, transaction boundaries, and algorithms. The LLD here is an implementation blueprint; SQL migrations and provider-specific request bodies remain future implementation work.

## Four areas, four different decisions

| Area | Core question | Output | Does it move money? |
| --- | --- | --- | --- |
| Underwriting and risk monitoring | Is this business and its receivable pool eligible, and how much exposure is acceptable? | Versioned approval, limits, holds, review cases | No |
| Fraud detection | Is this activity or destination change sufficiently suspicious to stop or review? | Allow/review/block assessment with evidence | No |
| Money movement and reconciliation | Can this request consume capacity, what cash moved, and where does each cent belong? | Reserved request, transfer lifecycle, balanced journals, matched settlement | Orchestrates movement through the adapter |
| Partner bank integration | How do we express and observe that movement using this provider's contracts? | Provider requests, evidence, normalized facts | Calls the provider that executes movement |

The existing Kotlin `evaluate` function belongs inside underwriting's policy calculation. It is a quote, not a complete underwriter, a funds reservation, a payment instruction, or proof of settlement.

## Business assumptions selected for this design

1. Start with USD, one contracted banking provider, verified businesses, and store receivables that can contractually be collected through an agreed repayment route. Extend by explicit currency-specific ledgers later.
2. Use 80% as an illustrative maximum advance rate and 2.5% of principal as the example fee. Actual credit policy can lower the advance rate or hold funding. The fee is deducted at disbursement in our model. Fee recognition is a separate finance policy.
3. Assume the store's eventual payout enters a designated collection account and can be attributed to the developer and store period. If this routing cannot be established, automated self-liquidating repayment is unavailable and onboarding must remain incomplete for this product design. A bank API alone does not create collection rights.
4. Keep two ledgers of meaning separate: the operational receivables register records what is expected from stores; the double-entry subledger records our cash, advance assets, and obligations. A sales estimate is not a booked bank deposit.
5. Use a Kotlin modular monolith, PostgreSQL, an outbox, background workers, and object storage initially. Logical modules may run in separate worker processes without becoming independent financial databases.
6. All numerical risk thresholds, capacity estimates, timing targets, and retry budgets are illustrative. Production settings require observed outcomes and partner agreements.

Public RevenueCat materials establish the advertised advance/fee and name Core Bank as a banking-services provider. They do not establish collection-account ownership, underwriting rules, rail selection, recourse, API semantics, or legal/accounting treatment. [RevenueCat Early Payouts](https://www.revenuecat.com/early-payouts)

## Non-negotiable engineering invariants

- No reservation can consume the same available capacity concurrently with another reservation.
- No additional advance can be generated merely because the same report was downloaded again or old principal was repaid.
- Every posted journal balances within a currency; corrections append reversing or adjusting journals.
- An ambiguous bank response preserves the existing operation and its reservation. A timeout is not proof of failure.
- A provider's acceptance, a cash posting, and a transfer's settlement/return are distinct facts.
- Every payment, decision, bank fact, and adjustment has a durable identity and traceable evidence.
- Held or expired decisions cannot authorize new dispatch; holds do not erase obligations already created.
- No residual developer payout is released from an unmatched or ambiguous collection.

## Completion boundary

The specification covers the main product lifecycle, all four requested areas, their LLD contracts, failure cases, and implementation gates. Production details that depend on a bank, finance, risk, or legal owner are listed with a proposed default and an owner in [operations and rollout](operations-rollout.md). They are explicitly unresolved facts, not features implemented by this document.
