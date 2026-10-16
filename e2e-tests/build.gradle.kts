plugins {
    java
}

dependencies {
    testImplementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.0"))
    testImplementation(platform("org.testcontainers:testcontainers-bom:2.0.5"))

    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core")
    testImplementation("org.awaitility:awaitility")
    testImplementation("org.testcontainers:testcontainers")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.testcontainers:testcontainers-kafka")
    testImplementation("eu.rekawek.toxiproxy:toxiproxy-java:2.1.7")
    testImplementation("org.apache.kafka:kafka-clients")
    testImplementation("tools.jackson.core:jackson-databind")
    testRuntimeOnly("org.postgresql:postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// The two services run as containers built from their real bootJars, so the test exercises
// exactly what ships. (Dockerfile-from-jar instead of bootBuildImage: Paketo has no JDK 27 yet
// and this keeps the build to seconds instead of pulling buildpack layers.)
tasks.test {
    useJUnitPlatform()
    dependsOn(":payment-orchestrator:bootstrap:bootJar", ":ledger-service:bootstrap:bootJar")
    systemProperty("orchestrator.libs", "$rootDir/payment-orchestrator/bootstrap/build/libs")
    systemProperty("ledger.libs", "$rootDir/ledger-service/bootstrap/build/libs")
    systemProperty("repo.root", "$rootDir")
    testLogging { events("passed", "failed", "skipped"); showStandardStreams = false }
}
