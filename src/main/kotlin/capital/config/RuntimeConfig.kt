package capital.config

import capital.payments.Database
import java.nio.file.Path
import java.time.Duration

/** Operational bounds; commercial policy is deliberately not configured here. */
data class JdbcSettings(
    val lockTimeoutMillis: Int = 5_000,
    val statementTimeoutMillis: Int = 15_000,
    val connectTimeoutSeconds: Int = 5,
    val socketTimeoutSeconds: Int = 30,
) {
    init {
        require(lockTimeoutMillis > 0 && statementTimeoutMillis >= lockTimeoutMillis)
        require(connectTimeoutSeconds > 0 && socketTimeoutSeconds > 0)
        require(socketTimeoutSeconds.toLong() * 1_000 > statementTimeoutMillis)
    }
}

data class HttpSettings(
    val port: Int = 8080,
    val workerThreads: Int = 4,
    val queueCapacity: Int = 64,
    val backlog: Int = 32,
    val maxActiveRuns: Int = 100,
    val maxRequestBytes: Int = 8 * 1_024,
) {
    init {
        require(port in 0..65_535)
        require(workerThreads in 1..64 && queueCapacity in 1..4_096 && backlog > 0)
        require(maxActiveRuns in 1..1_000 && maxRequestBytes in 1..1_048_576)
    }
}

data class RecoverySettings(
    val lease: Duration = Duration.ofSeconds(30),
    val retryDelay: Duration = Duration.ofSeconds(2),
) {
    init {
        require(!lease.isNegative && !lease.isZero && !retryDelay.isNegative)
    }
}

data class BankHttpSettings(
    val connectTimeout: Duration = Duration.ofSeconds(2),
    val responseTimeout: Duration = Duration.ofSeconds(3),
) {
    init {
        require(!connectTimeout.isNegative && !connectTimeout.isZero)
        require(responseTimeout >= connectTimeout)
    }
}

/** A regular class intentionally has no generated toString containing the password. */
class DatabaseConfig(
    val url: String,
    val user: String,
    private val password: String,
    val settings: JdbcSettings = JdbcSettings(),
) {
    init {
        require(url.startsWith("jdbc:postgresql:") && user.isNotBlank() && password.isNotBlank()) {
            "PostgreSQL URL, user and password are required"
        }
    }

    fun database(schema: String) =
        Database(
            url,
            user,
            password,
            schema,
            settings.lockTimeoutMillis,
            settings.statementTimeoutMillis,
            settings.connectTimeoutSeconds,
            settings.socketTimeoutSeconds,
        )
}

data class RuntimeConfig(
    val database: DatabaseConfig,
    val http: HttpSettings,
    val evidenceDirectory: Path,
) {
    companion object {
        fun fromEnvironment(environment: Map<String, String> = System.getenv()): RuntimeConfig {
            fun integer(name: String, default: Int): Int =
                environment[name]?.let {
                    it.toIntOrNull() ?: throw IllegalArgumentException("$name must be an integer")
                } ?: default
            val defaults = JdbcSettings()
            val httpDefaults = HttpSettings()
            val jdbc =
                JdbcSettings(
                    integer("LAB_DB_LOCK_TIMEOUT_MS", defaults.lockTimeoutMillis),
                    integer("LAB_DB_STATEMENT_TIMEOUT_MS", defaults.statementTimeoutMillis),
                    integer("LAB_DB_CONNECT_TIMEOUT_SECONDS", defaults.connectTimeoutSeconds),
                    integer("LAB_DB_SOCKET_TIMEOUT_SECONDS", defaults.socketTimeoutSeconds),
                )
            return RuntimeConfig(
                DatabaseConfig(
                    environment["LAB_JDBC_URL"] ?: "jdbc:postgresql://127.0.0.1:55432/capital_lab",
                    environment["LAB_DB_USER"] ?: "capital_lab",
                    environment["LAB_DB_PASSWORD"] ?: "local_demo_only",
                    jdbc,
                ),
                HttpSettings(
                    port = integer("LAB_HTTP_PORT", httpDefaults.port),
                    workerThreads = integer("LAB_HTTP_WORKERS", httpDefaults.workerThreads),
                    queueCapacity = integer("LAB_HTTP_QUEUE_CAPACITY", httpDefaults.queueCapacity),
                    maxActiveRuns = integer("LAB_MAX_ACTIVE_RUNS", httpDefaults.maxActiveRuns),
                ),
                Path.of(environment["LAB_EVIDENCE_DIR"] ?: "build/dashboard-runs"),
            )
        }
    }
}
