package capital.dashboard

import com.google.gson.Gson
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.LongAdder

/**
 * Small, bounded-cardinality operational view; no request bodies, credentials, or financial
 * amounts.
 */
class Telemetry(private val sink: (String) -> Unit = System.out::println) {
    private val completed = LongAdder()
    private val failed = LongAdder()
    private val elapsed = LongAdder()
    private val statuses = ConcurrentHashMap<Int, LongAdder>()

    fun record(
        requestId: String,
        method: String,
        route: String,
        status: Int,
        durationMillis: Long,
    ) {
        completed.increment()
        elapsed.add(durationMillis)
        if (status >= 500) failed.increment()
        statuses.computeIfAbsent(status) { LongAdder() }.increment()
        sink(
            Gson()
                .toJson(
                    mapOf(
                        "time" to Instant.now().toString(),
                        "event" to "http_request",
                        "requestId" to requestId,
                        "method" to method,
                        "route" to route,
                        "status" to status,
                        "durationMillis" to durationMillis,
                    )
                )
        )
    }

    fun snapshot(): Map<String, Any> =
        mapOf(
            "requestsCompleted" to completed.sum(),
            "serverErrors" to failed.sum(),
            "totalDurationMillis" to elapsed.sum(),
            "responsesByStatus" to statuses.mapValues { it.value.sum() },
        )
}
