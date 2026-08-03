package com.hwgeneralins.dch.puls.welfare.backend.region

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.core.io.ClassPathResource

class RegionCatalogServiceTest {
    private val service = RegionCatalogService(ObjectMapper(), ClassPathResource("regions/korea_sido_sigungu_20260630.json"))

    @Test
    fun `serves complete legal region catalog rather than policy distinct values`() {
        val catalog = service.catalog()
        assertThat(catalog.sido).hasSizeGreaterThanOrEqualTo(16)
        assertThat(catalog.sido.sumOf { it.sigungu.size }).isGreaterThanOrEqualTo(229)
        assertThat(service.contains("서울특별시", "마포구")).isTrue()
        assertThat(service.contains("서울특별시", "없는구")).isFalse()
    }
}
