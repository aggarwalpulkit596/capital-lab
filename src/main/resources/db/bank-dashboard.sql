-- Confirmed synthetic store receipts are independent bank-side evidence.
CREATE TABLE lab_store_receipts (
    id TEXT PRIMARY KEY,
    report_id TEXT NOT NULL,
    pool_id TEXT NOT NULL,
    cents BIGINT NOT NULL CHECK (cents > 0),
    currency TEXT NOT NULL CHECK (currency = 'USD')
);
