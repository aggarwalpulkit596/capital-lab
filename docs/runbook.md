# Local operations

Use JDK 17, Docker, and optionally Node 24 for development tooling. The shipped browser application has no JavaScript runtime dependencies or bundling step.

```sh
docker compose up -d --wait postgres
./gradlew dashboard
```

Open <http://127.0.0.1:8080/>. The developer view starts with a quote only. Clicking Request initiates a synthetic payout. Finance and engineering views read the same run's records. The server binds loopback; do not expose this unauthenticated demo publicly.

## Settings

| Environment variable             | Default                                         | Meaning                                        |
| -------------------------------- | ----------------------------------------------- | ---------------------------------------------- |
| `LAB_JDBC_URL`                   | `jdbc:postgresql://127.0.0.1:55432/capital_lab` | Local PostgreSQL connection                    |
| `LAB_DB_USER`                    | `capital_lab`                                   | Local demo user                                |
| `LAB_DB_PASSWORD`                | `local_demo_only`                               | Local-only credential; not a production secret |
| `LAB_HTTP_PORT`                  | `8080`                                          | Loopback HTTP port                             |
| `LAB_HTTP_WORKERS`               | `4`                                             | Request worker count                           |
| `LAB_HTTP_QUEUE_CAPACITY`        | `64`                                            | Queued HTTP tasks                              |
| `LAB_MAX_ACTIVE_RUNS`            | `100`                                           | In-memory run limit per server session         |
| `LAB_EVIDENCE_DIR`               | `build/dashboard-runs`                          | Saved observation archives                     |
| `LAB_DB_LOCK_TIMEOUT_MS`         | `5000`                                          | Maximum lock wait                              |
| `LAB_DB_STATEMENT_TIMEOUT_MS`    | `15000`                                         | Maximum SQL statement time                     |
| `LAB_DB_CONNECT_TIMEOUT_SECONDS` | `5`                                             | JDBC connection timeout                        |
| `LAB_DB_SOCKET_TIMEOUT_SECONDS`  | `30`                                            | JDBC socket timeout                            |

Invalid settings fail before server startup. Statement timeout must be at least the lock timeout and below the socket timeout. Payment recovery and bank transport use separately injectable typed settings in `RuntimeConfig.kt`. The fixed commercial terms are in `FinancialTerms.kt`.

## Investigating a failed operation

Use the response's `X-Request-Id` or JSON `requestId` to locate its structured request log. Inspect `/health/ready` for database connectivity and `/api/metrics` for request/error counts. Responses distinguish input errors, oversized payloads, capacity limits, and dependency failures.

After an ambiguous bank outcome, inspect the existing payment. Keep reservations while the outcome is unknown. A risk hold permits lookup only. Do not create a replacement payment to clear the error. The workbench's lost-response scenarios demonstrate this path.

Reconciliation exceptions remain visible. A mismatch is not repaired by changing an immutable journal or inventing a fee. This demo does not implement real manual-review approvals.

## Restart and storage

Stop the server with Ctrl-C. Restart it to load new Kotlin or classpath resources. Use run history or the finance selector to inspect previous sessions as read-only archives; use a new demo account for a new writable walkthrough. JSON archives are observations, not a worker recovery queue.

`docker compose stop postgres` preserves the database volume. Each run has isolated schemas. There is currently no automatic retention/cleanup job; long-running use accumulates schemas and evidence. Back up anything you want to keep before manually removing it. Never use volume deletion as a routine restart.

## Checks

```sh
./gradlew check
npm ci --ignore-scripts
npm run format:check
npm run test:browser
```

Browser tests use port 8091 and the Compose database; install Chromium first with `npx playwright install chromium`. CI installs the browser and supplies PostgreSQL automatically. It runs desktop and mobile viewport checks and retains failure traces/screenshots. Kotlin formatting uses `./gradlew formatKotlin`; frontend/docs use `npm run format`.

Dependencies are locked, Gradle artifacts have verification checksums, the wrapper has a distribution checksum, and CI actions are commit-pinned. A legitimate Gradle dependency update requires reviewing and regenerating locks/checksums with `--write-locks --write-verification-metadata sha256`, then running normal checks without those flags. Generated checksums establish the reviewed baseline; they are not an independent security audit of dependencies.
