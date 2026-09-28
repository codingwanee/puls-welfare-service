package com.hwgeneralins.dch.puls.welfare.backend.region.reload

import com.hwgeneralins.dch.puls.welfare.backend.region.RegionCatalogService
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.core.io.ClassPathResource
import org.springframework.core.io.FileSystemResource
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import java.nio.file.Files
import java.nio.file.Path

class RegionReloadControllerTest {
    @TempDir lateinit var dir: Path
    @Test fun `authenticated reload preserves public contract and invalid file preserves data`() {
        val mapper = jacksonObjectMapper().findAndRegisterModules()
        val path = dir.resolve("current.json")
        val original = ClassPathResource("regions/korea_sido_sigungu_20260630.json").inputStream.use { it.readBytes() }
        Files.write(path, original)
        val options = RegionReloadOptions(true, "test-token", "wsbd", "test-01")
        val service = RegionCatalogService(mapper, FileSystemResource(path), options = options)
        val mvc = MockMvcBuilders.standaloneSetup(RegionReloadController(service))
            .setMessageConverters(MappingJackson2HttpMessageConverter(mapper))
            .addFilters<org.springframework.test.web.servlet.setup.StandaloneMockMvcBuilder>(RegionReloadAuthFilter(options, mapper)).build()
        mvc.perform(post("/internal/regions/reload")).andExpect(status().isUnauthorized)
            .andExpect(jsonPath("code").value("REGION_RELOAD_UNAUTHORIZED"))
        mvc.perform(get("/internal;ignored/regions/status")).andExpect(status().isUnauthorized)
        mvc.perform(get("/internal/regions/status").header("Authorization", "Bearer wrong")).andExpect(status().isUnauthorized)
        mvc.perform(post("/internal/regions/reload").header("Authorization", "Bearer test-token"))
            .andExpect(status().isOk).andExpect(jsonPath("result").value("UNCHANGED"))
            .andExpect(header().string("Cache-Control", "no-store"))
        Files.writeString(path, original.toString(Charsets.UTF_8).replace("종로구", "종로신구"))
        mvc.perform(post("/internal/regions/reload").header("Authorization", "Bearer test-token"))
            .andExpect(status().isOk).andExpect(jsonPath("result").value("RELOADED"))
        kotlin.test.assertTrue(service.contains("서울특별시", "종로신구"))
        kotlin.test.assertFalse(service.contains("서울특별시", "종로구"))
        Files.writeString(path, "{")
        mvc.perform(post("/internal/regions/reload").header("Authorization", "Bearer test-token"))
            .andExpect(status().isUnprocessableEntity).andExpect(jsonPath("code").value("REGION_CATALOG_INVALID"))
        mvc.perform(get("/internal/regions/status").header("Authorization", "Bearer test-token"))
            .andExpect(status().isOk).andExpect(jsonPath("instanceId").value("test-01"))
            .andExpect(jsonPath("lastReloadResult").value("REGION_CATALOG_INVALID"))
        kotlin.test.assertTrue(service.contains("서울특별시", "종로신구"))
        kotlin.test.assertFalse(service.contains("서울특별시", "종로구"))
    }
}
