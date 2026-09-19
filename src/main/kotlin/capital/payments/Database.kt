package capital.payments

import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDate

/** Each call opens a separate JDBC connection. No JVM mutex supplies transaction correctness. */
class Database(val url: String, private val user: String, private val password: String, val schema: String) {
    init { require(schema.matches(Regex("[a-z][a-z0-9_]{0,62}"))) }

    fun connection(): Connection = DriverManager.getConnection(url, user, password).also {
        it.createStatement().use { statement -> statement.execute("SET search_path TO $schema") }
    }

    fun install(resource: String) {
        DriverManager.getConnection(url, user, password).use { connection ->
            connection.autoCommit = false
            connection.createStatement().use {
                it.execute("CREATE SCHEMA IF NOT EXISTS $schema")
                it.execute("SET search_path TO $schema")
                val sql = checkNotNull(javaClass.getResource(resource)).readText()
                it.execute(sql)
            }
            connection.commit()
        }
    }

    fun <T> transaction(block: (Connection) -> T): T = connection().use { connection ->
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

internal fun Connection.update(sql: String, vararg parameters: Any?): Int = prepareStatement(sql).use {
    it.bind(parameters)
    it.executeUpdate()
}

internal fun <T> Connection.rows(sql: String, vararg parameters: Any?, read: (ResultSet) -> T): List<T> =
    prepareStatement(sql).use { statement ->
        statement.bind(parameters)
        statement.executeQuery().use { result -> buildList { while (result.next()) add(read(result)) } }
    }

internal fun Connection.number(sql: String, vararg parameters: Any?): Long = rows(sql, *parameters) { it.getLong(1) }.single()
