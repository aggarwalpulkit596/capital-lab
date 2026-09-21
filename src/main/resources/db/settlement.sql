-- Stage 1: the post-disbursement lifecycle.
-- Partial and aggregated store remittances, refund-driven proceeds revision, advance returns,
-- and residual disbursement. Appended after capital.sql; capital.sql itself stays immutable.

-- A funding journal can now be followed by an appending reversal for the same advance.
-- Correction is a new balanced journal, never an edit to posted history.
ALTER TABLE journals DROP CONSTRAINT journals_advance_id_key;
ALTER TABLE journals ADD COLUMN kind TEXT NOT NULL DEFAULT 'FUNDING' CHECK (kind IN ('FUNDING', 'REVERSAL'));
ALTER TABLE journals ADD CONSTRAINT one_journal_per_advance_kind UNIQUE (advance_id, kind);

-- A settled advance can be returned by the bank after the fact.
ALTER TABLE advances DROP CONSTRAINT advances_state_check;
ALTER TABLE advances ADD CONSTRAINT advances_state_check
    CHECK (state IN ('READY', 'DISPATCHING', 'UNKNOWN', 'SETTLED', 'REJECTED', 'CANCELED', 'RETURNED'));

-- Residual owed to the developer, and principal reclassified as directly recoverable from them.
ALTER TABLE developers ADD COLUMN payable_cents BIGINT NOT NULL DEFAULT 0 CHECK (payable_cents >= 0);

-- Store money arrives as a remittance that may cover many pools and may be partial.
CREATE TABLE remittances (
    id TEXT PRIMARY KEY,
    store TEXT NOT NULL,
    developer_id TEXT NOT NULL REFERENCES developers(id),
    received_cents BIGINT NOT NULL CHECK (received_cents > 0),
    currency TEXT NOT NULL CHECK (currency = 'USD'),
    received_at TIMESTAMPTZ NOT NULL,
    applied_cents BIGINT NOT NULL CHECK (applied_cents >= 0),
    unapplied_cents BIGINT NOT NULL CHECK (unapplied_cents >= 0),
    created_at TIMESTAMPTZ NOT NULL,
    CHECK (received_cents = applied_cents + unapplied_cents)
);

-- One line per pool covered by that remittance, with the waterfall's outcome recorded per line.
CREATE TABLE remittance_lines (
    remittance_id TEXT NOT NULL REFERENCES remittances(id),
    pool_id TEXT NOT NULL REFERENCES pools(id),
    line_cents BIGINT NOT NULL CHECK (line_cents > 0),
    recovery_cents BIGINT NOT NULL CHECK (recovery_cents >= 0),
    principal_cents BIGINT NOT NULL CHECK (principal_cents >= 0),
    residual_cents BIGINT NOT NULL CHECK (residual_cents >= 0),
    final_line BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (remittance_id, pool_id),
    CHECK (line_cents = recovery_cents + principal_cents + residual_cents)
);

-- Principal that store proceeds can no longer be expected to repay, after a refund revision.
CREATE TABLE recovery_obligations (
    pool_id TEXT PRIMARY KEY REFERENCES pools(id),
    developer_id TEXT NOT NULL REFERENCES developers(id),
    open_cents BIGINT NOT NULL DEFAULT 0 CHECK (open_cents >= 0),
    lifetime_cents BIGINT NOT NULL DEFAULT 0 CHECK (lifetime_cents >= 0),
    CHECK (open_cents <= lifetime_cents)
);

-- Each downward revision of a pool's expected proceeds, recorded once.
CREATE TABLE proceeds_revisions (
    id TEXT PRIMARY KEY,
    pool_id TEXT NOT NULL REFERENCES pools(id),
    reason TEXT NOT NULL CHECK (reason IN ('REFUND', 'CHARGEBACK', 'STORE_ADJUSTMENT')),
    reduction_cents BIGINT NOT NULL CHECK (reduction_cents > 0),
    proceeds_before_cents BIGINT NOT NULL,
    proceeds_after_cents BIGINT NOT NULL,
    reclassified_cents BIGINT NOT NULL CHECK (reclassified_cents >= 0),
    created_at TIMESTAMPTZ NOT NULL
);

-- Residual actually released to the developer, net of any open recovery.
CREATE TABLE residual_payouts (
    id TEXT PRIMARY KEY,
    developer_id TEXT NOT NULL REFERENCES developers(id),
    requested_cents BIGINT NOT NULL CHECK (requested_cents > 0),
    recovered_cents BIGINT NOT NULL CHECK (recovered_cents >= 0),
    paid_cents BIGINT NOT NULL CHECK (paid_cents >= 0),
    created_at TIMESTAMPTZ NOT NULL,
    CHECK (requested_cents = recovered_cents + paid_cents)
);

-- Settlement subledger. Separate from the funding ledger because these postings concern
-- collected store cash and developer obligations, not our disbursement of advance principal.
CREATE TABLE settlement_journals (
    id UUID PRIMARY KEY,
    kind TEXT NOT NULL CHECK (kind IN ('REMITTANCE', 'REVISION', 'RESIDUAL_PAYOUT')),
    reference TEXT NOT NULL,
    posting_key TEXT NOT NULL UNIQUE,
    created_transaction BIGINT NOT NULL DEFAULT txid_current(),
    created_at TIMESTAMPTZ NOT NULL
);
CREATE TABLE settlement_entries (
    id BIGSERIAL PRIMARY KEY,
    journal_id UUID NOT NULL REFERENCES settlement_journals(id),
    account TEXT NOT NULL CHECK (account IN (
        'COLLECTION_CASH', 'ADVANCE_RECEIVABLE', 'DEVELOPER_PAYABLE',
        'RECOVERY_RECEIVABLE', 'UNAPPLIED_CASH')),
    currency TEXT NOT NULL CHECK (currency = 'USD'),
    side TEXT NOT NULL CHECK (side IN ('DEBIT', 'CREDIT')),
    cents BIGINT NOT NULL CHECK (cents > 0),
    pool_id TEXT REFERENCES pools(id)
);
CREATE TRIGGER immutable_settlement_journal BEFORE UPDATE OR DELETE ON settlement_journals
    FOR EACH ROW EXECUTE FUNCTION immutable_posting();
CREATE TRIGGER immutable_settlement_entry BEFORE UPDATE OR DELETE ON settlement_entries
    FOR EACH ROW EXECUTE FUNCTION immutable_posting();
CREATE FUNCTION prevent_late_settlement_entries() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF (SELECT created_transaction FROM settlement_journals WHERE id = NEW.journal_id) <> txid_current() THEN
        RAISE EXCEPTION 'Settlement entries must be inserted in the journal creation transaction';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER no_late_settlement_entries BEFORE INSERT ON settlement_entries
    FOR EACH ROW EXECUTE FUNCTION prevent_late_settlement_entries();
CREATE FUNCTION verify_settlement_journal() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE journal_ref UUID;
BEGIN
    IF TG_TABLE_NAME = 'settlement_journals' THEN journal_ref := NEW.id;
    ELSE journal_ref := NEW.journal_id; END IF;
    IF (SELECT count(*) FROM settlement_entries WHERE journal_id = journal_ref) < 2
       OR (SELECT sum(CASE WHEN side = 'DEBIT' THEN cents::numeric ELSE -cents::numeric END)
           FROM settlement_entries WHERE journal_id = journal_ref) <> 0 THEN
        RAISE EXCEPTION 'Unbalanced or empty settlement journal %', journal_ref;
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER settlement_journal_balance AFTER INSERT ON settlement_journals
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION verify_settlement_journal();
CREATE CONSTRAINT TRIGGER settlement_entry_balance AFTER INSERT ON settlement_entries
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION verify_settlement_journal();
CREATE INDEX settlement_entries_by_pool ON settlement_entries(pool_id) WHERE pool_id IS NOT NULL;
CREATE INDEX remittance_lines_by_pool ON remittance_lines(pool_id);
