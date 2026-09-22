package capital.settlement

import capital.payments.AdvanceRequest
import capital.payments.AdvanceService
import capital.payments.Database
import capital.payments.FakeBankServer
import capital.payments.HttpBankGateway
import capital.payments.number
import capital.payments.rows
import capital.payments.update
import capital.simulation.ScenarioClock
import com.github.dockerjava.api.model.ExposedPort
import com.github.dockerjava.api.model.PortBinding
import com.github.dockerjava.api.model.Ports
import java.time.LocalDate
import java.util.UUID
import kotlin.test.*
import kotlin.test.Test
import org.junit.jupiter.api.*
import org.testcontainers.postgresql.PostgreSQLContainer

/**
 * The post-disbursement lifecycle against a real PostgreSQL instance: partial and aggregated store
 * remittances, refund-driven reclassification, bank returns, and residual release.
 */
@Tag("postgres")
@Timeout(60)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SettlementIntegrationTest {
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
    private lateinit var remittances: RemittanceService
    private lateinit var revisions: ProceedsRevisionService
    private lateinit var returns: AdvanceReturnService
    private lateinit var residuals: ResidualPayoutService

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
        bankDatabase.install("/db/bank.sql")
        clock = ScenarioClock()
        remittances = RemittanceService(database, clock)
        revisions = ProceedsRevisionService(database, clock)
        returns = AdvanceReturnService(database, clock)
        residuals = ResidualPayoutService(database, clock)
    }

    /** Three pools, each already carrying outstanding principal from earlier advances. */
    private fun seedPools(vararg pools: Triple<String, Long, Long>, cash: Long = 1_000_000) {
        database.transaction { c ->
            c.update("INSERT INTO treasury(id,cash_cents) VALUES (1,?)", cash)
            c.update(
                "INSERT INTO developers(id,limit_cents,outstanding_cents,destination_version) VALUES (?,?,?,?)",
                developer,
                10_000_000,
                pools.sumOf { it.third },
                destination,
            )
            pools.forEach { (id, proceeds, outstanding) ->
                c.update(
                    """INSERT INTO pools(id,developer_id,net_proceeds_cents,funded_lifetime_cents,outstanding_cents,
                        report_through,downloaded_at) VALUES (?,?,?,?,?,?,?)""",
                    id,
                    developer,
                    proceeds,
                    outstanding,
                    outstanding,
                    reportThrough,
                    clock.instant(),
                )
            }
        }
    }

    private fun pool(id: String) = database.transaction { c ->
        c.rows(
                "SELECT outstanding_cents,settled_proceeds_cents,closed,net_proceeds_cents FROM pools WHERE id=?",
                id,
            ) {
                listOf(
                    it.getLong(1),
                    it.getLong(2),
                    if (it.getBoolean(3)) 1L else 0L,
                    it.getLong(4),
                )
            }
            .single()
    }

    private fun developerRow() = database.transaction { c ->
        c.rows("SELECT outstanding_cents,payable_cents FROM developers WHERE id=?", developer) {
                it.getLong(1) to it.getLong(2)
            }
            .single()
    }

    private fun settlementJournalsBalance(): Boolean = database.transaction { c ->
        c.rows(
                """SELECT j.id, sum(CASE WHEN e.side='DEBIT' THEN e.cents ELSE -e.cents END)
                        FROM settlement_journals j JOIN settlement_entries e ON e.journal_id=j.id
                        GROUP BY j.id"""
            ) {
                it.getLong(2)
            }
            .all { it == 0L }
    }

    // ---------------------------------------------------------------- remittances

    @Test
    fun `one store payment covers many pools and allocates principal before residual`() {
        seedPools(
            Triple("pool-a", 500_000L, 300_000L),
            Triple("pool-b", 900_000L, 620_000L),
            Triple("pool-c", 200_000L, 100_000L),
        )
        val result =
            remittances.apply(
                Remittance(
                    "rem-1",
                    "apple",
                    developer,
                    840_000,
                    clock.instant(),
                    listOf(
                        RemittanceLine("pool-a", 340_000, finalLine = true),
                        RemittanceLine("pool-b", 500_000),
                    ),
                )
            )
        assertEquals(RemittanceStatus.APPLIED, result.status)
        assertEquals(840_000, result.appliedCents)
        assertEquals(0, result.unappliedCents)

        // pool-a: 300,000 principal repaid, 40,000 residual, closed by the final line.
        val a = result.lines.single { it.poolId == "pool-a" }
        assertEquals(300_000, a.principalCents)
        assertEquals(40_000, a.residualCents)
        assertTrue(a.poolClosed)
        assertEquals(listOf(0L, 340_000L, 1L, 500_000L), pool("pool-a"))

        // pool-b: partial. 500,000 of 620,000 principal; no residual; stays open.
        val b = result.lines.single { it.poolId == "pool-b" }
        assertEquals(500_000, b.principalCents)
        assertEquals(0, b.residualCents)
        assertFalse(b.poolClosed)
        assertEquals(listOf(120_000L, 500_000L, 0L, 900_000L), pool("pool-b"))

        // pool-c was not named and is untouched.
        assertEquals(listOf(100_000L, 0L, 0L, 200_000L), pool("pool-c"))

        // Developer outstanding fell by exactly the principal applied; residual became payable.
        assertEquals((1_020_000L - 800_000L) to 40_000L, developerRow())
        assertTrue(settlementJournalsBalance())
    }

    @Test
    fun `a partial remittance leaves the pool open and a later one finishes it`() {
        seedPools(Triple("pool-a", 500_000L, 300_000L))
        val first =
            remittances.apply(
                Remittance(
                    "rem-1",
                    "google",
                    developer,
                    120_000,
                    clock.instant(),
                    listOf(RemittanceLine("pool-a", 120_000)),
                )
            )
        assertEquals(RemittanceStatus.APPLIED, first.status)
        assertEquals(120_000, first.principalCents)
        assertEquals(listOf(180_000L, 120_000L, 0L, 500_000L), pool("pool-a"))

        val second =
            remittances.apply(
                Remittance(
                    "rem-2",
                    "google",
                    developer,
                    230_000,
                    clock.instant(),
                    listOf(RemittanceLine("pool-a", 230_000, finalLine = true)),
                )
            )
        assertEquals(180_000, second.principalCents)
        assertEquals(50_000, second.residualCents)
        assertEquals(listOf(0L, 350_000L, 1L, 500_000L), pool("pool-a"))
        assertEquals(0L to 50_000L, developerRow())
        assertTrue(settlementJournalsBalance())
    }

    @Test
    fun `replaying a remittance id has no additional effect`() {
        seedPools(Triple("pool-a", 500_000L, 300_000L))
        val remittance =
            Remittance(
                "rem-1",
                "apple",
                developer,
                100_000,
                clock.instant(),
                listOf(RemittanceLine("pool-a", 100_000)),
            )
        assertEquals(RemittanceStatus.APPLIED, remittances.apply(remittance).status)
        val before = pool("pool-a")
        val replay = remittances.apply(remittance)
        assertEquals(RemittanceStatus.ALREADY_APPLIED, replay.status)
        assertEquals(100_000, replay.appliedCents)
        assertEquals(before, pool("pool-a"))
        assertEquals(1, database.transaction { it.number("SELECT count(*) FROM remittances") })
    }

    @Test
    fun `the same id carrying a different amount is a conflict rather than a silent replay`() {
        seedPools(Triple("pool-a", 500_000L, 300_000L))
        remittances.apply(
            Remittance(
                "rem-1",
                "apple",
                developer,
                100_000,
                clock.instant(),
                listOf(RemittanceLine("pool-a", 100_000)),
            )
        )
        assertFailsWith<IllegalStateException> {
            remittances.apply(
                Remittance(
                    "rem-1",
                    "apple",
                    developer,
                    250_000,
                    clock.instant(),
                    listOf(RemittanceLine("pool-a", 250_000)),
                )
            )
        }
    }

    @Test
    fun `lines that do not total the received amount post nothing`() {
        seedPools(Triple("pool-a", 500_000L, 300_000L))
        val result =
            remittances.apply(
                Remittance(
                    "rem-1",
                    "apple",
                    developer,
                    100_000,
                    clock.instant(),
                    listOf(RemittanceLine("pool-a", 90_000)),
                )
            )
        assertEquals(RemittanceStatus.REJECTED, result.status)
        assertTrue(result.explanation.contains("unexplained"))
        assertEquals(listOf(300_000L, 0L, 0L, 500_000L), pool("pool-a"))
        assertEquals(0, database.transaction { it.number("SELECT count(*) FROM remittances") })
    }

    @Test
    fun `cash naming an unknown pool is held unapplied rather than absorbed`() {
        seedPools(Triple("pool-a", 500_000L, 300_000L))
        val result =
            remittances.apply(
                Remittance(
                    "rem-1",
                    "apple",
                    developer,
                    150_000,
                    clock.instant(),
                    listOf(
                        RemittanceLine("pool-a", 100_000),
                        RemittanceLine("pool-unknown", 50_000),
                    ),
                )
            )
        assertEquals(RemittanceStatus.PARTIALLY_APPLIED, result.status)
        assertEquals(100_000, result.appliedCents)
        assertEquals(50_000, result.unappliedCents)
        assertTrue(result.explanation.contains("not absorbed"))
        assertEquals(
            50_000,
            database.transaction {
                it.number("SELECT cents FROM settlement_entries WHERE account='UNAPPLIED_CASH'")
            },
        )
        assertTrue(settlementJournalsBalance())
    }

    // ---------------------------------------------------------------- refund revisions

    @Test
    fun `a refund inside the revised limit reclassifies nothing`() {
        seedPools(Triple("pool-a", 500_000L, 300_000L))
        val result =
            revisions.revise(
                ProceedsRevision("rev-1", "pool-a", RevisionReason.REFUND, 20_000, clock.instant())
            )
        assertEquals(RevisionStatus.WITHIN_LIMIT, result.status)
        assertEquals(480_000, result.proceedsAfterCents)
        assertEquals(384_000, result.effectiveLimitCents) // 80% of 480,000
        assertEquals(0, result.reclassifiedCents)
        assertEquals(listOf(300_000L, 0L, 0L, 480_000L), pool("pool-a"))
        assertEquals(300_000L to 0L, developerRow())
    }

    @Test
    fun `a refund below outstanding principal reclassifies the excess as recoverable`() {
        seedPools(Triple("pool-a", 500_000L, 300_000L))
        // 80% of 300,000 = 240,000 limit, leaving 60,000 of the 300,000 outstanding above it.
        val result =
            revisions.revise(
                ProceedsRevision(
                    "rev-1",
                    "pool-a",
                    RevisionReason.CHARGEBACK,
                    200_000,
                    clock.instant(),
                )
            )
        assertEquals(RevisionStatus.RECLASSIFIED, result.status)
        assertEquals(240_000, result.effectiveLimitCents)
        assertEquals(60_000, result.reclassifiedCents)
        assertEquals(60_000, result.openRecoveryCents)
        // Pool outstanding drops by the reclassified amount; the developer still owes the total.
        assertEquals(listOf(240_000L, 0L, 0L, 300_000L), pool("pool-a"))
        assertEquals(300_000L to 0L, developerRow())
        assertTrue(result.explanation.contains("not retrieved"))
        assertTrue(settlementJournalsBalance())
    }

    @Test
    fun `replaying a revision id has no additional effect`() {
        seedPools(Triple("pool-a", 500_000L, 300_000L))
        val revision =
            ProceedsRevision("rev-1", "pool-a", RevisionReason.REFUND, 200_000, clock.instant())
        assertEquals(RevisionStatus.RECLASSIFIED, revisions.revise(revision).status)
        val after = pool("pool-a")
        assertEquals(RevisionStatus.ALREADY_APPLIED, revisions.revise(revision).status)
        assertEquals(after, pool("pool-a"))
        assertEquals(
            60_000,
            database.transaction {
                it.number("SELECT open_cents FROM recovery_obligations WHERE pool_id='pool-a'")
            },
        )
    }

    @Test
    fun `later store money repays recovery before principal`() {
        seedPools(Triple("pool-a", 500_000L, 300_000L))
        revisions.revise(
            ProceedsRevision("rev-1", "pool-a", RevisionReason.REFUND, 200_000, clock.instant())
        )
        val result =
            remittances.apply(
                Remittance(
                    "rem-1",
                    "apple",
                    developer,
                    100_000,
                    clock.instant(),
                    listOf(RemittanceLine("pool-a", 100_000)),
                )
            )
        assertEquals(60_000, result.recoveredCents)
        assertEquals(40_000, result.principalCents)
        assertEquals(0, result.residualCents)
        assertEquals(
            0,
            database.transaction {
                it.number("SELECT open_cents FROM recovery_obligations WHERE pool_id='pool-a'")
            },
        )
        assertEquals(200_000L to 0L, developerRow())
        assertTrue(settlementJournalsBalance())
    }

    // ---------------------------------------------------------------- bank returns

    private fun settledAdvance(principal: Long): UUID {
        database.transaction { c ->
            c.update("INSERT INTO treasury(id,cash_cents) VALUES (1,?)", 1_000_000)
            c.update(
                "INSERT INTO developers(id,limit_cents,outstanding_cents,destination_version) VALUES (?,?,?,?)",
                developer,
                10_000_000,
                0,
                destination,
            )
            c.update(
                """INSERT INTO pools(id,developer_id,net_proceeds_cents,funded_lifetime_cents,outstanding_cents,
                    report_through,downloaded_at) VALUES (?,?,?,?,?,?,?)""",
                "pool-a",
                developer,
                1_000_000,
                0,
                0,
                reportThrough,
                clock.instant(),
            )
        }
        bankDatabase.transaction {
            it.update("INSERT INTO bank_balance(id,cents) VALUES (1,?)", 10_000_000)
        }
        val server = FakeBankServer(bankDatabase)
        val service = AdvanceService(database, clock, reportThrough)
        val advance =
            service.reserve(AdvanceRequest(developer, "pool-a", principal, destination), "key-1")
        assertTrue(service.process(advance.id, HttpBankGateway(server.endpoint)))
        assertEquals(
            "SETTLED",
            database.transaction { c ->
                c.rows("SELECT state FROM advances WHERE id=?", advance.id) { it.getString(1) }
                    .single()
            },
        )
        return advance.id
    }

    @Test
    fun `a returned advance is reversed by an appending journal and restores capacity`() {
        val id = settledAdvance(200_000)
        val cashAfterFunding = database.transaction {
            it.number("SELECT cash_cents FROM treasury WHERE id=1")
        }

        val result = returns.returnAdvance(id, "R01_INSUFFICIENT_FUNDS")
        assertEquals(ReturnStatus.REVERSED, result.status)
        assertEquals(200_000, result.principalCents)

        // Exposure and lifetime funding are unwound; cash comes back.
        assertEquals(listOf(0L, 0L, 0L, 1_000_000L), pool("pool-a"))
        assertEquals(0L to 0L, developerRow())
        assertEquals(
            cashAfterFunding + result.cashCents,
            database.transaction { it.number("SELECT cash_cents FROM treasury WHERE id=1") },
        )

        // The original funding journal is untouched; the reversal is a second, balanced journal.
        val kinds = database.transaction { c ->
            c.rows("SELECT kind FROM journals WHERE advance_id=? ORDER BY kind", id) {
                it.getString(1)
            }
        }
        assertEquals(listOf("FUNDING", "REVERSAL"), kinds)
        val net = database.transaction { c ->
            c.rows(
                    """SELECT j.kind, sum(CASE WHEN e.side='DEBIT' THEN e.cents ELSE -e.cents END)
                            FROM journals j JOIN ledger_entries e ON e.journal_id=j.id
                            WHERE j.advance_id=? GROUP BY j.kind""",
                    id,
                ) {
                    it.getLong(2)
                }
                .toSet()
        }
        assertEquals(setOf(0L), net)
        assertEquals(
            0,
            database.transaction { c ->
                c.number(
                    """SELECT coalesce(sum(CASE WHEN e.side='DEBIT' THEN e.cents ELSE -e.cents END),0)
                        FROM journals j JOIN ledger_entries e ON e.journal_id=j.id WHERE j.advance_id=?""",
                    id,
                )
            },
        )
    }

    @Test
    fun `returning twice has no additional effect`() {
        val id = settledAdvance(200_000)
        assertEquals(ReturnStatus.REVERSED, returns.returnAdvance(id, "R01").status)
        val repeat = returns.returnAdvance(id, "R01")
        assertEquals(ReturnStatus.ALREADY_REVERSED, repeat.status)
        assertEquals(
            2,
            database.transaction {
                it.number("SELECT count(*) FROM journals WHERE advance_id=?", id)
            },
        )
    }

    @Test
    fun `a return is refused once store proceeds already repaid the principal`() {
        val id = settledAdvance(200_000)
        remittances.apply(
            Remittance(
                "rem-1",
                "apple",
                developer,
                200_000,
                clock.instant(),
                listOf(RemittanceLine("pool-a", 200_000)),
            )
        )
        val result = returns.returnAdvance(id, "R01")
        assertEquals(ReturnStatus.REJECTED, result.status)
        assertTrue(result.explanation.contains("manual resolution"))
        assertEquals(
            1,
            database.transaction {
                it.number("SELECT count(*) FROM journals WHERE advance_id=?", id)
            },
        )
    }

    @Test
    fun `an unsettled advance cannot be returned`() {
        database.transaction { c ->
            c.update("INSERT INTO treasury(id,cash_cents) VALUES (1,?)", 1_000_000)
            c.update(
                "INSERT INTO developers(id,limit_cents,outstanding_cents,destination_version) VALUES (?,?,?,?)",
                developer,
                10_000_000,
                0,
                destination,
            )
            c.update(
                """INSERT INTO pools(id,developer_id,net_proceeds_cents,funded_lifetime_cents,outstanding_cents,
                    report_through,downloaded_at) VALUES (?,?,?,?,?,?,?)""",
                "pool-a",
                developer,
                1_000_000,
                0,
                0,
                reportThrough,
                clock.instant(),
            )
        }
        val advance =
            AdvanceService(database, clock, reportThrough)
                .reserve(AdvanceRequest(developer, "pool-a", 100_000, destination), "key-1")
        val result = returns.returnAdvance(advance.id, "R01")
        assertEquals(ReturnStatus.REJECTED, result.status)
        assertTrue(result.explanation.contains("READY"))
    }

    // ---------------------------------------------------------------- residual release

    @Test
    fun `residual is released only after open recovery is netted`() {
        seedPools(Triple("pool-a", 500_000L, 300_000L), Triple("pool-b", 400_000L, 100_000L))
        // pool-b is refunded hard enough to reclassify all of its outstanding principal.
        revisions.revise(
            ProceedsRevision("rev-1", "pool-b", RevisionReason.REFUND, 400_000, clock.instant())
        )
        assertEquals(
            100_000,
            database.transaction {
                it.number("SELECT open_cents FROM recovery_obligations WHERE pool_id='pool-b'")
            },
        )
        // pool-a pays in full plus 150,000 of residual.
        remittances.apply(
            Remittance(
                "rem-1",
                "apple",
                developer,
                450_000,
                clock.instant(),
                listOf(RemittanceLine("pool-a", 450_000, finalLine = true)),
            )
        )
        assertEquals(150_000, developerRow().second)

        val result = residuals.release("payout-1", developer)
        assertEquals(ResidualStatus.PAID, result.status)
        assertEquals(150_000, result.requestedCents)
        assertEquals(100_000, result.recoveredCents)
        assertEquals(50_000, result.paidCents)
        assertEquals(0L to 0L, developerRow())
        assertTrue(settlementJournalsBalance())
    }

    @Test
    fun `residual smaller than open recovery releases nothing`() {
        seedPools(Triple("pool-a", 500_000L, 300_000L), Triple("pool-b", 400_000L, 300_000L))
        revisions.revise(
            ProceedsRevision("rev-1", "pool-b", RevisionReason.REFUND, 400_000, clock.instant())
        )
        remittances.apply(
            Remittance(
                "rem-1",
                "apple",
                developer,
                340_000,
                clock.instant(),
                listOf(RemittanceLine("pool-a", 340_000, finalLine = true)),
            )
        )
        assertEquals(40_000, developerRow().second)
        val result = residuals.release("payout-1", developer)
        assertEquals(ResidualStatus.FULLY_RECOVERED, result.status)
        assertEquals(0, result.paidCents)
        assertEquals(40_000, result.recoveredCents)
        assertTrue(settlementJournalsBalance())
    }

    @Test
    fun `replaying a residual payout id has no additional effect`() {
        seedPools(Triple("pool-a", 500_000L, 100_000L))
        remittances.apply(
            Remittance(
                "rem-1",
                "apple",
                developer,
                150_000,
                clock.instant(),
                listOf(RemittanceLine("pool-a", 150_000, finalLine = true)),
            )
        )
        assertEquals(ResidualStatus.PAID, residuals.release("payout-1", developer).status)
        val repeat = residuals.release("payout-1", developer)
        assertEquals(ResidualStatus.ALREADY_PAID, repeat.status)
        assertEquals(50_000, repeat.paidCents)
        assertEquals(
            1,
            database.transaction { it.number("SELECT count(*) FROM residual_payouts") },
        )
    }

    @Test
    fun `posted settlement history cannot be edited`() {
        seedPools(Triple("pool-a", 500_000L, 300_000L))
        remittances.apply(
            Remittance(
                "rem-1",
                "apple",
                developer,
                100_000,
                clock.instant(),
                listOf(RemittanceLine("pool-a", 100_000)),
            )
        )
        assertFailsWith<java.sql.SQLException> {
            database.transaction { it.update("UPDATE settlement_entries SET cents=1") }
        }
        assertFailsWith<java.sql.SQLException> {
            database.transaction { it.update("DELETE FROM settlement_journals") }
        }
    }
}
