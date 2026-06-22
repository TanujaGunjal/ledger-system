package com.ledger.api.service;

import com.ledger.api.domain.AccountBalance;
import com.ledger.api.domain.LedgerEntry;
import com.ledger.api.domain.PostingRequest;
import com.ledger.api.domain.PostingRequest.EntryType;
import com.ledger.api.domain.Transaction;
import com.ledger.api.domain.TransactionResult;
import com.ledger.api.exception.InsufficientFundsException;
import com.ledger.api.exception.UnbalancedPostingException;
import com.ledger.api.repository.AccountBalanceRepository;
import com.ledger.api.repository.LedgerEntryRepository;
import com.ledger.api.repository.OutboxRepository;
import com.ledger.api.repository.TransactionRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.ledger.api.repository.AccountRepository;
import com.ledger.api.domain.Account;

/**
 * Executes the double-entry posting algorithm in its own REQUIRES_NEW transaction.
 *
 * This class is intentionally separate from LedgerService so that Spring's
 * proxy-based AOP can intercept the @Transactional annotation on doPost().
 * If doPost() were a private/same-class method, the proxy would be bypassed and
 * @Transactional would have no effect — meaning a DuplicateKeyException or
 * InsufficientFundsException thrown inside doPost() would NOT roll back the
 * transaction, leaving orphaned PENDING rows in the transactions table.
 *
 * The separation into two Spring beans (LedgerService → PostingExecutor) is the
 * idiomatic Spring solution to this self-invocation proxy limitation.
 */
@Service
public class PostingExecutor {

    private static final Logger log = LoggerFactory.getLogger(PostingExecutor.class);

    private final TransactionRepository transactionRepo;
    private final AccountBalanceRepository accountBalanceRepo;
    private final LedgerEntryRepository ledgerEntryRepo;
    private final OutboxRepository outboxRepo;
    private final ObjectMapper objectMapper;
    private final AccountRepository accountRepo;

    public PostingExecutor(TransactionRepository transactionRepo,
                           AccountBalanceRepository accountBalanceRepo,
                           LedgerEntryRepository ledgerEntryRepo,
                           OutboxRepository outboxRepo,
                           ObjectMapper objectMapper,
                           AccountRepository accountRepo) {
        this.transactionRepo = transactionRepo;
        this.accountBalanceRepo = accountBalanceRepo;
        this.ledgerEntryRepo = ledgerEntryRepo;
        this.outboxRepo = outboxRepo;
        this.objectMapper = objectMapper;
        this.accountRepo = accountRepo;
    }

    /**
     * Execute the posting. Runs in its own REQUIRES_NEW transaction so that:
     * - If DuplicateKeyException fires, this transaction rolls back cleanly and
     *   the caller (LedgerService) can open a fresh transaction to look up the
     *   original result without seeing a Postgres "transaction aborted" error.
     * - If InsufficientFundsException fires, this transaction rolls back cleanly
     *   — no PENDING row or partial ledger entries are left in the database.
     *
     * @throws org.springframework.dao.DuplicateKeyException if the idempotency key
     *   already exists — propagates to LedgerService which handles it.
     * @throws InsufficientFundsException if a DEBIT would overdraw — propagates
     *   to the controller which maps it to HTTP 409.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public TransactionResult execute(PostingRequest request, String idempotencyKey) {

        // ── Step 1: Pre-DB validation ─────────────────────────────────────────
        // Validate that debits and credits net to zero per currency BEFORE
        // touching the database. Free computation — no locks, no writes.
        validateBalanced(request.entries());

        // ── Step 2: Insert the transaction row (idempotency anchor) ───────────
        // The idempotency_key column has a UNIQUE constraint. If the same key
        // is submitted concurrently, exactly one INSERT will succeed; the other
        // throws DuplicateKeyException, which propagates out of this @Transactional
        // method, rolls back this REQUIRES_NEW transaction, and is caught by the
        // caller (LedgerService.postTransaction) which runs a fresh lookup.
        Transaction txn = transactionRepo.insertPending(idempotencyKey, request.description());
        log.debug("Inserted PENDING transaction id={} idempotencyKey={}", txn.id(), idempotencyKey);

        // ── Step 3: Collect and sort account IDs — THE deadlock-prevention step ──
        //
        // Every concurrent transfer must lock accounts in the SAME order.
        // By sorting ascending here (before the first SELECT ... FOR UPDATE),
        // we guarantee that no two transactions can hold lock A while waiting
        // for lock B and simultaneously hold lock B while waiting for lock A.
        // That's the only scenario that produces a deadlock — eliminating it
        // eliminates deadlocks entirely, regardless of how many concurrent
        // transfers are in flight.
        List<Long> accountIdsInOrder = request.entries().stream()
            .map(PostingRequest.PostingEntry::accountId)
            .distinct()
            .sorted()                    // ← ascending order: the deadlock-prevention line
            .toList();

        log.debug("Acquiring locks for transaction id={} on accounts {} (ascending id order)",
            txn.id(), accountIdsInOrder);

        // ── Step 4: Acquire pessimistic row-level locks (SELECT ... FOR UPDATE) ──
        //
        // We iterate in the sorted order established above.
        // AccountBalanceRepository.selectForUpdate() issues:
        //   SELECT ... FROM account_balances WHERE account_id = ? FOR UPDATE
        // If another transaction holds the lock on any of these rows,
        // this call blocks here — it does NOT deadlock because we always arrive
        // at each row in the same global order.
        Map<Long, AccountBalance> lockedBalances = new HashMap<>();
        for (Long accountId : accountIdsInOrder) {
            AccountBalance balance = accountBalanceRepo.selectForUpdate(accountId);
            lockedBalances.put(accountId, balance);
            log.debug("Locked account_balances row for accountId={} balance={}",
                accountId, balance.balance());
        }

        // ── Step 5: Validate sufficient balance for each DEBIT ────────────────
        //
        // We check against the LOCKED balance. This is safe — no other
        // transaction can change this balance until we commit.
        //
        // NOTE: this "no negative balances" rule currently assumes debit-normal
        // (asset-style) accounts only. Liability/contra-asset accounts that
        // should carry credit balances need a per-account-type exemption here.
        for (PostingRequest.PostingEntry entry : request.entries()) {
            if (entry.entryType() == EntryType.DEBIT) {
                Account account = accountRepo.findById(entry.accountId()).orElseThrow();
                
                // EQUITY/funding-source accounts are expected to carry a large negative balance
                // by design (as they act as the ultimate source of funds in double-entry accounting).
                if ("EQUITY".equals(account.accountType())) {
                    continue;
                }

                AccountBalance bal = lockedBalances.get(entry.accountId());
                if (bal.balance().compareTo(entry.amount()) < 0) {
                    // Throw before writing anything. This @Transactional boundary
                    // rolls back atomically: the PENDING transaction row, any ledger
                    // entries already written, and any balance updates all disappear.
                    // The idempotency_key slot is released so the caller can retry
                    // once funds are available with a fresh idempotency key.
                    throw new InsufficientFundsException(entry.accountId());
                }
            }
        }

        // ── Step 6: Insert immutable ledger entries ───────────────────────────
        //
        // sequence_no = last_entry_sequence + 1 for each account.
        // We track the running sequence in a mutable map so that if two entries
        // in the same posting touch the same account, each gets a distinct
        // monotonically increasing sequence number.
        //
        // ledger_entries is INSERT-ONLY: there is no UPDATE or DELETE path
        // anywhere in this codebase. The (account_id, sequence_no) UNIQUE
        // constraint enforces append-only semantics at the DB level.
        Map<Long, Long> nextSequence = new HashMap<>();
        for (Long accountId : accountIdsInOrder) {
            nextSequence.put(accountId, lockedBalances.get(accountId).lastEntrySequence());
        }

        List<LedgerEntry> insertedEntries = new ArrayList<>();
        for (PostingRequest.PostingEntry entry : request.entries()) {
            long seq = nextSequence.get(entry.accountId()) + 1;
            nextSequence.put(entry.accountId(), seq);

            LedgerEntry ledgerEntry = ledgerEntryRepo.insert(
                txn.id(),
                entry.accountId(),
                entry.entryType(),
                entry.amount(),
                entry.currency(),
                seq
            );
            insertedEntries.add(ledgerEntry);
            log.debug("Inserted ledger entry accountId={} type={} amount={} seq={}",
                entry.accountId(), entry.entryType(), entry.amount(), seq);
        }

        // ── Step 7: Update materialized balances ──────────────────────────────
        //
        // Still holding the FOR UPDATE locks. We apply the balance delta and
        // write the new last_entry_sequence back. The ledger_entries table remains
        // the source of truth; this is a derived cache kept in sync within the
        // same transaction.
        for (PostingRequest.PostingEntry entry : request.entries()) {
            AccountBalance current = lockedBalances.get(entry.accountId());
            BigDecimal newBalance = entry.entryType() == EntryType.CREDIT
                ? current.balance().add(entry.amount())
                : current.balance().subtract(entry.amount());
            long newSeq = nextSequence.get(entry.accountId());

            accountBalanceRepo.update(entry.accountId(), newBalance, newSeq);

            // Update the local snapshot so that multiple entries touching the
            // same account within one posting each see the running balance.
            lockedBalances.put(entry.accountId(), new AccountBalance(
                entry.accountId(), newBalance, newSeq, current.updatedAt()
            ));
            log.debug("Updated account_balances accountId={} newBalance={} newSeq={}",
                entry.accountId(), newBalance, newSeq);
        }

        // ── Step 8: Write outbox event IN THE SAME TRANSACTION ───────────────
        //
        // This is the core of the Transactional Outbox pattern:
        // - If the DB transaction commits, BOTH the ledger writes AND this outbox
        //   row become durable simultaneously. The Kafka publisher will pick it up.
        // - If the transaction rolls back (for any reason), the outbox row
        //   disappears with the ledger writes — no phantom event is ever emitted.
        //
        // Kafka message key — what it guarantees and what it does NOT:
        // - aggregateId = transactionRef UUID. Kafka routes messages with the same
        //   key to the same partition, so if this transaction ever produced multiple
        //   events (it currently produces exactly one — "TransactionPosted") they
        //   would all land on the same partition and be consumed in insertion order.
        // - What this does NOT provide: per-account ordering across separate
        //   transactions. If account A is debited in txn-1 and again in txn-3,
        //   those two events carry different keys (different transactionRefs) and
        //   can land on different partitions. A consumer that needs to process all
        //   events for a given account in strict chronological order cannot rely on
        //   partition routing alone — it must sort by ledger sequence_no after
        //   consuming.
        // - True per-account partition routing would require keying on account_id,
        //   but a double-entry posting touches two accounts simultaneously, so there
        //   is no single "the account id" to use as a key without either picking one
        //   side arbitrarily or emitting one event per leg. That is a Phase 3
        //   reconciliation-worker concern, not a Phase 2 outbox concern.
        // - This is a known, accepted limitation for Phase 2.
        String aggregateId = txn.transactionRef().toString();
        String payload = buildPayload(txn, insertedEntries);
        outboxRepo.insert("Transaction", aggregateId, "TransactionPosted", payload);
        log.debug("Inserted outbox event for transaction id={} aggregateId={}", txn.id(), aggregateId);

        // ── Step 9: Mark POSTED and commit ────────────────────────────────────
        //
        // This UPDATE is the last write in the transaction. When @Transactional
        // commits after this method returns, all of the above writes — the PENDING
        // insert, the ledger entries, the balance updates, the outbox row, and
        // this status change — become visible atomically. Before commit, none
        // are visible to concurrent readers (Postgres MVCC).
        transactionRepo.markPosted(txn.id());
        log.info("Transaction id={} ref={} POSTED successfully", txn.id(), txn.transactionRef());

        Transaction posted = transactionRepo.findById(txn.id()).orElseThrow();
        return TransactionResult.from(posted, insertedEntries);
    }

    /**
     * Serialize the transaction and its entries to a JSON string for the outbox payload.
     * Uses a simple inline record rather than pulling in a DTO so the outbox payload
     * is self-contained and not coupled to the API response shape.
     */
    private String buildPayload(Transaction txn, List<LedgerEntry> entries) {
        // Build a minimal map: {transactionRef, status, entries: [{accountId, entryType, amount, currency}]}
        var entriesPayload = entries.stream().map(e -> java.util.Map.of(
            "accountId",  e.accountId(),
            "entryType",  e.entryType().toString(),
            "amount",     e.amount().toPlainString(),
            "currency",   e.currency()
        )).toList();

        var payloadMap = java.util.Map.of(
            "transactionRef",  txn.transactionRef().toString(),
            "idempotencyKey",  txn.idempotencyKey(),
            "description",     txn.description() != null ? txn.description() : "",
            "status",          "POSTED",
            "entries",         entriesPayload
        );

        try {
            return objectMapper.writeValueAsString(payloadMap);
        } catch (JsonProcessingException ex) {
            // This should never happen with a well-formed Map — rethrow as unchecked
            // so the @Transactional boundary rolls back cleanly.
            throw new IllegalStateException("Failed to serialize outbox payload for txn " + txn.id(), ex);
        }
    }

    /**
     * Validate that debits and credits net to zero for each currency group.
     * Called BEFORE any DB interaction — free computation, no locks.
     */
    private void validateBalanced(List<PostingRequest.PostingEntry> entries) {
        Map<String, BigDecimal> netByCurrency = new HashMap<>();
        for (PostingRequest.PostingEntry entry : entries) {
            BigDecimal signed = entry.entryType() == EntryType.CREDIT
                ? entry.amount()
                : entry.amount().negate();
            netByCurrency.merge(entry.currency(), signed, BigDecimal::add);
        }
        for (Map.Entry<String, BigDecimal> e : netByCurrency.entrySet()) {
            if (e.getValue().compareTo(BigDecimal.ZERO) != 0) {
                throw new UnbalancedPostingException(e.getKey(), e.getValue());
            }
        }
    }
}
