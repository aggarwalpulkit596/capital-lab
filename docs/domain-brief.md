# Domain brief

## Purpose

Given an earnings snapshot and existing exposure, explain how much additional advance principal our sample policy permits. Detect when revised earnings leave existing advances above the policy limit.

The first milestone produces a quote. A quote does not reserve funds or authorize a payment.

## Public facts and boundaries

Checked on 19 September 2026:

- RevenueCat advertises advances of up to 80% of app store proceeds and a 2.5% fee on the advanced amount, with next-business-day timing subject to the product's conditions.
- RevenueCat describes both automatic and on-demand payouts.
- RevenueCat names Core Bank as its banking-services provider.

Sources:

- https://www.revenuecat.com/early-payouts
- https://www.revenuecat.com/customers/pocket-bard

These sources do not establish RevenueCat's underwriting thresholds, fraud algorithms, accounting entries, or internal system architecture. All policy and implementation choices below are our proposed learning design.

Revenue and proceeds must be distinguished. Store taxes and commissions affect proceeds; RevenueCat documents estimation considerations at https://www.revenuecat.com/docs/dashboard-and-metrics/taxes-and-commissions. We accept synthetic net proceeds directly instead of estimating store deductions.

## Vocabulary

- Receivable: money expected from a store but not yet collected.
- Advance principal: the amount advanced against that receivable, before our example fee deduction.
- Exposure: outstanding principal plus any committed reservations against the same receivable pool.
- Eligibility: the additional amount permitted by this sample policy.
- Monitoring: reevaluating that policy when inputs change, and reporting reduced capacity or excess exposure.

## Prototype model

Use one developer, one store, one currency (USD), and one identified, unsettled receivable pool per calculation. The pool's net proceeds include proceeds already supporting outstanding advances; they are not a remaining unadvanced balance. Mixing these definitions would double-subtract advances.

Inputs:

- Pool and snapshot identifiers.
- Cumulative net proceeds for that pool, after known adjustments such as refunds.
- Existing exposure against that same pool.
- A sample fixed exposure ceiling.
- Report coverage date, download timestamp, evaluation timestamp, and policy version. Download time must not be used as a substitute for report coverage.

All money uses integer cents with checked arithmetic. Negative proceeds can represent net adjustments; negative exposure or a negative ceiling is invalid input. Future timestamps are invalid. The clock is explicit for repeatable examples.

Proposed calculation:

```
receivable_limit = floor(max(0, net_proceeds_cents) * 80 / 100)
effective_limit = min(receivable_limit, exposure_ceiling_cents)
additional_capacity = max(0, effective_limit - existing_exposure_cents)
excess_exposure = max(0, existing_exposure_cents - effective_limit)
```

The ceiling and freshness threshold are illustrative policy parameters, not empirically calibrated underwriting decisions. After the user accepted the reporting-delay explanation, implementation proceeded with the assistant's recommended default: hold new quotes when report coverage is behind the expected period. An optional allowance of 0–7 extra reporting days is configurable; the default is zero. Permitting an extra day explicitly accepts the risk of quoting against older data.

## Report availability and fixture strategy

The user challenged why data would be old if we fetch the latest store reports, and suggested mock or pre-downloaded data. This prompted a more precise freshness model:

- Apple makes daily Sales and Trends reports available the following day, generally by 8 a.m. Pacific Time: https://developer.apple.com/help/app-store-connect/reference/reporting/sales-and-trends-reports-availability
- Apple distinguishes next-day Sales and Trends data from final proceeds in Payments and Financial Reports: https://developer.apple.com/help/app-store-connect/measure-app-performance/download-and-view-reports
- Google generates estimated sales reports daily, but says new transactions can take several days to appear: https://support.google.com/googleplay/android-developer/answer/2482017?hl=en

Fetching successfully now does not guarantee that a report covers the expected period or includes every adjustment. Conversely, yesterday's report may be entirely normal for the source's publication schedule. Report coverage is also not proof of final settlement completeness.

For the prototype, use synthetic normalized snapshots, not purported real store exports. Keep `reportThroughDate`, `downloadedAt`, and `evaluatedAt` separate. Supply `expectedReportThroughDate` explicitly in fixtures; a real source adapter would derive it from the reporting calendar and an agreed delay allowance. Do not claim that a simple age threshold implements both stores' schedules.

Replay saved fixtures against a fixed evaluation time so that historical test data does not become stale merely because today's date advances. Include a freshly downloaded report with old coverage to test the distinction. A hold sets eligible principal, fee, and net cash to zero while retaining the arithmetic capacity as a separately named diagnostic field. Report coverage is checked in the configured reporting time zone. The expected reporting period is a trusted fixture input, not computed by a real store adapter yet.

Fee convention implemented for the demo: 2.5% of the newly quoted principal, rounded half-up to a cent, deducted from that advance. Display principal, fee, and net cash separately. This fee-collection and rounding convention is a prototype assumption.

## Hand-worked examples to review before implementation

Assume fresh data and a $2,000 exposure ceiling throughout.

| Scenario                  | Net proceeds | Existing exposure | Effective limit | Additional capacity | Excess exposure |
| ------------------------- | -----------: | ----------------: | --------------: | ------------------: | --------------: |
| New pool                  |       $1,000 |                $0 |            $800 |                $800 |              $0 |
| Already partly advanced   |       $1,000 |              $600 |            $800 |                $200 |              $0 |
| Refund adjustment of $250 |         $750 |              $600 |            $600 |                  $0 |              $0 |
| Refund adjustment of $300 |         $700 |              $600 |            $560 |                  $0 |             $40 |

On the $200 additional advance, the example fee is $5 and net cash is $195.

The final row must preserve the $600 exposure and report a $40 policy-limit excess. Reducing a limit does not retrieve money already advanced. Excess exposure is not automatically a realized loss or proof of fraud.

These expected values were worked out by the assistant before implementation. They still need user review; they must not be described as independently user-validated yet.

## Deliberately outside the first milestone

Live banking, real customer data, settlement allocation, multiple currencies, bank-holiday scheduling, identity checks, predictive risk models, fraud classification, and payment execution.

A later reservation milestone must prevent two requests from consuming the same capacity. A pure eligibility function cannot provide that guarantee.
