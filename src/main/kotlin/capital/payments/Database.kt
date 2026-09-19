package capital.payments

import capital.config.JdbcSettings
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDate
import java.util.Properties

/** Each call opens a separate JDBC connection. No JVM mutex supplies transaction correctness. */
class Database(
    val url: String,
    private val user: String,
    private val password: String,
    val schema: String,
    private val lockTimeoutMillis: Int = JdbcSettings().lockTimeoutMillis,
    private val statementTimeoutMillis: Int = JdbcSettings().statementTimeoutMillis,
    private val connectTimeoutSeconds: Int = JdbcSettings().connectTimeoutSeconds,
    private val socketTimeoutSeconds: Int = JdbcSettings().socketTimeoutSeconds,
) {
    init {
        JdbcSettings(
            lockTimeoutMillis,
            statementTimeoutMillis,
            connectTimeoutSeconds,
            socketTimeoutSeconds,
        )
        require(schema.matches(Regex("[a-z][a-z0-9_]{0,62}")))
    }

    private fun open(): Connection =
        DriverManager.getConnection(
            url,
            Properties().apply {
                setProperty("user", user)
                setProperty("password", password)
                setProperty("connectTimeout", connectTimeoutSeconds.toString())
                setProperty("socketTimeout", socketTimeoutSeconds.toString())
                setProperty("ApplicationName", "capital-lab")
            },
        )

    fun connection(): Connection =
        open().also { connection ->
            try {
                require(lockTimeoutMillis > 0 && statementTimeoutMillis > 0)
                connection.createStatement().use { statement ->
                    statement.execute("SET search_path TO $schema")
                    statement.execute("SET lock_timeout TO '$lockTimeoutMillis'")
                    statement.execute("SET statement_timeout TO '$statementTimeoutMillis'")
                }
            } catch (failure: Throwable) {
                connection.close()
                throw failure
            }
        }

    /**
     * Resource paths are migration identities. Applied SQL is immutable; append a new resource to
     * evolve it.
     */
    fun install(resource: String) {
        val sql = checkNotNull(javaClass.getResource(resource)).readText()
        val hash =
            MessageDigest.getInstance("SHA-256")
                .digest(sql.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        transaction { connection ->
            connection.rows("SELECT pg_advisory_xact_lock(hashtext(?))", schema) { Unit }
            connection.createStatement().use {
                it.execute("CREATE SCHEMA IF NOT EXISTS $schema")
                it.execute(
                    "CREATE TABLE IF NOT EXISTS schema_migrations (resource TEXT PRIMARY KEY, sha256 TEXT NOT NULL, installed_at TIMESTAMPTZ NOT NULL DEFAULT now())"
                )
            }
            val previous =
                connection
                    .rows("SELECT sha256 FROM schema_migrations WHERE resource=?", resource) {
                        it.getString(1)
                    }
                    .singleOrNull()
            if (previous != null) {
                check(previous == hash) {
                    "Applied migration checksum changed: $resource. Add a new migration instead of editing history."
                }
                return@transaction
            }
            connection.createStatement().use {
                it.execute(sql)
            }
            connection.update(
                "INSERT INTO schema_migrations(resource,sha256) VALUES (?,?)",
                resource,
                hash,
            )
        }
    }

    fun <T> transaction(block: (Connection) -> T): T =
        connection().use { connection ->
            connection.autoCommit = false
            connection.transactionIsolation = Connection.TRANSACTION_READ_COMMITTED
            try {
                val result = block(connection)
                connection.commit()
                result
            } catch (failure: Throwable) {
                runCatching { connection.rollback() }
                throw failure
            }
        }
}

internal fun PreparedStatement.bind(parameters: Array<out Any?>) {
    parameters.forEachIndexed { index, value ->
        when (value) {
            is Instant -> setTimestamp(index + 1, java.sql.Timestamp.from(value))
            is LocalDate -> setDate(index + 1, java.sql.Date.valueOf(value))
            else -> setObject(index + 1, value)
        }
    }
}

internal fun Connection.update(sql: String, vararg parameters: Any?): Int =
    prepareStatement(sql).use {
        it.bind(parameters)
        it.executeUpdate()
    }

internal fun <T> Connection.rows(
    sql: String,
    vararg parameters: Any?,
    read: (ResultSet) -> T,
): List<T> =
    prepareStatement(sql).use { statement ->
        statement.bind(parameters)
        statement.executeQuery().use { result ->
            buildList { while (result.next()) add(read(result)) }
        }
    }

internal fun Connection.number(sql: String, vararg parameters: Any?): Long =
    rows(sql, *parameters) { it.getLong(1) }.single()
