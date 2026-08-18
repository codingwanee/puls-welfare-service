import org.gradle.jvm.tasks.Jar
import org.springframework.boot.gradle.tasks.bundling.BootJar

plugins {
    kotlin("jvm") version "1.9.25"
    kotlin("plugin.spring") version "1.9.25"
    kotlin("plugin.jpa") version "1.9.25"
    id("org.springframework.boot") version "3.5.13"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.hwgeneralins.dch"
val localVersion = providers.fileContents(layout.projectDirectory.file("VERSION"))
    .asText
    .map { "${it.trim()}-SNAPSHOT" }
version = providers.gradleProperty("buildVersion").orElse(localVersion).get()

val buildId = providers.gradleProperty("buildId").orElse("local")
val buildCommit = providers.gradleProperty("buildCommit").orElse("unknown")
val buildBranch = providers.gradleProperty("buildBranch").orElse("local")

java { toolchain { languageVersion = JavaLanguageVersion.of(17) } }
repositories { mavenCentral() }

configurations.configureEach {
    exclude(group = "org.springframework.boot", module = "spring-boot-starter-logging")
    exclude(group = "org.apache.logging.log4j", module = "log4j-to-slf4j")
    exclude(group = "ch.qos.logback", module = "logback-classic")
    exclude(group = "ch.qos.logback", module = "logback-core")
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-aop")
    implementation("org.springframework.boot:spring-boot-starter-log4j2")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.9")
    implementation("com.hazelcast:hazelcast:5.5.0")
    implementation("com.github.ulisesbocchio:jasypt-spring-boot-starter:4.0.4")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    runtimeOnly("com.mysql:mysql-connector-j")
    runtimeOnly("com.h2database:h2")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin { compilerOptions { freeCompilerArgs.addAll("-Xjsr305=strict") } }
tasks.withType<Test> { useJUnitPlatform() }

tasks.named<Jar>("jar") {
    enabled = false
}

tasks.named<BootJar>("bootJar") {
    manifest {
        attributes(
            "Implementation-Title" to rootProject.name,
            "Implementation-Version" to project.version.toString(),
            "Build-Id" to buildId.get(),
            "Build-Commit" to buildCommit.get(),
            "Build-Branch" to buildBranch.get(),
        )
    }
}
