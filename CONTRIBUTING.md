# Contributing

Start with the [dashboard walkthrough](docs/dashboard.md) and [architecture](docs/architecture/README.md). Contributions to original code and documentation use Apache 2.0; dataset derivatives retain their source license and attribution.

## Local development

Use JDK 17 and Docker. Node 24 runs formatting and browser-test tooling; the dashboard needs no bundling step.

```sh
./gradlew check
docker compose up -d --wait postgres
./gradlew dashboard
```

Open `http://127.0.0.1:8080`. Stop the server with Ctrl-C and the database with `docker compose stop postgres`. The Compose volume is preserved. Testcontainers creates and removes a separate temporary database for the test suite.

The UI is in `src/main/resources/dashboard`; Kotlin HTTP routes and scenario orchestration are in `capital/dashboard`; the transactional engine is in `capital/payments`. Restart the dashboard after editing compiled resources or Kotlin code. Run `npm ci --ignore-scripts`, `npm run format:check`, and the browser checks described in the [runbook](docs/runbook.md). Format Kotlin with `./gradlew formatKotlin` and frontend/docs with `npm run format`.

## Changes worth testing

- Money rules: boundary values, exact rounding, and zero/negative adjustments.
- Payment behavior: duplicate requests, unknown outcomes, holds, concurrency, and recovery after external commit.
- Collection allocation: mismatches, duplicate receipts, outstanding principal, residual liability, and closed-pool eligibility.
- Public data: attribution, transformations, source hashes, currency, and temporal leakage.

Keep scenarios deterministic. Use isolated database schemas; never reset somebody else's existing schema. New external side effects belong behind a simulator or test adapter. Explain any change to a financial invariant in the PR, including the concrete failure case and validation.

Do not present illustrative rules as a calibrated risk or fraud model. Distinguish implemented behavior, tested behavior, and future architecture.

See [engineering boundaries](docs/engineering-reference.md) before changing policy, fixtures, or runtime settings. Applied SQL resources are immutable checksum-verified migrations. Append a new resource for a schema change. Dependency updates must include reviewed lock/checksum changes. CI runs backend checks and browser regressions; do not describe a check as passed until its result is available.
