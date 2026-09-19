# Implementation and validation plan

Status: first milestone implemented and verified by the assistant. User selected Kotlin, proposed mock/saved data, challenged the freshness premise, and accepted the reporting-delay explanation. The assistant selected the remaining illustrative implementation defaults. User code and arithmetic review remains outstanding. See validation.md for executed evidence.

## Milestone 1: explainable policy evaluation

1. Review the worked examples and settle the stale-data behavior together.
   Use local synthetic fixtures with a fixed clock. Evaluate report coverage against the expected reporting period, independently of download success and download time.
2. Implement a small Kotlin domain module: typed inputs, versioned policy, deterministic evaluator, structured decision reasons.
3. Add a command-line demo with synthetic snapshots showing normal eligibility, refund adjustments, stale data, and excess exposure.
4. Emit a decision record containing the inputs, policy version, evaluation time, calculated limit, capacity, excess exposure, and reasons. A demo JSON record is not a durable production audit system.

Start with a pure function because it isolates policy arithmetic from storage and transport. A web server and dashboard can wait until they solve a demonstrated need.

Java 17 is installed. An existing Gradle distribution was found outside PATH and used to generate the Gradle 8.11.1 wrapper. The project pins Kotlin 2.1.21. Gradle required sandbox escalation for its local communication socket and dependency downloads.

## Validation before claiming completion

- Review expected values by hand before reading generated implementation output.
- Test the worked examples with literal expected amounts, not a second copy of the calculation formula.
- Exercise one-cent rounding, exact freshness boundaries, future timestamps, invalid inputs, and arithmetic overflow.
- Verify a newly downloaded report with old coverage is not treated as current, and the expected prior-day report is not held solely because it is not real-time.
- Check that increasing exposure cannot increase capacity, and reducing proceeds cannot increase capacity, with other inputs fixed.
- Check replay: the same snapshot, policy, exposure, and evaluation time yield the same decision.
- Check that a refund can create excess exposure without rewriting outstanding principal.
- If stale-data holds are selected, verify zero actionable capacity plus an explicit hold reason. Any last-known calculation must be distinguishable from an actionable quote.
- Temporarily change one relevant comparison or rounding rule and confirm a targeted test fails; restore the correct implementation. Record this as a deliberate test-strength check, not as an organically discovered agent mistake.

Passing these checks demonstrates implementation behavior under the specified policy. It does not establish predictive underwriting accuracy or production readiness.

## Milestone 2: optional transactional reservations

Update: the user authorized this milestone. PostgreSQL reservations, request idempotency, a durable dispatch intent, a separately committed HTTP bank simulator, minimal funding journals, and unknown-outcome recovery are implemented. There are 21 passing integration tests, including concurrent requests and a separate-JVM crash after bank commit. See [transactional-payments.md](transactional-payments.md) for scope and remaining work. The plan below is retained as the original milestone description.

If time permits after the policy review, add local persistence for reservation requests. Reevaluate exposure and create the reservation in the same database transaction. Persist idempotency keys and reject reuse with a different payload.

Required evidence for this milestone: repeated requests create one reservation; conflicting concurrent requests cannot jointly exceed capacity; state survives process restart. Only claim these guarantees if the milestone is implemented and tested.

## Future production questions

Identify authoritative data and reconcile estimates against store settlements; calibrate policy against historical outcomes; define manual review and operational ownership; integrate a bank sandbox; handle uncertain payment outcomes and returns; establish durable audit records and monitoring. Document these as future work, not completed features.

## Application answer

After implementation and user review, write 150–200 words covering the real context supplied, plan selection, a meaningful review or correction, validation evidence, and the learning outcome. No fabricated production impact, timing claims, test counts, or user decisions.

## Milestone 3 — interactive local lab

Completed: Kotlin HTTP dashboard, 20 guided scenarios with evidence exports, illustrative risk/fraud review signals, read-only payment reconciliation, idempotent final collection allocation, and a reproducible UCI daily-data replay. The user selected a local interview walkthrough and Apache 2.0 for code. Public data retain CC BY 4.0 attribution. Validation: 72 passing tests and desktop browser review. See [dashboard scope](dashboard.md) and [validation](validation.md). The source has since been published at [capital-lab](https://github.com/aggarwalpulkit596/capital-lab). The application remains local. See [engineering boundaries](engineering-reference.md) for the subsequent refactor.
