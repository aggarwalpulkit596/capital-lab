package capital.underwriting

import kotlin.test.*

/**
 * The underwriting model is pure, so it is pinned by hand-worked expectations rather than by a
 * database. Every figure below was computed from the stated policy before the code ran.
 */
class RiskModelTest {

    private fun steady(days: Int, daily: Long, refund: Long = 0, chargeback: Long = 0) =
        Observation(
            tenureDays = days,
            grossCents = daily * days,
            refundCents = refund,
            chargebackCents = chargeback,
            dailyGrossCents = List(days) { daily },
        )

    // ------------------------------------------------------------------ advance rate

    @Test
    fun `clean established history keeps the full base rate`() {
        val decision = advanceRate(steady(180, 10_000))
        assertEquals(8_000, decision.basisPoints)
        assertTrue(decision.factors.isEmpty())
        assertTrue(decision.explanation.contains("No adjustment"))
    }

    @Test
    fun `refund bands step the rate down and the harshest band wins`() {
        // 180 days x 10,000 = 1,800,000 gross. 3% refunds -> only the 2% band.
        assertEquals(7_500, advanceRate(steady(180, 10_000, refund = 54_000)).basisPoints)
        // 6% -> the 5% band, not the sum of the 2% and 5% bands.
        assertEquals(6_500, advanceRate(steady(180, 10_000, refund = 108_000)).basisPoints)
        // 12% -> the 10% band.
        assertEquals(5_000, advanceRate(steady(180, 10_000, refund = 216_000)).basisPoints)
    }

    @Test
    fun `the refund rate counts chargebacks alongside refunds`() {
        val observation = steady(180, 10_000, refund = 27_000, chargeback = 27_000)
        assertEquals(300, observation.refundRateBasisPoints)
        assertEquals(7_500, advanceRate(observation).basisPoints)
    }

    @Test
    fun `short history reduces the rate until the established threshold`() {
        assertEquals(6_500, advanceRate(steady(10, 10_000)).basisPoints)
        assertEquals(7_500, advanceRate(steady(60, 10_000)).basisPoints)
        assertEquals(8_000, advanceRate(steady(90, 10_000)).basisPoints)
    }

    @Test
    fun `dispersed daily sales reduce the rate`() {
        val spiky =
            Observation(
                tenureDays = 180,
                grossCents = 400_000,
                refundCents = 0,
                chargebackCents = 0,
                // Mean 100,000; mean absolute deviation 150,000 -> 15,000 bps.
                dailyGrossCents = listOf(0, 0, 0, 400_000),
            )
        assertEquals(15_000, spiky.volatilityBasisPoints)
        assertEquals(7_000, advanceRate(spiky).basisPoints)
        assertEquals(0, steady(180, 10_000).volatilityBasisPoints)
    }

    @Test
    fun `penalties are additive and the floor bounds the worst case`() {
        // 12% refunds (-3000), 10 days (-1500), fully dispersed (-1000) = 2500, below the floor.
        val bad =
            Observation(
                tenureDays = 10,
                grossCents = 400_000,
                refundCents = 48_000,
                chargebackCents = 0,
                dailyGrossCents = listOf(0, 0, 0, 400_000),
            )
        val decision = advanceRate(bad)
        assertEquals(3_000, decision.basisPoints)
        assertEquals(
            listOf("REFUND_RATE", "SHORT_HISTORY", "VOLATILE_SALES", "FLOOR_APPLIED"),
            decision.factors.map { it.code },
        )
        assertTrue(decision.explanation.contains("30.00%"))
    }

    @Test
    fun `good history never raises the rate above the base`() {
        val decision = advanceRate(steady(3_650, 10_000))
        assertEquals(8_000, decision.basisPoints)
        assertTrue(decision.factors.none { it.deltaBasisPoints > 0 })
    }

    @Test
    fun `a developer with no observed sales is not divided by zero`() {
        val empty = Observation(0, 0, 0, 0, listOf())
        assertEquals(0, empty.refundRateBasisPoints)
        assertEquals(0, empty.volatilityBasisPoints)
        assertEquals(6_500, advanceRate(empty).basisPoints)
    }

    @Test
    fun `negative observations are rejected rather than clamped`() {
        assertFailsWith<IllegalArgumentException> { Observation(-1, 0, 0, 0, listOf()) }
        assertFailsWith<IllegalArgumentException> { Observation(1, -1, 0, 0, listOf()) }
        assertFailsWith<IllegalArgumentException> { Observation(1, 0, 0, 0, listOf(-1)) }
        assertFailsWith<IllegalArgumentException> {
            UnderwritingPolicy("v", baseBasisPoints = 1_000, floorBasisPoints = 2_000)
        }
    }

    // ------------------------------------------------------------------ exposure caps

    @Test
    fun `concentration limits how much may sit behind one pool`() {
        // Cap 60% above a 1,000,000 portfolio. Pool holds 0 of 2,000,000; x satisfies
        // 0.6*(2000000+x) >= 0+x, so x <= 3,000,000 and the 1,000,000 request passes.
        val fresh = assessExposure(1_000_000, 0, 2_000_000, 0, steady(30, 200_000))
        assertEquals(
            3_000_000,
            fresh.constraints.single { it.code == "CONCENTRATION" }.permittedCents,
        )
        assertEquals(1_000_000, fresh.permittedCents)

        // Pool already holds 1,500,000 of 2,000,000: past the cap, so nothing more.
        val concentrated = assessExposure(100_000, 1_500_000, 2_000_000, 0, steady(30, 200_000))
        assertEquals(
            0,
            concentrated.constraints.single { it.code == "CONCENTRATION" }.permittedCents,
        )
        assertEquals(0, concentrated.permittedCents)
        assertEquals("CONCENTRATION", concentrated.binding!!.code)
    }

    @Test
    fun `the concentration cap does not block a first advance`() {
        // Every first advance puts 100% of the portfolio behind one pool. A cap with no size
        // floor would refuse every new developer.
        val first = assessExposure(500_000, 0, 0, 0, steady(30, 100_000))
        assertEquals(500_000, first.permittedCents)
        assertTrue(
            first.constraints
                .single { it.code == "CONCENTRATION" }
                .explanation
                .contains("below the")
        )
    }

    @Test
    fun `velocity limits new principal against observed gross`() {
        // 30 days x 100,000 = 3,000,000 observed; 1x multiple; 2,500,000 already funded.
        val assessment = assessExposure(1_000_000, 0, 0, 2_500_000, steady(30, 100_000))
        assertEquals(
            500_000,
            assessment.constraints.single { it.code == "VELOCITY" }.permittedCents,
        )
        assertEquals(500_000, assessment.permittedCents)
        assertEquals("VELOCITY", assessment.binding!!.code)
        assertTrue(assessment.explanation.contains("VELOCITY"))
    }

    @Test
    fun `the tightest constraint binds and a permitted request is reported as such`() {
        val assessment = assessExposure(50_000, 0, 0, 0, steady(30, 100_000))
        assertEquals(50_000, assessment.permittedCents)
        assertTrue(assessment.explanation.contains("Every portfolio constraint permits"))
    }

    @Test
    fun `exposure caps never permit more than was requested`() {
        val assessment = assessExposure(1_000, 0, 0, 0, steady(365, 1_000_000))
        assertEquals(1_000, assessment.permittedCents)
    }

    // ------------------------------------------------------------------ step-up ladder

    @Test
    fun `repaid pools raise the limit up to a cap`() {
        val base = 1_000_000L
        assertEquals(base, stepUpLimit(base, steady(90, 1_000).copy(repaidPools = 0)))
        assertEquals(1_100_000, stepUpLimit(base, steady(90, 1_000).copy(repaidPools = 1)))
        assertEquals(1_300_000, stepUpLimit(base, steady(90, 1_000).copy(repaidPools = 3)))
        // Capped at 50% however many pools repay.
        assertEquals(1_500_000, stepUpLimit(base, steady(90, 1_000).copy(repaidPools = 9)))
        assertEquals(1_500_000, stepUpLimit(base, steady(90, 1_000).copy(repaidPools = 100)))
    }

    // ------------------------------------------------------------------ fraud rules

    private fun context(
        requested: Long = 100_000,
        observation: Observation = steady(180, 10_000),
        baseline: Long? = 10_000,
        postFundingRefunds: Int? = null,
    ) = RequestContext(requested, observation, baseline, postFundingRefunds)

    @Test
    fun `an ordinary request fires nothing`() {
        val assessment = assessFraud(context())
        assertEquals(Verdict.ALLOW, assessment.verdict)
        assertTrue(assessment.signals.isEmpty())
        assertTrue(assessment.explanation.contains("No configured abuse signal"))
    }

    @Test
    fun `a recently changed destination is reviewable but not blocked`() {
        val assessment =
            assessFraud(
                context(observation = steady(180, 10_000).copy(daysSinceDestinationChange = 2))
            )
        assertEquals(Verdict.REVIEW, assessment.verdict)
        assertEquals("RECENT_DESTINATION_CHANGE", assessment.signals.single().code)
        assertEquals(2, assessment.signals.single().evidence["daysSinceChange"])
        // Outside the cooling period it stops firing.
        assertEquals(
            Verdict.ALLOW,
            assessFraud(
                    context(observation = steady(180, 10_000).copy(daysSinceDestinationChange = 30))
                )
                .verdict,
        )
    }

    @Test
    fun `a revenue spike against baseline is reviewable`() {
        val spiking = Observation(180, 1_800_000, 0, 0, List(179) { 10_000L } + listOf(40_000L))
        val assessment = assessFraud(context(observation = spiking, baseline = 10_000))
        assertEquals(Verdict.REVIEW, assessment.verdict)
        assertEquals("REVENUE_SPIKE", assessment.signals.single().code)
    }

    @Test
    fun `a large request on a new account is reviewable`() {
        val assessment = assessFraud(context(requested = 600_000, observation = steady(3, 10_000)))
        assertEquals(Verdict.REVIEW, assessment.verdict)
        assertTrue(assessment.signals.any { it.code == "NEW_ACCOUNT_LARGE_REQUEST" })
        // The same account asking for a small amount does not fire it.
        assertEquals(
            Verdict.ALLOW,
            assessFraud(context(requested = 1_000, observation = steady(3, 10_000))).verdict,
        )
    }

    @Test
    fun `refunds concentrated after funding are reviewable`() {
        val assessment = assessFraud(context(postFundingRefunds = 3_000))
        assertEquals(Verdict.REVIEW, assessment.verdict)
        assertEquals("POST_FUNDING_REFUND_SPIKE", assessment.signals.single().code)
        assertEquals(Verdict.ALLOW, assessFraud(context(postFundingRefunds = 500)).verdict)
    }

    @Test
    fun `chargebacks at the stop level block rather than review`() {
        val disputed = steady(180, 10_000, chargeback = 360_000) // 20% of 1,800,000
        val assessment = assessFraud(context(observation = disputed))
        assertEquals(Verdict.BLOCK, assessment.verdict)
        assertEquals("CHARGEBACK_LEVEL", assessment.signals.single().code)
        assertEquals(2_000, assessment.signals.single().evidence["chargebackRateBasisPoints"])
    }

    @Test
    fun `escalation keeps the more severe verdict whichever order it arrives in`() {
        // Order-independence matters: the composite verdict folds over signals in a fixed order,
        // so a rule listed later must not be able to soften an earlier one.
        assertEquals(Verdict.BLOCK, Verdict.REVIEW.escalate(Verdict.BLOCK))
        assertEquals(Verdict.BLOCK, Verdict.BLOCK.escalate(Verdict.REVIEW))
        assertEquals(Verdict.REVIEW, Verdict.ALLOW.escalate(Verdict.REVIEW))
        assertEquals(Verdict.REVIEW, Verdict.REVIEW.escalate(Verdict.ALLOW))
        assertEquals(Verdict.BLOCK, Verdict.BLOCK.escalate(Verdict.ALLOW))
        assertEquals(Verdict.ALLOW, Verdict.ALLOW.escalate(Verdict.ALLOW))
    }

    @Test
    fun `the composite verdict is the most severe signal and never softens`() {
        val worst = steady(180, 10_000, chargeback = 360_000).copy(daysSinceDestinationChange = 1)
        val assessment = assessFraud(context(observation = worst))
        assertEquals(Verdict.BLOCK, assessment.verdict)
        assertEquals(2, assessment.signals.size)
        assertTrue(assessment.signals.any { it.verdict == Verdict.REVIEW })
    }

    @Test
    fun `signals carry the numbers they fired on`() {
        val assessment = assessFraud(context(requested = 600_000, observation = steady(3, 10_000)))
        val signal = assessment.signals.single()
        assertEquals(600_000L, signal.evidence["requestedCents"])
        assertEquals(3, signal.evidence["tenureDays"])
        assertEquals("illustrative-fraud-v1", assessment.policyVersion)
    }

    @Test
    fun `an absent baseline cannot fire the spike rule`() {
        val assessment = assessFraud(context(observation = steady(3, 1_000_000), baseline = null))
        assertTrue(assessment.signals.none { it.code == "REVENUE_SPIKE" })
    }
}
