import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
    java
    jacoco
    id("org.springframework.boot") version "4.2.0"
    id("com.diffplug.spotless") version "8.12.0"
}

group = "vn.signa"
version = "0.1.0"

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

repositories { mavenCentral() }

val springdocVersion = "3.2.1"
val minioVersion = "9.0.4"
val archunitVersion = "1.5.1"

dependencies {
    implementation(platform(SpringBootPlugin.BOM_COORDINATES))
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.flywaydb:flyway-mysql")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:$springdocVersion")
    implementation("io.minio:minio:$minioVersion")
    runtimeOnly("com.mysql:mysql-connector-j")

    testImplementation(platform(SpringBootPlugin.BOM_COORDINATES))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-mysql")
    testImplementation("org.testcontainers:testcontainers-minio")
    testImplementation("com.tngtech.archunit:archunit-junit5:$archunitVersion")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    implementation("org.springframework.boot:spring-boot-starter-security") // lát 1
    testImplementation("org.springframework.boot:spring-boot-starter-security-test") // lát 1
    implementation("com.twelvemonkeys.imageio:imageio-webp:3.15.3") // lát 3
    testImplementation("org.wiremock:wiremock-standalone:3.13.2") // lát 3
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-parameters")
}

tasks.test {
    useJUnitPlatform()
    exclude("**/*IT.class")
}

val integrationTest by tasks.registering(Test::class) {
    description = "Chạy test tích hợp (*IT) với Testcontainers. Cần Docker."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform()
    include("**/*IT.class")
    shouldRunAfter(tasks.test)
}

tasks.check { dependsOn(integrationTest) }

tasks.jacocoTestReport {
    executionData.setFrom(fileTree(layout.buildDirectory).include("jacoco/*.exec"))
    dependsOn(tasks.test)
    // Gộp cả jacoco/integrationTest.exec khi integrationTest có trong cùng lần chạy.
    mustRunAfter(integrationTest)
    reports { xml.required = true }
}

spotless {
    java {
        googleJavaFormat()
        target("src/**/*.java")
    }
}
