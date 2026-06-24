-- V3__fraud_schema.sql
--
-- Fraud detection schema for the fraud-service microservice.
-- This migration is owned by fraud-service but applied by ledger-api (which owns
-- Flyway for the shared "ledger" database). fraud-service sets flyway.enabled=false
-- in production and true in its own test environment.
--
-- Version 3 follows the reconciliation-worker's V2 migration.

CREATE TABLE fraud_cases (
    id                  BIGSERIAL PRIMARY KEY,
    -- UUID string referencing the transaction in the ledger.
    -- Not a FK to transactions.id because fraud-service must stay decoupled from
    -- ledger-api's schema — it only knows about events received via Kafka.
    transaction_ref     VARCHAR(36) NOT NULL,
    -- The account on the DEBIT side of the transaction that triggered the case.
    account_id          BIGINT NOT NULL,
    -- Scoring result: HIGH (70–100), MEDIUM (31–69), LOW (0–30).
    risk_level          VARCHAR(10) NOT NULL,
    -- Composite score 0–100 summed across all rules.
    score               INTEGER NOT NULL,
    -- Comma-separated list of rule class names that fired (e.g. "VelocityRule,NewAccountRule").
    triggered_rules     TEXT NOT NULL,
    -- Lifecycle status: OPEN when created, REVIEWED or DISMISSED after analyst action.
    status              VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    -- Amount on the DEBIT entry that triggered scoring.
    amount              NUMERIC(20,4),
    -- Currency of the DEBIT entry.
    currency            VARCHAR(3),
    -- Arbitrary JSON blob for rule-specific context (reasons, balance snapshot, etc.).
    details             JSONB,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Set when an analyst reviews or dismisses the case.
    reviewed_at         TIMESTAMPTZ
);

-- Analysts primarily work through the OPEN queue, so index status first.
CREATE INDEX idx_fraud_cases_status  ON fraud_cases(status);
-- Dashboard filtering by risk level (e.g. "show only HIGH risk").
CREATE INDEX idx_fraud_cases_risk    ON fraud_cases(risk_level);
-- Per-account fraud history lookup.
CREATE INDEX idx_fraud_cases_account ON fraud_cases(account_id);
-- Idempotency check: before inserting a new case, check if one already exists
-- for this transaction_ref to avoid duplicates on Kafka redelivery.
CREATE INDEX idx_fraud_cases_txn_ref ON fraud_cases(transaction_ref);
