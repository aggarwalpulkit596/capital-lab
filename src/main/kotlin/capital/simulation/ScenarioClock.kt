package capital.simulation

import java.time.*
import java.util.concurrent.atomic.AtomicReference

/** Only for repeatable demonstrations and tests, not a production clock. */
class ScenarioClock(initial: Instant = DemoFixture().evaluatedAt) : Clock() {
    private val current = AtomicReference(initial)

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = fixed(instant(), zone)

    override fun instant(): Instant = current.get()

    fun advance(duration: Duration) {
        current.updateAndGet { it.plus(duration) }
    }
}
