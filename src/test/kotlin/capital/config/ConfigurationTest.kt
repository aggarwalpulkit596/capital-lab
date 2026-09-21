package capital.config

import capital.policy.FinancialTerms
import capital.policy.applyBasisPoints
import capital.risk.MonitoringPolicy
import capital.risk.RiskInputs
import capital.risk.monitor
import java.time.Duration
import kotlin.test.*
import org.junit.jupiter.api.Test

class ConfigurationTest {
    @Test
    fun `environment is validated before starting resources and secrets are not printable`() {
        val config =
            RuntimeConfig.fromEnvironment(
                mapOf("LAB_HTTP_PORT" to "8090", "LAB_DB_PASSWORD" to "secret-for-test")
            )
        assertEquals(8090, config.http.port)
        assertFalse(config.toString().contains("secret-for-test"))
        assertFailsWith<IllegalArgumentException> {
            RuntimeConfig.fromEnvironment(mapOf("LAB_HTTP_PORT" to "oops"))
        }
        assertFailsWith<IllegalArgumentException> {
            RuntimeConfig.fromEnvironment(mapOf("LAB_HTTP_PORT" to "65536"))
        }
        assertFailsWith<IllegalArgumentException> {
            RuntimeConfig.fromEnvironment(mapOf("LAB_HTTP_WORKERS" to "0"))
        }
        assertFailsWith<IllegalArgumentException> {
            JdbcSettings(lockTimeoutMillis = 20_000, statementTimeoutMillis = 10_000)
        }
        assertFailsWith<IllegalArgumentException> {
            BankHttpSettings(responseTimeout = Duration.ZERO)
        }
    }

    @Test
    fun `financial terms bound rates and preserve exact rounding at long limits`() {
        val terms = FinancialTerms.EARLY_PAYOUTS_V1
        assertEquals(500, terms.fee(20_000))
        assertEquals(1, terms.fee(20))
        assertEquals(Long.MAX_VALUE, applyBasisPoints(Long.MAX_VALUE, 10_000))
        assertFailsWith<IllegalArgumentException> { FinancialTerms("invalid", 8_000, 10_000) }
        assertFailsWith<IllegalArgumentException> { terms.copy(version = "") }
    }

    @Test
    fun `monitor uses injected thresholds for both decisions and explanations`() {
        val policy = MonitoringPolicy("review-test-v2", 2_000, 4, 3)
        assertTrue(monitor(RiskInputs(100_000, 19_999, 100_000, 3), policy).isEmpty())
        val cancellation = monitor(RiskInputs(100_000, 20_000, 100_000, 3), policy).single()
        assertEquals("HIGH_CANCELLATIONS", cancellation.code)
        assertTrue(cancellation.explanation.contains("20%"))
        val spike = monitor(RiskInputs(400_000, 0, 100_000, 3), policy).single()
        assertEquals("REVENUE_SPIKE", spike.code)
        assertTrue(spike.explanation.contains("4×"))
        assertTrue(spike.explanation.contains("3 observed"))
    }
}
