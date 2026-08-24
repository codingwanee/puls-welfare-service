package com.hwgeneralins.dch.puls.welfare.backend.policy.api

import com.hwgeneralins.dch.puls.welfare.backend.policy.domain.CursorPageDto
import com.hwgeneralins.dch.puls.welfare.backend.policy.domain.PolicyDetailDto
import com.hwgeneralins.dch.puls.welfare.backend.policy.domain.PolicyListItemDto
import com.hwgeneralins.dch.puls.welfare.backend.policy.domain.RecentPolicyUpdateCountDto
import com.hwgeneralins.dch.puls.welfare.backend.policy.domain.RegionCatalogDto
import com.hwgeneralins.dch.puls.welfare.backend.policy.service.PolicyQueryService
import com.hwgeneralins.dch.puls.welfare.backend.region.RegionCatalogService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/v1")
@Tag(name = "정책", description = "고객 앱용 게시 정책 조회 API")
class PolicyController(
    private val policyQueryService: PolicyQueryService,
    private val regionCatalogService: RegionCatalogService,
) {
    @Operation(summary = "정책 목록 조회", description = "지역 조건과 cursor를 사용하여 게시 정책을 조회합니다.")
    @GetMapping("/welfare-policies")
    fun list(
        @Parameter(
            description = "시도명. 시도와 시군구를 모두 생략하면 전체 정책을 조회합니다.",
            example = "서울특별시",
        )
        @RequestParam(required = false) sido: String?,
        @Parameter(
            description = "시군구명. 시도만 입력하고 생략하면 선택한 시도 전체 정책을 조회합니다.",
            example = "마포구",
        )
        @RequestParam(required = false) sigungu: String?,
        @Parameter(
            description = "이전 응답의 nextCursor. 첫 페이지는 입력하지 않습니다.",
        )
        @RequestParam(required = false) cursor: String?,
        @Parameter(
            description = "페이지당 조회 건수",
            example = "20",
            schema = Schema(defaultValue = "20", minimum = "1", maximum = "100"),
        )
        @RequestParam(defaultValue = "20") limit: Int,
    ): CursorPageDto<PolicyListItemDto> = policyQueryService.list(sido, sigungu, cursor, limit)

    @Operation(
        summary = "최근 정책 업데이트 건수 조회",
        description = "지역 조건에 해당하며 요청한 최근 일수 동안 수정된 게시 정책 수를 조회합니다.",
    )
    @GetMapping("/welfare-policies/recent-updates/count")
    fun recentUpdateCount(
        @Parameter(description = "시도명. 시도와 시군구를 모두 생략하면 전체 정책을 조회합니다.", example = "서울특별시")
        @RequestParam(required = false) sido: String?,
        @Parameter(description = "시군구명. 시도 없이 입력할 수 없습니다.", example = "마포구")
        @RequestParam(required = false) sigungu: String?,
        @Parameter(
            description = "최근 업데이트 조회 일수. 1 이상의 정수를 입력합니다.",
            example = "7",
            schema = Schema(defaultValue = "7", minimum = "1"),
        )
        @RequestParam(defaultValue = "7") updateDays: Int,
    ): RecentPolicyUpdateCountDto = policyQueryService.recentUpdateCount(sido, sigungu, updateDays)

    @Operation(summary = "정책 상세 조회", description = "게시 중이며 현재 노출 가능한 정책의 상세 정보를 조회합니다.")
    @GetMapping("/welfare-policies/{id:\\d+}")
    fun detail(
        @Parameter(description = "정책 ID", example = "94", required = true)
        @PathVariable id: Long,
    ): PolicyDetailDto = policyQueryService.detail(id)

    @Operation(summary = "지역 목록 조회", description = "정책 검색에 사용할 전체 시도·시군구 목록을 조회합니다.")
    @GetMapping("/regions")
    fun regions(): RegionCatalogDto = regionCatalogService.catalog()
}
