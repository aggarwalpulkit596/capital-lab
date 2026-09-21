package capital.automation

import capital.payments.AdvanceService
import capital.payments.Database
import capital.payments.FakeBankServer
import capital.payments.HttpBankGateway
import capital.payments.number
import capital.payments.rows
import capital.payments.update
import capital.portfolio.PortfolioView
import capital.simulation.ScenarioClock
import com.github.dockerjava.api.model.ExposedPort
import com.github.dockerjava.api.model.PortBinding
import com.github.dockerjava.api.model.Ports
import java.time.Duration
import java.time.LocalDate
import java.util.UUID
import kotlin.test.*
import kotlin.test.Test
import org.junit.jupiter.api.*
import org.testcontainers.postgresql.PostgreSQLContainer

/** Automatic payout cycles and the multi-pool portfolio view, against real PostgreSQL. */
@Tag("postgres")
@Timeout(60)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AutomationIntegrationTest {
    private val postgres =
        PostgreSQLContainer("postgres:17-alpine")
            .withCommand("postgres", "-c", "fsync=on")
            .withCreateContainerCmdModifier { command ->
                command.hostConfig!!.withPortBindings(
                    PortBinding(Ports.Binding.bindIpAndPort("127.0.0.1", 0), ExposedPort(5432))
                )
            }

    private lateinit var database: Database
    private lateinit var bankDatabase: Database
    private lateinit var clock: ScenarioClock
    private lateinit var advances: AdvanceService
    private lateinit var scheduler: PayoutScheduler
    private lateinit var portfolio: PortfolioView

    private val developer = "developer-1"
    private val destination = "verified-destination-v1"
    private val reportThrough: LocalDate = LocalDate.parse("2026-09-18")

    @BeforeAll fun startPostgres() = postgres.start()

    @AfterAll fun stopPostgres() = postgres.stop()

    @BeforeEach
    fun setup() {
        val suffix = UUID.randomUUID().toString().replace("-", "")
        database = Database(postgres.jdbcUrl, postgres.username, postgres.password, "cap_$suffix")
        bankDatabase =
            Database(postgres.jdbcUrl, postgres.username, postgres.password, "bank_$suffix")
        database.install("/db/capital.sql")
        database.install("/db/settlement.sql")
        database.install("/db/automation.sql")
        bankDatabase.install("/db/bank.sql")
        clock = ScenarioClock()
        advances = AdvanceService(database, clock, reportThrough)
        scheduler = PayoutScheduler(database, advances, clock)
        portfolio = PortfolioView(database)
    }

    /** Pools carrying proceeds but nothing advanced yet, so each has real capacity. */
    private fun seed(vararg pools: Pair<String, Long>, cash: Long = 10_000_000) {
        database.transaction { c ->
            c.update("INSERT INTO treasury(id,cash_cents) VALUES (1,?)", cash)
            c.update(
                "INSERT INTO developers(id,limit_cents,outstanding_cents,destination_version) VALUES (?,?,?,?)",
                developer,
                10_000_000,
                0,
                destination,
            )
            pools.forEach { (id, proceeds) ->
                c.update(
                    """INSERT INTO pools(id,developer_id,net_proceeds_cents,funded_lifetime_cents,outstanding_cents,
                        report_through,downloaded_at) VALUES (?,?,?,0,0,?,?)""",
                    id,
                    developer,
                    proceeds,
                    reportThrough,
                    clock.instant(),
                )
            }
        }
        bankDatabase.transaction {
            it.update("INSERT INTO bank_balance(id,cents) VALUES (1,?)", 100_000_000)
        }
    }

    @Test
    fun `an automatic cycle funds every eligible pool without a human request`() {
        seed("pool-a" to 500_000L, "pool-b" to 300_000L)
        scheduler.setPolicy(PayoutPolicy(developer, PayoutMode.AUTOMATIC, minimumCents = 10_000))

        val result = scheduler.runCycle(developer)
        assertEquals(CycleStatus.RAN, result.status)
        assertEquals(2, result.fundedPools)
        assertEquals(400_000 + 240_000, result.requestedCents)
        assertTrue(result.decisions.all { it.outcome == PoolOutcome.REQUESTED })
        assertEquals(
            640_000,
            database.transaction { it.number("SELECT sum(principal_cents) FROM advances") },
        )
    }

    @Test
    fun `a second cycle in the same period does nothing`() {
        seed("pool-a" to 500_000L)
        scheduler.setPolicy(PayoutPolicy(developer, PayoutMode.AUTOMATIC, minimumCents = 10_000))
        assertEquals(CycleStatus.RAN, scheduler.runCycle(developer).status)

        val repeat = scheduler.runCycle(developer)
        assertEquals(CycleStatus.ALREADY_RAN, repeat.status)
        assertEquals(1, repeat.decisions.size)
        assertEquals(1, database.transaction { it.number("SELECT count(*) FROM advances") })
    }

    @Test
    fun `a later period funds only the capacity that has since appeared`() {
        seed("pool-a" to 500_000L)
        scheduler.setPolicy(PayoutPolicy(developer, PayoutMode.AUTOMATIC, minimumCents = 1))
        assertEquals(400_000, scheduler.runCycle(developer).requestedCents)

        clock.advance(Duration.ofDays(1))
        val second = scheduler.runCycle(developer)
        assertEquals(CycleStatus.RAN, second.status)
        assertEquals(0, second.requestedCents)
        assertEquals(PoolOutcome.NO_CAPACITY, second.decisions.single().outcome)

        database.transaction {
            it.update("UPDATE pools SET net_proceeds_cents=600000 WHERE id='pool-a'")
        }
        clock.advance(Duration.ofDays(1))
        val third = scheduler.runCycle(developer)
        assertEquals(80_000, third.requestedCents)
        assertEquals(
            480_000,
            database.transaction { it.number("SELECT sum(principal_cents) FROM advances") },
        )
    }

    @Test
    fun `a weekly cadence funds once per ISO week`() {
        seed("pool-a" to 500_000L)
        scheduler.setPolicy(
            PayoutPolicy(developer, PayoutMode.AUTOMATIC, 1, cadence = Cadence.WEEKLY)
        )
        assertEquals(CycleStatus.RAN, scheduler.runCycle(developer).status)
        // 2026-09-19 is a Saturday, so the next day is still the same ISO week.
        clock.advance(Duration.ofDays(1))
        assertEquals(CycleStatus.ALREADY_RAN, scheduler.runCycle(developer).status)
        clock.advance(Duration.ofDays(1))
        assertEquals(CycleStatus.RAN, scheduler.runCycle(developer).status)
    }

    @Test
    fun `capacity below the minimum is left alone rather than sent as dust`() {
        seed("pool-a" to 500_000L, "pool-b" to 10_000L)
        scheduler.setPolicy(PayoutPolicy(developer, PayoutMode.AUTOMATIC, minimumCents = 50_000))
        val result = scheduler.runCycle(developer)
        assertEquals(1, result.fundedPools)
        val small = result.decisions.single { it.poolId == "pool-b" }
        assertEquals(PoolOutcome.BELOW_MINIMUM, small.outcome)
        assertEquals(8_000, small.availableCents)
        assertEquals(0, small.requestedCents)
        assertTrue(small.reason!!.contains("minimum"))
    }

    @Test
    fun `a per-cycle maximum caps the request below available capacity`() {
        seed("pool-a" to 500_000L)
        scheduler.setPolicy(
            PayoutPolicy(developer, PayoutMode.AUTOMATIC, 1, maximumCents = 100_000)
        )
        val decision = scheduler.runCycle(developer).decisions.single()
        assertEquals(400_000, decision.availableCents)
        assertEquals(100_000, decision.requestedCents)
    }

    @Test
    fun `a risk hold blocks the automatic cycle and records why`() {
        seed("pool-a" to 500_000L)
        scheduler.setPolicy(PayoutPolicy(developer, PayoutMode.AUTOMATIC, minimumCents = 1))
        advances.setHold(developer, true)

        val result = scheduler.runCycle(developer)
        assertEquals(CycleStatus.RAN, result.status)
        assertEquals(0, result.fundedPools)
        val decision = result.decisions.single()
        assertEquals(PoolOutcome.BLOCKED, decision.outcome)
        assertEquals("RISK_HOLD", decision.reason)
        assertEquals(0, database.transaction { it.number("SELECT count(*) FROM advances") })
    }

    @Test
    fun `a stale report blocks the automatic cycle exactly as it blocks a manual request`() {
        seed("pool-a" to 500_000L)
        database.transaction {
            it.update(
                "UPDATE pools SET report_through=? WHERE id='pool-a'",
                reportThrough.minusDays(2),
            )
        }
        scheduler.setPolicy(PayoutPolicy(developer, PayoutMode.AUTOMATIC, minimumCents = 1))
        val decision = scheduler.runCycle(developer).decisions.single()
        assertEquals(PoolOutcome.BLOCKED, decision.outcome)
        assertEquals("REPORT_COVERAGE_BEHIND", decision.reason)
    }

    @Test
    fun `manual and paused policies do not run cycles`() {
        seed("pool-a" to 500_000L)
        scheduler.setPolicy(PayoutPolicy(developer, PayoutMode.MANUAL, 1))
        assertEquals(CycleStatus.NOT_AUTOMATIC, scheduler.runCycle(developer).status)

        scheduler.setPolicy(PayoutPolicy(developer, PayoutMode.AUTOMATIC, 1, paused = true))
        assertEquals(CycleStatus.PAUSED, scheduler.runCycle(developer).status)

        assertEquals(0, database.transaction { it.number("SELECT count(*) FROM advances") })
    }

    @Test
    fun `a developer with no policy is never funded automatically`() {
        seed("pool-a" to 500_000L)
        assertEquals(CycleStatus.NO_POLICY, scheduler.runCycle(developer).status)
        assertEquals(0, database.transaction { it.number("SELECT count(*) FROM advances") })
    }

    @Test
    fun `a closed pool is not evaluated`() {
        seed("pool-a" to 500_000L, "pool-b" to 300_000L)
        database.transaction { it.update("UPDATE pools SET closed=TRUE WHERE id='pool-b'") }
        scheduler.setPolicy(PayoutPolicy(developer, PayoutMode.AUTOMATIC, minimumCents = 1))
        assertEquals(listOf("pool-a"), scheduler.runCycle(developer).decisions.map { it.poolId })
    }

    @Test
    fun `automatic reservations dispatch through the same bank path as manual ones`() {
        seed("pool-a" to 500_000L)
        scheduler.setPolicy(PayoutPolicy(developer, PayoutMode.AUTOMATIC, minimumCents = 1))
        val advanceId = scheduler.runCycle(developer).decisions.single().advanceId!!
        val server = FakeBankServer(bankDatabase)
        assertTrue(advances.process(advanceId, HttpBankGateway(server.endpoint)))
        assertEquals(
            "SETTLED",
            database.transaction { c ->
                c.rows("SELECT state FROM advances WHERE id=?", advanceId) { it.getString(1) }
                    .single()
            },
        )
    }

    // ---------------------------------------------------------------- portfolio

    @Test
    fun `the portfolio aggregates every pool and reports headroom`() {
        seed("pool-a" to 500_000L, "pool-b" to 300_000L, "pool-c" to 200_000L)
        scheduler.setPolicy(PayoutPolicy(developer, PayoutMode.AUTOMATIC, minimumCents = 1))
        scheduler.runCycle(developer)

        val view = portfolio.of(developer)!!
        assertEquals(3, view.positions.size)
        assertEquals(3, view.openPools)
        assertEquals(10_000_000, view.limitCents)
        // Nothing has settled yet, so the whole cycle sits in reserved, not outstanding.
        assertEquals(0, view.outstandingCents)
        assertEquals(400_000 + 240_000 + 160_000, view.reservedCents)
        assertEquals(10_000_000 - 800_000, view.headroomCents)
        assertEquals(0, view.openRecoveryCents)
    }

    @Test
    fun `concentration reflects where outstanding principal actually sits`() {
        seed("pool-a" to 500_000L, "pool-b" to 300_000L)
        database.transaction { c ->
            c.update("UPDATE pools SET outstanding_cents=300000 WHERE id='pool-a'")
            c.update("UPDATE pools SET outstanding_cents=100000 WHERE id='pool-b'")
            c.update("UPDATE developers SET outstanding_cents=400000 WHERE id=?", developer)
        }
        val view = portfolio.of(developer)!!
        assertEquals(400_000, view.outstandingCents)
        assertEquals(7_500, view.largestPoolShareBasisPoints)
    }

    @Test
    fun `an unknown developer has no portfolio rather than an empty one`() {
        seed("pool-a" to 500_000L)
        assertNull(portfolio.of("nobody"))
        assertEquals(0, portfolio.of(developer)!!.largestPoolShareBasisPoints)
    }
}
