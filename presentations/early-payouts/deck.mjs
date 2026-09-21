export const sources = {
  product: ['RevenueCat Early Payouts', 'https://www.revenuecat.com/early-payouts'],
  pocket: [
    'RevenueCat: Pocket Bard case study',
    'https://www.revenuecat.com/customers/pocket-bard',
  ],
  opal: ['RevenueCat: Opal case study', 'https://www.revenuecat.com/customers/opal'],
  taxes: [
    'RevenueCat: Taxes and Commissions',
    'https://www.revenuecat.com/docs/dashboard-and-metrics/taxes-and-commissions',
  ],
  reports: [
    'RevenueCat: Reconciling with App Store Financial Reports',
    'https://www.revenuecat.com/docs/dashboard-and-metrics/reconciling-with-financial-reports',
  ],
  webhooks: ['RevenueCat: Webhooks', 'https://www.revenuecat.com/docs/integrations/webhooks'],
  events: [
    'RevenueCat: Event Types and Fields',
    'https://www.revenuecat.com/docs/integrations/webhooks/event-types-and-fields',
  ],
  exports: [
    'RevenueCat: Scheduled Data Exports',
    'https://www.revenuecat.com/docs/integrations/scheduled-data-exports',
  ],
  apple: [
    'Apple: Sales and Trends availability',
    'https://developer.apple.com/help/app-store-connect/reference/reporting/sales-and-trends-reports-availability/',
  ],
  appleReports: [
    'Apple: Download and view reports',
    'https://developer.apple.com/help/app-store-connect/measure-app-performance/download-and-view-reports/',
  ],
  google: [
    'Google: Sales and payout reports',
    'https://support.google.com/googleplay/android-developer/answer/2482017?hl=en',
  ],
  rtdn: [
    'Google: Real-time developer notifications',
    'https://developer.android.com/google/play/billing/rtdn-reference',
  ],
  voided: [
    'Google: Voided Purchases API',
    'https://developers.google.com/android-publisher/voided-purchases',
  ],
  repo: [
    'Capital Lab source at 97a40ca',
    'https://github.com/aggarwalpulkit596/capital-lab/tree/97a40ca',
  ],
  hld: [
    'Capital Lab: HLD and LLD',
    'https://github.com/aggarwalpulkit596/capital-lab/tree/97a40ca/docs/architecture',
  ],
  tests: [
    'Capital Lab: passing backend and browser CI',
    'https://github.com/aggarwalpulkit596/capital-lab/actions/runs/35431330379',
  ],
  data: [
    'Capital Lab: public dataset provenance',
    'https://github.com/aggarwalpulkit596/capital-lab/blob/97a40ca/data/README.md',
  ],
};

const list = (...items) => `<ul>${items.map((x) => `<li>${x}</li>`).join('')}</ul>`;
const table = (headers, rows) =>
  `<table><thead><tr>${headers.map((x) => `<th>${x}</th>`).join('')}</tr></thead><tbody>${rows.map((r) => `<tr>${r.map((x) => `<td>${x}</td>`).join('')}</tr>`).join('')}</tbody></table>`;
const lead = (text) => `<p class="lead">${text}</p>`;
const note = (text) => `<p class="annotation">${text}</p>`;
const equation = (text) => `<div class="equation">${text}</div>`;
const split = (left, right) => `<div class="columns"><div>${left}</div><div>${right}</div></div>`;
const code = (text) =>
  `<pre><code>${text.replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;')}</code></pre>`;
const slides = [];
function add(
  id,
  chapter,
  title,
  body,
  notes,
  refs = ['repo'],
  scope = 'Capital Lab',
  interview = false,
) {
  slides.push({ id, chapter, title, body, notes, refs, scope, interview });
}

add(
  'cover',
  '01 Fundamentals',
  'RC Capital &amp;<br>Early Payouts',
  '<p class="subtitle">Financial concepts, a working Kotlin reference,<br>and the architecture we could build next</p><p class="byline">Pulkit Aggarwal<br>Learning edition · 19 September 2026</p>',
  'This independent learning deck connects public RevenueCat product information to Capital Lab, the Kotlin and PostgreSQL project built in this session. It does not describe RevenueCat’s private systems. Open the chapter menu for the complete teaching route or the shorter interview route. Use S for speaker notes. The project implementation snapshot is commit 97a40ca.',
  ['product', 'repo'],
  'Independent learning project',
  true,
);
add(
  'learning-map',
  '01 Fundamentals',
  'The learning sequence',
  table(
    ['Part', 'What you will understand'],
    [
      [
        '1. Product and money',
        'Why cash timing matters, what gets advanced, and who receives each amount',
      ],
      [
        '2. Data and risk',
        'Report freshness, receivables, underwriting, monitoring, and fraud review',
      ],
      ['3. Architecture and payments', 'HLD, LLD, reservations, idempotency, and recovery'],
      [
        '4. Accounting and operations',
        'Ledgers, reconciliation, collections, validation, and future work',
      ],
    ],
  ) +
    note(
      'The full deck supports several study sessions. The interview route selects the central explanation and evidence.',
    ),
  'Follow the chapters in order if these concepts are new. Return to the appendix for acronyms. The short route omits many detailed contracts and operational extensions but keeps the main money example, architecture, correctness, and implementation boundaries.',
  ['hld'],
  'Learning guide',
);
add(
  'boundaries',
  '01 Fundamentals',
  'Three evidence boundaries',
  table(
    ['Label', 'Meaning', 'Example'],
    [
      [
        'RevenueCat public',
        'Published product or documentation claim',
        'Advertised advance and fee',
      ],
      [
        'Capital Lab',
        'Implemented behavior with repository evidence',
        'Reservation retained after an ambiguous bank result',
      ],
      [
        'Future design',
        'A proposed extension or contract-dependent decision',
        'Partial collections and bank returns',
      ],
    ],
  ) +
    note(
      'RevenueCat’s internal underwriting, bank APIs, legal structure, and accounting policy are not public facts established by this project.',
    ),
  'Labels apply to the content on each slide. Our use of an advance asset and a deferred fee is a learning accounting convention. We do not infer whether the real commercial arrangement is a recourse loan, receivables purchase, or another structure. A successful local test establishes only the behavior it exercised.',
  ['product', 'hld'],
  'Reading convention',
  true,
);
add(
  'cash-cycle',
  '01 Fundamentals',
  'Profitability and cash timing',
  lead('An app can earn money before it has cash available to spend.') +
    table(
      ['Moment', 'Business effect'],
      [
        ['Acquire a subscriber', 'Marketing spend reduces cash now'],
        ['Subscriber pays through a store', 'The app earns proceeds that may remain uncollected'],
        ['Store remits the payout', 'Cash becomes available for another growth cycle'],
      ],
    ) +
    note('Earlier liquidity helps only when the expected benefit exceeds its fee and risks.'),
  'A receivable is an expected collection. Profitability measures economic performance, while liquidity measures the ability to meet cash obligations. A subscription trial can postpone the purchase before the store payment schedule adds its own delay. RevenueCat’s Pocket Bard case illustrates the timing problem. Its reported growth-spend effect is a customer estimate, not a forecast for every app.',
  ['pocket'],
  'Concept + public example',
  true,
);
add(
  'published-terms',
  '01 Fundamentals',
  'Published Early Payouts terms',
  '<div class="numbers"><div><strong>Up to 80%</strong><span>of app-store proceeds advanced</span></div><div><strong>2.5%</strong><span>fee on the advanced amount</span></div></div>' +
    lead('The unadvanced remainder follows the regular store payout schedule.') +
    note('Availability and timing depend on enrollment and product conditions.'),
  'RevenueCat’s Pocket Bard case states the fee applies to the advanced amount and the remaining portion has no Early Payouts fee. The worked examples that follow add our own upfront deduction and rounding convention. Public marketing does not determine the full contract or all operational edge cases.',
  ['pocket'],
  'RevenueCat public',
  true,
);
add(
  'payout-modes',
  '01 Fundamentals',
  'Automatic and on-demand payouts',
  table(
    ['Mode', 'Published behavior'],
    [
      [
        'Automatic',
        'Daily next-business-day payouts above $5,000 monthly revenue, or biweekly below that',
      ],
      ['On demand', 'Choose a store and request up to 80% of the accrued balance'],
      ['Pause', 'Developers can pause the service'],
    ],
  ) +
    note(
      'Confirm the exact $5,000 boundary, enrollment rules, and banking calendar in the applicable agreement.',
    ),
  'These modes appear on the public product page. Capital Lab demonstrates a request against one pool. It does not implement a production daily or biweekly scheduler. Avoid translating business-day marketing language into a universal 24-hour guarantee.',
  ['product'],
  'RevenueCat public',
);
add(
  'onboarding',
  '01 Fundamentals',
  'Business and bank onboarding',
  table(
    ['Public information', 'Future implementation gate'],
    [
      [
        'A US entity and an account in good standing appear in the case-study conditions',
        'Verify business eligibility and authorized control',
      ],
      [
        'Core Bank provides the named banking services',
        'Confirm account ownership, permitted funds use, and responsibilities',
      ],
      [
        'The Opal story describes an agreement, partner-bank account, and store connection',
        'Verify the collection route before enabling funding',
      ],
    ],
  ) +
    note(
      'The bank relationship does not reveal its private API, rail selection, or collection rights.',
    ),
  'RevenueCat says it is not a bank. Public case studies also qualify timing around cutoffs, weekends, and bank holidays. A real implementation needs an agreed timezone and calendar, verified beneficiaries, and a contractually valid repayment route. Capital Lab has fictional verified destinations and no actual business verification.',
  ['product', 'opal', 'pocket'],
  'Public facts + future gates',
);
add(
  'money-terms',
  '01 Fundamentals',
  'Revenue, proceeds, and cash',
  table(
    ['Term', 'Meaning in this deck'],
    [
      ['Revenue', 'Sales before all store-related deductions'],
      ['Net proceeds', 'Expected amount after relevant taxes, commissions, and adjustments'],
      ['Receivable', 'Proceeds expected from the store but not yet collected'],
      ['Principal', 'Advance amount before the example financing fee deduction'],
      ['Net cash', 'Principal minus the fee deducted in our model'],
    ],
  ) +
    note(
      'The prototype accepts normalized net proceeds. It does not estimate store tax or commission schedules.',
    ),
  'RevenueCat documents proceeds estimates and transaction-specific tax and commission inputs. A flat store commission should not be hardcoded into a production ingestion adapter. Net proceeds must also distinguish currency, period, source revision, and relevant adjustments. A quote based on estimated proceeds is still separate from proof of cash collection.',
  ['taxes', 'repo'],
  'Concept + Capital Lab',
);
add(
  'full-money-example',
  '01 Fundamentals',
  'A $1,000 receivable',
  table(
    ['Cash or obligation', 'Amount'],
    [
      ['Advance principal at 80%', '$800'],
      ['Example fee: $800 × 2.5%', '$20'],
      ['Cash paid early: $800 − $20', '$780'],
      ['Residual after full collection: $1,000 − $800', '$200'],
      ['Total developer cash across both moments', '$980'],
    ],
  ) +
    note(
      'Illustrative full lifecycle. Upfront fee deduction is our convention. Actual residual disbursement remains future work.',
    ),
  'Keep principal separate from the early cash payment. The $800 principal is the amount recovered from the later $1,000 collection. The developer receives $780 early and, under the example contract, $200 later. The $20 is charged once. The demo collection service can create a residual payable, but it does not send that residual through a bank.',
  ['repo', 'hld'],
  'Worked model',
  true,
);
add(
  'remaining-advance',
  '01 Fundamentals',
  'The account shown in our demo',
  '<div class="money-line"><span>$1,000 proceeds</span><span>$600 already advanced</span></div>' +
    equation('$800 limit − $600 exposure = $200 capacity') +
    '<div class="numbers"><div><strong>$200</strong><span>new principal</span></div><div><strong>− $5</strong><span>fixed fee</span></div><div><strong>$195</strong><span>new cash paid</span></div></div>' +
    note('The $600 opening exposure is seeded data. The demo does not invent fee history for it.'),
  'The default account is already partially advanced. Do not confuse the $200 new advance with the $800 total principal after funding. The finance view counts only the new $5 fee whose funding entry actually exists in this run. Another fixture uses $900 proceeds and $400 exposure: $320 principal, $8 fee, and $312 net cash.',
  ['repo'],
  'Capital Lab',
  true,
);
add(
  'fee-economics',
  '01 Fundamentals',
  'Fees withheld and fee revenue',
  table(
    ['Quantity', 'What it tells us'],
    [
      ['Fee withheld', 'Amount deducted from a recorded funded advance'],
      ['Deferred fee balance', 'Fee amount awaiting recognition under a defined policy'],
      ['Recognized fee revenue', 'Amount earned under the finance-approved accounting treatment'],
      [
        'Contribution economics',
        'Fee income less capital cost, bank costs, losses, and operating costs',
      ],
    ],
  ) +
    note(
      'Capital Lab records a deferred fee. It does not implement revenue recognition or claim that the fee is profit.',
    ),
  'A financing fee is not a universal annual interest rate. Any time-normalized comparison requires the actual amount, timing, and contract. The 2.5% charge alone does not establish profitability. Also separate fees on advances from store commissions, bank fees, and residual cash owed to the developer.',
  ['repo', 'hld'],
  'Model and future finance policy',
);

add(
  'freshness',
  '02 Data and receivables',
  'A new download can contain old data',
  table(
    ['Time field', 'Question it answers'],
    [
      ['reportThroughDate', 'Which economic period does the report cover?'],
      ['downloadedAt', 'When did we retrieve this copy?'],
      ['evaluatedAt', 'When are we deciding whether to advance?'],
      ['expectedReportThroughDate', 'Which period should the source have published by now?'],
    ],
  ) +
    note(
      'The lab holds new quotes when coverage is behind the expected period. A successful HTTP response cannot prove completeness.',
    ),
  'This distinction came from the user’s challenge: why would data be old if we can call the store API? Publication lag, late transactions, revisions, and an outage can all leave current downloads incomplete. A historical fixture uses a fixed evaluation clock so it does not become stale merely because the test runs on a later day.',
  ['repo'],
  'Capital Lab',
  true,
);
add(
  'store-reports',
  '02 Data and receivables',
  'Store reporting clocks',
  table(
    ['Source', 'Published reporting behavior', 'Design consequence'],
    [
      [
        'Apple daily reports',
        'Next-day availability, generally by 8 a.m. Pacific',
        'Use the source publication calendar',
      ],
      [
        'Google estimated sales',
        'Daily updates to a monthly file, with some new transactions arriving days later',
        'Preserve revisions and completeness information',
      ],
      [
        'Final financial reports',
        'Different purpose from near-term sales estimates',
        'Retain settlement evidence separately',
      ],
    ],
  ),
  'Apple distinguishes Sales and Trends from Payments and Financial Reports. Google says financial reports use UTC. Download time and report coverage must therefore be separate inputs. Our expected-period fixture does not implement either store’s real calendar. Publication timing can change, so adapters need versioned calendar configuration and monitoring.',
  ['apple', 'appleReports', 'google'],
  'Official store documentation',
);
add(
  'rc-webhooks',
  '02 Data and receivables',
  'RevenueCat events as an input',
  list(
    'Verify the configured authorization and, when enabled, the documented HMAC signature over raw request bytes.',
    'Persist a durable inbox record, then acknowledge promptly and process asynchronously.',
    'Deduplicate by event identity. Tolerate new fields and event types.',
    'Separate production purchases from sandbox transactions.',
  ) +
    note(
      'A subscription event is an input to a receivables model. It does not authorize a payout.',
    ),
  'RevenueCat documents duplicate deliveries, retry behavior, and optional HMAC signing. The signature timestamp describes the delivery attempt rather than the business event time. Our proposed adapter retains both. Capital Lab does not ingest live RevenueCat webhooks. Financial effects also require semantic deduplication when several sources describe one economic event.',
  ['webhooks', 'hld'],
  'Documented input + future adapter',
);
add(
  'event-semantics',
  '02 Data and receivables',
  'Cancellation, refund, and expiration',
  table(
    ['Observation', 'Financial interpretation'],
    [
      ['Voluntary unsubscribe', 'The customer may retain paid access until expiration'],
      ['Refund', 'An economic adjustment that may reduce proceeds'],
      ['Billing issue', 'A failed charge attempt, with possible later recovery'],
      ['Expiration', 'A lifecycle/access state, not proof of a bank debit'],
    ],
  ) +
    note(
      'RevenueCat’s CANCELLATION type can represent cancellation or refund. Inspect its reason and associated transaction.',
    ),
  'The current event documentation also says subscription refunds for older periods do not trigger the same cancellation event. One event stream cannot be assumed to capture every financial adjustment. Our UCI cancellation amounts are retail invoice reversals. They are not subscription churn rates and must not be mapped directly into a production churn threshold.',
  ['events', 'data'],
  'RevenueCat semantics + model boundary',
);
add(
  'exports-finality',
  '02 Data and receivables',
  'Exports and final financial reports',
  split(
    lead('Scheduled data') +
      list(
        'RevenueCat supports selected transaction columns, scheduled delivery, and CSV or Parquet.',
        'Backfill and compare data using durable source identities.',
      ),
    lead('Settlement evidence') +
      list(
        'Apple financial reports group settlement into fiscal periods.',
        'Revenue analytics can group by transaction dates and calendar periods.',
      ),
  ) +
    note(
      'A report mismatch can be a definition or timing difference. It is not automatically missing cash.',
    ),
  'RevenueCat explains that a renewal due in one period can settle in another, and Apple’s fiscal calendar can differ from calendar months. Align currency, period definitions, refund timing, and quantity before comparing totals. Use final report and bank evidence for allocation. Scheduled data exports improve data access but do not themselves prove a store bank payout.',
  ['exports', 'reports'],
  'RevenueCat documentation',
);
add(
  'normalization',
  '02 Data and receivables',
  'A proposed source ingestion pipeline',
  table(
    ['Stage', 'Durable output'],
    [
      ['Receive or fetch', 'Raw payload, retrieval metadata, source identity, checksum'],
      ['Validate', 'Tenant/store ownership, environment, schema, currency, chronology'],
      ['Normalize', 'Economic amount, pool, period, transaction identity, revision'],
      ['Apply revision', 'Replace the matching observation without adding revenue twice'],
      ['Publish assessment input', 'Feature snapshot, source watermark, quality flags'],
    ],
  ) +
    note(
      'Production ingestion is future work. The lab begins with normalized fixtures or an attributed retail replay.',
    ),
  'Treat missing values as unknown rather than zero. A monthly-to-date report is a revised view of a period. Adding each download to yesterday’s sum would repeatedly finance the same earnings. Persist source references and transformation versions so an analyst can reproduce an assessment. Google RTDN should trigger retrieval of purchase state rather than be treated as a complete purchase record.',
  ['hld', 'rtdn'],
  'Future design',
);
add(
  'pool-model',
  '02 Data and receivables',
  'Receivable pools and obligations',
  table(
    ['Record', 'Identity and purpose'],
    [
      ['Developer', 'Business whose exposure and authorization we control'],
      ['Receivable pool', 'A defined store, currency, and economic period'],
      ['Source snapshot', 'A revision of the evidence describing that pool'],
      ['Advance', 'Frozen principal, fee, destination, and bank operation identity'],
      ['Receipt and allocation', 'Cash evidence and its assignment to principal or residual'],
    ],
  ) +
    note(
      'A subscriber identifier alone is not a developer account, a store payout, or a receivable-pool identity.',
    ),
  'The current demo uses one developer and one pool per scenario. The target design can use per-pool allocations when an advance spans several pools. Keep cross-pool borrower limits and shared treasury budgets authoritative. Pool closure prevents settled earnings from becoming eligible again.',
  ['repo', 'hld'],
  'Capital Lab + target model',
);

add(
  'underwriting',
  '03 Underwriting and risk',
  'What underwriting decides',
  lead('Whether the business and its receivables qualify, and how much exposure is acceptable.') +
    list(
      'Confirm ownership, provenance, and an operational collection route.',
      'Assess adjustments, estimate error, concentration, and possible withholding.',
      'Set limits, advance rates, evidence requirements, and review conditions.',
    ) +
    note('A fixed 80% calculator implements only a small part of this decision.'),
  'Earned-receivable risk is more specific than predicting future subscription revenue. Evidence that an app retains users does not prove the current store receivable exists, is unencumbered, or can be collected through our route. Recourse and risk-bearing require actual contractual terms. This is an architecture proposal, not a calibrated RevenueCat model.',
  ['hld'],
  'Future design',
  true,
);
add(
  'risk-inputs',
  '03 Underwriting and risk',
  'Inputs, quality, and validity',
  table(
    ['Input group', 'Assessment use'],
    [
      ['Business and store control', 'Establish eligibility and authority'],
      ['Proceeds and adjustments', 'Estimate the collectible balance and uncertainty'],
      ['Outstanding and reserved amounts', 'Measure current committed exposure'],
      ['Concentration and treasury', 'Constrain correlated risk and available liquidity'],
      ['Fraud and destination events', 'Apply scoped holds or require review'],
    ],
  ) +
    note(
      'Every decision needs an evidence version, a reason, an assessment time, and an expiry or reevaluation rule.',
    ),
  'Churn, renewals, cohort retention, and recurring revenue may inform stability and future origination. Use mature cohorts and time-aligned features. Missing data needs a specified action, such as a hold or manual review, rather than a favorable score. Production decisions require ownership, calibration, and an appeals or review process appropriate to the product.',
  ['hld'],
  'Future design',
);
add(
  'quote-calculation',
  '03 Underwriting and risk',
  'The first eligibility calculation',
  code(
    'receivableLimit = floor(max(0, proceeds) × advanceRate)\neffectiveLimit  = min(receivableLimit, exposureCeiling)\ncapacity        = max(0, effectiveLimit − existingExposure)\nexcessExposure  = max(0, existingExposure − effectiveLimit)',
  ) +
    note(
      'Freshness hold: actionable principal, fee, and net cash become zero. Diagnostic arithmetic remains visible.',
    ),
  'The calculator works in integer cents and uses widened arithmetic for rate multiplication. It rejects invalid policy inputs and impossible timestamps. This pure function returns an explanation. A later transaction must recheck current state before funds can be committed. Tax estimation and source calendar computation remain upstream responsibilities.',
  ['repo'],
  'Capital Lab',
);
add(
  'capacity-controls',
  '03 Underwriting and risk',
  'Outstanding, reserved, and lifetime funded',
  table(
    ['Symbol', 'Meaning', 'Changes when'],
    [
      ['O', 'Outstanding principal', 'Funding increases it, repayment reduces it'],
      [
        'R',
        'Reserved principal',
        'Reservation increases it, completion or safe cancellation releases it',
      ],
      [
        'F',
        'Lifetime principal funded against this pool',
        'Funding increases it, ordinary repayment leaves it unchanged',
      ],
    ],
  ) +
    equation('New capacity respects both exposure and unused origination') +
    note(
      'The service also checks remaining receivables, borrower limits, closed pools, holds, and net funding cash.',
    ),
  'A single outstanding balance cannot stop a repaid receivable from being financed again. The implementation limits origination to floor(total proceeds × rate) minus F minus other reservations. It separately checks remaining proceeds after settled amounts, borrower exposure O plus R, and treasury cash. Own-reservation exclusion during dispatch avoids counting the same order twice.',
  ['repo'],
  'Capital Lab',
  true,
);
add(
  'no-recycling',
  '03 Underwriting and risk',
  'Repayment does not create new earnings',
  table(
    ['Example pool', 'Value'],
    [
      ['Original proceeds', '$1,000'],
      ['Lifetime funded F', '$800'],
      ['Outstanding O after some repayment', '$300'],
      ['Unused lifetime origination', '$800 − $800 = $0'],
    ],
  ) + lead('A lower outstanding balance does not make this same $1,000 pool newly advanceable.'),
  'This example isolates why F and O have different meanings. Actual post-repayment authorization also considers how much economic receivable remains and whether the pool is closed. A new economic period needs a new correctly identified pool. A write-off likewise must not silently regenerate origination capacity.',
  ['repo', 'hld'],
  'Worked model',
);
add(
  'refund-example',
  '03 Underwriting and risk',
  'A refund can erase capacity',
  table(
    ['Revised proceeds', '80% limit', 'Existing exposure', 'New capacity', 'Excess'],
    [
      ['$1,000', '$800', '$600', '$200', '$0'],
      ['$750', '$600', '$600', '$0', '$0'],
      ['$700', '$560', '$600', '$0', '$40'],
    ],
  ) +
    note(
      'Reducing a limit stops new funding. It does not erase an existing obligation or rewrite history.',
    ),
  'The $40 is exposure above the new policy limit, not automatically a realized credit loss. The system records the state and may open a review. At final collection, compare the amount collected with actual outstanding principal before identifying a shortfall. Distinguish observed revisions from assumptions about recovery.',
  ['repo'],
  'Capital Lab',
);
add(
  'interactive-quote',
  '03 Underwriting and risk',
  'Try the eligibility rule',
  `<form id="quote-lab" class="quote-lab"><div class="quote-inputs"><label>Net proceeds, USD<input name="proceeds" type="number" min="0" max="1000000" step="1" value="1000"></label><label>Existing exposure, USD<input name="exposure" type="number" min="0" max="1000000" step="1" value="600"></label><label>Exposure ceiling, USD<input name="ceiling" type="number" min="0" max="1000000" step="1" value="2000"></label><label class="check"><input name="stale" type="checkbox"> Coverage is stale</label></div><output id="quote-output" aria-live="polite"></output></form>` +
    note(
      'Teaching calculation only. Fixed 80% / 2.5% terms, whole-dollar inputs, exact cent outputs. No reservation or bank call.',
    ),
  'Change proceeds from 1000 to 700 with exposure at 600. Capacity becomes zero and excess exposure becomes $40. Mark coverage stale with the original values: the diagnostic $200 capacity remains, but actionable principal, fee, and net cash become zero. This JavaScript teaching example mirrors the basic quote, not the complete transactional payment engine. PDF shows the default example.',
  ['repo'],
  'Interactive teaching model',
);
add(
  'monitoring',
  '03 Underwriting and risk',
  'Continuous monitoring',
  table(
    ['Implemented illustrative rule', 'Outcome'],
    [
      ['Cancellation amount ≥ 10% of gross sales', 'Hold for review'],
      ['Gross sales ≥ 3× preceding 7 observed days’ average', 'Hold for review'],
      ['Fewer than 7 prior observations or zero baseline', 'Limited-history information signal'],
    ],
  ) +
    note(
      'These thresholds belong to a versioned demo policy. They have not been calibrated for lending or subscription fraud.',
    ),
  'The input is a monetary cancellation/reversal amount, not the number of customers who turned off renewal. The baseline excludes the current day and uses observed days, not zero-filled calendar days. Limited history is visible but does not independently hold the current demo. A production policy would explicitly decide which missing assessments prevent funding.',
  ['repo', 'data'],
  'Capital Lab',
);
add(
  'fraud',
  '03 Underwriting and risk',
  'Fraud review and financial risk',
  table(
    ['Risk', 'Possible evidence', 'Response'],
    [
      [
        'Fabricated or reversible proceeds',
        'Revenue spike plus adjustments or source inconsistency',
        'Review the affected pool',
      ],
      [
        'Account takeover or diversion',
        'Verified destination changed, unusual account activity',
        'Hold the destination and inspect authority',
      ],
      [
        'Ordinary business deterioration',
        'Higher refunds, shrinking proceeds, concentration',
        'Reassess limits and collectibility',
      ],
    ],
  ) + note('A spike can come from a successful campaign. A review signal is not a fraud verdict.'),
  'Store-based financing does not automatically expose raw card data, issuer signals, or device fingerprints. Use only evidence the platform actually has authority to collect. Google’s Voided Purchases API supplies specific void/refund information but should not be treated as a universal refund feed. Correlated signals should not be naively summed into a probability.',
  ['hld', 'voided'],
  'Future design',
);
add(
  'review',
  '03 Underwriting and risk',
  'A scoped review lifecycle',
  table(
    ['Stage', 'Persistent record or action'],
    [
      ['Signal', 'Type, source, time, evidence reference, affected scope'],
      ['Assessment', 'Allow, review, or block with reasons and expiry'],
      ['Case', 'Owner, evidence, status, deadline, disposition'],
      ['Hold', 'Gate tied to the developer, pool, or destination'],
      ['Resolution', 'Authorized action with version checks and an audit trail'],
    ],
  ) +
    note(
      'Clearing one review case must not clear unrelated underwriting, onboarding, or liquidity gates.',
    ),
  'A critical destination change should install a gate synchronously before slower scoring runs. Monitoring can enrich the case afterward. Held payments with unknown bank outcomes remain reserved and queryable. The current dashboard exposes illustrative signals and scenario-driven holds, not a production case-management approval workflow.',
  ['hld', 'repo'],
  'Future design',
);
add(
  'risk-model-roadmap',
  '03 Underwriting and risk',
  'Predictive risk models require outcomes',
  list(
    'Start with observable rules and explainable reasons.',
    'Collect labeled estimate errors, losses, recoveries, and review outcomes.',
    'Use time-based splits, mature outcomes, and leakage-resistant features.',
    'Evaluate loss by dollars, false positives, concentration, and policy impact.',
    'Deploy in shadow mode before allowing a score to change capital exposure.',
  ),
  'A high classification accuracy can coexist with large financial loss if rare large advances fail. Monitor calibration and drift, and assess the cost of holding legitimate developers. Sampling only funded applications creates selection bias. Models cannot override integrity gates such as an unverified destination or absent collection authority.',
  ['hld'],
  'Future design',
);

add(
  'hld',
  '04 Architecture and payments',
  'High-level design',
  table(
    ['Boundary', 'Responsibilities', 'Durable state'],
    [
      [
        'Source adapters',
        'Reports, verified events, normalized revisions',
        'Raw evidence and receivables',
      ],
      [
        'Kotlin financial core',
        'Risk gates, quote, reservation, accounting',
        'PostgreSQL controls and journals',
      ],
      [
        'Payment workers',
        'Claim, submit, recover, record evidence',
        'Outbox, leases, bank identities',
      ],
      [
        'Partner adapter',
        'Provider contracts, transfer calls, transaction facts',
        'Provider evidence references',
      ],
      [
        'Developer and finance views',
        'Requests, status, review, reconciliation',
        'Read projections and audited commands',
      ],
    ],
  ) + note('Start as logical modules with one financial database. The bank commits independently.'),
  'HLD means responsibilities, boundaries, and end-to-end interactions. These rows do not imply five microservices or five teams. The reference uses synchronous scenario orchestration for teaching. The target architecture introduces background workers and an evidence inbox while keeping related money updates transactional. This is our proposed architecture, not RevenueCat’s private deployment.',
  ['hld', 'repo'],
  'Reference HLD',
  true,
);
add(
  'end-to-end',
  '04 Architecture and payments',
  'The end-to-end lifecycle',
  table(
    ['Phase', 'Financial effect'],
    [
      ['Onboard, ingest, assess', 'Establish evidence and permission to consider funding'],
      ['Quote and reserve', 'Explain terms, then atomically commit capacity and cash'],
      ['Dispatch and observe', 'Call the bank and resolve the existing operation'],
      ['Post and reconcile', 'Record supported cash movement once'],
      ['Collect and allocate', 'Repay principal, establish residual, close the pool'],
    ],
  ),
  'Each phase has different authority. A source estimate does not become a cash entry. A quote does not consume capacity. A successful local commit does not prove a bank transfer. A bank receipt does not prove which store period it settles. The lifecycle becomes reliable by retaining those distinctions and requiring explicit evidence at every boundary.',
  ['hld'],
  'Reference design',
);
add(
  'lld',
  '04 Architecture and payments',
  'Low-level records and contracts',
  table(
    ['Relationship', 'Constraint'],
    [
      ['Developer has pools and advances', 'Borrower exposure spans all pools'],
      [
        'Advance has a frozen provider command',
        'Retries preserve amount, currency, destination, and key',
      ],
      ['Advance has a dispatch outbox row', 'Commit the intent together with the reservation'],
      [
        'Journal has entries and a posting identity',
        'Per-currency debits equal credits, posting occurs once',
      ],
      ['Receipt has allocations', 'Allocated cash cannot exceed the confirmed receipt'],
    ],
  ) +
    note(
      'LLD specifies data, invariants, interfaces, and transaction boundaries. It does not require a separate service for every entity.',
    ),
  'The implementation uses SQL constraints, uniqueness, locks, and append-only posting restrictions. The broader target adds tenant-bound authorization, source revisions, scoped assessments, inbox facts, and many-to-many settlement allocations. An API request must derive tenant identity from authentication, not trust a client-supplied tenant field.',
  ['repo', 'hld'],
  'Implemented subset + future LLD',
);
add(
  'code-boundaries',
  '04 Architecture and payments',
  'Policy, configuration, and simulation',
  table(
    ['Location', 'Owns'],
    [
      ['FinancialTerms', 'Versioned advance and fee terms plus exact cents arithmetic'],
      ['MonitoringPolicy', 'Illustrative cancellation and velocity thresholds'],
      ['RuntimeConfig', 'Validated database, HTTP, recovery, and transport settings'],
      ['simulation', 'Fictional identities, seed balances, deterministic clock'],
      [
        'payments / collections / reconciliation',
        'Financial transitions and independent evidence checks',
      ],
    ],
  ) +
    note(
      'Quoting and reservation share the same policy. A policy change cannot silently rewrite an already frozen fee.',
    ),
  'The user identified excessive hardcoding and loosely related mutable variables. The refactor separated financial policy, operational configuration, and fixture data. Scenario progress and observations became cohesive records. A test injects a 70% advance rate and 1.25% fee, then checks the quote, reservation, bank debit, and ledger. A retry under default policy still returns the original frozen fee.',
  ['repo', 'tests'],
  'Capital Lab',
  true,
);
add(
  'reservation',
  '04 Architecture and payments',
  'A quote can become stale immediately',
  lead('Two callers can both see the last $200 of capacity.') +
    table(
      ['Without transactional reservation', 'With the implemented control'],
      [
        [
          'Both read the same available amount',
          'Each request locks and rereads authoritative controls',
        ],
        ['Both decide to pay', 'Only the first valid reservation consumes the remaining capacity'],
        [
          'A UI button attempts to prevent duplicates',
          'Database uniqueness and locks protect all callers',
        ],
      ],
    ) +
    note(
      'The scenario races eight database clients. Frontend state is not the financial correctness mechanism.',
    ),
  'A quote is useful for a human-facing price explanation but is not authorization. Funding cash can also disappear between a quote and reservation. Multiple pools can share a borrower ceiling, and multiple borrowers can share treasury cash. The tests exercise these shared constraints rather than only a single browser click.',
  ['repo', 'tests'],
  'Capital Lab',
  true,
);
add(
  'reservation-transaction',
  '04 Architecture and payments',
  'The reservation transaction',
  table(
    ['Order', 'Within one PostgreSQL transaction'],
    [
      ['1', 'Lock treasury, developer, and affected financial controls consistently'],
      ['2', 'Return an identical existing request or reject a conflicting payload'],
      ['3', 'Recheck coverage, holds, destination, limits, origination, and cash'],
      ['4', 'Freeze the advance and reserve principal plus net funding cash'],
      ['5', 'Insert the dispatch intent and commit'],
    ],
  ) + note('The bank call happens after commit and outside the database transaction.'),
  'Global lock ordering prevents conflicting acquisition patterns. The outbox closes the gap between recording a reservation and recording work to dispatch it. It does not make a remote bank commit atomic with PostgreSQL. On rollback, neither capacity nor an orphaned payment command should remain.',
  ['repo', 'hld'],
  'Capital Lab',
);
add(
  'idempotency',
  '04 Architecture and payments',
  'Idempotency at every boundary',
  table(
    ['Boundary', 'Durable identity', 'Duplicate behavior'],
    [
      [
        'Client request',
        'Developer + request key + payload hash',
        'Same result, or conflict for changed input',
      ],
      [
        'Bank submission',
        'Frozen provider key and payload',
        'Follow the provider’s verified replay contract',
      ],
      [
        'Bank observation',
        'Transaction identity and economic role',
        'Do not post the same cash fact twice',
      ],
      ['Ledger posting', 'Unique business posting key', 'No second journal for the same effect'],
      ['Collection allocation', 'Receipt identity', 'No second repayment'],
    ],
  ) +
    note(
      'Repeated delivery is normal. The objective is one supported financial effect for each business identity.',
    ),
  'Transport attempts and economic operations are different. Two callbacks with different event IDs can describe the same bank debit. A callback and statement can also overlap. Deduplicate the economic fact as well as the envelope. Provider idempotency scopes and key-retention windows vary and require a contract.',
  ['repo', 'hld'],
  'Capital Lab + future adapter',
  true,
);
add(
  'dispatch-lease',
  '04 Architecture and payments',
  'Dispatch, leases, and stale workers',
  table(
    ['Mechanism', 'Purpose'],
    [
      ['Final recheck', 'Catch new holds or a changed destination before submission'],
      ['Lease', 'Allow another worker to recover after the current worker stops'],
      ['Generation token', 'Reject a stale worker’s attempt to finish an old claim'],
      ['Frozen command', 'Keep retries on the same amount, destination, and bank key'],
    ],
  ) +
    note(
      'Lease expiry permits recovery of the same operation. It does not prove that no money moved.',
    ),
  'A worker first commits a dispatch claim, releases database locks, and calls the adapter. A later generation can claim recovery after the lease ends. When a response returns, the local finishing transaction validates the generation. This protects local transitions while the stable bank identity addresses duplicate external attempts.',
  ['repo'],
  'Capital Lab',
);
add(
  'payment-states',
  '04 Architecture and payments',
  'The implemented payment state model',
  table(
    ['State', 'Meaning', 'Allowed direction'],
    [
      ['READY', 'Reserved before dispatch', 'Claim or safe cancellation'],
      ['DISPATCHING', 'A worker may have submitted', 'Observe or recover after lease'],
      ['UNKNOWN', 'Outcome unresolved', 'Retain reservation, resolve identity'],
      ['SETTLED', 'Confirmed and posted funding', 'Terminal in this lab'],
      ['REJECTED / CANCELED', 'Definite rejection or safe cancellation', 'Release reservation'],
    ],
  ) +
    note(
      'Real providers need additional accepted, debit, settlement, and return facts. The simulator collapses some of these distinctions.',
    ),
  'Do not interpret this state machine as universal bank semantics. A provider’s 200 response might mean accepted rather than settled. The target design retains independent timestamps and bank transaction facts, including later returns, without erasing the original settlement history.',
  ['repo', 'hld'],
  'Capital Lab',
);
add(
  'lost-response',
  '04 Architecture and payments',
  'The bank paid, but the response vanished',
  table(
    ['Observation', 'Bank', 'Capital Lab'],
    [
      ['Reserve $200 principal', 'No transfer yet', '$200 principal and $195 cash reserved'],
      ['Submit the frozen command', 'Debits $195', 'Response body is lost'],
      ['Record uncertainty', 'One completed transfer', 'UNKNOWN, reservation retained'],
      ['Apply risk hold', 'Existing transfer still exists', 'New funding is held'],
      ['Query the same bank identity', 'Returns existing transfer', 'Post once and reconcile'],
    ],
  ) + note('Expected result: one bank POST, one lookup, one funding journal.'),
  'This is the central failure example. The application and bank commit independently. The lost response does not roll back the bank. The user chose query-only recovery during a hold, so the system can discover and account for an existing payment without initiating a previously unexecuted one.',
  ['repo', 'tests'],
  'Capital Lab',
  true,
);
add(
  'hold-recovery',
  '04 Architecture and payments',
  'Recovery during a risk hold',
  table(
    ['Lookup result', 'Action while held'],
    [
      ['Existing settled transfer', 'Record evidence and post its confirmed effect once'],
      [
        'Not found or inconclusive',
        'Keep the original reservation and continue controlled investigation',
      ],
      ['Network error', 'Remain uncertain and preserve identity'],
      [
        'Hold later clears',
        'Revalidate before any same-key submission permitted by the bank contract',
      ],
    ],
  ) + lead('No resubmission during the hold.'),
  'A not-found lookup may be eventually consistent at a real provider, so it is not automatically definitive nonexecution. The demo also tests a hold introduced during lookup and checks it again before resubmission. This costs resolution speed but prevents recovery from creating a new payment after risk permission has been withdrawn.',
  ['repo', 'tests'],
  'User-selected invariant',
  true,
);
add(
  'timeout-bug',
  '04 Architecture and payments',
  'The response-body timeout defect',
  table(
    ['Discovery', 'Correction'],
    [
      [
        'The bank committed and sent a truncated response',
        'A request timeout alone did not reliably bound body consumption',
      ],
      ['The test worker stalled', 'Inspect the blocked call and isolate the full response future'],
      [
        'Bound completion of the future',
        'Cancel transport on timeout and classify the outcome as unknown',
      ],
    ],
  ) +
    note(
      'Fault injection exposed a real implementation defect. Passing the happy path would not have found it.',
    ),
  'The test suite and simulator reproduced a partially delivered response after bank execution. The fix bounded the asynchronous future including response-body completion. Recovery kept the bank identity and reservation. This is strong evidence for the agent-coding story because it explains how a generated change was challenged and corrected.',
  ['repo', 'tests'],
  'Observed project defect',
);
add(
  'bank-contract',
  '04 Architecture and payments',
  'The bank adapter’s capability contract',
  table(
    ['Capability', 'Question to resolve before launch'],
    [
      ['Idempotency', 'What is the key scope, retention period, and mismatch behavior?'],
      ['Recovery', 'Can an unknown operation be found reliably by our reference?'],
      ['Lifecycle', 'What proves acceptance, debit, settlement, rejection, or return?'],
      [
        'Evidence feeds',
        'How do callbacks, pagination, and statements identify the same cash event?',
      ],
      [
        'Accounts and destinations',
        'What establishes ownership, verification, and payment permission?',
      ],
    ],
  ) +
    note(
      'These are provider-neutral questions. Capital Lab does not implement a private Core Bank API.',
    ),
  'The adapter handles authentication and transport mapping. It does not recompute principal or decide risk policy. Preserve original provider statuses and raw evidence references. If safe recovery is unavailable, ambiguous execution cannot be automated by guessing. Never switch banks to retry an operation that may already have executed.',
  ['hld'],
  'Future design',
);
add(
  'rails-calendar',
  '04 Architecture and payments',
  'Banking rails and business calendars',
  table(
    ['Concern', 'Why it affects the product'],
    [
      [
        'Rail capability',
        'Different mechanisms have different settlement, cancellation, and return semantics',
      ],
      [
        'Cutoff and holiday calendar',
        'A next-business-day promise depends on when the provider accepts the instruction',
      ],
      [
        'Available cash',
        'Ledger cash, provider holds, and our reservations can describe different balances',
      ],
      ['Destination change', 'Existing orders stay bound to the verified version they accepted'],
    ],
  ) +
    note(
      'Rail names alone do not establish guarantees. The contracted provider determines supported behavior.',
    ),
  'ACH, wire, and instant-payment rails are potential integration choices, not claims about RevenueCat’s implementation. The product’s published case-study conditions mention timing cutoffs and nonbusiness days. A real scheduler must settle the timezone, daylight-saving interpretation, provider calendar, and risk refresh policy before promising arrival.',
  ['hld', 'pocket'],
  'Future design',
);

add(
  'ledger-primer',
  '05 Ledger and reconciliation',
  'Double-entry accounting basics',
  table(
    ['Account type', 'A debit usually', 'A credit usually'],
    [
      ['Asset, such as funding cash or an advance receivable', 'Increases it', 'Decreases it'],
      ['Liability, such as developer payable', 'Decreases it', 'Increases it'],
      ['Income, such as earned fees', 'Decreases it', 'Increases it'],
    ],
  ) +
    equation('Total debits = total credits, within one currency') +
    note('Debit does not simply mean money out. The account type determines its effect.'),
  'We use a simplified operational subledger. A balanced journal is necessary but not sufficient: it can balance while naming the wrong developer, currency, period, or evidence. Posting rules need finance ownership. A ledger entry records an economic fact according to policy, while a reservation encumbers capacity before that fact exists.',
  ['hld'],
  'Accounting concepts',
);
add(
  'funding-journal',
  '05 Ledger and reconciliation',
  'The implemented funding journal',
  table(
    ['Account', 'Debit', 'Credit'],
    [
      ['Advance principal receivable', '$200', ''],
      ['Funding cash', '', '$195'],
      ['Deferred fee', '', '$5'],
      ['Total', '$200', '$200'],
    ],
  ) + lead('One confirmed $195 bank debit supports $200 principal and a $5 deferred fee.'),
  'The simulator supplies settlement and cash evidence together. The implementation posts a single balanced funding journal, then moves reserved principal into outstanding and lifetime-funded balances atomically. A duplicate response or replay cannot create a second posting. Opening fixture balances are not a complete opening general ledger.',
  ['repo'],
  'Capital Lab',
  true,
);
add(
  'future-postings',
  '05 Ledger and reconciliation',
  'The future ledger needs more stages',
  table(
    ['Separate event', 'Example accounting role'],
    [
      ['Bank debit before settlement', 'Payout in transit'],
      ['Fee becomes earned', 'Move deferred fee to recognized revenue under approved policy'],
      ['Unattributed store cash arrives', 'Collection cash and unapplied-cash liability'],
      ['Collection becomes attributable', 'Repay principal and establish developer payable'],
      ['Residual payout or internal sweep', 'Track its own transfer and confirmed cash movement'],
    ],
  ) +
    note(
      'These extensions are specified, not implemented. Actual accounting treatment depends on contracts and finance policy.',
    ),
  'The full target example separates bank debit from settlement, allocation from receipt, and payable creation from payout. A sweep returning recovered principal to a funding account is its own bank operation. Residual customer liabilities cannot be used as unrestricted company funding liquidity. The demo keeps collection cash distinct from funding cash but does not implement these complete flows.',
  ['hld'],
  'Future design',
);
add(
  'reconciliation-levels',
  '05 Ledger and reconciliation',
  'Three reconciliation questions',
  table(
    ['Level', 'Compare'],
    [
      ['Payment', 'Frozen cash instruction, provider operation, bank debit, and funding journal'],
      ['Receivable', 'Earlier proceeds estimate, final store report, and attributed receipt'],
      [
        'Ledger and balances',
        'External cash movements and closing balances against posted accounts',
      ],
    ],
  ) + note('A matching amount alone cannot establish ownership or settlement identity.'),
  'Payment matching needs a stable key and transfer reference plus amount, currency, status, and posting evidence. Receivable matching also needs the correct store account, period, and report. A general cash reconciliation must handle unmatched receipts and fee debits as their own supported facts. Capital Lab implements a bounded subset of these responsibilities.',
  ['repo', 'hld'],
  'Capital Lab + future design',
  true,
);
add(
  'matching',
  '05 Ledger and reconciliation',
  'Matching and allocation',
  table(
    ['Step', 'Required evidence or invariant'],
    [
      ['Import and deduplicate', 'Account ownership, currency, stable external identities'],
      ['Find a unique supported match', 'Store report, pool, transfer, or receipt reference'],
      ['Lock and allocate', 'Allocation cannot exceed confirmed cash or outstanding principal'],
      ['Post and close', 'Journal, allocation, pool state, and effect identity commit together'],
      ['Leave exceptions open', 'Ambiguous cash needs evidence and an owner'],
    ],
  ) +
    note(
      'Amount and date similarity can suggest a candidate. They cannot authorize an unsupported payout.',
    ),
  'A production reconciler supports one-to-many and many-to-one allocations. Feed cursors advance only after durable page ingestion. Independent statement checks help detect missed callbacks. A human resolution records an authorized financial or nonfinancial explanation rather than changing a screen status without evidence.',
  ['hld'],
  'Future algorithm',
);
add(
  'collection',
  '05 Ledger and reconciliation',
  'A final $1,000 collection',
  table(
    ['Before collection', 'After allocation'],
    [
      ['$800 total outstanding principal', '$800 principal repaid'],
      ['$1,000 matched store report and receipt', '$200 residual payable'],
      ['Pool may still be unsettled', 'Pool closed to further origination'],
      ['$800 lifetime funded F', '$800 lifetime funded F, unchanged'],
    ],
  ) +
    note(
      'The dashboard’s $600 opening principal plus $200 new advance creates the $800 outstanding balance.',
    ),
  'The current CollectionService accepts one final report and one confirmed receipt per pool, applies principal first, and creates the residual obligation. It handles duplicate allocation safely, including concurrent calls. It does not support partial or aggregated receipts, actual residual bank payouts, or an automatic sweep into funding cash.',
  ['repo', 'tests'],
  'Capital Lab',
);
add(
  'collection-exceptions',
  '05 Ledger and reconciliation',
  'Shortfall and mismatch are different',
  table(
    ['Evidence', 'Result'],
    [
      [
        '$700 final report and $700 receipt against $800 principal',
        'Repay $700, retain $100 outstanding, create no residual, close the pool',
      ],
      [
        '$1,000 report but $995 bank receipt',
        'Keep the receipt unapplied and investigate the $5 difference',
      ],
      ['Same receipt delivered again', 'Return the existing allocation without another repayment'],
    ],
  ) +
    note(
      'The unexplained $5 difference is not the advance fee. That fee was already withheld at funding.',
    ),
  'A final collection shortfall is supported by matching evidence that is smaller than the obligation. A mismatch means the report and bank disagree. The lab intentionally treats these as separate scenarios. Whether a developer owes the remaining principal is a contractual question. The current service retains the obligation and reports the shortfall without inventing collection rights.',
  ['repo'],
  'Capital Lab',
  true,
);
add(
  'returns',
  '05 Ledger and reconciliation',
  'Returns and accounting corrections',
  list(
    'Retain original settlement evidence when a later return arrives.',
    'Link return cash to the original transfer and remaining obligations.',
    'Append an approved reversal or adjustment instead of editing a posted journal.',
    'Quarantine automatic reversal if repayment or residual payout already changed the balances.',
  ) +
    note(
      'Returns are part of the target design. The current simulator’s SETTLED state is terminal.',
    ),
  'An accepted transfer, a bank debit, a settlement fact, and a later return should not be compressed into a universal irreversible flag. Partial returns require amount-based remaining balances. Reversing an entire funded asset after part was repaid could create negative balances. Fee reversal and loss treatment require the actual contractual and accounting policy.',
  ['hld'],
  'Future design',
);

add(
  'dashboard',
  '06 The working reference',
  'Three views of the same evidence',
  table(
    ['Audience', 'Primary task'],
    [
      ['Developer payouts', 'Review principal, fee, and net cash before requesting an advance'],
      ['Finance operations', 'Inspect payouts, fees withheld, risk, ledger, and reconciliation'],
      ['Engineering workbench', 'Run faults, concurrency, and evidence-oriented scenarios'],
    ],
  ) +
    note(
      'Each selected account or scenario is isolated. The UI does not combine unrelated run balances.',
    ),
  'The user asked for simpler product flows and visible fees. The resulting developer view shows the $200 / $5 / $195 breakdown. Finance reads the same backend records and sums settled recorded fees, excluding seed history. Scenario archives remain read-only after restart. The demo has no production authentication or real account operations.',
  ['repo'],
  'Capital Lab',
  true,
);
add(
  'scenarios-a',
  '06 The working reference',
  'Scenarios: funding and uncertainty',
  table(
    ['Scenario', 'Expected behavior'],
    [
      ['Normal advance', 'One reservation, transfer, and funding journal'],
      ['Paid, response lost, hold appears', 'Query the existing transfer and post once'],
      ['Unknown, not found, hold active', 'Preserve reservation without resubmitting'],
      ['Retry after nonexecution', 'Reuse the same frozen bank identity'],
      ['Worker stops after bank payment', 'Recover the same payment after lease expiry'],
    ],
  ),
  'These are five of the twenty catalog scenarios. The browser workbench simulates a worker interruption. The integration suite additionally halts an actual child JVM after external execution and validates recovery. The real database and simulator use independent transactions, while both remain local and synthetic.',
  ['repo', 'tests'],
  'Capital Lab',
);
add(
  'scenarios-b',
  '06 The working reference',
  'Scenarios: authorization and source changes',
  table(
    ['Scenario', 'Expected behavior'],
    [
      ['Definite bank rejection', 'Release reservation, no funding journal'],
      ['Hold before dispatch', 'Cancel only a provably undispatched payment'],
      ['Destination changes', 'Do not silently replace the frozen beneficiary'],
      ['Fresh download, old coverage', 'Hold the quote and prevent reservation'],
      ['Refunds reduce proceeds', 'Report reduced capacity and excess exposure'],
    ],
  ),
  'These scenarios distinguish events before submission from uncertainty after possible execution. A new hold can safely cancel an undispatched request but must not release a reservation merely because an unknown payment is inconvenient. A fresh transport response does not override stale report coverage.',
  ['repo'],
  'Capital Lab',
);
add(
  'scenarios-c',
  '06 The working reference',
  'Scenarios: monitoring and concurrency',
  table(
    ['Scenario', 'Expected behavior'],
    [
      ['Revenue spike', 'Illustrative review hold'],
      ['Higher cancellation amount', 'Illustrative review hold'],
      ['Credit available, cash unavailable', 'Reject reservation for insufficient funding cash'],
      ['Eight distinct requests', 'One winner for the last capacity'],
      ['Eight copies of one request', 'One operation, conflict if the payload changes'],
    ],
  ),
  'Credit risk and liquidity are separate. Financial correctness must survive distinct simultaneous callers as well as duplicate delivery of the same command. The monitoring rules are demonstrations, with explicit thresholds and missing-history behavior rather than claims of model accuracy.',
  ['repo'],
  'Capital Lab',
);
add(
  'scenarios-d',
  '06 The working reference',
  'Scenarios: reconciliation and replay',
  table(
    ['Scenario', 'Expected behavior'],
    [
      ['Statement differs by $1', 'Open an exception without rewriting the journal'],
      ['Final store collection', 'Repay principal and create residual payable once'],
      ['Final collection shortfall', 'Retain the shortfall and close the pool'],
      ['Report differs from receipt', 'Keep cash unapplied'],
      ['Public retail day', 'Normalize, assess, fund if eligible, then match evidence'],
    ],
  ),
  'These complete the twenty scenarios. A deliberately altered statement copy does not change the simulated bank’s actual record. The collection mismatch is distinct from a confirmed smaller final settlement. Public replay supplies real historical retail observations but all banking amounts remain synthetic.',
  ['repo', 'data'],
  'Capital Lab',
);
add(
  'public-data',
  '06 The working reference',
  'The public dataset pipeline',
  table(
    ['Stage', 'Recorded value or transformation'],
    [
      ['Source', 'UCI Online Retail, Daqing Chen, 2015, CC BY 4.0'],
      ['Original workbook', '541,909 rows'],
      ['Excluded records', '2,517 with nonpositive price or zero quantity'],
      ['Retained observations', '539,392 invoice lines aggregated into 305 days'],
      [
        'Teaching conversion',
        'Illustrative 1.25 USD/GBP, historical day mapped to a fresh demo period',
      ],
    ],
  ) +
    note('The importer removes customer identifiers and preserves attribution and source hashes.'),
  'The import uses decimal arithmetic for extended line amounts, half-up minor-unit rounding, and deterministic daily aggregation. Cancellation invoice prefixes or negative quantities represent reversals in this retail dataset. Source and workbook SHA-256 hashes are recorded in data/README.md. Reimporting produced identical bundled output.',
  ['data'],
  'Capital Lab',
);
add(
  'data-limits',
  '06 The working reference',
  'What this dataset cannot establish',
  list(
    'Subscription renewal, churn, and cohort retention behavior',
    'Actual app-store receivables or payout settlement schedules',
    'Verified fraud, default, or recovery labels',
    'Production underwriting accuracy or the economic effect of Early Payouts',
  ) +
    note(
      'Its useful role is reproducible pipeline and workflow exercise with real retail variation.',
    ),
  'The baseline uses only prior observed days. It does not fill missing days with zeros or use future observations. The fixed currency rate is a transparent teaching conversion, not historical FX. Real subscription model development requires an appropriate dataset, permissions, privacy controls, and matured outcomes.',
  ['data', 'hld'],
  'Dataset limitation',
);
add(
  'validation',
  '06 The working reference',
  'Evidence behind the implementation',
  '<div class="numbers"><div><strong>84</strong><span>backend tests</span></div><div><strong>6</strong><span>desktop/mobile browser tests</span></div></div>' +
    list(
      'Real PostgreSQL races, duplicate requests, and immutable balanced postings',
      'Lost responses, an actual JVM halt, and hold-aware recovery',
      'Policy consistency, migration checksums, evidence archives, and lock timeouts',
    ) +
    note(
      'Passing CI at implementation commit 97a40ca. These tests do not certify a real banking integration.',
    ),
  'The backend total includes calculator, configuration, monitoring, evidence, payment, and dashboard integration suites. The browser cases cover fee review, payout request and reload, finance records, hold recovery, public replay, and viewport layout. Tests assert specific invariants. They do not establish throughput, calibrated credit risk, legal validity, or production rail behavior.',
  ['tests', 'repo'],
  'Executed evidence',
  true,
);
add(
  'agent-session',
  '06 The working reference',
  'The agent-assisted engineering session',
  table(
    ['Human direction', 'Engineering response'],
    [
      [
        'Challenge why freshly fetched data could be old',
        'Separate report coverage from download time',
      ],
      [
        'Choose query-only recovery during a hold',
        'Preserve reservation and existing bank identity',
      ],
      [
        'Ask for visible fees and simpler product tasks',
        'Show principal, fee, net cash, and focused finance views',
      ],
      [
        'Identify hardcoding and scattered state',
        'Share policy and isolate configuration, fixtures, and observations',
      ],
    ],
  ) +
    note(
      'The strongest story connects a decision to an invariant, an implementation, and evidence that it holds.',
    ),
  'Context came from public product material, a domain brief, explicit assumptions, worked examples, HLD/LLD, and repository tests. The implementation plan grew from a pure calculator to transactional reservations, an independent bank simulator, collections, and a dashboard. The agent generated and tested code. Do not claim the applicant manually wrote or reviewed every line, or built a production RevenueCat system.',
  ['repo', 'hld', 'tests'],
  'Session evidence',
  true,
);

add(
  'operations',
  '07 Operations and future work',
  'Operating the local reference',
  table(
    ['Implemented control', 'Purpose'],
    [
      [
        'Validated JDBC and HTTP bounds',
        'Limit lock wait, statement time, requests, and queued work',
      ],
      ['Liveness and readiness', 'Separate process health from database reachability'],
      [
        'Request IDs and structured metrics',
        'Diagnose failures without logging financial bodies or credentials',
      ],
      [
        'Checksummed SQL installation',
        'Detect edited migration history and serialize installation',
      ],
      ['Atomic evidence archives', 'Prevent old writers replacing newer snapshots'],
    ],
  ) +
    note('Archive JSON is an observation, not a payment authorization or a worker recovery queue.'),
  'The server keeps active runs in memory and financial state in PostgreSQL. On restart, stored runs become read-only archives. Evidence files and database commits are not one atomic transaction. The current JDBC layer opens individual connections, and the HTTP server is a bounded local server rather than a production gateway.',
  ['repo'],
  'Capital Lab',
);
add(
  'security',
  '07 Operations and future work',
  'Production security responsibilities',
  table(
    ['Area', 'Future requirement'],
    [
      [
        'Identity and tenancy',
        'Authenticated developers, role-scoped operations, tenant-bound data',
      ],
      ['Financial commands', 'Authorization, version checks, audit evidence, separation of duties'],
      [
        'Secrets and evidence',
        'Credential rotation, encryption, redaction, retention, least privilege',
      ],
      [
        'Environment isolation',
        'Separate accounts, keys, callbacks, and data for test and production',
      ],
      ['Recovery', 'Backups, restore drills, incident ownership, bank escalation'],
    ],
  ) +
    note(
      'The demo binds loopback and uses synthetic money. It must not be exposed as an authenticated financial service.',
    ),
  'Business verification and financial compliance depend on the actual product and partner agreement. Bank integration does not transfer all responsibilities automatically. A production system must define ownership of fraud review, account restrictions, customer funds, privacy, and financial adjustments. This is a launch-work inventory, not legal advice.',
  ['hld', 'repo'],
  'Future design',
);
add(
  'scaling',
  '07 Operations and future work',
  'Scaling the system deliberately',
  table(
    ['Observed pressure', 'Possible response'],
    [
      ['Source arrival bursts', 'Separate ingestion workers, durable queues, bounded backfills'],
      [
        'Treasury or borrower lock contention',
        'Measure transaction time, partition safe budgets, shorten critical sections',
      ],
      [
        'Read and reporting load',
        'Use projections or replicas without authorizing from stale reads',
      ],
      [
        'Provider outages and rate limits',
        'Backoff, circuit controls, aging queues, operator escalation',
      ],
      [
        'More providers or regions',
        'Define ownership and reconciliation before splitting money state',
      ],
    ],
  ) +
    note(
      'The project has no production load benchmark. These are decision triggers, not measured RevenueCat traffic.',
    ),
  'Start with a modular monolith and one financial writer region. Logical worker separation can scale without creating distributed financial databases. Portfolio and borrower constraints may make naive partitioning unsafe. Unknown in-flight operations stay with their original provider, even during a migration or outage.',
  ['hld'],
  'Future design',
  true,
);
add(
  'roadmap',
  '07 Operations and future work',
  'A staged implementation roadmap',
  table(
    ['Stage', 'Exit evidence'],
    [
      ['Current reference', 'Policy, reservations, recovery, basic ledger and collection tests'],
      [
        'Real source ingestion',
        'Authorized adapters, revisions, completeness, reconciliation to final reports',
      ],
      [
        'Bank contract integration',
        'Sandbox and provider-confirmed recovery, lifecycle, and statement behavior',
      ],
      [
        'Full financial operations',
        'Partial collections, returns, residual payout, sweeps, audited case resolution',
      ],
      [
        'Controlled production pilot',
        'Security, restore drills, risk review, exposure caps, and operational ownership',
      ],
    ],
  ),
  'Advancing to a stage means collecting its evidence, not only finishing code. A sandbox cannot establish every production rail behavior. Begin a pilot with explicit exposure limits and operational review. Real loss and estimate-error outcomes can later support calibrated policy changes.',
  ['hld'],
  'Future design',
  true,
);
add(
  'future-products',
  '07 Operations and future work',
  'Possible extensions',
  table(
    ['Hypothesis', 'Dependency to solve first'],
    [
      [
        'Flexible schedules and partial on-demand requests',
        'Calendar-aware authorization and per-pool allocation',
      ],
      ['Dynamic advance limits', 'Reliable source history and evaluated risk outcomes'],
      [
        'More stores, currencies, or banking partners',
        'Separate currency ledgers, FX policy, contracts, and attribution',
      ],
      [
        'Longer-duration growth capital',
        'New underwriting, funding economics, repayment, and legal analysis',
      ],
      [
        'Finance forecasting and self-service cases',
        'Trusted data projections and audited permissions',
      ],
    ],
  ) +
    note(
      'These are exploration ideas for Capital Lab. They are not an announced RevenueCat roadmap.',
    ),
  'The infrastructure may be reusable, but a new financial product changes risk duration, contract requirements, and capital economics. Avoid assuming the 80% / 2.5% example policy transfers to every extension. Reuse tested identity, ledger, and recovery primitives while reassessing the commercial model.',
  ['hld'],
  'Product hypotheses',
);
add(
  'open-questions',
  '07 Operations and future work',
  'Questions before real money moves',
  list(
    'Which receivables can be collected through the agreed route, and who bears a shortfall?',
    'What bank evidence establishes debit, settlement, return, and definitive nonexecution?',
    'Who owns risk thresholds, review decisions, fee recognition, and financial corrections?',
    'How will partial or aggregated receipts be attributed and residuals released?',
    'What limits, recovery objectives, and incident procedures bound the first pilot?',
  ),
  'These questions connect engineering decisions to dependencies outside code. A useful interview discussion can explain a proposed default, the failure it prevents, and the evidence needed to change it. The project does not need to pretend that all provider, finance, and legal facts are already known.',
  ['hld'],
  'Future launch gates',
  true,
);

add(
  'glossary-money',
  '08 Reference',
  'Financial vocabulary',
  table(
    ['Term', 'Meaning'],
    [
      [
        'Receivable / advance principal',
        'Expected store collection / amount advanced before the example fee',
      ],
      ['Exposure / reservation', 'Committed risk / capacity held for pending funding'],
      [
        'Residual / shortfall',
        'Developer amount remaining after repayment / unpaid principal after a smaller final collection',
      ],
      [
        'Liquidity / reconciliation',
        'Usable cash / comparison of independent records and evidence',
      ],
      [
        'ROAS / CAC / LTV',
        'Return on ad spend / customer acquisition cost / customer lifetime value',
      ],
    ],
  ),
  'Define each metric’s currency, time window, and gross-versus-net basis before using it in a decision. A ROAS ratio over a chosen observation window does not by itself establish long-term profitability or immediate available cash. Risk assessments should distinguish measured outcomes from forecasts.',
  ['hld'],
  'Glossary',
);
add(
  'glossary-engineering',
  '08 Reference',
  'Engineering vocabulary',
  table(
    ['Term', 'Meaning'],
    [
      [
        'HLD / LLD',
        'System responsibilities and boundaries / detailed records, contracts, and transitions',
      ],
      [
        'Outbox / inbox',
        'Durable work recorded with local state / durable receipt of external events',
      ],
      [
        'Idempotency / fencing',
        'Repeated requests preserve one effect / stale workers cannot finish newer claims',
      ],
      ['Basis points', '10,000 basis points = 100%, so 250 basis points = 2.5%'],
      [
        'Watermark / revision',
        'Source coverage position / a newer observation of the same economic data',
      ],
    ],
  ),
  'These terms solve different failure modes. An outbox does not supply bank idempotency. A lease does not prove nonexecution. A watermark is not evidence that every future adjustment is known. A balanced journal is not sufficient evidence that the cash belongs to the selected developer.',
  ['hld', 'repo'],
  'Glossary',
);
add(
  'study-questions',
  '08 Reference',
  'Explain the system without the slides',
  list(
    'Why can $1,000 of proceeds yield $780 early cash and $200 later?',
    'Why does a current download still need a coverage check?',
    'Why do outstanding O and lifetime funded F both exist?',
    'What happens when the bank paid, the response disappeared, and a hold appeared?',
    'Why does a $995 receipt against a $1,000 report stay unresolved?',
  ),
  'Answers: $800 principal has a $20 example fee, leaving $780 cash and $200 residual. Download time differs from economic coverage. O tracks unpaid obligations while F prevents reusing the same earnings. The chosen hold policy allows lookup only and retains reservations until evidence resolves the operation. The unexplained $5 receipt difference is not a second advance fee.',
  ['repo', 'hld'],
  'Review exercise',
);
add(
  'sources-product',
  '08 Reference',
  'Product and RevenueCat documentation',
  table(
    ['Source', 'Used for'],
    [
      [
        'Early Payouts and customer stories',
        'Published terms, payout modes, partner, and onboarding context',
      ],
      ['Taxes and Commissions', 'Revenue versus estimated proceeds'],
      [
        'Webhooks and Event Types',
        'Delivery, authentication, duplication, and lifecycle semantics',
      ],
      ['Scheduled Data Exports', 'Batch access to selected transaction data'],
      [
        'Reconciling with Financial Reports',
        'Transaction dates, fiscal periods, and settlement differences',
      ],
    ],
  ) +
    note(
      'Clickable source links appear on the relevant slides and in speaker notes. Checked 19 September 2026.',
    ),
  'Public documentation can change. Recheck product terms and provider conditions before making a production decision. No private RevenueCat underwriting policy, bank API, or internal architecture was available for this deck.',
  ['product', 'pocket', 'opal', 'taxes', 'webhooks', 'events', 'exports', 'reports'],
  'Source guide',
);
add(
  'sources-reference',
  '08 Reference',
  'Store documents and project evidence',
  table(
    ['Source', 'Used for'],
    [
      ['Apple reports and availability', 'Publication timing and report purposes'],
      [
        'Google reports, RTDN, and voided purchases',
        'Delayed observations, state retrieval, and adjustment limits',
      ],
      ['Capital Lab source and HLD/LLD', 'Implemented behavior and proposed extensions'],
      ['CI run at 97a40ca', '84 backend and 6 browser tests'],
      ['Dataset provenance', 'Retail source, attribution, transformations, and limitations'],
    ],
  ) +
    note(
      'Source code and original documentation: Apache 2.0. UCI-derived data: CC BY 4.0. Reveal.js: MIT.',
    ),
  'The code and architecture links are pinned to the implementation snapshot discussed in the deck. The source package contains editable slide content, a reproducible Reveal.js build, and the complete source index. Data licensing is separate from the original project’s code license.',
  ['apple', 'appleReports', 'google', 'rtdn', 'voided', 'repo', 'hld', 'tests', 'data'],
  'Source guide',
);

for (const id of [
  'cash-cycle',
  'remaining-advance',
  'hold-recovery',
  'collection-exceptions',
  'scaling',
  'open-questions',
]) {
  slides.find((slide) => slide.id === id).interview = false;
}

export { slides };
