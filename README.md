# Capital Lab

[![Check](https://github.com/aggarwalpulkit596/capital-lab/actions/workflows/check.yml/badge.svg)](https://github.com/aggarwalpulkit596/capital-lab/actions/workflows/check.yml)
[![CodeQL](https://github.com/aggarwalpulkit596/capital-lab/actions/workflows/codeql.yml/badge.svg)](https://github.com/aggarwalpulkit596/capital-lab/actions/workflows/codeql.yml)
[![License: Apache 2.0](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)
[![JDK 17](https://img.shields.io/badge/JDK-17-orange.svg)](https://adoptium.net/temurin/releases/?version=17)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.1.21-7F52FF.svg)](https://kotlinlang.org)

A local interactive lab for understanding app-store receivables financing: explainable eligibility, risk holds, transactional reservations, simulated bank failures, balanced journals, and reconciliation. Built with Kotlin, PostgreSQL, and a dependency-free browser interface.

**20 guided scenarios · 305 public-data replay days · 163 passing backend tests · Apache 2.0 code**

All money is synthetic. The public dataset is real retail history, with a separately attributed CC BY 4.0 license; it is not app-store or labeled fraud data. This is an independent learning project, not RevenueCat's implementation or a validated underwriting model.

## Contents

| I want to…                              | Go to                                                                         |
| --------------------------------------- | ----------------------------------------------------------------------------- |
| See it running in five minutes          | [Interactive dashboard](#interactive-dashboard)                               |
| Learn the domain from slides            | [Learning presentation](#learning-presentation)                               |
| Run the tests and the calculator        | [Run the backend](#run-the-backend)                                           |
| Understand the numbers                  | [What the demo shows](#what-the-demo-shows)                                   |
| Read the code                           | [Review the implementation](#review-the-implementation)                       |
| Follow the money after it leaves        | [Lifecycle and tenant API](docs/lifecycle.md)                                 |
| See how risk sets the rate              | [Risk-based underwriting](docs/underwriting.md)                               |
| Know what is real and what is simulated | [Engineering reference](#engineering-reference)                               |
| Contribute                              | [CONTRIBUTING.md](CONTRIBUTING.md) · [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md) |

## Requirements

| Tool   | Version | Needed for                                                 |
| ------ | ------- | ---------------------------------------------------------- |
| JDK    | 17      | Everything. The Gradle wrapper pins the build tool itself. |
| Docker | any     | Dashboard, integration tests, and the payment demo.        |
| Node   | 24      | Slide deck, browser tests, and formatting only.            |

Nothing else is installed globally, and the dashboard has no runtime JavaScript dependencies.

## Interactive dashboard

Requires JDK 17 and Docker:

```sh
docker compose up -d --wait postgres
./gradlew dashboard
```

Open **http://127.0.0.1:8080**. Start with **Paid, but the response vanished**, then use **Next step** to follow the reservation, lost response, risk hold, and query-only recovery. Inspect decisions, bank calls, outbox records, ledger entries, and reconciliation exceptions. Export the resulting timeline as JSON.

The dashboard also runs concurrent reservation races, duplicate requests, stale reports, illustrative fraud-review rules, bank rejection, final collection repayment and shortfall, and a replay of real public data. **How it connects** explains the component boundaries.

See the [five-minute walkthrough and scope](docs/dashboard.md), [dataset provenance](data/README.md), and [contribution guide](CONTRIBUTING.md). No Node build step is required. The database volume and per-run evidence are preserved; stop the server with Ctrl-C and the database with `docker compose stop postgres`.

## Learning presentation

The [Reveal.js teaching deck](presentations/early-payouts/README.md) covers fundamentals through implementation and future architecture, with 71 slides, an 18-slide interview route, speaker notes, cited sources, and an interactive eligibility example. Run `npm ci` and `npm run slides`, then open **http://127.0.0.1:8081**. PDF export and offline viewing instructions are included.

## Run the backend

Requires JDK 17. The Gradle wrapper pins the build tool and downloads dependencies on first use.

```sh
./gradlew test
./gradlew -q run
```

For the transactional slice, Docker is also required:

```sh
./gradlew check                          # both suites; temporary PostgreSQL container
docker compose up -d --wait postgres     # persistent local demo database
./gradlew paymentDemo                    # execute, lose response, query, recover once
docker compose stop postgres
```

See [transactional payment walkthrough](docs/transactional-payments.md) for the state machine, user's recovery decision, scope, and test details. The demo saves `build/payment-demo.json` and preserves its uniquely named database schemas in the Compose volume.

To keep Gradle caches in the project, add `--gradle-user-home .gradle-user-home`. The agent used this option during setup and validation.

Build and run the packaged calculator:

```sh
./gradlew installDist
build/install/advance-eligibility-lab/bin/advance-eligibility-lab --help
build/install/advance-eligibility-lab/bin/advance-eligibility-lab
```

An optional argument selects a different fixture JSON file; run from this directory to use the default path. Decision records go to stdout as a JSON array, so the output pipes into `jq`. Unusable input is reported on stderr as one explanatory line naming the offending field, and exits with status 1; a wrong argument count exits with status 2.

## What the demo shows

Each scenario describes an alternative snapshot of one synthetic pool, not a sequence of executed payments. Amounts below are USD.

| Scenario                                   | New principal quoted | Fee | Net cash quoted | Explanation                                   |
| ------------------------------------------ | -------------------: | --: | --------------: | --------------------------------------------- |
| $1,000 proceeds, $600 outstanding          |                 $200 |  $5 |            $195 | Expected report is present                    |
| Same amounts, coverage one day behind      |                   $0 |  $0 |              $0 | Hold, despite a recent download               |
| Proceeds revised to $700, $600 outstanding |                   $0 |  $0 |              $0 | Existing exposure is $40 above the $560 limit |
| $900 proceeds, $400 outstanding            |                 $320 |  $8 |            $312 | Calculation discussed with the user           |

The JSON decision records preserve source inputs, policy parameters, evaluation time, reasons, and amounts. `arithmeticCapacityCents` is diagnostic only; `eligiblePrincipalCents` is the quote after freshness checks. A quote reserves no capacity and moves no money.

## Review the implementation

- [Policy evaluator](src/main/kotlin/capital/Eligibility.kt): pure calculation, report coverage checks, reason codes, exact cent arithmetic.
- [JSON adapter and demo](src/main/kotlin/capital/Main.kt): fixture loading and decision records; missing/fractional amounts and unsupported currencies are rejected.
- [Financial and policy tests](src/test/kotlin/capital/EligibilityTest.kt).
- [Fixture and input tests](src/test/kotlin/capital/FixtureTest.kt).
- [Transactional reservation and recovery service](src/main/kotlin/capital/payments/AdvanceService.kt).
- [HTTP bank simulator](src/main/kotlin/capital/payments/FakeBankServer.kt).
- [PostgreSQL integration tests](src/test/kotlin/capital/payments/ReservationIntegrationTest.kt).
- [Store remittances, refund revisions, bank returns, residual release](src/main/kotlin/capital/settlement).
- [Automatic payout policy and scheduler](src/main/kotlin/capital/automation/PayoutAutomation.kt).
- [Versioned tenant API](src/main/kotlin/capital/api) and its [OpenAPI 3.1 contract](src/main/resources/api/openapi.json).
- [Risk-based advance rate, portfolio caps, and abuse rules](src/main/kotlin/capital/underwriting).

The policy uses an illustrative 80% advance rate and 2.5% fee. Limits round down; fees round half-up and are deducted from principal. The optional reporting allowance defaults to zero extra days. Expected coverage is supplied by the fixture, not calculated from Apple or Google's reporting calendars.

The payment slice persists frozen commands, reservations, dispatch intents, simulator transfer records, and minimal funding journals. The dashboard adds read-only payment reconciliation and bounded allocation of one final store receipt per pool, including duplicate protection and shortfalls. There is no real bank integration, trained risk model, or full settlement platform. Simulator behavior and database tests do not establish production banking guarantees. The quote function itself remains side-effect free.

## Engineering reference

The default developer view shows principal, the fixed 2.5% fee, and net payout together. Finance has focused payout, risk, ledger, and reconciliation views. The engineering workbench retains the fault scenarios and detailed evidence.

- [Implemented code boundaries and financial guarantees](docs/engineering-reference.md)
- [Local configuration and operations](docs/runbook.md)

## Project notes

- [Full architecture specification: HLD and component LLD](docs/architecture/README.md) — broader target design; the implemented reservation/simulator slice is explicitly distinguished from proposed production components.
- [Domain brief and worked examples](docs/domain-brief.md)
- [Implementation and validation plan](docs/implementation-plan.md)
- [Validation evidence](docs/validation.md)
- [Dashboard walkthrough](docs/dashboard.md)
- [Public dataset and transformations](data/README.md)
- [Post-disbursement lifecycle and tenant API](docs/lifecycle.md)
- [Risk-based underwriting](docs/underwriting.md)
- [Local deployment boundary](SECURITY.md)
- [Release history](CHANGELOG.md)

## Contributing

Issues and pull requests are welcome. Read [CONTRIBUTING.md](CONTRIBUTING.md) for the local setup, the changes that need tests, and the rules that protect financial invariants; participation is governed by the [Code of Conduct](CODE_OF_CONDUCT.md). Report anything security-sensitive through the repository's private reporting rather than a public issue, as described in [SECURITY.md](SECURITY.md).

## License and release status

Original code and documentation use [Apache License 2.0](LICENSE). The UCI-derived dataset retains [CC BY 4.0 attribution](NOTICE).

[Source repository](https://github.com/aggarwalpulkit596/capital-lab) · [GitHub Actions checks](https://github.com/aggarwalpulkit596/capital-lab/actions/workflows/check.yml)

The dashboard runs locally; publishing the source does not deploy a public web service. Personal application drafts, local evidence, credentials, caches, and raw downloaded data are excluded by `.gitignore`.
