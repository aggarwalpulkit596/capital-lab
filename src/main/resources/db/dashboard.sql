-- Optional local-lab extension. Separate collection cash; never makes residual money available for funding.
CREATE TABLE lab_collections (
    receipt_id TEXT PRIMARY KEY,
    report_id TEXT NOT NULL UNIQUE,
    pool_id TEXT NOT NULL REFERENCES pools(id),
    received_cents BIGINT NOT NULL CHECK (received_cents > 0),
    principal_cents BIGINT NOT NULL CHECK (principal_cents >= 0),
    residual_cents BIGINT NOT NULL CHECK (residual_cents >= 0),
    CHECK (received_cents = principal_cents + residual_cents)
);
CREATE TABLE lab_collection_entries (
    id BIGSERIAL PRIMARY KEY,
    receipt_id TEXT NOT NULL REFERENCES lab_collections(receipt_id),
    account TEXT NOT NULL CHECK (account IN ('COLLECTION_CASH','ADVANCE_RECEIVABLE','DEVELOPER_PAYABLE')),
    side TEXT NOT NULL CHECK (side IN ('DEBIT','CREDIT')),
    cents BIGINT NOT NULL CHECK (cents > 0)
);
CREATE TRIGGER immutable_collection BEFORE UPDATE OR DELETE ON lab_collections FOR EACH ROW EXECUTE FUNCTION immutable_posting();
CREATE TRIGGER immutable_collection_entry BEFORE UPDATE OR DELETE ON lab_collection_entries FOR EACH ROW EXECUTE FUNCTION immutable_posting();
CREATE FUNCTION verify_collection() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE ref TEXT;
BEGIN
    ref := NEW.receipt_id;
    IF (SELECT count(*) FROM lab_collection_entries WHERE receipt_id=ref) < 2 OR
       (SELECT sum(CASE WHEN side='DEBIT' THEN cents::numeric ELSE -cents::numeric END) FROM lab_collection_entries WHERE receipt_id=ref) <> 0 THEN
        RAISE EXCEPTION 'Unbalanced collection';
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER collection_balance AFTER INSERT ON lab_collections DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION verify_collection();
CREATE CONSTRAINT TRIGGER collection_entry_balance AFTER INSERT ON lab_collection_entries DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION verify_collection();
ALTER TABLE lab_collections ADD COLUMN created_transaction BIGINT NOT NULL DEFAULT txid_current();
CREATE FUNCTION prevent_late_collection_entries() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF (SELECT created_transaction FROM lab_collections WHERE receipt_id=NEW.receipt_id) <> txid_current() THEN
        RAISE EXCEPTION 'Collection entries must accompany the allocation';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER no_late_collection_entries BEFORE INSERT ON lab_collection_entries FOR EACH ROW EXECUTE FUNCTION prevent_late_collection_entries();
