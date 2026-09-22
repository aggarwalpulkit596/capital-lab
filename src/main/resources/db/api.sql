-- Stage 2: tenant authentication for the versioned API.
-- Keys are stored only as SHA-256 digests. A leaked database row cannot be replayed as a
-- credential, and there is deliberately no way to read a key back out after it is issued.
CREATE TABLE api_keys (
    key_sha256 TEXT PRIMARY KEY,
    developer_id TEXT NOT NULL REFERENCES developers(id),
    label TEXT NOT NULL,
    scope TEXT NOT NULL CHECK (scope IN ('READ', 'WRITE', 'OPERATOR')),
    revoked BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL,
    last_used_at TIMESTAMPTZ
);
CREATE INDEX api_keys_by_developer ON api_keys(developer_id) WHERE NOT revoked;
