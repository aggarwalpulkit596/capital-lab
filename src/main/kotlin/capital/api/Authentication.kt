package capital.api

import capital.payments.Database
import capital.payments.rows
import capital.payments.update
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.util.Base64

enum class Scope {
    /** May read its own developer's state. */
    READ,
    /** May also request advances and run its own payout cycles. */
    WRITE,
    /**
     * May additionally post facts that originate outside the developer: store remittances, proceeds
     * revisions, bank returns. A developer must never be able to assert that a store paid them.
     */
    OPERATOR;

    fun allows(required: Scope) = ordinal >= required.ordinal
}

data class Principal(val developerId: String, val scope: Scope, val label: String)

class AuthenticationFailed(message: String) : RuntimeException(message)

/**
 * API-key authentication over a per-developer tenancy boundary.
 *
 * Only the SHA-256 digest of a key is stored, so the issued secret exists in exactly one place: the
 * response to [issue]. Lookup is by digest, and comparison happens in the database on the primary
 * key, so a wrong key is indistinguishable from an unknown one.
 *
 * This is a local demonstration boundary, not a production identity system: there is no rotation
 * schedule, rate limiting, expiry, or audit trail beyond `last_used_at`.
 */
class ApiKeys(private val database: Database, private val clock: Clock) {
    private val random = SecureRandom()

    fun issue(developerId: String, label: String, scope: Scope): String {
        require(label.isNotBlank()) { "An API key needs a label so it can be revoked knowingly" }
        val raw = ByteArray(32).also { random.nextBytes(it) }
        val key = "rck_" + Base64.getUrlEncoder().withoutPadding().encodeToString(raw)
        database.transaction { connection ->
            connection.rows("SELECT id FROM developers WHERE id=? FOR UPDATE", developerId) {}
            connection.update(
                "INSERT INTO api_keys(key_sha256,developer_id,label,scope,created_at) VALUES (?,?,?,?,?)",
                digest(key),
                developerId,
                label,
                scope.name,
                clock.instant(),
            )
        }
        return key
    }

    fun revoke(key: String) {
        database.transaction {
            it.update("UPDATE api_keys SET revoked=TRUE WHERE key_sha256=?", digest(key))
        }
    }

    fun authenticate(header: String?): Principal {
        val key =
            header?.removePrefix("Bearer ")?.trim()?.takeIf {
                it.isNotBlank() && it != header.trim()
            } ?: throw AuthenticationFailed("Provide an API key as: Authorization: Bearer <key>")
        val hash = digest(key)
        return database.transaction { connection ->
            val row =
                connection
                    .rows(
                        "SELECT developer_id,scope,label,revoked FROM api_keys WHERE key_sha256=?",
                        hash,
                    ) {
                        Stored(
                            Principal(
                                it.getString(1),
                                Scope.valueOf(it.getString(2)),
                                it.getString(3),
                            ),
                            it.getBoolean(4),
                        )
                    }
                    .singleOrNull() ?: throw AuthenticationFailed("Unknown API key")
            if (row.revoked) throw AuthenticationFailed("This API key was revoked")
            connection.update(
                "UPDATE api_keys SET last_used_at=? WHERE key_sha256=?",
                clock.instant(),
                hash,
            )
            row.principal
        }
    }

    private data class Stored(val principal: Principal, val revoked: Boolean)

    private fun digest(key: String): String =
        MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8)).joinToString(
            ""
        ) {
            "%02x".format(it)
        }
}
