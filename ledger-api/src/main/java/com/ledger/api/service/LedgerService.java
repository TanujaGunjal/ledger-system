package com.ledger.api.service;

import com.ledger.api.domain.LedgerEntry;
import com.ledger.api.domain.PostingRequest;
import com.ledger.api.domain.Transaction;
import com.ledger.api.domain.TransactionResult;
import com.ledger.api.repository.LedgerEntryRepository;
import com.ledger.api.repository.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.List;

/*
 * ============================================================
 * LOCKING STRATEGY
 * ============================================================
 *
 * THE PROBLEM: DEADLOCK BETWEEN CONCURRENT TRANSFERS
 * --------------------------------------------------
 * Suppose account A has id=1 and account B has id=2.
 *
 *   Thread 1: Transfer A → B  (debits account 1, credits account 2)
 *   Thread 2: Transfer B → A  (debits account 2, credits account 1)
 *
 * If each thread locks accounts in the order they appear in the
 * request, this happens:
 *
 *   Thread 1 locks account 1   ... waits for account 2
 *   Thread 2 locks account 2   ... waits for account 1
 *
 * Both threads are now waiting for each other — a deadlock.
 * Postgres will abort one at random with "deadlock detected".
 *
 *
 * THE FIX: ALWAYS ACQUIRE LOCKS IN ASCENDING account_id ORDER
 * ------------------------------------------------------------
 * Before issuing any SELECT ... FOR UPDATE, sort the distinct
 * account IDs into ascending numeric order. Both threads above
 * will then try to lock account 1 first — one wins and proceeds;
 * the other blocks (not deadlocks) until the winner commits.
 * No cycle in the wait-for graph → no deadlock, ever.
 * See PostingExecutor.execute() — the .sorted() call on line ~95
 * is the exact line that enforces this invariant.
 *
 *
 * WHAT "SELECT ... FOR UPDATE" DOES
 * ----------------------------------
 * Row-level pessimistic write lock. Once acquired, no other
 * transaction can run SELECT ... FOR UPDATE on the same row
 * until this transaction commits or rolls back. This serialises
 * the read-modify-write of account_balances — eliminating the
 * lost-update race condition.
 *
 *
 * WHY TWO CLASSES (LedgerService + PostingExecutor)?
 * --------------------------------------------------
 * Spring @Transactional works by wrapping beans in a proxy. If
 * a @Transactional method calls another @Transactional method in
 * the SAME class, it bypasses the proxy (self-invocation) and the
 * annotation has no effect. PostingExecutor is a separate @Service
 * so that its @Transactional(REQUIRES_NEW) is honoured by the proxy.
 *
 * This is what allows:
 *   - DuplicateKeyException to roll back PostingExecutor's transaction
 *     cleanly, so LedgerService can open a fresh one for the lookup.
 *   - InsufficientFundsException to roll back cleanly, leaving no
 *     orphaned PENDING rows or partial ledger entries in the DB.
 * ============================================================
 */
@Service
public class LedgerService {

    private static final Logger log = LoggerFactory.getLogger(LedgerService.class);

    private final PostingExecutor postingExecutor;
    private final TransactionRepository transactionRepo;
    private final LedgerEntryRepository ledgerEntryRepo;

    public LedgerService(PostingExecutor postingExecutor,
                         TransactionRepository transactionRepo,
                         LedgerEntryRepository ledgerEntryRepo) {
        this.postingExecutor = postingExecutor;
        this.transactionRepo = transactionRepo;
        this.ledgerEntryRepo = ledgerEntryRepo;
    }

    /**
     * Post a double-entry transaction. Idempotent on idempotencyKey.
     *
     * NOT @Transactional itself — it delegates to PostingExecutor.execute()
     * which runs in its own REQUIRES_NEW transaction. This separation ensures
     * that if DuplicateKeyException fires (idempotency key already exists),
     * PostingExecutor's transaction rolls back cleanly and we can open a fresh
     * transaction here to look up the original result without hitting Postgres's
     * "current transaction is aborted" error.
     *
     * @param request        the list of debit/credit entries — must net to zero per currency.
     * @param idempotencyKey supplied by the caller in the Idempotency-Key HTTP header.
     */
    public TransactionResult postTransaction(PostingRequest request, String idempotencyKey) {
        try {
            return postingExecutor.execute(request, idempotencyKey);
        } catch (DuplicateKeyException e) {
            // Idempotent retry — the unique constraint on idempotency_key fired.
            // PostingExecutor's REQUIRES_NEW transaction has been fully rolled back.
            // Fetch the original result in a fresh (default) transaction.
            log.info("Duplicate idempotency key '{}' — returning original result", idempotencyKey);
            return fetchExistingResult(idempotencyKey);
        }
    }

    /**
     * Fetch an existing transaction and its ledger entries by idempotency key.
     * Called only on a DuplicateKeyException — the row is guaranteed to exist.
     * Runs in a default (REQUIRED) transaction — a fresh one since we're called
     * from a non-transactional context.
     */
    private TransactionResult fetchExistingResult(String idempotencyKey) {
        Transaction txn = transactionRepo.findByIdempotencyKey(idempotencyKey)
            .orElseThrow(() -> new IllegalStateException(
                "DuplicateKeyException fired but transaction not found for key: " + idempotencyKey
            ));
        List<LedgerEntry> entries = ledgerEntryRepo.findByTransactionId(txn.id());
        return TransactionResult.from(txn, entries);
    }
}
