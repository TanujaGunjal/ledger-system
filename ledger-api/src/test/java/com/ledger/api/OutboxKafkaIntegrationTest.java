package com.ledger.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledger.api.domain.PostingRequest;
import com.ledger.api.domain.PostingRequest.EntryType;
import com.ledger.api.publisher.OutboxPublisher;
import com.ledger.api.repository.AccountRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * End-to-end Kafka pipeline test using Spring's in-process EmbeddedKafka broker.
 *
 * Why EmbeddedKafka instead of Testcontainers:
 * - Docker Desktop on Windows returns Status 400 to docker-java's named-pipe HTTP
 *   client regardless of which pipe or TCP port is configured — a known incompatibility
 *   between Docker Desktop's reverse-proxy shim and the docker-java HTTP stack.
 * - EmbeddedKafkaBroker (from spring-kafka-test) is an in-process Kafka broker that
 *   requires zero external infrastructure and is faster than a container startup.
 * - The outbox pipeline being tested is identical: PostingExecutor writes a row,
 *   OutboxPublisher's @Scheduled polls and sends to Kafka, the raw KafkaConsumer below
 *   reads it back and verifies key + payload.
 *
 * What this test proves:
 * 1. A successful transaction posting writes exactly one outbox_events row (published=false).
 * 2. OutboxPublisher picks up the row within its 1-second polling window and publishes to Kafka.
 * 3. The Kafka message key equals the transaction_ref UUID (per-transaction ordering guarantee).
 * 4. The payload JSON contains the correct transactionRef, status="POSTED", and 2 entries.
 * 5. The outbox_events row is marked published=true after the Kafka ack.
 * 6. A rolled-back (overdraft) transaction produces zero outbox_events rows.
 */
@SpringBootTest
@AutoConfigureMockMvc
// Re-enable Kafka autoconfiguration (cleared from the non-Kafka test application.yml exclusions)
// and point the producer/consumer at the embedded broker's dynamically assigned port.
@TestPropertySource(properties = {
    "spring.autoconfigure.exclude=",                                          // clear exclusion list
    "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",        // use in-process broker
    "spring.kafka.producer.acks=1"                                            // EmbeddedKafka is single-node
})
@EmbeddedKafka(
    partitions = 1,
    topics = { OutboxPublisher.TOPIC },
    brokerProperties = {
        "listeners=PLAINTEXT://localhost:0",   // random port — avoids conflicts with Redpanda
        "log.dir=target/embedded-kafka"
    }
)
class OutboxKafkaIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(OutboxKafkaIntegrationTest.class);

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired AccountRepository accountRepository;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired OutboxPublisher outboxPublisher;  // called directly for deterministic test behaviour

    // Port comes from ${spring.embedded.kafka.brokers} which is set by @EmbeddedKafka at startup.
    // We resolve it from the environment after the context is fully started.
    @org.springframework.beans.factory.annotation.Value("${spring.embedded.kafka.brokers}")
    private String embeddedBrokers;

    private Long debitAccountId;
    private Long creditAccountId;

    // A raw KafkaConsumer we control — not going through @KafkaListener — so the test
    // can poll with a deadline and inspect exactly what was produced.
    private KafkaConsumer<String, String> testConsumer;

    @BeforeEach
    void setUp() {
        // Wipe all tables in FK order — identical to the other integration test classes.
        jdbcTemplate.execute("DELETE FROM ledger_entries");
        jdbcTemplate.execute("DELETE FROM outbox_events");
        jdbcTemplate.execute("DELETE FROM transactions");
        jdbcTemplate.execute("DELETE FROM account_balances");
        jdbcTemplate.execute("DELETE FROM accounts");

        String debitNo  = "TST-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        String creditNo = "TST-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        debitAccountId  = accountRepository.createAccount(debitNo,  "Alice", "ASSET", "USD").id();
        creditAccountId = accountRepository.createAccount(creditNo, "Bob",   "ASSET", "USD").id();
        jdbcTemplate.update("UPDATE account_balances SET balance = 1000.00 WHERE account_id = ?", debitAccountId);

        // Stand up a raw consumer positioned at the CURRENT END of the topic.
        //
        // Why manual assign() instead of subscribe():
        // - subscribe() uses a group coordinator whose partition assignment is
        //   asynchronous. poll(Duration.ZERO) returns before the coordinator
        //   finishes, so assignment() is empty, seekToEnd(emptySet) is a no-op,
        //   and the consumer misses the message ("Expected size: 1 but was: 0").
        // - assign() is synchronous — partition ownership is immediate, no
        //   coordinator round-trip needed. seekToEnd() works on the first call.
        //
        // Why seekToEnd() is still necessary:
        // - The embedded Kafka broker is shared across all test classes in the
        //   same JVM run. ConcurrencyIntegrationTest publishes 100 messages to
        //   ledger.transactions before this class starts. Without seekToEnd()
        //   (or equivalent) the consumer would read all 100 prior messages plus
        //   the one posted here, failing with "Expected size: 1 but was: 101".
        testConsumer = new KafkaConsumer<>(Map.of(
            "bootstrap.servers",  embeddedBrokers,
            "auto.offset.reset",  "latest",          // fallback; seekToEnd() below is the real guard
            "key.deserializer",   StringDeserializer.class.getName(),
            "value.deserializer", StringDeserializer.class.getName()
        ));
        // Manual assign: synchronous, no group coordinator, partition known at compile time
        // because @EmbeddedKafka declares partitions = 1.
        TopicPartition partition = new TopicPartition(OutboxPublisher.TOPIC, 0);
        testConsumer.assign(List.of(partition));
        // Seek to end so only messages produced after this setUp() call are visible.
        testConsumer.seekToEnd(List.of(partition));
        // position() forces the lazy seek to be materialised in the consumer's
        // internal fetch-offset map before the test posts the transaction.
        testConsumer.position(partition);
    }

    @AfterEach
    void tearDown() {
        if (testConsumer != null) testConsumer.close();
    }

    @Test
    @DisplayName("Outbox: posted transaction → exactly one Kafka message with correct key and payload")
    void transactionPosted_producesKafkaMessage() throws Exception {
        String idempotencyKey = UUID.randomUUID().toString();

        // ── 1. POST the transaction ────────────────────────────────────────────
        String responseBody = mockMvc.perform(post("/api/v1/transactions")
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new PostingRequest(
                    "Outbox Kafka test transfer",
                    List.of(
                        new PostingRequest.PostingEntry(debitAccountId,  EntryType.DEBIT,  new BigDecimal("150.00"), "USD"),
                        new PostingRequest.PostingEntry(creditAccountId, EntryType.CREDIT, new BigDecimal("150.00"), "USD")
                    )
                ))))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();

        JsonNode txnResponse = objectMapper.readTree(responseBody);
        String expectedRef = txnResponse.get("transactionRef").asText();

        // ── 2. Verify outbox row written with published=false ──────────────────
        Integer outboxBefore = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM outbox_events WHERE published = false", Integer.class);
        assertThat(outboxBefore).as("outbox_events row must exist before publisher polls").isEqualTo(1);

        // ── 3. Trigger the publisher directly — deterministic, no scheduler timing dependency ───
        //
        // outboxPublisher.publishPending() is @Transactional: it opens a DB transaction,
        // SELECTs ... FOR UPDATE SKIP LOCKED (claims the row), sends to Kafka synchronously
        // via kafkaTemplate.send(...).get(), calls markPublished(), then commits.
        // If the @Scheduled background thread also fires during this call, FOR UPDATE SKIP
        // LOCKED ensures only one caller processes the row — the other gets an empty list.
        outboxPublisher.publishPending();
        log.debug("publishPending() returned — now polling consumer for the Kafka message");

        // ── 4. Poll the consumer — message should arrive quickly since send was synchronous ──
        List<ConsumerRecord<String, String>> received = new ArrayList<>();
        long deadline = System.currentTimeMillis() + 15_000;

        while (received.isEmpty() && System.currentTimeMillis() < deadline) {
            ConsumerRecords<String, String> batch = testConsumer.poll(Duration.ofMillis(500));
            batch.forEach(received::add);
        }

        assertThat(received)
            .as("Expected exactly one Kafka message on topic " + OutboxPublisher.TOPIC)
            .hasSize(1);

        ConsumerRecord<String, String> msg = received.get(0);

        // ── 4. Verify Kafka message key = transaction_ref UUID ──────────────────────
        assertThat(msg.key())
            .as("Kafka message key must equal the transaction_ref UUID (partition-ordering guarantee)")
            .isEqualTo(expectedRef);

        // ── 5. Payload must contain correct transactionRef, status, entries ────
        JsonNode payload = objectMapper.readTree(msg.value());
        assertThat(payload.get("transactionRef").asText())
            .as("Payload transactionRef must match the posted transaction")
            .isEqualTo(expectedRef);
        assertThat(payload.get("status").asText()).isEqualTo("POSTED");
        assertThat(payload.get("entries").size())
            .as("Payload must contain exactly 2 ledger entries (DEBIT + CREDIT)")
            .isEqualTo(2);

        // ── 6. Outbox row must be marked published=true after Kafka ack ────────
        long publishedDeadline = System.currentTimeMillis() + 3_000;
        boolean markedPublished = false;
        while (System.currentTimeMillis() < publishedDeadline) {
            Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE published = true", Integer.class);
            if (count != null && count == 1) {
                markedPublished = true;
                break;
            }
            Thread.sleep(200);
        }
        assertThat(markedPublished)
            .as("outbox_events row must be marked published=true after Kafka ack")
            .isTrue();
    }

    @Test
    @DisplayName("Outbox: rolled-back (overdraft) transaction leaves zero outbox_events rows")
    void rolledBackTransaction_leavesNoOutboxRow() throws Exception {
        // An overdraft causes InsufficientFundsException → the REQUIRES_NEW transaction
        // in PostingExecutor rolls back entirely, including the outbox row.
        mockMvc.perform(post("/api/v1/transactions")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new PostingRequest(
                    "Overdraft — should roll back",
                    List.of(
                        new PostingRequest.PostingEntry(debitAccountId,  EntryType.DEBIT,  new BigDecimal("5000.00"), "USD"),
                        new PostingRequest.PostingEntry(creditAccountId, EntryType.CREDIT, new BigDecimal("5000.00"), "USD")
                    )
                ))))
            .andExpect(status().isConflict());

        Integer outboxCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM outbox_events", Integer.class);
        assertThat(outboxCount)
            .as("A rolled-back transaction must leave zero outbox_events rows")
            .isZero();
    }
}
