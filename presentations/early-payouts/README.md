# RC Capital & Early Payouts — learning deck

A Reveal.js presentation that starts with receivables and cash timing, then explains the financial rules, Kotlin implementation, failure recovery, and future architecture. Public documentation was checked on **19 September 2026**. Implementation claims refer to Capital Lab commit **97a40ca**.

This is an independent teaching resource. Slide footers distinguish RevenueCat's published behavior, Capital Lab's implementation, worked examples, and proposals. The banking and underwriting examples are synthetic. Public product descriptions do not establish RevenueCat's private architecture, bank contracts, or accounting policies.

## Present or study

The offline package contains `index.html` (71 slides), `interview.html` (18 slides), `speaker-notes.md`, `sources.html`, and bundled Reveal.js assets. Open `index.html` directly for the slides, or serve the extracted directory for the full speaker view:

```sh
python3 -m http.server 8081 --bind 127.0.0.1
```

Open **http://127.0.0.1:8081**. The slides do not require an internet connection; external citation links do.

| Control                | Action                                                       |
| ---------------------- | ------------------------------------------------------------ |
| Arrow keys             | Previous or next slide                                       |
| M / Contents button    | Search topics and switch routes                              |
| S                      | Open speaker view with notes and timer; use the local server |
| Esc                    | Slide overview                                               |
| Contents → Scroll view | Read as a continuous document                                |
| Contents → Sources     | Open the full source index                                   |

The interactive eligibility example is slide 25 in the full deck. Change proceeds, existing exposure, the exposure ceiling, and the stale-data toggle. It demonstrates cent arithmetic and eligibility; it does not call the backend or initiate a payout. PDF export resets it to its initial example.

## Learning route

| Slides | Topics                                                                            |
| ------ | --------------------------------------------------------------------------------- |
| 1–11   | Receivables, cash timing, published terms, the 2.5% fee, worked examples          |
| 12–18  | Store reports, freshness, webhooks, refunds, exports, canonical data              |
| 19–29  | Underwriting, capacity, liquidity, risk monitoring, fraud signals, decisions      |
| 30–43  | HLD and component LLD, transactions, idempotency, outbox, bank adapters, recovery |
| 44–51  | Double-entry journals, deferred fees, reconciliation, final receipts, shortfalls  |
| 52–60  | Dashboard roles, all 20 scenarios, public data, validation, agent session         |
| 61–66  | Operations, security, scaling, deployment gaps, roadmap, future products          |
| 67–71  | Glossary, review questions, source guide                                          |

Use the 18-slide interview route for a shorter explanation of the product, engineering choices, difficult failure cases, and validation. Speaker notes provide the detail that would crowd the slides.

## Build from the repository

Run these commands from the Capital Lab repository root. Node.js 24 or newer is required; the preview command also uses Python 3.

```sh
npm ci
npm run slides
```

To build without serving, run `npm run slides:build`. Output is written to `build/presentations/early-payouts/`; it includes the local Reveal.js runtime and its MIT license. No dashboard, database, or bank simulator is needed to present.

Edit `presentations/early-payouts/deck.mjs` for content, notes, sources, and route membership; `theme.css` for layout; and `client.js` for navigation and the interactive example. Rebuild after editing. Generated HTML is not the source of truth.

## PDF export and verification

For repeatable export, install Playwright's Chromium once and run:

```sh
npx playwright install chromium
npm run slides:export
```

The exporter renders every teaching slide, checks for layout overflow, exercises the quote example and topic search, and checks the print-page counts. It writes:

- `output/presentations/rc-capital-learning.pdf` — 71 slides.
- `output/presentations/rc-capital-interview.pdf` — 18 slides.
- `build/slide-review/` — screenshots and a validation report.

Inspect the generated PDF visually after content or layout changes. The automated bounds checks do not replace that review. The initial release was also checked for nonblank PDF pages, slide titles, and text staying within each page.

For manual export in Chrome or Chromium, open **Contents → Print / PDF**, then print to PDF with landscape orientation, no margins, background graphics enabled, and browser headers and footers disabled. Use **PDF with notes** to include separate speaker-note pages. The supplied PDFs contain slides only; `speaker-notes.md` contains all notes and citations.

## License

Original presentation content and code: Apache 2.0, supplied in `LICENSE`. Bundled Reveal.js: MIT, supplied in `vendor/LICENSE-reveal.js` in the built package. Referenced third-party documentation retains its own rights. The separately attributed public retail dataset is described in the project's data README; no raw dataset is bundled with this presentation.
