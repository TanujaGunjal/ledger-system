# Ledger System

A double-entry ledger and automated reconciliation engine built to demonstrate
three specific backend engineering problems: idempotent transaction posting,
deadlock-free concurrent balance updates, and self-healing reconciliation.

## Architecture

Two independently deployable Spring Boot services that both connect independently
to a shared PostgreSQL database. **ledger-api** (port 9090) owns schema migrations
(Flyway), the double-entry posting algorithm, and an outbox-based Kafka publisher.
**reconciliation-worker** (port 9091) runs a scheduled matching job that
joins internal ledger entries against external statement feeds and raises
exceptions for discrepancies it can self-resolve (matched on retry) or
escalate (amount mismatch, missing entry). Both services communicate via
Kafka topics brokered by Redpanda — a Kafka-API-compatible single binary
that starts in under two seconds compared to the ZooKeeper-Kafka pair.
A React/TypeScript frontend (port 80 in Docker, 5173 in dev) provides a
Reconciliation Queue, a Post Transaction form, and a Ledger Explorer.

## Prerequisites

Docker and Docker Compose. Nothing else.

```bash
docker --version     # 24.x or later
docker compose version  # v2.x (the `compose` subcommand, not `docker-compose`)
```

## Start everything

```bash
git clone <repo-url>
cd ledger-system
docker compose up --build
```

First build downloads base images and compiles both JARs — allow 3–5 minutes.
Subsequent starts reuse cached layers and are typically under 30 seconds.

Open **http://localhost** in a browser. Three tabs are available in the nav bar.

## Demonstrating the three hard problems

### (a) Idempotent posting

1. Click **Post Transaction**.
2. Fill in Source: Alice, Destination: Bob, Amount: 50, Description: "Test".
3. Note the **Idempotency Key** shown below the form — copy it.
4. Click **Post Transaction**. A green banner appears with a `transactionRef` UUID.
5. Open a terminal and POST the same payload with the same `Idempotency-Key` header:
   ```bash
   curl -s -X POST http://localhost:9090/api/v1/transactions \
     -H "Content-Type: application/json" \
     -H "Idempotency-Key: <paste-key-here>" \
     -d '{"description":"Test","entries":[{"accountId":186,"entryType":"DEBIT","amount":50,"currency":"USD"},{"accountId":187,"entryType":"CREDIT","amount":50,"currency":"USD"}]}'
   ```
6. The response `transactionRef` is **identical** to the one shown in the UI.
   No duplicate ledger entries were written.

### (b) Deadlock-free concurrent posting

The test suite proves this. `ConcurrencyIntegrationTest` fires 20 concurrent
threads that all attempt to transfer between the same two accounts simultaneously.
Against the naïve implementation (no lock ordering) this reliably deadlocks.
Against the sorted-lock implementation in `PostingExecutor` it passes 100% of
the time.

Run the tests against a live compose stack:

```bash
# In a separate terminal with docker compose already up:
cd ledger-api
./mvnw test -Dtest=ConcurrencyIntegrationTest -Duser.timezone=UTC
```

The `LOCKING STRATEGY` comment block at the top of `LedgerService.java` explains
the deadlock scenario and the fix (always acquire locks in ascending account ID order).

### (c) Self-healing reconciliation

1. Post a transaction (step a above is sufficient — Alice → Bob $50).
2. Click **Reconciliation Queue** → **Run Reconciliation Now**.
   A `MISSING_EXTERNAL` exception appears: the ledger has the transaction,
   the external statement does not.
3. Insert a matching external statement entry via psql:
   ```bash
   docker exec -it ledger-postgres psql -U ledger -d ledger -c "
     INSERT INTO external_statement_entries
       (external_reference, amount, currency, statement_date)
     VALUES ('<paste-transactionRef-here>', 50.00, 'USD', CURRENT_DATE);
   "
   ```
4. Click **Run Reconciliation Now** again.
   The exception moves from **Open** to **Resolved**. The reconciliation engine
   matched on `transaction_ref` and updated the status atomically.

## Running tests

Integration tests require a live Postgres instance. Run them against the
compose stack:

```bash
# ledger-api tests (includes ConcurrencyIntegrationTest, PostTransactionIntegrationTest,
# OutboxKafkaIntegrationTest — the last uses an in-process embedded Kafka broker)
cd ledger-api
./mvnw test -Duser.timezone=UTC

# reconciliation-worker tests
cd reconciliation-worker
./mvnw test -Duser.timezone=UTC
```

`-DskipTests` is used in the Dockerfiles because `docker build` has no network
access to the compose stack. Tests are structurally dependent on a live database
and must be run separately.

## Known limitations

These are real constraints in the current implementation, not oversights:

- **Single-currency reconciliation.** The matching algorithm sums amounts without
  grouping by currency. A transaction with USD and EUR legs would produce a
  spurious mismatch if external statements are multi-currency.

- **Debit-normal balance enforcement only.** `PostingExecutor` rejects any DEBIT
  that would take a non-EQUITY account negative. Liability accounts that should
  carry credit balances (e.g. accounts payable) require their own `accountType`
  exemption — the EQUITY exemption added for the house account shows the pattern
  but it is not generalised.

- **No pagination on the exceptions endpoint.** `GET /api/v1/reconciliation/exceptions`
  returns the full result set. At portfolio scale (hundreds of exceptions) this
  is fine; at bank scale it needs a cursor. A one-line comment in
  `ReconciliationExceptionRepository.findAll()` flags this.

- **Kafka key provides transaction-level ordering, not cross-transaction
  per-account ordering.** Each outbox event is keyed by `transactionId`, so
  events for the same transaction are ordered. Events across different
  transactions on the same account are not sequentially ordered at the Kafka
  level — a consumer that cares about per-account ordering must re-sort by
  `sequenceNo`.

## What I would build next

- **Admin dispute-resolution endpoint + UI.** A `POST /api/v1/reconciliation/exceptions/{id}/resolve` endpoint with an optional `resolutionNote` field, and a modal on the Reconciliation Queue to manually close exceptions that the engine cannot self-resolve (e.g. known rounding differences accepted by the business).

- **WebSocket live balance updates.** The Kafka consumer in `ledger-api` already has a `@KafkaListener` stub and the WebSocket dependency is in the POM. Wire `LedgerEntryPublishedEvent` through a `SimpMessagingTemplate` so the Ledger Explorer balance updates in real time without polling.

- **Testcontainers replacing the compose dependency for tests.** Currently integration tests require `docker compose up` before `mvn test`. Testcontainers would spin up a throwaway Postgres per test class, making the test suite self-contained and safe to run in CI without pre-provisioned infrastructure.

- **Per-currency amount tolerance in the reconciliation engine.** Replace the hard equality check with a configurable tolerance map (e.g. `USD: 0.01`, `EUR: 0.005`) to handle known FX rounding differences without manual exception closure.
