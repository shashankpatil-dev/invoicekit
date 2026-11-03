plugins {
  java
  jacoco
  id("org.springframework.boot") version "3.5.6"
  id("io.spring.dependency-management") version "1.1.7"
  id("com.diffplug.spotless") version "7.2.1"
}

group = "com.shashank"
version = "0.1.0"

java {
  toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

repositories {
  mavenCentral()
}

// Spring Boot's managed version is too old for Docker Engine 29.
extra["testcontainers.version"] = "1.21.4"

dependencies {
  implementation("org.springframework.boot:spring-boot-starter-web")
  implementation("org.springframework.boot:spring-boot-starter-jdbc")
  implementation("org.springframework.boot:spring-boot-starter-validation")
  implementation("org.springframework.boot:spring-boot-starter-actuator")
  implementation("org.springframework.boot:spring-boot-starter-data-redis")
  implementation("org.springframework.kafka:spring-kafka")
  implementation("org.flywaydb:flyway-core")
  implementation("org.flywaydb:flyway-database-postgresql")
  implementation("com.google.cloud:google-cloud-storage:2.53.3")
  implementation("io.micrometer:micrometer-registry-prometheus")
  runtimeOnly("org.postgresql:postgresql")

  testImplementation("org.springframework.boot:spring-boot-starter-test")
  testImplementation("org.springframework.boot:spring-boot-testcontainers")
  testImplementation("org.springframework.kafka:spring-kafka-test")
  testImplementation("org.testcontainers:junit-jupiter")
  testImplementation("org.testcontainers:postgresql")
  testImplementation("org.testcontainers:kafka")
  testImplementation("com.tngtech.archunit:archunit-junit5:1.4.1")
  testImplementation("org.apache.pdfbox:pdfbox:3.0.5")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

springBoot {
  mainClass = "com.shashank.invoicekit.Application"
}

spotless {
  java {
    googleJavaFormat()
  }
}

tasks.test {
  useJUnitPlatform()
  finalizedBy(tasks.jacocoTestReport)
}

// Modules held to the coverage limits below; new modules join as they are built.
val coveredClasses =
  listOf("documentengine", "numbering").map { "com/shashank/invoicekit/$it/**" }

tasks.jacocoTestReport {
  dependsOn(tasks.test)
  classDirectories.setFrom(sourceSets.main.get().output.asFileTree.matching { include(coveredClasses) })
}

tasks.jacocoTestCoverageVerification {
  classDirectories.setFrom(sourceSets.main.get().output.asFileTree.matching { include(coveredClasses) })
  violationRules {
    rule {
      limit {
        counter = "INSTRUCTION"
        minimum = "0.90".toBigDecimal()
      }
    }
    rule {
      limit {
        counter = "BRANCH"
        minimum = "0.90".toBigDecimal()
      }
    }
  }
}

tasks.check {
  dependsOn(tasks.jacocoTestCoverageVerification)
}

// ---- TEMPORARY numbering load test harness (loadtest/numbering/run.sh) ----
// To remove it, delete this block, src/loadtest and loadtest/numbering.
// Its classes go only into build/libs/invoicekit-loadtest.jar, never into the normal bootJar.
val loadtest: SourceSet by sourceSets.creating {
  compileClasspath += sourceSets.main.get().output
  runtimeClasspath += sourceSets.main.get().output
}

configurations[loadtest.implementationConfigurationName].extendsFrom(configurations.implementation.get())
configurations[loadtest.runtimeOnlyConfigurationName].extendsFrom(configurations.runtimeOnly.get())

// Reuses the temporary test series from the tests instead of copying it.
tasks.named<ProcessResources>(loadtest.processResourcesTaskName) {
  from("src/test/resources") { include("db/testseries/**") }
}

tasks.register<org.springframework.boot.gradle.tasks.bundling.BootJar>("loadtestBootJar") {
  archiveFileName = "invoicekit-loadtest.jar"
  mainClass = "com.shashank.invoicekit.Application"
  targetJavaVersion = java.targetCompatibility
  classpath(loadtest.runtimeClasspath)
}
// ---- END TEMPORARY numbering load test harness ----
