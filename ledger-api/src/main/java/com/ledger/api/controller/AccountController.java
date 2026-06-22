package com.ledger.api.controller;

import com.ledger.api.domain.Account;
import com.ledger.api.domain.AccountBalance;
import com.ledger.api.domain.LedgerEntry;
import com.ledger.api.repository.AccountBalanceRepository;
import com.ledger.api.repository.AccountRepository;
import com.ledger.api.repository.LedgerEntryRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/accounts")
public class AccountController {

    private final AccountRepository accountRepository;
    private final AccountBalanceRepository accountBalanceRepository;
    private final LedgerEntryRepository ledgerEntryRepository;

    public AccountController(AccountRepository accountRepository,
                             AccountBalanceRepository accountBalanceRepository,
                             LedgerEntryRepository ledgerEntryRepository) {
        this.accountRepository = accountRepository;
        this.accountBalanceRepository = accountBalanceRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
    }

    public record CreateAccountRequest(
        @NotBlank String accountNumber,
        @NotBlank String ownerName,
        @NotBlank String accountType,
        @NotBlank String currency
    ) {}

    @GetMapping
    public ResponseEntity<List<Account>> listAccounts() {
        return ResponseEntity.ok(accountRepository.findAll());
    }

    @PostMapping
    public ResponseEntity<Account> createAccount(@Valid @RequestBody CreateAccountRequest request) {
        Account account = accountRepository.createAccount(
            request.accountNumber(), request.ownerName(), request.accountType(), request.currency()
        );
        return ResponseEntity.ok(account);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Account> getAccount(@PathVariable Long id) {
        return accountRepository.findById(id)
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * GET /api/v1/accounts/{id}/balance
     *
     * Returns the materialized balance from account_balances.
     * This is a derived value kept in sync by the posting transaction —
     * it is fast (single row lookup, no aggregation) and correct as long
     * as every posting goes through LedgerService.
     */
    @GetMapping("/{id}/balance")
    public ResponseEntity<AccountBalance> getBalance(@PathVariable Long id) {
        return accountBalanceRepository.findByAccountId(id)
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * GET /api/v1/accounts/{id}/ledger-entries?afterSequence=0&limit=50
     *
     * Sequence-cursor pagination: the caller passes the last sequence_no they
     * saw as `afterSequence` to get the next page. Sequence numbers are
     * monotonically increasing per account and never reused, so this cursor
     * is stable even if new entries are inserted between page fetches.
     *
     * Default: start from the beginning (afterSequence=0), 50 entries per page.
     */
    @GetMapping("/{id}/ledger-entries")
    public ResponseEntity<List<LedgerEntry>> getLedgerEntries(
            @PathVariable Long id,
            @RequestParam(defaultValue = "0") Long afterSequence,
            @RequestParam(defaultValue = "50") int limit) {

        // Cap the page size to prevent runaway queries.
        int effectiveLimit = Math.min(limit, 200);
        List<LedgerEntry> entries = ledgerEntryRepository.findByAccountId(id, afterSequence, effectiveLimit);
        return ResponseEntity.ok(entries);
    }
}
