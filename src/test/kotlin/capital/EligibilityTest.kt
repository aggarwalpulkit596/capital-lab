package capital

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.random.Random
import kotlin.test.*

class EligibilityTest {
    private val context =
        EvaluationContext(
            Instant.parse("2026-09-19T18:05:00Z"),
            LocalDate.parse("2026-09-18"),
            ZoneId.of("UTC"),
        )
    private val policy = Policy("illustrative-v1", 200_000)
    private val snapshot =
        Snapshot(
            "pool-1",
            "snapshot-1",
            LocalDate.parse("2026-09-18"),
            Instant.parse("2026-09-19T18:00:00Z"),
            100_000,
            60_000,
        )

    @Test
    fun `partly advanced pool yields 200 dollars principal and 195 dollars cash`() {
        val result = evaluate(snapshot, policy, context)
        assertEquals(Status.ELIGIBLE, result.status)
        assertEquals(80_000, result.effectiveLimitCents)
        assertEquals(20_000, result.eligiblePrincipalCents)
        assertEquals(500, result.feeCents)
        assertEquals(19_500, result.netCashCents)
        assertEquals(listOf(Reason.CAPACITY_AVAILABLE), result.reasons)
    }

    @Test
    fun `new pool yields 800 dollars principal`() {
        val result = evaluate(snapshot.copy(existingExposureCents = 0), policy, context)
        assertEquals(80_000, result.eligiblePrincipalCents)
        assertEquals(2_000, result.feeCents)
        assertEquals(78_000, result.netCashCents)
    }

    @Test
    fun `refund to exact exposure limit leaves no new capacity`() {
        val result = evaluate(snapshot.copy(netProceedsCents = 75_000), policy, context)
        assertEquals(Status.NO_CAPACITY, result.status)
        assertEquals(0, result.eligiblePrincipalCents)
        assertEquals(0, result.excessExposureCents)
    }

    @Test
    fun `refund reports excess exposure without rewriting principal`() {
        val result = evaluate(snapshot.copy(netProceedsCents = 70_000), policy, context)
        assertEquals(56_000, result.effectiveLimitCents)
        assertEquals(4_000, result.excessExposureCents)
        assertEquals(60_000, result.snapshot.existingExposureCents)
        assertEquals(0, result.eligiblePrincipalCents)
        assertTrue(Reason.EXISTING_EXPOSURE_ABOVE_LIMIT in result.reasons)
    }

    @Test
    fun `fresh download cannot make old coverage eligible`() {
        val result =
            evaluate(
                snapshot.copy(reportThroughDate = LocalDate.parse("2026-09-17")),
                policy,
                context,
            )
        assertEquals(Status.HOLD, result.status)
        assertEquals(20_000, result.arithmeticCapacityCents)
        assertEquals(0, result.eligiblePrincipalCents)
        assertEquals(0, result.feeCents)
        assertEquals(0, result.netCashCents)
        assertTrue(Reason.REPORT_BEHIND_ALLOWED_PERIOD in result.reasons)
    }

    @Test
    fun `expected prior day coverage is eligible at exact boundary`() {
        assertEquals(Status.ELIGIBLE, evaluate(snapshot, policy, context).status)
        val old = snapshot.copy(reportThroughDate = context.expectedReportThroughDate.minusDays(1))
        assertEquals(Status.HOLD, evaluate(old, policy, context).status)
    }

    @Test
    fun `explicit one day allowance admits boundary but holds older coverage`() {
        val permitted = policy.copy(allowedReportingLagDays = 1)
        val atBoundary =
            evaluate(
                snapshot.copy(reportThroughDate = LocalDate.parse("2026-09-17")),
                permitted,
                context,
            )
        assertEquals(Status.ELIGIBLE, atBoundary.status)
        assertTrue(Reason.REPORT_WITHIN_DELAY_ALLOWANCE in atBoundary.reasons)
        assertEquals(LocalDate.parse("2026-09-17"), atBoundary.minimumReportThroughDate)
        assertEquals(
            Status.HOLD,
            evaluate(
                    snapshot.copy(reportThroughDate = LocalDate.parse("2026-09-16")),
                    permitted,
                    context,
                )
                .status,
        )
    }

    @Test
    fun `hold still reports existing excess exposure`() {
        val result =
            evaluate(
                snapshot.copy(
                    reportThroughDate = LocalDate.parse("2026-09-17"),
                    netProceedsCents = 70_000,
                ),
                policy,
                context,
            )
        assertEquals(Status.HOLD, result.status)
        assertEquals(4_000, result.excessExposureCents)
        assertTrue(Reason.EXISTING_EXPOSURE_ABOVE_LIMIT in result.reasons)
    }

    @Test
    fun `ceiling constrains exposure across the whole pool`() {
        val result = evaluate(snapshot, policy.copy(exposureCeilingCents = 65_000), context)
        assertEquals(5_000, result.eligiblePrincipalCents)
        assertEquals(125, result.feeCents)
    }

    @Test
    fun `advance rounding never rounds up a fractional cent`() {
        val result =
            evaluate(
                snapshot.copy(netProceedsCents = 1, existingExposureCents = 0),
                policy,
                context,
            )
        assertEquals(0, result.effectiveLimitCents)
    }

    @Test
    fun `fee rounds half cent up and smaller fraction down`() {
        val hundredPercent = policy.copy(advanceBasisPoints = 10_000)
        assertEquals(
            1,
            evaluate(
                    snapshot.copy(netProceedsCents = 20, existingExposureCents = 0),
                    hundredPercent,
                    context,
                )
                .feeCents,
        )
        assertEquals(
            0,
            evaluate(
                    snapshot.copy(netProceedsCents = 19, existingExposureCents = 0),
                    hundredPercent,
                    context,
                )
                .feeCents,
        )
    }

    @Test
    fun `negative proceeds preserve outstanding exposure`() {
        val result = evaluate(snapshot.copy(netProceedsCents = Long.MIN_VALUE), policy, context)
        assertEquals(0, result.effectiveLimitCents)
        assertEquals(0, result.eligiblePrincipalCents)
        assertEquals(60_000, result.excessExposureCents)
    }

    @Test
    fun `maximum Long amount does not overflow percentage arithmetic`() {
        val result =
            evaluate(
                snapshot.copy(netProceedsCents = Long.MAX_VALUE, existingExposureCents = 0),
                policy.copy(exposureCeilingCents = Long.MAX_VALUE),
                context,
            )
        assertEquals(7_378_697_629_483_820_645L, result.eligiblePrincipalCents)
        assertEquals(184_467_440_737_095_516L, result.feeCents)
        assertEquals(7_194_230_188_746_725_129L, result.netCashCents)
        val fullyExposed =
            evaluate(
                snapshot.copy(existingExposureCents = Long.MAX_VALUE, netProceedsCents = 0),
                policy,
                context,
            )
        assertEquals(Long.MAX_VALUE, fullyExposed.excessExposureCents)
    }

    @Test
    fun `invalid exposure and policy are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            evaluate(snapshot.copy(existingExposureCents = -1), policy, context)
        }
        listOf(
                policy.copy(exposureCeilingCents = -1),
                policy.copy(advanceBasisPoints = 10_001),
                policy.copy(feeBasisPoints = -1),
                policy.copy(allowedReportingLagDays = -1),
                policy.copy(allowedReportingLagDays = 8),
                policy.copy(version = ""),
            )
            .forEach {
                assertFailsWith<IllegalArgumentException> { evaluate(snapshot, it, context) }
            }
        assertFailsWith<IllegalArgumentException> {
            evaluate(snapshot.copy(poolId = ""), policy, context)
        }
    }

    @Test
    fun `impossible temporal inputs are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            evaluate(
                snapshot.copy(downloadedAt = context.evaluatedAt.plusSeconds(1)),
                policy,
                context,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            evaluate(
                snapshot.copy(reportThroughDate = LocalDate.parse("2026-09-20")),
                policy,
                context,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            evaluate(
                snapshot,
                policy,
                context.copy(expectedReportThroughDate = LocalDate.parse("2026-09-20")),
            )
        }
    }

    @Test
    fun `calendar checks use the reporting zone`() {
        val boundary =
            context.copy(
                evaluatedAt = Instant.parse("2026-09-19T01:00:00Z"),
                reportingZone = ZoneId.of("America/Los_Angeles"),
            )
        val futureLocalDate =
            snapshot.copy(
                downloadedAt = boundary.evaluatedAt,
                reportThroughDate = LocalDate.parse("2026-09-19"),
            )
        assertFailsWith<IllegalArgumentException> { evaluate(futureLocalDate, policy, boundary) }
    }

    @Test
    fun `replay is deterministic and retains the decision inputs`() {
        val first = evaluate(snapshot, policy, context)
        assertEquals(first, evaluate(snapshot, policy, context))
        assertEquals(snapshot, first.snapshot)
        assertEquals(policy, first.policy)
        assertEquals(context, first.context)
    }

    @Test
    fun `less proceeds or more exposure cannot increase capacity`() {
        val random = Random(20260919)
        var casesWithCapacity = 0
        repeat(1_000) {
            val proceeds = random.nextLong(1, 500_000)
            val exposure = random.nextLong(0, 250_000)
            val current =
                snapshot.copy(netProceedsCents = proceeds, existingExposureCents = exposure)
            val baseline = evaluate(current, policy, context)
            if (baseline.eligiblePrincipalCents > 0) casesWithCapacity++
            val lessProceeds =
                evaluate(
                    current.copy(netProceedsCents = random.nextLong(0, proceeds)),
                    policy,
                    context,
                )
            val moreExposure =
                evaluate(current.copy(existingExposureCents = exposure + 1), policy, context)
            assertTrue(lessProceeds.eligiblePrincipalCents <= baseline.eligiblePrincipalCents)
            assertTrue(moreExposure.eligiblePrincipalCents <= baseline.eligiblePrincipalCents)
            assertTrue(
                baseline.eligiblePrincipalCents <=
                    (policy.exposureCeilingCents - exposure).coerceAtLeast(0)
            )
            assertEquals(baseline.eligiblePrincipalCents, baseline.feeCents + baseline.netCashCents)
        }
        assertTrue(
            casesWithCapacity > 100,
            "Exercise positive capacity as well as exhausted limits",
        )
    }
}
