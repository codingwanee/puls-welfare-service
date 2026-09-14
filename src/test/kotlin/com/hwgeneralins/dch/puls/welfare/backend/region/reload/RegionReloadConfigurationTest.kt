package com.hwgeneralins.dch.puls.welfare.backend.region.reload

import com.hwgeneralins.dch.puls.welfare.backend.region.RegionCatalogService
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Import
import org.springframework.core.io.ClassPathResource
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class RegionReloadConfigurationTest {
    @TempDir lateinit var dir: Path
    private val runner = WebApplicationContextRunner().withUserConfiguration(Config::class.java)

    @Test fun `disabled by default with no operations endpoints`() {
        runner.run { context ->
            assertNull(context.startupFailure)
            assertTrue(context.getBeansOfType(RegionReloadController::class.java).isEmpty())
            assertTrue(context.getBeansOfType(RegionReloadAuthFilter::class.java).isEmpty())
            assertNotNull(context.getBean(RegionCatalogService::class.java))
        }
    }

    @Test fun `enabled requires external file and token and wires operations beans`() {
        runner.withPropertyValues("region.catalog.reload.enabled=true").run { assertNotNull(it.startupFailure) }
        runner.withPropertyValues("region.catalog.reload.enabled=true", "region.catalog.reload.token=test").run { assertNotNull(it.startupFailure) }
        val path = dir.resolve("current.json")
        Files.write(path, ClassPathResource("regions/korea_sido_sigungu_20260630.json").inputStream.use { it.readBytes() })
        runner.withPropertyValues("region.catalog.reload.enabled=true", "region.catalog.reload.token=test",
            "region.catalog.sido-sigungu-resource=${path.toUri()}", "region.catalog.reload.instance-id=test-01").run { context ->
            assertNull(context.startupFailure)
            assertNotNull(context.getBean(RegionReloadController::class.java))
            assertNotNull(context.getBean(RegionReloadAuthFilter::class.java))
            assertEquals("test-01", context.getBean(RegionCatalogService::class.java).runtime.status().instanceId)
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @Import(RegionCatalogService::class, RegionReloadOptions::class, RegionReloadController::class, RegionReloadAuthFilter::class)
    class Config {
        @Bean fun mapper(): ObjectMapper = jacksonObjectMapper().findAndRegisterModules()
    }
}
