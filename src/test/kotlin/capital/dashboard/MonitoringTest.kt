package capital.dashboard

import kotlin.test.*
import org.junit.jupiter.api.Test

class MonitoringTest {
    @Test fun `cancellation threshold includes equality and cannot overflow`() {
        assertTrue(monitor(RiskInputs(100_000, 9_999, 100_000, 7)).isEmpty())
        assertEquals("HIGH_CANCELLATIONS", monitor(RiskInputs(100_000, 10_000, 100_000, 7)).single().code)
        assertTrue(monitor(RiskInputs(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, 7)).any { it.code == "HIGH_CANCELLATIONS" })
    }
    @Test fun `spike needs seven preceding observations and positive baseline`() {
        assertEquals("LIMITED_HISTORY", monitor(RiskInputs(300_000, 0, 100_000, 6)).single().code)
        assertEquals("REVENUE_SPIKE", monitor(RiskInputs(300_000, 0, 100_000, 7)).single().code)
        assertTrue(monitor(RiskInputs(299_999, 0, 100_000, 7)).isEmpty())
        assertEquals("LIMITED_HISTORY", monitor(RiskInputs(300_000, 0, 0, 7)).single().code)
    }
    @Test fun `public aggregate has provenance and preserves source arithmetic`() {
        assertEquals(541_909, PublicDataset.json["sourceRows"].asInt)
        assertEquals(2_517, PublicDataset.json["excludedRows"].asInt)
        assertEquals(305, PublicDataset.days.size)
        assertEquals("CC BY 4.0", PublicDataset.json["license"].asString)
        val first = PublicDataset.day("2010-12-01")
        assertEquals(5_896_079, first["grossMinor"].asLong)
        assertEquals(32_523, first["cancellationsMinor"].asLong)
        for (day in PublicDataset.days) assertEquals(day["grossMinor"].asLong-day["cancellationsMinor"].asLong, day["netMinor"].asLong)
        assertEquals(539_392, PublicDataset.days.sumOf { it["saleLines"].asInt + it["cancellationLines"].asInt })
        assertEquals(PublicDataset.days.map { it["date"].asString }.sorted(), PublicDataset.days.map { it["date"].asString })
    }
    @Test fun `replay has explicit FX rounding and no future observations in baseline`() {
        assertEquals(125, PublicDataset.toDemoUsd(100))
        assertEquals(3, PublicDataset.toDemoUsd(2))
        assertEquals(-3, PublicDataset.toDemoUsd(-2))
        assertNull(PublicDataset.inputs("2010-12-01").baselineGrossCents)
        assertEquals(PublicDataset.toDemoUsd(5_896_079), PublicDataset.inputs("2010-12-02").baselineGrossCents)
        assertEquals(7, PublicDataset.inputs("2011-01-10").baselineDays)
        assertFailsWith<IllegalArgumentException> { PublicDataset.day("2026-01-01") }
    }
    @Test fun `reconciliation requires identity currency amount and posting evidence`() {
        val payment = PaymentEvidence("key", "bank-1", 19_500, "USD", "SETTLED", 19_500)
        val line = StatementLine("key", "bank-1", 19_500, "USD")
        fun status(p: PaymentEvidence = payment, s: List<StatementLine> = listOf(line)) = reconcile(listOf(p), s).first().status
        assertEquals("MATCHED", status())
        assertEquals("REFERENCE_MISMATCH", status(s=listOf(line.copy(transferId="different"))))
        assertEquals("AMOUNT_OR_CURRENCY_MISMATCH", status(s=listOf(line.copy(currency="GBP"))))
        assertEquals("AMOUNT_OR_CURRENCY_MISMATCH", status(s=listOf(line.copy(cents=19_501))))
        assertEquals("LEDGER_MISMATCH", status(payment.copy(postedCashCents=0)))
        assertEquals("DUPLICATE_STATEMENT", status(s=listOf(line,line)))
        assertEquals("MISSING_BANK_DEBIT", status(s=emptyList()))
    }
    @Test fun `unknown bank debits and unsettled local operations remain exceptions`() {
        val line = StatementLine("key", "bank-1", 19_500, "USD")
        assertEquals("UNKNOWN_BANK_DEBIT", reconcile(emptyList(), listOf(line)).single().status)
        val payment = PaymentEvidence("key", null, 19_500, "USD", "UNKNOWN", 0)
        assertEquals("UNPOSTED_BANK_DEBIT", reconcile(listOf(payment), listOf(line)).single().status)
        assertEquals("PENDING", reconcile(listOf(payment), emptyList()).single().status)
        assertEquals("NO_CASH_EXPECTED", reconcile(listOf(payment.copy(state="REJECTED")), emptyList()).single().status)
    }
}
