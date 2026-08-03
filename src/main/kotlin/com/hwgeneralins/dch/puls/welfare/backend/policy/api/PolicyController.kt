package com.hwgeneralins.dch.puls.welfare.backend.policy.api

import com.hwgeneralins.dch.puls.welfare.backend.policy.domain.CursorPageDto
import com.hwgeneralins.dch.puls.welfare.backend.policy.domain.PolicyDetailDto
import com.hwgeneralins.dch.puls.welfare.backend.policy.domain.PolicyListItemDto
import com.hwgeneralins.dch.puls.welfare.backend.policy.domain.RegionCatalogDto
import com.hwgeneralins.dch.puls.welfare.backend.policy.service.PolicyQueryService
import com.hwgeneralins.dch.puls.welfare.backend.region.RegionCatalogService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/v1")
class PolicyController(
    private val policyQueryService: PolicyQueryService,
    private val regionCatalogService: RegionCatalogService,
) {
    @GetMapping("/policies")
    fun list(
        @RequestParam(required = false) sido: String?,
        @RequestParam(required = false) sigungu: String?,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(defaultValue = "20") limit: Int,
    ): CursorPageDto<PolicyListItemDto> = policyQueryService.list(sido, sigungu, cursor, limit)

    @GetMapping("/policies/{id:\\d+}")
    fun detail(@PathVariable id: Long): PolicyDetailDto = policyQueryService.detail(id)

    @GetMapping("/regions")
    fun regions(): RegionCatalogDto = regionCatalogService.catalog()
}
