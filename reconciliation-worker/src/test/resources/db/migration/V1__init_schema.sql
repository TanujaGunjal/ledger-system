-- V1: Core ledger schema.
-- ledger_entries is INSERT-only by design -- there is no UPDATE or DELETE path
-- against it anywhere in the application. That's what "immutable" means in practice.

CREATE TABLE accounts (
    id             BIGSERIAL PRIMARY KEY,
    account_number VARCHAR(34) UNIQUE NOT NULL,
    owner_name     VARCHAR(255) NOT NULL,
    account_type   VARCHAR(20) NOT NULL,
    currency       CHAR(3) NOT NULL DEFAULT 'USD',
    status         VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE transactions (
    id              BIGSERIAL PRIMARY KEY,
    transaction_ref UUID UNIQUE NOT NULL,
    idempotency_key VARCHAR(255) UNIQUE NOT NULL,
    description     TEXT,
    status          VARCHAR(20) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    posted_at       TIMESTAMPTZ
);

CREATE TABLE ledger_entries (
    id             BIGSERIAL PRIMARY KEY,
    transaction_id BIGINT NOT NULL REFERENCES transactions(id),
    account_id     BIGINT NOT NULL REFERENCES accounts(id),
    entry_type     VARCHAR(6) NOT NULL CHECK (entry_type IN ('DEBIT', 'CREDIT')),
    amount         NUMERIC(20, 4) NOT NULL CHECK (amount > 0),
    currency       CHAR(3) NOT NULL,
    sequence_no    BIGINT NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (account_id, sequence_no)
);

CREATE TABLE account_balances (
    account_id          BIGINT PRIMARY KEY REFERENCES accounts(id),
    balance             NUMERIC(20, 4) NOT NULL DEFAULT 0,
    last_entry_sequence BIGINT NOT NULL DEFAULT 0,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE outbox_events (
    id             BIGSERIAL PRIMARY KEY,
    aggregate_type VARCHAR(50) NOT NULL,
    aggregate_id   VARCHAR(100) NOT NULL,
    event_type     VARCHAR(50) NOT NULL,
    payload        JSONB NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    published      BOOLEAN NOT NULL DEFAULT FALSE,
    published_at   TIMESTAMPTZ
);

CREATE TABLE reconciliation_exceptions (
    id                      BIGSERIAL PRIMARY KEY,
    internal_transaction_id BIGINT REFERENCES transactions(id),
    external_reference      VARCHAR(255),
    exception_type          VARCHAR(50) NOT NULL,
    details                 JSONB,
    status                  VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at             TIMESTAMPTZ
);

CREATE INDEX idx_ledger_entries_account ON ledger_entries (account_id, sequence_no);
CREATE INDEX idx_outbox_unpublished ON outbox_events (published) WHERE published = FALSE;
CREATE INDEX idx_reconciliation_open ON reconciliation_exceptions (status) WHERE status = 'OPEN';
