package com.hwgeneralins.dch.puls.welfare.backend.region.reload

import com.hwgeneralins.dch.puls.welfare.backend.region.RegionCatalogService
import com.hwgeneralins.dch.puls.welfare.backend.policy.api.PolicyController
import com.hwgeneralins.dch.puls.welfare.backend.policy.service.PolicyQueryService
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.core.io.ClassPathResource
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/** Real HTTP verifies servlet filter registration, context path and the existing public region route. */
class RegionReloadHttpTest {
    @TempDir lateinit var dir: Path

    @Test fun `application yaml path changes are visible through existing HTTP API without restarting`() {
        val original = ClassPathResource("regions/korea_sido_sigungu_20260630.json").inputStream.use { it.readBytes() }
        val path = dir.resolve("current.json")
        Files.write(path, original)
        val release = dir.resolve("release.json")
        Files.writeString(release, original.toString(Charsets.UTF_8).replace("종로구", "종로신구"))
        val config = dir.resolve("application.yml")
        fun configure(file: Path) = Files.writeString(config, "region:\n  catalog:\n    sido-sigungu-resource: '${file.toUri()}'\n")
        configure(path)
        SpringApplicationBuilder(Config::class.java).run(
            "--server.port=0", "--server.address=127.0.0.1", "--server.servlet.context-path=/api/wsbd",
            "--region.catalog.reload.enabled=true", "--region.catalog.reload.token=test-token",
            "--spring.config.location=${config.toUri()}", "--region.catalog.next-sido-sigungu-resource=",
            "--spring.jmx.enabled=false",
        ).use { context ->
            val port = (context as ServletWebServerApplicationContext).webServer.port
            val client = HttpClient.newHttpClient()
            fun request(method: String, route: String, auth: Boolean = true): HttpResponse<String> {
                val builder = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/api/wsbd$route"))
                    .timeout(java.time.Duration.ofSeconds(10)).method(method, HttpRequest.BodyPublishers.noBody())
                if (auth) builder.header("Authorization", "Bearer test-token")
                return client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
            }
            val before = request("GET", "/v1/regions", false)
            assertEquals(200, before.statusCode())
            assertTrue(before.body().contains("종로구"))
            assertEquals(401, request("POST", "/internal/regions/reload", false).statusCode())
            assertEquals(401, request("GET", "/internal;ignored/regions/status", false).statusCode())
            configure(release)
            assertEquals(200, request("POST", "/internal/regions/reload").statusCode())
            val after = request("GET", "/v1/regions", false)
            assertEquals(200, after.statusCode())
            assertTrue(after.body().contains("종로신구"))
            Files.writeString(config, "region: [invalid")
            assertEquals(422, request("POST", "/internal/regions/reload").statusCode())
            assertEquals(after.body(), request("GET", "/v1/regions", false).body())
            configure(dir.resolve("missing.json"))
            assertEquals(503, request("POST", "/internal/regions/reload").statusCode())
            assertEquals(after.body(), request("GET", "/v1/regions", false).body())
            configure(path)
            assertEquals(200, request("POST", "/internal/regions/reload").statusCode())
            assertEquals(before.body(), request("GET", "/v1/regions", false).body())
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = [DataSourceAutoConfiguration::class, HibernateJpaAutoConfiguration::class])
    @Import(RegionCatalogService::class, RegionReloadOptions::class, RegionReloadController::class, RegionReloadAuthFilter::class, PolicyController::class)
    class Config {
        @Bean fun mapper(): ObjectMapper = jacksonObjectMapper().findAndRegisterModules()
        @Bean fun policyQueryService(): PolicyQueryService = org.mockito.Mockito.mock(PolicyQueryService::class.java)
    }
}
