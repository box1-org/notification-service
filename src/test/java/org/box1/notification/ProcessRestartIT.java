package org.box1.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Runs the packaged application with the prod profile, shuts it down with a retry pending, and restarts it. */
@Tag("containers")
class ProcessRestartIT {
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final FullStackResource STACK = new FullStackResource();
    private static String publicKey;
    private final List<Process> started = new ArrayList<>();

    @BeforeAll
    static void containers() {
        STACK.start();
        publicKey = new TestTokens().start().get("mp.jwt.verify.publickey.location");
    }

    @AfterAll
    static void stop() {
        STACK.stop();
    }

    @AfterEach
    void stopEverything() throws InterruptedException {
        for (Process process : started) {
            process.destroyForcibly();
            process.waitFor();
        }
        started.clear();
    }

    private record App(Process process, int port, Path log) {}

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private ProcessBuilder builder(int port, Path log) {
        ProcessBuilder builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-jar", Path.of("target", "quarkus-app", "quarkus-run.jar").toString());
        Map<String, String> env = builder.environment();
        env.put("QUARKUS_HTTP_PORT", Integer.toString(port));
        env.put("QUARKUS_DATASOURCE_JDBC_URL", FullStackResource.postgres.getJdbcUrl());
        env.put("QUARKUS_DATASOURCE_USERNAME", FullStackResource.postgres.getUsername());
        env.put("QUARKUS_DATASOURCE_PASSWORD", FullStackResource.postgres.getPassword());
        env.put("KAFKA_BOOTSTRAP_SERVERS", FullStackResource.kafka.getBootstrapServers());
        env.put("NOTIFY_JWKS_URL", publicKey);
        env.put("NOTIFY_EVENTS_FLOOR", FullStackResource.FLOOR);
        env.put("POD_NAMESPACE", FullStackResource.NAMESPACE);
        env.put("NOTIFY_STUB_FAULTS_ENABLED", "true");
        env.put("NOTIFY_STUB_FAULT_NAMESPACES", FullStackResource.NAMESPACE);
        env.put("NOTIFY_DELIVERY_POLL_INTERVAL", "200ms");
        // Long enough that the retry is still pending when the first process shuts down.
        env.put("NOTIFY_DELIVERY_BASE_BACKOFF_SECONDS", "20");
        env.put("NOTIFY_OUTBOX_POLL_INTERVAL", "200ms");
        builder.redirectErrorStream(true).redirectOutput(log.toFile());
        return builder;
    }

    private App start() throws Exception {
        int port = freePort();
        Path log = Files.createTempFile("run16-notification", ".log");
        App app = new App(builder(port, log).start(), port, log);
        started.add(app.process());
        Harness.await("application ready", Duration.ofSeconds(90), () -> {
            try {
                return request(app, "GET", "/q/health/ready", null, null).statusCode() == 200;
            } catch (IOException | InterruptedException error) {
                return false;
            }
        }, ready -> ready);
        return app;
    }

    private static HttpResponse<String> request(App app, String method, String path, String token, String body)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + app.port() + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        if (body != null) {
            builder.header("Content-Type", "application/json");
        }
        return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void shutdownWithARetryPendingResumesAfterRestartWithoutADuplicateDelivery() throws Exception {
        App first = start();
        assertEquals(200, request(first, "POST", "/api/v1/stub/faults", TestTokens.faultOperator(),
                "{\"mode\":\"TRANSIENT_503\",\"ttlSeconds\":300}").statusCode());
        String order = Harness.orderId();
        Harness.send(Harness.ORDER_TOPIC, order, Events.stateChanged(order, "SUBMITTED", "REJECTED", "UNKNOWN_SKU").toString());
        Harness.await("a retry pending", Duration.ofSeconds(30), () -> Harness.notifications(order),
                rows -> rows.size() == 1 && "RETRY_SCHEDULED".equals(rows.get(0).get("status")));
        // Graceful shutdown (SIGTERM), as Kubernetes does on a rollout.
        first.process().destroy();
        assertTrue(first.process().waitFor(30, TimeUnit.SECONDS), "shuts down");
        Map<String, Object> pending = Harness.notifications(order).get(0);
        assertEquals("RETRY_SCHEDULED", pending.get("status"));
        assertEquals(1, pending.get("attempt_count"));
        assertEquals(0L, Harness.count("SELECT count(*) FROM delivery_receipt WHERE notification_id = ?",
                pending.get("notification_id")));

        App second = start();
        assertEquals(200, request(second, "DELETE", "/api/v1/stub/faults", TestTokens.faultOperator(), null).statusCode());
        List<Map<String, Object>> rows = Harness.await("delivery after restart", Duration.ofSeconds(60),
                () -> Harness.notifications(order), list -> "DELIVERED".equals(list.get(0).get("status")));
        assertEquals(2, rows.get(0).get("attempt_count"));
        assertEquals(1L, Harness.count("SELECT count(*) FROM delivery_receipt WHERE notification_id = ?",
                rows.get(0).get("notification_id")));
        assertEquals(1, Harness.read(Harness.EVENTS_TOPIC, order, 2, Duration.ofSeconds(10)).size());
        String body = request(second, "GET", "/api/v1/notifications?orderId=" + order, TestTokens.reader(), null).body();
        assertTrue(body.contains("\"status\":\"DELIVERED\""), body);
        String log = Files.readString(first.log());
        assertTrue(log.contains("\"service\":\"notification-service\""), "prod logs are JSON with the service field");
    }

    @Test
    void refusesToStartWithInvalidSafetyConfiguration() throws Exception {
        refuses(Map.of("NOTIFY_EVENTS_FLOOR", ""), "notification.consumer.events-floor");
        refuses(Map.of("NOTIFY_EVENTS_FLOOR", "2999-01-01T00:00:00Z"), "is in the future");
        refuses(Map.of("NOTIFY_DELIVERY_MAX_ATTEMPTS", "50"), "max-attempts");
        refuses(Map.of("NOTIFY_STUB_MAX_TTL_SECONDS", "86400"), "max-ttl-seconds");
        refuses(Map.of("NOTIFY_PROVIDER", "smtp"), "Only the stub provider exists");
    }

    private void refuses(Map<String, String> env, String expectedInLog) throws Exception {
        Path log = Files.createTempFile("run16-notification-refused", ".log");
        ProcessBuilder builder = builder(freePort(), log);
        builder.environment().putAll(env);
        Process process = builder.start();
        started.add(process);
        assertTrue(process.waitFor(90, TimeUnit.SECONDS), "exits by itself");
        assertNotEquals(0, process.exitValue());
        assertTrue(Files.readString(log).contains(expectedInLog), "says what is wrong: " + expectedInLog);
    }
}
