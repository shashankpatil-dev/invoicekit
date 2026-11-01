plugins {
  java
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
}
