-- Fresh-schema prototype installation, not a production migration framework.
CREATE TABLE treasury (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    cash_cents BIGINT NOT NULL CHECK (cash_cents >= 0),
    reserved_cash_cents BIGINT NOT NULL DEFAULT 0 CHECK (reserved_cash_cents >= 0 AND reserved_cash_cents <= cash_cents)
);
CREATE TABLE developers (
    id TEXT PRIMARY KEY,
    limit_cents BIGINT NOT NULL CHECK (limit_cents >= 0),
    outstanding_cents BIGINT NOT NULL DEFAULT 0 CHECK (outstanding_cents >= 0),
    reserved_cents BIGINT NOT NULL DEFAULT 0 CHECK (reserved_cents >= 0),
    hold BOOLEAN NOT NULL DEFAULT FALSE,
    destination_version TEXT NOT NULL
);
CREATE TABLE pools (
    id TEXT PRIMARY KEY,
    developer_id TEXT NOT NULL REFERENCES developers(id),
    net_proceeds_cents BIGINT NOT NULL,
    settled_proceeds_cents BIGINT NOT NULL DEFAULT 0 CHECK (settled_proceeds_cents >= 0),
    funded_lifetime_cents BIGINT NOT NULL DEFAULT 0 CHECK (funded_lifetime_cents >= 0),
    outstanding_cents BIGINT NOT NULL DEFAULT 0 CHECK (outstanding_cents >= 0),
    reserved_cents BIGINT NOT NULL DEFAULT 0 CHECK (reserved_cents >= 0),
    report_through DATE NOT NULL,
    downloaded_at TIMESTAMPTZ NOT NULL,
    closed BOOLEAN NOT NULL DEFAULT FALSE,
    UNIQUE (developer_id, id)
);
CREATE TABLE advances (
    id UUID PRIMARY KEY,
    developer_id TEXT NOT NULL REFERENCES developers(id),
    pool_id TEXT NOT NULL,
    request_key TEXT NOT NULL,
    request_hash TEXT NOT NULL,
    principal_cents BIGINT NOT NULL CHECK (principal_cents > 0),
    fee_cents BIGINT NOT NULL CHECK (fee_cents >= 0 AND fee_cents < principal_cents),
    cash_cents BIGINT NOT NULL CHECK (cash_cents > 0 AND cash_cents = principal_cents - fee_cents),
    currency TEXT NOT NULL CHECK (currency = 'USD'),
    destination_version TEXT NOT NULL,
    provider_key TEXT NOT NULL UNIQUE,
    decision_json TEXT NOT NULL,
    state TEXT NOT NULL CHECK (state IN ('READY', 'DISPATCHING', 'UNKNOWN', 'SETTLED', 'REJECTED', 'CANCELED')),
    generation BIGINT NOT NULL DEFAULT 0,
    lease_until TIMESTAMPTZ,
    bank_transfer_id TEXT UNIQUE,
    last_reason TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (developer_id, request_key),
    FOREIGN KEY (developer_id, pool_id) REFERENCES pools(developer_id, id)
);
CREATE TABLE outbox (
    advance_id UUID PRIMARY KEY REFERENCES advances(id),
    done BOOLEAN NOT NULL DEFAULT FALSE,
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL
);
CREATE FUNCTION freeze_advance_command() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF (NEW.id,NEW.developer_id,NEW.pool_id,NEW.request_key,NEW.request_hash,NEW.principal_cents,
        NEW.fee_cents,NEW.cash_cents,NEW.currency,NEW.destination_version,NEW.provider_key,NEW.decision_json)
       IS DISTINCT FROM
       (OLD.id,OLD.developer_id,OLD.pool_id,OLD.request_key,OLD.request_hash,OLD.principal_cents,
        OLD.fee_cents,OLD.cash_cents,OLD.currency,OLD.destination_version,OLD.provider_key,OLD.decision_json) THEN
        RAISE EXCEPTION 'A reserved payment command cannot be changed';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER frozen_advance BEFORE UPDATE ON advances FOR EACH ROW EXECUTE FUNCTION freeze_advance_command();
CREATE TABLE journals (
    id UUID PRIMARY KEY,
    advance_id UUID NOT NULL UNIQUE REFERENCES advances(id),
    posting_key TEXT NOT NULL UNIQUE,
    created_transaction BIGINT NOT NULL DEFAULT txid_current(),
    created_at TIMESTAMPTZ NOT NULL
);
CREATE TABLE ledger_entries (
    id BIGSERIAL PRIMARY KEY,
    journal_id UUID NOT NULL REFERENCES journals(id),
    account TEXT NOT NULL CHECK (account IN ('ADVANCE_RECEIVABLE', 'FUNDING_CASH', 'DEFERRED_FEE')),
    currency TEXT NOT NULL CHECK (currency = 'USD'),
    side TEXT NOT NULL CHECK (side IN ('DEBIT', 'CREDIT')),
    cents BIGINT NOT NULL CHECK (cents > 0)
);
CREATE FUNCTION immutable_posting() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Posted ledger history is immutable';
END;
$$;
CREATE TRIGGER immutable_journal BEFORE UPDATE OR DELETE ON journals FOR EACH ROW EXECUTE FUNCTION immutable_posting();
CREATE TRIGGER immutable_entry BEFORE UPDATE OR DELETE ON ledger_entries FOR EACH ROW EXECUTE FUNCTION immutable_posting();
CREATE FUNCTION prevent_late_entries() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF (SELECT created_transaction FROM journals WHERE id = NEW.journal_id) <> txid_current() THEN
        RAISE EXCEPTION 'Entries must be inserted in the journal creation transaction';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER no_late_entries BEFORE INSERT ON ledger_entries FOR EACH ROW EXECUTE FUNCTION prevent_late_entries();
CREATE FUNCTION verify_balanced_journal() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE journal_ref UUID;
BEGIN
    IF TG_TABLE_NAME = 'journals' THEN journal_ref := NEW.id;
    ELSE journal_ref := NEW.journal_id; END IF;
    IF (SELECT count(*) FROM ledger_entries WHERE journal_id = journal_ref) < 2
       OR (SELECT sum(CASE WHEN side = 'DEBIT' THEN cents::numeric ELSE -cents::numeric END)
           FROM ledger_entries WHERE journal_id = journal_ref) <> 0 THEN
        RAISE EXCEPTION 'Unbalanced or empty journal %', journal_ref;
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER journal_balance AFTER INSERT ON journals DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION verify_balanced_journal();
CREATE CONSTRAINT TRIGGER entry_balance AFTER INSERT ON ledger_entries DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION verify_balanced_journal();
CREATE INDEX pending_outbox ON outbox(next_attempt_at) WHERE NOT done;
