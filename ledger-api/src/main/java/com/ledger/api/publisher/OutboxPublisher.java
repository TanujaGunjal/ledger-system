package com.ledger.api.publisher;

import com.ledger.api.domain.OutboxEvent;
import com.ledger.api.repository.OutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.ExecutionException;

/**
 * Polling publisher that drains the outbox_events table into Kafka.
 *
 * Delivery guarantee:
 * - The query uses SELECT ... FOR UPDATE SKIP LOCKED, so multiple instances
 *   of this publisher (or multiple threads) can't process the same row twice.
 * - markPublished() is only called AFTER the Kafka broker has ACK'd the send.
 *   A crash between send and markPublished means the row stays unpublished and
 *   is re-sent on the next poll — at-least-once delivery is guaranteed.
 * - Kafka consumers must be idempotent on the message key (aggregate_id /
 *   transaction_ref) to handle the rare re-delivery case safely.
 *
 * Ordering guarantee:
 * - The Kafka message KEY is set to the aggregate_id (transaction_ref UUID).
 *   Kafka routes all messages with the same key to the same partition, so all
 *   events for a given transaction arrive at consumers in insertion order.
 * - Rows are fetched in id ASC order (i.e. insertion order) before publishing.
 *
 * Conditional wiring:
 * - KafkaTemplate is injected via @Autowired(required=false).
 *   When Kafka is excluded from the application context (e.g. non-Kafka integration
 *   tests), no KafkaTemplate bean exists; the setter is never called; kafkaTemplate
 *   stays null; publishPending() exits immediately — the bean is a harmless no-op.
 * - This avoids @ConditionalOnBean on a @Component class, which is unreliable
 *   because conditions on component-scanned beans evaluate BEFORE autoconfiguration
 *   runs, so KafkaTemplate is not yet registered when the condition is checked.
 */
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    public static final String TOPIC = "ledger.transactions";
    private static final int BATCH_SIZE = 100;

    private final OutboxRepository outboxRepo;

    /**
     * Injected after construction via setter so that required=false works correctly.
     * Null when Kafka is not configured (e.g. excluded in non-Kafka test contexts).
     */
    private KafkaTemplate<String, String> kafkaTemplate;

    public OutboxPublisher(OutboxRepository outboxRepo) {
        this.outboxRepo = outboxRepo;
    }

    /**
     * Optional Kafka wiring. When no KafkaTemplate bean exists in the context,
     * Spring skips this setter and kafkaTemplate remains null.
     */
    @Autowired(required = false)
    public void setKafkaTemplate(KafkaTemplate<String, String> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    /**
     * Poll every second. In production you would tune fixedDelay and BATCH_SIZE
     * (or switch to Postgres LISTEN/NOTIFY for sub-second latency), but 1-second
     * polling is more than sufficient to demonstrate end-to-end correctness.
     *
     * Each call runs in its own DEFAULT transaction (REQUIRED):
     * - findUnpublished() issues a SELECT ... FOR UPDATE SKIP LOCKED inside this
     *   transaction, claiming the rows exclusively.
     * - For each row: send to Kafka synchronously (kafkaTemplate.send().get()),
     *   then call markPublished() — still inside the same DB transaction.
     * - The transaction commits only after all rows in the batch are acked and
     *   marked. If Kafka is down, the send throws, the transaction rolls back,
     *   and the rows remain unpublished for the next poll.
     */
    @Scheduled(fixedDelay = 1000)
    @Transactional
    public void publishPending() {
        // Short-circuit when Kafka is not configured (e.g. non-Kafka test contexts).
        // The transaction opens and immediately commits as a no-op — harmless.
        if (kafkaTemplate == null) return;

        List<OutboxEvent> events = outboxRepo.findUnpublished(BATCH_SIZE);
        if (events.isEmpty()) return;

        log.debug("OutboxPublisher: found {} unpublished event(s)", events.size());

        for (OutboxEvent event : events) {
            try {
                // KEY = aggregateId (transaction_ref UUID) — routes all events for
                // the same transaction to the same Kafka partition, preserving order.
                SendResult<String, String> result =
                    kafkaTemplate.send(TOPIC, event.aggregateId(), event.payload()).get();

                log.info("Published outbox event id={} aggregateId={} partition={} offset={}",
                    event.id(), event.aggregateId(),
                    result.getRecordMetadata().partition(),
                    result.getRecordMetadata().offset());

                // Mark published ONLY after confirmed broker ack.
                outboxRepo.markPublished(event.id());

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("OutboxPublisher interrupted while sending event id={}", event.id());
                throw new RuntimeException("Publisher interrupted", e);
            } catch (ExecutionException e) {
                // Kafka is unavailable or the send failed. Log and rethrow so the
                // @Transactional rolls back — the row stays unpublished for the next poll.
                log.error("Failed to publish outbox event id={}: {}", event.id(), e.getMessage());
                throw new RuntimeException("Kafka send failed for outbox event " + event.id(), e);
            }
        }
    }
}
