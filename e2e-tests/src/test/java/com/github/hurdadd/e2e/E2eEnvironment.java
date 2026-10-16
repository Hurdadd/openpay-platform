package com.github.hurdadd.e2e;

import com.github.dockerjava.api.model.ExposedPort;
import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.ToxiproxyClient;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import com.github.dockerjava.api.command.InspectContainerResponse;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

import java.io.File;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/** The whole platform, started once per JVM and shared by every scenario (Ryuk cleans up). */
final class E2eEnvironment {

    static final String PSP_SECRET = "e2e-secret";
    static final String TOPIC = "payment-events";

    static final Network NET = Network.newNetwork();

    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17")
            .withNetwork(NET).withNetworkAliases("postgres-real")
            .withUsername("openpay").withPassword("openpay").withDatabaseName("openpay")
            // same script docker-compose mounts: creates the second database for the ledger
            .withCopyFileToContainer(
                    MountableFile.forHostPath(Path.of(System.getProperty("repo.root"),
                            "docker/postgres/init/01-create-ledger-database.sql")),
                    "/docker-entrypoint-initdb.d/01-create-ledger-database.sql");

    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine")
            .withNetwork(NET).withNetworkAliases("redis-real")
            .withCommand("redis-server", "--requirepass", "openpay-redis")
            .withExposedPorts(6379);

    // Plain container configured like docker-compose.yml (same image). Listeners use an empty host:
    // Kafka 3.9 in combined mode rejects an explicit 0.0.0.0 on the CONTROLLER listener at startup.
    // The external advertised port is only known after the container starts, so the real start
    // command is written in then (the same trick the Testcontainers module uses).
    static final GenericContainer<?> KAFKA = new Broker()
            .withNetwork(NET).withNetworkAliases("kafka-real")
            .withExposedPorts(9092)
            .withEnv(Map.ofEntries(
                    Map.entry("KAFKA_NODE_ID", "1"),
                    Map.entry("KAFKA_PROCESS_ROLES", "broker,controller"),
                    Map.entry("KAFKA_LISTENERS", "INTERNAL://:29092,EXTERNAL://:9092,CONTROLLER://:9093"),
                    Map.entry("KAFKA_LISTENER_SECURITY_PROTOCOL_MAP", "INTERNAL:PLAINTEXT,EXTERNAL:PLAINTEXT,CONTROLLER:PLAINTEXT"),
                    Map.entry("KAFKA_INTER_BROKER_LISTENER_NAME", "INTERNAL"),
                    Map.entry("KAFKA_CONTROLLER_LISTENER_NAMES", "CONTROLLER"),
                    Map.entry("KAFKA_CONTROLLER_QUORUM_VOTERS", "1@localhost:9093"),
                    Map.entry("KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR", "1"),
                    Map.entry("KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR", "1"),
                    Map.entry("KAFKA_TRANSACTION_STATE_LOG_MIN_ISR", "1"),
                    Map.entry("CLUSTER_ID", "openpay-e2e-cluster-001")))
            .withCommand("sh", "-c", "while [ ! -f /tmp/start.sh ]; do sleep 0.1; done; exec bash /tmp/start.sh")
            .withLogConsumer(new ContainerLog())
            .waitingFor(Wait.forLogMessage(".*Kafka Server started.*", 1).withStartupTimeout(Duration.ofMinutes(2)));

    // PAY-305: every orchestrator -> Postgres/Redis/Kafka connection (and Kafka's advertised address,
    // so clients cannot bypass it) goes through Toxiproxy under the real service names. The ledger
    // reaches its own database directly, so DB faults injected here hit the orchestrator only.
    static final GenericContainer<?> TOXIPROXY = new GenericContainer<>("ghcr.io/shopify/toxiproxy:2.9.0")
            .withNetwork(NET).withNetworkAliases("postgres", "redis", "kafka")
            .withExposedPorts(8474)
            .waitingFor(Wait.forHttp("/version").forPort(8474).forStatusCode(200));

    static Proxy pgProxy;
    static Proxy redisProxy;
    static Proxy kafkaProxy;

    static final GenericContainer<?> WIREMOCK = new GenericContainer<>("wiremock/wiremock:3.9.2")
            .withNetwork(NET).withNetworkAliases("wiremock")
            .withExposedPorts(8080)
            .waitingFor(Wait.forHttp("/__admin/mappings").forStatusCode(200));

    static final GenericContainer<?> ORCHESTRATOR = service("orchestrator.libs", 8080)
            .withNetworkAliases("payment-orchestrator")
            .withEnv(Map.of(
                    "PAYMENT_DB_URL", "jdbc:postgresql://postgres:5432/openpay",
                    "MANAGEMENT_ENDPOINTS_WEB_EXPOSURE_INCLUDE", "health,metrics",
                    "REDIS_HOST", "redis",
                    "KAFKA_BOOTSTRAP_SERVERS", "kafka:29092",
                    "PSP_BASE_URL", "http://wiremock:8080",
                    "SLOW_PSP_SECRET", PSP_SECRET,
                    // short enough that the reconciler settles within the test's patience
                    "RECONCILER_STUCK_THRESHOLD", "PT2S",
                    "RECONCILER_POLL_INTERVAL", "PT1S"));

    static final GenericContainer<?> LEDGER = service("ledger.libs", 8081)
            .withNetworkAliases("ledger-service")
            .withEnv(Map.of(
                    "LEDGER_DB_URL", "jdbc:postgresql://postgres-real:5432/openpay_ledger",
                    "KAFKA_BOOTSTRAP_SERVERS", "kafka:29092"));

    static {
        // infrastructure first, topic before the services so the ledger consumer never races it
        try {
            List.of(POSTGRES, REDIS, KAFKA, TOXIPROXY, WIREMOCK).forEach(GenericContainer::start);
            ToxiproxyClient toxiproxy = new ToxiproxyClient(TOXIPROXY.getHost(), TOXIPROXY.getMappedPort(8474));
            pgProxy = proxy(toxiproxy, "postgres", 5432);
            redisProxy = proxy(toxiproxy, "redis", 6379);
            kafkaProxy = proxy(toxiproxy, "kafka", 29092);
            createTopic();
            ORCHESTRATOR.start();
            LEDGER.start();
        } catch (RuntimeException e) {
            // a container that dies on startup is useless without its own output
            List.of(KAFKA, TOXIPROXY, ORCHESTRATOR, LEDGER).forEach(c -> System.err.println(
                    "---- " + c.getDockerImageName() + " ----\n" + c.getLogs()));
            throw e;
        }
    }

    private static Proxy proxy(ToxiproxyClient client, String name, int port) {
        try {
            return client.createProxy(name, "0.0.0.0:" + port, name + "-real:" + port);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static final class Broker extends GenericContainer<Broker> {
        Broker() {
            super("apache/kafka:3.9.0");
        }

        @Override
        protected void containerIsStarting(InspectContainerResponse info) {
            copyFileToContainer(Transferable.of("""
                    #!/bin/bash
                    export KAFKA_ADVERTISED_LISTENERS=INTERNAL://kafka:29092,EXTERNAL://localhost:%d
                    exec /etc/kafka/docker/run
                    """.formatted(getMappedPort(9092)), 0777), "/tmp/start.sh");
        }
    }

    private E2eEnvironment() {
    }

    /** Looked up per call: a restarted container comes back on a new host port. */
    static String orchestratorUrl() {
        int port = Integer.parseInt(ORCHESTRATOR.getDockerClient().inspectContainerCmd(ORCHESTRATOR.getContainerId())
                .exec().getNetworkSettings().getPorts().getBindings()
                .get(ExposedPort.tcp(8080))[0].getHostPortSpec());
        return "http://" + ORCHESTRATOR.getHost() + ":" + port;
    }

    static String kafkaBootstrap() {
        return "localhost:" + KAFKA.getMappedPort(9092);
    }

    static String wiremockUrl() {
        return "http://" + WIREMOCK.getHost() + ":" + WIREMOCK.getMappedPort(8080);
    }

    static String jdbcUrl(String database) {
        return "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/" + database;
    }

    private static GenericContainer<?> service(String libsProperty, int port) {
        File jar = bootJar(Path.of(System.getProperty(libsProperty)));
        ImageFromDockerfile image = new ImageFromDockerfile()
                .withFileFromFile("app.jar", jar)
                .withDockerfileFromBuilder(b -> b
                        .from("eclipse-temurin:27-jre")
                        .copy("app.jar", "/app.jar")
                        .entryPoint("java", "-jar", "/app.jar"));
        return new GenericContainer<>(image)
                .withNetwork(NET)
                .withExposedPorts(port)
                .withLogConsumer(new ContainerLog())
                .waitingFor(Wait.forHttp("/actuator/health").forStatusCode(200)
                        .withStartupTimeout(Duration.ofMinutes(2)));
    }

    // build/libs also holds the "-plain" jar; the executable one is the other
    private static File bootJar(Path libs) {
        File[] jars = libs.toFile().listFiles((dir, name) -> name.endsWith(".jar") && !name.endsWith("-plain.jar"));
        if (jars == null || jars.length != 1) {
            throw new IllegalStateException("Expected exactly one bootJar in " + libs + " (run via gradle)");
        }
        return jars[0];
    }

    private static void createTopic() {
        try (AdminClient admin = AdminClient.create(Map.of("bootstrap.servers", kafkaBootstrap()))) {
            admin.createTopics(List.of(new NewTopic(TOPIC, 3, (short) 1))).all().get();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot create topic " + TOPIC, e);
        }
    }
}

