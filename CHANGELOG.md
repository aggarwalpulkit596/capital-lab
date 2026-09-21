# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project aims to follow
[Semantic Versioning](https://semver.org/spec/v2.0.0.html) once a first version is tagged.

Capital Lab is a teaching simulator. "Breaking" here means a change that alters a money rule, a
persisted schema, a decision-record field, or a documented HTTP contract — not only a change to a
published library API.

## [Unreleased]

### Added

- **Post-disbursement lifecycle.** Store remittances that are partial and aggregated across pools,
  with a fixed recovery -> principal -> residual waterfall; refund and chargeback revisions that
  reclassify principal as recoverable rather than forgiving it; bank returns that reverse a settled
  advance by appending a journal instead of editing one; residual release netted against open
  recovery. See [docs/lifecycle.md](docs/lifecycle.md).
- **Automatic payouts.** Payout policies (mode, per-cycle minimum and maximum, daily or weekly
  cadence) and a scheduler that reserves capacity with no human request. Cycles are unique per
  developer and period, and each reservation carries a derived idempotency key, so a re-run cannot
  fund twice.
- **Portfolio read model.** Aggregate position across every pool: headroom, open recovery, residual
  payable, and largest-pool concentration.
- **Versioned tenant API** at `/v1` with API-key authentication, per-developer tenancy, ordered
  READ/WRITE/OPERATOR scopes, and an OpenAPI 3.1 contract served from the checked-in resource.
  Facts originating outside the developer require OPERATOR scope.
- `AdvanceService.available`, a non-binding capacity quote with the policy evidence behind it.

- `--help` / `-h` on the packaged calculator, documenting the fixture argument, the default fixture
  path, and the stdout/stderr split.
- Startup preflight for the dashboard: an unreachable demo database, an invalid `LAB_*` setting, or
  an occupied port now fails before the port is bound, with the command that fixes it.
- `CommandLineTest`, pinning the command-line contract: one record per fixture case, parseable JSON
  on stdout, and refusals that name the offending field.
- Code of conduct, changelog, feature-request and question issue templates, code owners, and a
  CodeQL analysis workflow.

### Changed

- Unusable fixture input is reported as one explanatory line on stderr with exit status 1, instead
  of a Java stack trace. Unexpected exceptions still surface their stack trace so defects stay
  visible.
- Wrong argument counts exit with status 2 and print usage, separating misuse from bad input.
- Runtime configuration bounds now report the variable, the accepted range, and the rejected value
  instead of Kotlin's bare `Failed requirement.`

## [0.1.0] - 2026-09-21

The state of the repository before this changelog was introduced, summarized from its history.

### Added

- Explainable eligibility evaluator with exact cent arithmetic, report-coverage checks, reason
  codes, and JSON decision records.
- Transactional advance slice: frozen commands, reservations, dispatch intents, an HTTP bank
  simulator, lost-response recovery, and minimal funding journals on PostgreSQL.
- Local interactive dashboard with 20 guided scenarios, concurrency and fault injection, ledger and
  reconciliation views, per-run evidence archives, and JSON timeline export.
- Replay of 305 days of public UCI retail history, with recorded provenance and transformations.
- Reveal.js learning deck with 71 slides, an 18-slide interview route, speaker notes, cited sources,
  and verified PDF export.
- Architecture specification separating the implemented slice from proposed production components.
- GitHub Actions checks for backend, browser, and presentation builds; Dependabot; dependency
  locking and checksum verification.

[Unreleased]: https://github.com/aggarwalpulkit596/capital-lab/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/aggarwalpulkit596/capital-lab/releases/tag/v0.1.0
