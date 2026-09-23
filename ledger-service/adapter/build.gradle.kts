plugins {
    `java-library`
}

dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.0"))

    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework:spring-aop:7.0.8")
    implementation ("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.kafka:spring-kafka:4.1.0")
    api(project(":ledger-service:application"))
    compileOnly("org.projectlombok:lombok:1.18.46")
    runtimeOnly("org.postgresql:postgresql")
    annotationProcessor("org.projectlombok:lombok:1.18.46")
    testCompileOnly("org.projectlombok:lombok")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation(platform("org.testcontainers:testcontainers-bom:2.0.5"))
    testImplementation("org.testcontainers:testcontainers")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.testcontainers:testcontainers-kafka")
    testRuntimeOnly("com.h2database:h2")
    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

tasks.register("forbidFloatingPointMoney") {
    doLast {
        val offenders = mutableListOf<String>()
        listOf("src/main/java", "src/main/resources/db").forEach { root ->
            fileTree(root) { include("**/*.java", "**/*.sql") }.forEach { file ->
                file.readLines().forEachIndexed { index, line ->
                    if (line.contains(Regex("\\b(float|double)\\b")) && !line.contains("double-entry")) {
                        offenders += "${file.path}:${index + 1}: ${line.trim()}"
                    }
                }
            }
        }
        if (offenders.isNotEmpty()) {
            throw GradleException(
                "Floating-point types are forbidden in the money path:\n" + offenders.joinToString("\n"))
        }
    }
}

tasks.check {
    dependsOn("forbidFloatingPointMoney")
}