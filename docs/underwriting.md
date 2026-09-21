# Risk-based underwriting

Everything on this page is **illustrative**. Each threshold was chosen to make the mechanism legible, not fitted to outcomes. A real advance rate needs observed repayment performance, a credit owner, and a model risk review; this lab has none of those. Nothing here changes a limit or blocks a payout — the authoritative controls are the ones enforced under lock in the reservation path. The assessment is advisory output a reader can inspect.

The model itself ([`capital/underwriting/RiskModel.kt`](../src/main/kotlin/capital/underwriting/RiskModel.kt), [`FraudRules.kt`](../src/main/kotlin/capital/underwriting/FraudRules.kt)) is pure and unit-tested against hand-worked expectations. [`UnderwritingService`](../src/main/kotlin/capital/underwriting/UnderwritingService.kt) only gathers inputs from recorded state.

## Observed history

`Observation` holds whole days of normalized activity. Nothing is projected, smoothed, or filled in: **a missing day is a shorter window, not a zero**, because treating an unreported day as zero sales would understate volatility and overstate tenure.

Refund evidence comes from recorded [proceeds revisions](lifecycle.md#refunds-and-chargebacks-reclassification-not-forgiveness), not an estimate — the same rows the settlement path writes.

## Advance rate

A flat 80% for every developer ignores everything we have observed. The rate is derived instead:

| Factor           | Band                       | Adjustment |
| ---------------- | -------------------------- | ---------: |
| Base             | —                          |     80.00% |
| `REFUND_RATE`    | refunds + chargebacks ≥ 2% |    −5.00pp |
|                  | ≥ 5%                       |   −15.00pp |
|                  | ≥ 10%                      |   −30.00pp |
| `SHORT_HISTORY`  | under 30 days observed     |   −15.00pp |
|                  | 30–89 days observed        |    −5.00pp |
| `VOLATILE_SALES` | daily dispersion ≥ 75%     |   −10.00pp |
| `FLOOR_APPLIED`  | result below the 30% floor |  to 30.00% |

Three properties are deliberate:

- **Additive, not multiplicative**, so the worst case is bounded and each contribution stays separately visible.
- **Bands partition a range**, so exactly one refund band applies — 6% refunds take −15pp, not −5pp _and_ −15pp.
- **The rate never rises above base.** Good history removes penalties rather than earning leverage.

Volatility is mean absolute deviation over the mean, which needs no square root and stays in exact integer arithmetic. It is a descriptive statistic, not a fitted model.

Every decision carries its `factors`, so a rate can always be explained: _"Base 80.00% adjusted to 30.00% by: REFUND_RATE −30.00pp; SHORT_HISTORY −15.00pp; VOLATILE_SALES −10.00pp; FLOOR_APPLIED +5.00pp"_.

## Portfolio caps

Applied **after** per-pool policy capacity, never instead of it. These express risks a single pool's arithmetic cannot see. Each cap reports what it would permit, so the binding one is identifiable rather than buried in one number.

**`CONCENTRATION`** — at most 60% of outstanding principal may sit behind one receivable pool. The cap binds only above a portfolio size floor: without one it is unusable, because any first advance puts 100% of a zero portfolio behind one pool and every new developer would be refused.

**`VELOCITY`** — new principal over a rolling 30 days may not exceed the gross observed in that window. Funding that outruns sales is the shape a receivable-financing product cannot sustain.

## Step-up ladder

Pools repaid in full through store proceeds raise the limit, 10% of base each, capped at 50%. Deliberately asymmetric: a repaid pool raises the ceiling, but a loss does not lower it here, because reducing a committed limit has contractual consequences rather than being an arithmetic result.

## Abuse rules

These are **rules, not a classifier**: no training data, no score, no calibration. A signal firing is grounds for a human to look, never a finding of fraud.

| Signal                      | Verdict | Fires when                                                |
| --------------------------- | ------- | --------------------------------------------------------- |
| `RECENT_DESTINATION_CHANGE` | REVIEW  | Payout destination changed inside a 7-day cooling period  |
| `REVENUE_SPIKE`             | REVIEW  | Latest day ≥ 3× the baseline daily gross                  |
| `NEW_ACCOUNT_LARGE_REQUEST` | REVIEW  | ≥ $5,000 requested against under 14 days of history       |
| `POST_FUNDING_REFUND_SPIKE` | REVIEW  | Refunds since the last advance settled reach 25% of gross |
| `CHARGEBACK_LEVEL`          | BLOCK   | Chargebacks reach 15% of observed gross                   |

Only chargebacks block, and even then the explanation describes a stop rather than a verdict about intent: these are already-disputed transactions, so the receivable being advanced against is itself contested.

The composite verdict is the **most severe** signal, so adding a rule can never soften an existing one. Every signal carries the numbers it fired on, so a decision can be re-derived later.

## Reading it

`GET /v1/pools/{poolId}/underwriting?requestedCents=100000` returns the observation, the rate with its factors, both caps, the abuse verdict with its signals, and the stepped limit. See the [OpenAPI contract](../src/main/resources/api/openapi.json).

## What this is not

No calibration against realized losses; no cohort or vintage analysis; no identity, device, or cross-tenant signals; no appeal or override workflow; no automatic enforcement. The model reads recorded state and returns an opinion.
