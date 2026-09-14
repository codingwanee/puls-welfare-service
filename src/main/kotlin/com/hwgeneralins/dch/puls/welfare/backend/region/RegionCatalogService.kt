package com.hwgeneralins.dch.puls.welfare.backend.region

import com.fasterxml.jackson.databind.ObjectMapper
import com.hwgeneralins.dch.puls.welfare.backend.policy.domain.RegionCatalogDto
import com.hwgeneralins.dch.puls.welfare.backend.policy.domain.SidoDto
import com.hwgeneralins.dch.puls.welfare.backend.policy.domain.SigunguDto
import com.hwgeneralins.dch.puls.welfare.backend.region.reload.RegionCatalogRuntime
import com.hwgeneralins.dch.puls.welfare.backend.region.reload.RegionReloadOptions
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.Resource
import org.springframework.stereotype.Service
import java.util.Collections

@Service
class RegionCatalogService(
    objectMapper: ObjectMapper,
    @Value("\${region.catalog.sido-sigungu-resource:classpath:regions/korea_sido_sigungu_20260630.json}") resource: Resource,
    options: RegionReloadOptions = RegionReloadOptions(),
) {
    val runtime = RegionCatalogRuntime(objectMapper, resource, null, options) { root, _ ->
        RegionCatalogData(RegionCatalogDto(Collections.unmodifiableList(root["sido"].map { sido ->
            SidoDto(sidoName = sido["sidoName"].asText(),
                sigungu = Collections.unmodifiableList(sido["sigungu"].map { SigunguDto(it["sigunguName"].asText()) }))
        })))
    }
    fun snapshot(): RegionCatalogData = runtime.snapshot().value
    fun catalog(): RegionCatalogDto = snapshot().catalog
    fun contains(sido: String, sigungu: String?): Boolean = snapshot().contains(sido, sigungu)
}

class RegionCatalogData(val catalog: RegionCatalogDto) {
    private val names = catalog.sido.associate { sido -> sido.sidoName to sido.sigungu.map { it.sigunguName }.toSet() }
    fun contains(sido: String, sigungu: String?): Boolean =
        names[sido]?.let { sigungu == null || sigungu in it } ?: false
}
