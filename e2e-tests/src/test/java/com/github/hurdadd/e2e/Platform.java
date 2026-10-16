package com.github.hurdadd.e2e;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Thin black-box helpers: HTTP in, SQL/Kafka out. No application classes are used on purpose. */
final class Platform {

    record Response(int status, String body) {}

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final ObjectMapper JSON = new ObjectMapper();

    private Platform() {
    }

    // ---- orchestrator API ----------------------------------------------------------------

    static Response createPayment(String idempotencyKey, UUID merchantId, String amount) {
        return send(HttpRequest.newBuilder(URI.create(E2eEnvironment.orchestratorUrl() + "/payments"))
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", idempotencyKey)
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"amount\":" + amount + ",\"currency\":\"USD\",\"merchantId\":\"" + merchantId + "\"}")));
    }

    /** Like {@link #createPayment} but gives up after {@code within}; a client-side give-up is status -1. */
    static Response createPaymentWithin(Duration within, String idempotencyKey, UUID merchantId, String amount) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(E2eEnvironment.orchestratorUrl() + "/payments"))
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", idempotencyKey)
                .timeout(within)
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"amount\":" + amount + ",\"currency\":\"USD\",\"merchantId\":\"" + merchantId + "\"}"))
                .build();
        try {
            HttpResponse<String> r = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            return new Response(r.statusCode(), r.body());
        } catch (java.io.IOException e) { // timeout or connection reset: the caller saw no answer
            return new Response(-1, e.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** Orchestrator's Hikari gauge (actuator is exposed for the e2e container only). */
    static double hikari(String gauge) {
        Response r = send(HttpRequest.newBuilder(URI.create(
                E2eEnvironment.orchestratorUrl() + "/actuator/metrics/hikaricp.connections." + gauge)).GET());
        return JSON.readTree(r.body()).get("measurements").get(0).get("value").asDouble();
    }

    static boolean orchestratorHealthy() {
        try {
            return send(HttpRequest.newBuilder(URI.create(E2eEnvironment.orchestratorUrl() + "/actuator/health")).GET())
                    .status() == 200;
        } catch (RuntimeException e) {
            return false; // still starting
        }
    }

    static UUID paymentId(Response created) {
        return UUID.fromString(JSON.readTree(created.body()).get("paymentId").asString());
    }

    /** HMAC-signed PSP callback, signed exactly as the PSP would: HMAC(ts.nonce.body). */
    static Response callback(String pspReference, String status, String nonce, String secret) {
        String body = "{\"pspReference\":\"" + pspReference + "\",\"status\":\"" + status + "\"}";
        long ts = System.currentTimeMillis() / 1000;
        return send(HttpRequest.newBuilder(URI.create(E2eEnvironment.orchestratorUrl() + "/callbacks/psp/slow-psp"))
                .header("Content-Type", "application/json")
                .header("X-Psp-Timestamp", String.valueOf(ts))
                .header("X-Psp-Nonce", nonce)
                .header("X-Psp-Signature", hmac(secret, ts + "." + nonce + "." + body))
                .POST(HttpRequest.BodyPublishers.ofString(body)));
    }

    private static String hmac(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- WireMock as the PSP -------------------------------------------------------------

    /** Wipes stubs + request journal, then installs the "slow PSP" baseline used by both scenarios. */
    static void resetPsp() {
        admin("POST", "/__admin/reset", null);
        // authorize: the PSP does approve, but only after 3s — longer than our 1s time limit
        admin("POST", "/__admin/mappings", """
                {"priority":5,"request":{"method":"POST","url":"/authorize"},
                 "response":{"status":200,"headers":{"Content-Type":"application/json"},"jsonBody":{"outcome":"APPROVED"},"fixedDelayMilliseconds":3000}}""");
        // status: unknown unless a scenario says otherwise (TIMEOUT = "PSP doesn't know yet")
        admin("POST", "/__admin/mappings", """
                {"priority":10,"request":{"method":"GET","urlPathPattern":"/status/.*"},
                 "response":{"status":200,"headers":{"Content-Type":"application/json"},"jsonBody":{"outcome":"TIMEOUT"}}}""");
    }

    /** WireMock scenario: status says "unknown" {@code unknownTimes} times, then APPROVED — a PSP that catches up. */
    static void pspApprovesAfterPolls(String pspReference, int unknownTimes) {
        String scenario = "status-" + pspReference;
        for (int i = 0; i <= unknownTimes; i++) {
            String from = i == 0 ? "Started" : "poll-" + i;
            String outcome = i < unknownTimes ? "TIMEOUT" : "APPROVED";
            String next = i < unknownTimes ? "\"newScenarioState\":\"poll-" + (i + 1) + "\"," : "";
            admin("POST", "/__admin/mappings", """
                    {"priority":1,"scenarioName":"%s","requiredScenarioState":"%s",%s
                     "request":{"method":"GET","url":"/status/%s"},
                     "response":{"status":200,"headers":{"Content-Type":"application/json"},"jsonBody":{"outcome":"%s"}}}"""
                    .formatted(scenario, from, next, pspReference, outcome));
        }
    }

    static int statusRequests(String pspReference) {
        String body = admin("POST", "/__admin/requests/count",
                "{\"method\":\"GET\",\"url\":\"/status/" + pspReference + "\"}");
        return JSON.readTree(body).get("count").asInt();
    }

    static void pspReportsApproved(String pspReference) {
        admin("POST", "/__admin/mappings", """
                {"priority":1,"request":{"method":"GET","url":"/status/%s"},
                 "response":{"status":200,"headers":{"Content-Type":"application/json"},"jsonBody":{"outcome":"APPROVED"}}}""".formatted(pspReference));
    }

    static int authorizeRequests(String pspReference) {
        String body = admin("POST", "/__admin/requests/count",
                "{\"method\":\"POST\",\"url\":\"/authorize\",\"bodyPatterns\":[{\"contains\":\"" + pspReference + "\"}]}");
        return JSON.readTree(body).get("count").asInt();
    }

    private static String admin(String method, String path, String body) {
        return send(HttpRequest.newBuilder(URI.create(E2eEnvironment.wiremockUrl() + path)
                ).header("Content-Type", "application/json")
                .method(method, body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body))).body();
    }

    private static Response send(HttpRequest.Builder request) {
        try {
            HttpResponse<String> r = HTTP.send(request.timeout(Duration.ofSeconds(15)).build(),
                    HttpResponse.BodyHandlers.ofString());
            return new Response(r.statusCode(), r.body());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- databases -----------------------------------------------------------------------

    static <T> T query(String database, String sql, Object param, java.util.function.Function<ResultSet, T> read) {
        try (Connection c = DriverManager.getConnection(E2eEnvironment.jdbcUrl(database), "openpay", "openpay");
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, param);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return read.apply(rs);
            }
        } catch (Exception e) {
            throw new IllegalStateException(sql, e);
        }
    }

    static long count(String database, String sql, Object param) {
        return query(database, sql, param, rs -> {
            try {
                return rs.getLong(1);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }

    static String pspReference(UUID paymentId) {
        return query("openpay", "SELECT psp_reference FROM payments WHERE payment_id = ?", paymentId, rs -> {
            try {
                return rs.getString(1);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }

    static String status(UUID paymentId) {
        return query("openpay", "SELECT status FROM payments WHERE payment_id = ?", paymentId, rs -> {
            try {
                return rs.getString(1);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }

    static boolean outboxPublished(UUID paymentId) {
        return count("openpay", "SELECT count(*) FROM payment_outbox WHERE payment_id = ? AND published", paymentId) == 1;
    }

    /** Payment ids in the order their outbox rows were written. */
    static List<String> outboxOrder(java.util.Collection<UUID> paymentIds) {
        try (Connection c = DriverManager.getConnection(E2eEnvironment.jdbcUrl("openpay"), "openpay", "openpay");
             PreparedStatement ps = c.prepareStatement(
                     "SELECT payment_id FROM payment_outbox WHERE payment_id = ANY(?) ORDER BY created_at")) {
            ps.setArray(1, c.createArrayOf("uuid", paymentIds.toArray()));
            var ids = new java.util.ArrayList<String>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ids.add(rs.getString(1));
                }
            }
            return ids;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static long unpublishedOutbox(UUID paymentId) {
        return count("openpay", "SELECT count(*) FROM payment_outbox WHERE payment_id = ? AND NOT published", paymentId);
    }

    static long ledgerEntries(UUID paymentId) {
        return count("openpay_ledger", "SELECT count(*) FROM journal_entry WHERE description = ?", "Settlement " + paymentId);
    }

    /** Σ of all postings of this payment's journal entries — must be 0 (double entry). */
    static long ledgerSum(UUID paymentId) {
        return count("openpay_ledger", """
                SELECT COALESCE(SUM(p.amount), 0) FROM posting p
                JOIN journal_entry j ON j.id = p.journal_entry_id WHERE j.description = ?""", "Settlement " + paymentId);
    }

    static long merchantBalance(UUID merchantId) {
        return count("openpay_ledger", """
                SELECT COALESCE(SUM(p.amount), 0) FROM posting p
                JOIN account a ON a.id = p.account_id WHERE a.name = ?""", "merchant:" + merchantId);
    }

    // ---- Kafka ---------------------------------------------------------------------------

    /** Reads the PaymentSettled message for this payment straight off the topic. */
    static String readSettledMessage(UUID paymentId) {
        try (KafkaConsumer<String, String> consumer = consumer("e2e-reader-" + UUID.randomUUID())) {
            List<TopicPartition> partitions = partitions(consumer);
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (System.nanoTime() < deadline) {
                for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(500))) {
                    if (paymentId.toString().equals(r.key())) {
                        return r.value();
                    }
                }
            }
            throw new IllegalStateException("No Kafka message for payment " + paymentId);
        }
    }

    record Delivered(int partition, long offset, String key) {}

    /** Every message on the topic for these payments, in (partition, offset) order. */
    static List<Delivered> delivered(java.util.Collection<UUID> paymentIds) {
        var wanted = paymentIds.stream().map(UUID::toString).collect(java.util.stream.Collectors.toSet());
        var out = new java.util.ArrayList<Delivered>();
        try (KafkaConsumer<String, String> consumer = consumer("e2e-reader-" + UUID.randomUUID())) {
            List<TopicPartition> partitions = partitions(consumer);
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            Map<TopicPartition, Long> end = consumer.endOffsets(partitions);
            while (end.entrySet().stream().anyMatch(e -> consumer.position(e.getKey()) < e.getValue())) {
                for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(500))) {
                    if (wanted.contains(r.key())) {
                        out.add(new Delivered(r.partition(), r.offset(), r.key()));
                    }
                }
            }
        }
        return out;
    }

    /** Creates a payment and settles it with signed callbacks (the PSP stays "unknown" for the reconciler). */
    static UUID settledViaCallbacks(UUID merchant, String amount) {
        Response created = createPayment("F-" + UUID.randomUUID(), merchant, amount);
        if (created.status() != 201) {
            throw new IllegalStateException("create failed: " + created);
        }
        UUID id = paymentId(created);
        String ref = pspReference(id);
        for (String status : List.of("AUTHORIZED", "SETTLED")) {
            Response cb = callback(ref, status, UUID.randomUUID().toString(), E2eEnvironment.PSP_SECRET);
            if (cb.status() != 200) {
                throw new IllegalStateException(status + " callback: " + cb);
            }
        }
        return id;
    }

    static void produceAgain(UUID paymentId, String message) {
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(Map.of(
                "bootstrap.servers", E2eEnvironment.kafkaBootstrap(),
                "acks", "all",
                "key.serializer", StringSerializer.class.getName(),
                "value.serializer", StringSerializer.class.getName()))) {
            producer.send(new ProducerRecord<>(E2eEnvironment.TOPIC, paymentId.toString(), message)).get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** True once the ledger's consumer group has committed everything on the topic. */
    static boolean ledgerConsumerCaughtUp() {
        try (KafkaConsumer<String, String> consumer = consumer("e2e-offsets-" + UUID.randomUUID());
             AdminClient admin = AdminClient.create(Map.of("bootstrap.servers", E2eEnvironment.kafkaBootstrap()))) {
            Map<TopicPartition, Long> end = consumer.endOffsets(partitions(consumer));
            var committed = admin.listConsumerGroupOffsets("ledger-service")
                    .partitionsToOffsetAndMetadata().get();
            return end.entrySet().stream().allMatch(e ->
                    e.getValue() == 0
                            || (committed.get(e.getKey()) != null && committed.get(e.getKey()).offset() >= e.getValue()));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static KafkaConsumer<String, String> consumer(String group) {
        return new KafkaConsumer<>(Map.of(
                "bootstrap.servers", E2eEnvironment.kafkaBootstrap(),
                "group.id", group,
                "enable.auto.commit", "false",
                "key.deserializer", StringDeserializer.class.getName(),
                "value.deserializer", StringDeserializer.class.getName()));
    }

    private static List<TopicPartition> partitions(KafkaConsumer<String, String> consumer) {
        return consumer.partitionsFor(E2eEnvironment.TOPIC).stream()
                .map(p -> new TopicPartition(p.topic(), p.partition())).toList();
    }
}
