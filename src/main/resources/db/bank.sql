-- Isolated simulator schema. These commits are independent of the capital application's commits.
CREATE TABLE bank_balance (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    cents BIGINT NOT NULL CHECK (cents >= 0)
);
CREATE TABLE bank_operations (
    provider_key TEXT PRIMARY KEY,
    payload_hash TEXT NOT NULL,
    transfer_id TEXT NOT NULL UNIQUE,
    currency TEXT NOT NULL CHECK (currency = 'USD'),
    amount_cents BIGINT NOT NULL CHECK (amount_cents > 0),
    destination TEXT NOT NULL,
    state TEXT NOT NULL CHECK (state IN ('SETTLED', 'REJECTED')),
    submit_count INTEGER NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
