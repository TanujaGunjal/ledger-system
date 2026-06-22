package com.ledger.api.controller;

import com.ledger.api.domain.PostingRequest;
import com.ledger.api.domain.TransactionResult;
import com.ledger.api.exception.MissingIdempotencyKeyException;
import com.ledger.api.service.LedgerService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/transactions")
public class TransactionController {

    private final LedgerService ledgerService;

    public TransactionController(LedgerService ledgerService) {
        this.ledgerService = ledgerService;
    }

    /**
     * POST /api/v1/transactions
     *
     * Required header: Idempotency-Key (a UUID generated client-side before the
     * first attempt — re-sending the same key on retry returns the original result
     * without re-posting the transaction).
     *
     * Returns 200 on idempotent retry (result already exists).
     * Returns 201 on first successful posting.
     * Returns 400 if entries don't net to zero or if the header is missing.
     * Returns 409 if a DEBIT would overdraw an account.
     */
    @PostMapping
    public ResponseEntity<TransactionResult> postTransaction(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody PostingRequest request) {

        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new MissingIdempotencyKeyException();
        }

        TransactionResult result = ledgerService.postTransaction(request, idempotencyKey.trim());

        // 200 on idempotent replay (status was already POSTED before we ran),
        // 201 on a fresh successful posting. The caller can distinguish these
        // via HTTP status if needed, but both bodies are identical by design.
        HttpStatus status = "POSTED".equals(result.status()) ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(result);
    }
}
