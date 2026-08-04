package com.hwgeneralins.dch.puls.welfare.backend.region

import com.fasterxml.jackson.databind.ObjectMapper
import com.hwgeneralins.dch.puls.welfare.backend.policy.domain.RegionCatalogDto
import com.hwgeneralins.dch.puls.welfare.backend.policy.domain.SidoDto
import com.hwgeneralins.dch.puls.welfare.backend.policy.domain.SigunguDto
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.Resource
import org.springframework.stereotype.Service

@Service
class RegionCatalogService(
    objectMapper: ObjectMapper,
    @Value("classpath:regions/korea_sido_sigungu_20260630.json") resource: Resource,
) {
    private val catalog: RegionCatalogDto = resource.inputStream.use { input ->
        val root = objectMapper.readTree(input)
        RegionCatalogDto(root["sido"].map { sido ->
            SidoDto(
                sidoName = sido["sidoName"].asText(),
                sigungu = sido["sigungu"].map { SigunguDto(it["sigunguName"].asText()) },
            )
        })
    }

    fun catalog(): RegionCatalogDto = catalog

    fun contains(sido: String, sigungu: String?): Boolean = catalog.sido.any {
        it.sidoName == sido && (sigungu == null || it.sigungu.any { item -> item.sigunguName == sigungu })
    }
}
