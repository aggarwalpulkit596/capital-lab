-- Stage 2: automatic payouts and the portfolio read model.
-- RevenueCat describes both automatic and on-demand payouts; only on-demand existed before this.

CREATE TABLE payout_policies (
    developer_id TEXT PRIMARY KEY REFERENCES developers(id),
    mode TEXT NOT NULL CHECK (mode IN ('MANUAL', 'AUTOMATIC')),
    -- Below this, an automatic cycle leaves capacity alone rather than sending dust.
    minimum_cents BIGINT NOT NULL CHECK (minimum_cents >= 0),
    -- An upper bound per cycle, independent of policy capacity. 0 means "no extra cap".
    maximum_cents BIGINT NOT NULL DEFAULT 0 CHECK (maximum_cents >= 0),
    cadence TEXT NOT NULL CHECK (cadence IN ('DAILY', 'WEEKLY')),
    paused BOOLEAN NOT NULL DEFAULT FALSE,
    last_cycle DATE,
    updated_at TIMESTAMPTZ NOT NULL
);

-- One row per evaluation cycle, so an automatic payout can be explained after the fact.
CREATE TABLE payout_cycles (
    id TEXT PRIMARY KEY,
    developer_id TEXT NOT NULL REFERENCES developers(id),
    cycle_date DATE NOT NULL,
    evaluated_pools INTEGER NOT NULL CHECK (evaluated_pools >= 0),
    funded_pools INTEGER NOT NULL CHECK (funded_pools >= 0),
    requested_cents BIGINT NOT NULL CHECK (requested_cents >= 0),
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (developer_id, cycle_date)
);

-- Every pool the cycle looked at, including the ones it deliberately skipped and why.
CREATE TABLE payout_cycle_items (
    cycle_id TEXT NOT NULL REFERENCES payout_cycles(id),
    pool_id TEXT NOT NULL REFERENCES pools(id),
    outcome TEXT NOT NULL CHECK (outcome IN ('REQUESTED', 'BELOW_MINIMUM', 'NO_CAPACITY', 'BLOCKED', 'FAILED')),
    available_cents BIGINT NOT NULL CHECK (available_cents >= 0),
    requested_cents BIGINT NOT NULL CHECK (requested_cents >= 0),
    advance_id UUID REFERENCES advances(id),
    reason TEXT,
    PRIMARY KEY (cycle_id, pool_id)
);
CREATE INDEX payout_cycle_items_by_pool ON payout_cycle_items(pool_id);
