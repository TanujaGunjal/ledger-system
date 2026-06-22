package com.ledger.api.consumer;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Trivial Kafka consumer that logs every event from the ledger.transactions
 * topic. Its sole purpose in Phase 2 is to prove that the end-to-end pipeline
 * (PostingExecutor → outbox_events → OutboxPublisher → Kafka → here) works.
 *
 * In Phase 3 (reconciliation), a real consumer in the separate
 * reconciliation-worker service will replace this placeholder.
 *
 * Idempotency note: Kafka delivers at-least-once. This consumer is trivially
 * idempotent (it only logs). Real consumers must deduplicate on the message key
 * (aggregate_id / transaction_ref) if exactly-once semantics are needed.
 *
 * No @ConditionalOnBean needed: @KafkaListener annotations are processed by
 * KafkaListenerAnnotationBeanPostProcessor, which is only registered when
 * KafkaAutoConfiguration runs. When Kafka is excluded from the context (e.g.
 * non-Kafka test contexts), this annotation is silently ignored and the bean
 * is a harmless plain object.
 */
@Component
public class TrivialKafkaConsumer {

    private static final Logger log = LoggerFactory.getLogger(TrivialKafkaConsumer.class);

    @KafkaListener(topics = "ledger.transactions", groupId = "ledger-api-consumer")
    public void onMessage(ConsumerRecord<String, String> record) {
        log.info("Kafka ← received: topic={} partition={} offset={} key={} payload={}",
            record.topic(), record.partition(), record.offset(), record.key(), record.value());
    }
}
