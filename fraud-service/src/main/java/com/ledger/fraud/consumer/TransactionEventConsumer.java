package com.ledger.fraud.consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledger.fraud.domain.RiskLevel;
import com.ledger.fraud.service.FraudCaseService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Kafka consumer that processes {@code TransactionPosted} events from the
 * {@code ledger.transactions} topic and triggers fraud scoring.
 *
 * This consumer is the asynchronous counterpart to the synchronous
 * {@code POST /api/v1/fraud/score} REST endpoint. Both paths lead to
 * {@link FraudCaseService#scoreAndPersist}, so idempotency is enforced in one place.
 *
 * Event payload shape (produced by ledger-api's outbox publisher):
 * <pre>
 * {
 *   "transactionRef": "uuid-string",
 *   "idempotencyKey": "string",
 *   "description":    "string",
 *   "status":         "POSTED",
 *   "entries": [
 *     {"accountId": 1, "entryType": "DEBIT",  "amount": "50.0000", "currency": "USD"},
 *     {"accountId": 2, "entryType": "CREDIT", "amount": "50.0000", "currency": "USD"}
 *   ]
 * }
 * </pre>
 *
 * Error handling:
 * <ul>
 *   <li>JSON parse failures: logged and skipped. Re-throwing would cause Kafka to
 *       retry the message indefinitely for a malformed payload, which is never useful.</li>
 *   <li>Scoring failures: caught in {@link FraudCaseService} and returned as LOW risk.
 *       The consumer always commits the offset even if scoring produced a degraded result.</li>
 *   <li>No Dead Letter Topic is wired here — a DLT would be a Phase 4 concern.</li>
 * </ul>
 */
@Component
public class TransactionEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(TransactionEventConsumer.class);

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final FraudCaseService fraudCaseService;
    private final ObjectMapper     objectMapper;

    public TransactionEventConsumer(FraudCaseService fraudCaseService,
                                    ObjectMapper objectMapper) {
        this.fraudCaseService = fraudCaseService;
        this.objectMapper     = objectMapper;
    }

    /**
     * Consume a single {@code TransactionPosted} message.
     *
     * The method signature uses {@link ConsumerRecord} so we have access to the
     * Kafka partition and offset for diagnostic logging — invaluable when tracing
     * why a specific transaction was or was not scored.
     *
     * groupId matches {@code spring.kafka.consumer.group-id} in application.yml.
     * Using a separate group-id from ledger-api ensures this service maintains its
     * own independent consumer offset and does not interfere with other consumers
     * on the same topic.
     */
    @KafkaListener(
        topics   = "ledger.transactions",
        groupId  = "fraud-service-consumer"
    )
    public void onMessage(ConsumerRecord<String, String> record) {

        log.debug("Received Kafka message partition={} offset={} key={}",
                  record.partition(), record.offset(), record.key());

        // ── Step 1: Parse the JSON payload ─────────────────────────────────
        Map<String, Object> event;
        try {
            event = objectMapper.readValue(record.value(), MAP_TYPE);
        } catch (JsonProcessingException e) {
            // Malformed JSON — log and acknowledge the offset so Kafka doesn't retry.
            log.error("Failed to parse TransactionPosted payload at partition={} offset={}: {}",
                      record.partition(), record.offset(), e.getMessage());
            return;
        }

        String transactionRef = (String) event.get("transactionRef");
        if (transactionRef == null || transactionRef.isBlank()) {
            log.warn("TransactionPosted event missing transactionRef — skipping. offset={}",
                     record.offset());
            return;
        }

        // ── Step 2: Find the DEBIT entry ────────────────────────────────────
        // A double-entry posting always has exactly one DEBIT side. If somehow
        // there are multiple DEBITs (not valid for current ledger rules) we take
        // the first one. If there are none, we skip scoring.
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> entries =
            (List<Map<String, Object>>) event.getOrDefault("entries", List.of());

        Map<String, Object> debitEntry = entries.stream()
            .filter(e -> "DEBIT".equalsIgnoreCase((String) e.get("entryType")))
            .findFirst()
            .orElse(null);

        if (debitEntry == null) {
            log.debug("No DEBIT entry found for txnRef={} — skipping fraud scoring", transactionRef);
            return;
        }

        Long       accountId = toLong(debitEntry.get("accountId"));
        BigDecimal amount    = toBigDecimal(debitEntry.get("amount"));
        String     currency  = (String) debitEntry.getOrDefault("currency", "USD");

        if (accountId == null || amount == null) {
            log.warn("DEBIT entry missing accountId or amount for txnRef={} — skipping", transactionRef);
            return;
        }

        // ── Step 3: Score and persist ────────────────────────────────────────
        // scoreAndPersist is idempotent — if a case already exists for this txnRef
        // (e.g. from the synchronous /score call by ledger-api pre-posting), the
        // insert is skipped silently.
        try {
            var score = fraudCaseService.scoreAndPersist(transactionRef, accountId, amount, currency);

            if (score.riskLevel() == RiskLevel.HIGH) {
                log.warn("HIGH RISK event processed — txnRef={} accountId={} score={}",
                         transactionRef, accountId, score.totalScore());
            } else {
                log.info("Scored txnRef={} accountId={} riskLevel={} score={}",
                         transactionRef, accountId, score.riskLevel(), score.totalScore());
            }
        } catch (Exception e) {
            // Catch-all: if something unexpected slips through, log it but still
            // commit the offset. Never let a scoring failure cause infinite retries.
            log.error("Unexpected error scoring txnRef={}: {}", transactionRef, e.getMessage(), e);
        }
    }

    // ── Type helpers ──────────────────────────────────────────────────────────

    private Long toLong(Object v) {
        if (v == null) return null;
        if (v instanceof Integer i) return i.longValue();
        if (v instanceof Long l) return l;
        if (v instanceof Double d) return d.longValue();
        try { return Long.parseLong(v.toString()); } catch (NumberFormatException e) { return null; }
    }

    private BigDecimal toBigDecimal(Object v) {
        if (v == null) return null;
        if (v instanceof BigDecimal bd) return bd;
        try { return new BigDecimal(v.toString()); } catch (NumberFormatException e) { return null; }
    }
}
