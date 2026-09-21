# Fraud detection

Proposed component. The dashboard implements an illustrative velocity signal and review hold. No fraud classifier, device collection, or ML model is implemented; public retail cancellations are not fraud labels. See [implemented scope](../dashboard.md).

## Responsibilities and available evidence

Detect potentially manufactured receivables, account takeover, payout diversion, repeated financing, and abusive requests. A revenue spike is a signal for investigation, not proof of fraud. Keep financial volatility in risk monitoring and deliberate abuse in fraud assessment, while allowing them to share evidence.

| Threat                        | Evidence we might possess                                                                | Proposed control                                                                                  | Important limitation                                                            |
| ----------------------------- | ---------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------- |
| Manufactured purchase surge   | Verified purchases, proceeds estimates, refund/void patterns, historical seasonality     | Baseline-relative anomaly plus review/temporary hold                                              | Successful purchases can later reverse; a legitimate campaign can also spike    |
| Payout destination takeover   | Authenticated session, destination change, account verification, authorization history   | Step-up authentication, re-verification, cooling period, independent review for high-risk changes | Store events do not establish bank-account ownership                            |
| Replayed or forged events     | Source signature, tenant binding, event/transaction IDs, API verification                | Reject invalid signatures; deduplicate economic events                                            | A valid notification can still be old                                           |
| Same proceeds advanced twice  | Unique economic lot membership, lifetime funded principal, reservations                  | Transactional capacity checks and source deduplication                                            | External financing/assignment requires separate evidence and contractual checks |
| Suspicious related businesses | Verified owners, shared approved identifiers, bank-account relationships where permitted | Related-party review and aggregate exposure limits                                                | Shared infrastructure alone is not evidence of fraud                            |
| Payout-request abuse          | Request velocity, idempotency conflicts, authorization failures                          | Rate limits and scoped review                                                                     | Rate limits do not replace transaction-level correctness                        |

Do not assume access to raw card numbers, issuer decisions, payment-card fingerprints, or device identity from store reports. Device/session signals are usable only if our platform actually collects them with a defined purpose and proper controls. Start with available signals and record missingness.

Google's RTDN is a trigger to retrieve full state from the developer API, rather than a complete purchase record by itself. Voided-purchase data includes certain refunds and chargebacks but is not a universal refund feed. [Google RTDN](https://developer.android.com/google/play/billing/rtdn-reference), [Voided Purchases API](https://developers.google.com/android-publisher/voided-purchases)

## Position in the system

Fraud workers consume verified source changes and account-security events. They write assessments and scoped holds to the financial core; they never edit balances. The advance orchestrator checks a current assessment at reservation and again when claiming dispatch. The bank adapter does not decide fraud policy.

When ingestion accepts a critical event such as a revoked source connection or payout-destination change, install a pending-review gate synchronously. Asynchronous scoring can then enrich it. This prevents an apparently healthy cached score from authorizing funding while a known critical change waits in a queue.

## Low-level records and interfaces

```text
FraudSignal
  signal_id, tenant_id, developer_id, scope_type, scope_id
  type, observed_at, received_at, source_reference, confidence, feature_version

FraudAssessment
  assessment_id, developer_id, destination_version?, pool_id?
  action: ALLOW | REVIEW | BLOCK
  reasons[], signal_ids[], feature_snapshot_id, policy_version, model_version?
  source_watermarks, created_at, expires_at

ReviewCase
  case_id, tenant_id, scope, evidence_refs[], current_state
  assignee, opened_at, due_at, disposition, disposition_reason
  actions[], actor_ids[], policy_version
```

`assess(context)` returns a persisted assessment ID and decision. `openHold(scope, reason, evidence)` and `resolveCase(caseId, disposition, version)` use scoped authorizations and version checks. A review approval clears only holds owned by that case. Unexpired underwriting, onboarding, liquidity, and other fraud gates must still pass.

## Initial rules and review workflow

- Compare volume and proceeds to the developer's own recent baseline and an appropriate cohort, with minimum-history and seasonality checks. A demo might flag a fourfold jump for review; this is illustrative, not a proposed production cutoff.
- Treat spikes alongside refunds, unusually short purchase lifetimes, concentration in a new product/country, or large estimate/final-report discrepancies. Do not simply sum correlated signals into a claimed probability.
- Treat a new beneficiary plus an unusual payout request as a destination-security event even when proceeds are legitimate.
- Put hard integrity failures (invalid signature, mismatched tenant, unverified destination) in deterministic gates; an ML score cannot override them.
- Route uncertain cases to `REVIEW`, retaining current commitments but stopping new dispatch when policy requires. A human gets raw evidence links, a timeline, the amount at risk, and available actions.

Case states: `OPEN → INVESTIGATING → RESOLVED_ALLOW | RESOLVED_BLOCK`, with explicit reopen on new evidence. Every disposition includes who, why, when, and an expiry if temporary. Expose a useful customer status and support route without revealing thresholds that enable evasion. Monitor review turnaround to avoid indefinite unexplained holds.

## Dispatch races and post-payment discoveries

A dispatch claim and a new hold serialize on the same developer/risk lock. If the hold commits first, dispatch stops. If the dispatch claim commits first, the operation may already leave the system; record the discovery as post-dispatch risk and request cancellation only when the provider supports it. Never claim that an asynchronous detector can stop money already irrevocably sent.

After disbursement, fraud detection can freeze further advances, notify authorized operators, create an exposure/recovery case, and support provider action permitted by contract. It cannot manufacture a refund or silently debit the developer's other accounts.

## Evaluation and model evolution

Keep rule/model versions and a point-in-time feature snapshot. Use confirmed outcomes and analyst dispositions with provenance; a rule's own block is not proof that it found fraud. Labels arrive late and can be biased by which cases were reviewed.

Measure precision at the available review capacity, false-hold rate on legitimate customers, exposure-weighted loss, detection latency before dispatch, review turnaround, and drift. Run shadow rules and evaluate false positives before enabling blocking. Test legitimate launch campaigns, seasonal spikes, low-history apps, missing features, deliberately replayed notifications, and destination-change races.

If the assessment service is unavailable or evidence is stale, hold new funding for affected scopes; continue reconciliation and already-committed payment recovery. The fraud component is not an LLM deciding who gets money. A future LLM could assist an analyst with evidence summaries under access controls, without financial write authority.
