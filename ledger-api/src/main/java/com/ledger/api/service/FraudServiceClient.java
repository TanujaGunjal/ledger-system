package com.ledger.api.service;

import com.ledger.api.domain.PostingRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * HTTP client for calling the fraud-service's synchronous scoring endpoint.
 *
 * This client is used by {@link com.ledger.api.controller.TransactionController}
 * as a pre-posting fraud gate. Key design decisions:
 *
 * <ul>
 *   <li><strong>Hard 500 ms timeout</strong>: the fraud check must never slow down
 *       the happy-path posting. If fraud-service doesn't respond in 500 ms, we
 *       fall through and post the transaction normally (degraded mode).</li>
 *   <li><strong>Non-blocking on any failure</strong>: network errors, timeouts,
 *       non-2xx responses, and JSON parse failures are all caught and treated as
 *       LOW risk. The posting path is never blocked by fraud-service unavailability.</li>
 *   <li><strong>Plain java.net.http.HttpClient</strong>: avoids pulling in WebClient
 *       (which requires spring-boot-starter-webflux) or RestTemplate (deprecated).
 *       The built-in JDK client is sufficient for a single outbound call per request.</li>
 * </ul>
 *
 * The fraud-service URL is configured via {@code fraud.service.url} in application.yml
 * (defaulting to {@code http://fraud-service:9092} for Docker network use). Override
 * with {@code FRAUD_SERVICE_URL} environment variable for different environments.
 */
@Component
public class FraudServiceClient {

    private static final Logger log = LoggerFactory.getLogger(FraudServiceClient.class);

    /** Timeout for the entire fraud-service HTTP call — connect + read combined. */
    private static final Duration TIMEOUT = Duration.ofMillis(500);

    private final HttpClient    httpClient;
    private final ObjectMapper  objectMapper;

    @Value("${fraud.service.url:http://fraud-service:9092}")
    private String fraudServiceUrl;

    public FraudServiceClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        // Build the client once — HttpClient is thread-safe and should be reused.
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .build();
    }

    // ── Result record ─────────────────────────────────────────────────────────

    /**
     * The scoring result returned to the controller.
     *
     * @param riskLevel   "HIGH", "MEDIUM", or "LOW".
     * @param score       Numeric score 0–100.
     * @param reachable   False if the HTTP call failed; true on a successful response.
     */
    public record FraudCheckResult(String riskLevel, int score, boolean reachable) {

        /** Convenience factory for the degraded-mode (unreachable service) path. */
        public static FraudCheckResult unreachable() {
            return new FraudCheckResult("LOW", 0, false);
        }

        public boolean isHighRisk()   { return "HIGH".equals(riskLevel); }
        public boolean isMediumRisk() { return "MEDIUM".equals(riskLevel); }
    }

    // ── Scoring call ──────────────────────────────────────────────────────────

    /**
     * Call {@code POST /api/v1/fraud/score} on the fraud-service with a 500 ms timeout.
     *
     * Finds the DEBIT entry in the posting request and sends its accountId, amount,
     * and currency. Only DEBIT entries are evaluated — CREDIT entries represent
     * the receiving side and are not a fraud signal on their own.
     *
     * @param transactionRef A UUID string for correlation (not yet persisted — this
     *                       is the provisional ref generated before posting begins).
     * @param request        The posting request from the controller.
     * @return The scoring result, or {@link FraudCheckResult#unreachable()} on any failure.
     */
    public FraudCheckResult checkFraud(String transactionRef, PostingRequest request) {
        // Find the first DEBIT entry — this is the account that is losing funds.
        PostingRequest.PostingEntry debitEntry = request.entries().stream()
            .filter(e -> e.entryType() == PostingRequest.EntryType.DEBIT)
            .findFirst()
            .orElse(null);

        if (debitEntry == null) {
            // No DEBIT entry — this is an invalid posting (will be caught later),
            // but we return LOW risk here so the error path is handled by the validator.
            return new FraudCheckResult("LOW", 0, true);
        }

        try {
            String body = objectMapper.writeValueAsString(Map.of(
                "transactionRef", transactionRef,
                "accountId",      debitEntry.accountId(),
                "amount",         debitEntry.amount(),
                "currency",       debitEntry.currency(),
                "entryType",      "DEBIT"
            ));

            HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(fraudServiceUrl + "/api/v1/fraud/score"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(TIMEOUT)
                .build();

            HttpResponse<String> response = httpClient.send(
                httpRequest, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("Fraud-service returned HTTP {} for txnRef={} — treating as LOW risk",
                         response.statusCode(), transactionRef);
                return FraudCheckResult.unreachable();
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = objectMapper.readValue(response.body(), Map.class);
            String riskLevel = (String) parsed.getOrDefault("riskLevel", "LOW");
            int    score     = ((Number) parsed.getOrDefault("score", 0)).intValue();

            log.debug("Fraud check: txnRef={} riskLevel={} score={}", transactionRef, riskLevel, score);
            return new FraudCheckResult(riskLevel, score, true);

        } catch (java.net.http.HttpTimeoutException e) {
            log.warn("Fraud-service timed out (>500 ms) for txnRef={} — proceeding normally", transactionRef);
            return FraudCheckResult.unreachable();
        } catch (Exception e) {
            log.warn("Fraud-service unreachable for txnRef={}: {} — proceeding normally",
                     transactionRef, e.getMessage());
            return FraudCheckResult.unreachable();
        }
    }
}
