plugins {
    java
    id("org.springframework.boot") version "4.0.7" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
}
allprojects {
    group = "com.github.hurdadd"
    version = "0.1.0"
}

subprojects {
    apply(plugin = "java")


    java {
        toolchain {
            languageVersion = JavaLanguageVersion.of(27)
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        // Spring resolves @PathVariable/@RequestParam/@RequestHeader names from bytecode
        // parameter names. Without -parameters these must be spelled out in every annotation.
        options.compilerArgs.add("-parameters")
    }

    repositories {
        mavenCentral()
    }
}