-- V2: Phase 3 reconciliation schema.
-- Adds the external statement feed table, the matching results table,
-- and extends reconciliation_exceptions (created in V1) with the columns
-- the engine needs for string-based ref matching.

-- ── 1. External statement entries ────────────────────────────────────────────
-- One row per item in the bank/processor statement feed.
-- external_reference echoes the transaction_ref UUID we put on the payment instruction.
-- UNIQUE constraint prevents duplicate ingestion of the same statement item.
CREATE TABLE external_statement_entries (
    id                 BIGSERIAL       PRIMARY KEY,
    external_reference VARCHAR(36)     NOT NULL,
    amount             NUMERIC(20, 4)  NOT NULL CHECK (amount > 0),
    currency           CHAR(3)         NOT NULL,
    statement_date     DATE            NOT NULL,
    raw_payload        JSONB,
    ingested_at        TIMESTAMPTZ     NOT NULL DEFAULT now(),
    CONSTRAINT ux_external_reference UNIQUE (external_reference)
);

CREATE INDEX idx_external_ref ON external_statement_entries (external_reference);

-- ── 2. Reconciliation matches ────────────────────────────────────────────────
-- One row per successfully matched internal/external pair.
-- Presence here IS the definition of "reconciled" — no reconciled_at column
-- is added to either source table (see algorithm comments in ReconciliationEngine).
CREATE TABLE reconciliation_matches (
    id                  BIGSERIAL       PRIMARY KEY,
    transaction_ref     VARCHAR(36)     NOT NULL,
    external_reference  VARCHAR(36)     NOT NULL,
    internal_amount     NUMERIC(20, 4)  NOT NULL,
    external_amount     NUMERIC(20, 4)  NOT NULL,
    amount_delta        NUMERIC(20, 4)  NOT NULL,   -- |internal - external|, always >= 0
    date_delta_days     INTEGER         NOT NULL,   -- |posted_at - statement_date|, informational only
    matched_at          TIMESTAMPTZ     NOT NULL DEFAULT now(),
    CONSTRAINT ux_match_txn_ref UNIQUE (transaction_ref),
    CONSTRAINT ux_match_ext_ref UNIQUE (external_reference)
);

-- ── 3. Extend reconciliation_exceptions (created in V1 with a different layout) ─
-- V1 used internal_transaction_id (BIGINT FK). The engine works on string refs
-- so MISSING_INTERNAL rows can be inserted without an internal transaction existing,
-- and to avoid a JOIN on every exception lookup.
-- The old internal_transaction_id column stays (nullable) for historical rows.
-- AUTHORITATIVE KEY: transaction_ref (VARCHAR) is the column the reconciliation
-- engine treats as canonical from Phase 3 onward. internal_transaction_id exists
-- only for backward-compatibility with any V1-era rows and must NOT be used by
-- Phase 4 or later consumers — use transaction_ref instead.
ALTER TABLE reconciliation_exceptions
    ADD COLUMN IF NOT EXISTS transaction_ref  VARCHAR(36),
    ADD COLUMN IF NOT EXISTS internal_amount  NUMERIC(20, 4),
    ADD COLUMN IF NOT EXISTS external_amount  NUMERIC(20, 4);

-- Partial unique indexes for idempotent INSERT ... ON CONFLICT DO NOTHING.
-- Scoped to status='OPEN' so a resolved exception can coexist with a later OPEN
-- one for the same ref+type (anomaly recurred after resolution).
CREATE UNIQUE INDEX ux_exc_txn_type_open
    ON reconciliation_exceptions (transaction_ref, exception_type)
    WHERE transaction_ref IS NOT NULL AND status = 'OPEN';

CREATE UNIQUE INDEX ux_exc_ext_type_open
    ON reconciliation_exceptions (external_reference, exception_type)
    WHERE external_reference IS NOT NULL AND status = 'OPEN';

COMMENT ON TABLE external_statement_entries IS
    'Bank/processor statement feed rows. Populated by MockFeedGenerator in Phase 3; real ingestion job in production.';
COMMENT ON TABLE reconciliation_matches IS
    'Successfully matched internal/external pairs within configured amount and date tolerances.';
COMMENT ON TABLE reconciliation_exceptions IS
    'Open or resolved reconciliation discrepancies. exception_type: MISSING_EXTERNAL | MISSING_INTERNAL | AMOUNT_MISMATCH.';
