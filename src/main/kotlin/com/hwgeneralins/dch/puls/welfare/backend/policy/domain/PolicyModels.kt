package com.hwgeneralins.dch.puls.welfare.backend.policy.domain

import com.fasterxml.jackson.annotation.JsonInclude
import io.swagger.v3.oas.annotations.media.Schema
import java.io.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

data class RegionRequest(val sido: String?, val sigungu: String?)

data class PolicyRecord(
    val id: Long,
    val serviceId: String,
    val serviceName: String,
    val servicePurposeSummary: String?,
    val supportTarget: String?,
    val selectionCriteria: String?,
    val supportContent: String?,
    val supportBenefitSummary: String?,
    val supportType: String?,
    val applicationMethod: String?,
    val applicationDeadline: String?,
    val applicationDeadlineSortGroup: Int?,
    val applicationDeadlineSortDate: LocalDate?,
    val documents: String?,
    val receivingAgencyName: String?,
    val contact: String?,
    val onlineApplicationUrl: String?,
    val updatedDate: LocalDate?,
    val competentAgency: String?,
    val administrativeRules: String?,
    val localOrdinances: String?,
    val statutes: String?,
    val sido: String?,
    val sigungu: String?,
    val policyDataType: String,
    val policyStatus: String,
    val exposureStartAt: LocalDateTime?,
    val exposureEndAt: LocalDateTime?,
    val siteUrl: String?,
    val inputAt: LocalDateTime?,
    val modifiedAt: LocalDateTime?,
) : Serializable {
    fun isVisibleAt(now: LocalDateTime): Boolean =
        policyStatus == "PUBLISHED" &&
            (exposureStartAt == null || !exposureStartAt.isAfter(now)) &&
            (exposureEndAt == null || exposureEndAt.isAfter(now))
}

@JsonInclude(JsonInclude.Include.ALWAYS)
@Schema(description = "정책 목록 항목")
data class PolicyListItemDto(
    @field:Schema(description = "정책 ID", example = "94")
    val id: Long,
    @field:Schema(description = "서비스명", example = "부모성장을 위한 심리지원서비스")
    val serviceName: String,
    @field:Schema(description = "서비스 목적 요약", example = "임신부 또는 자녀를 둔 부모를 대상으로 심리지원서비스 제공")
    val servicePurposeSummary: String?,
    @field:Schema(description = "소관기관명", example = "서울특별시 동작구")
    val competentAgency: String?,
    @field:Schema(description = "시도명", example = "서울특별시")
    val sido: String?,
    @field:Schema(description = "시군구명", example = "동작구")
    val sigungu: String?,
) : Serializable

@JsonInclude(JsonInclude.Include.ALWAYS)
@Schema(description = "정책 상세")
data class PolicyDetailDto(
    @field:Schema(description = "정책 ID", example = "94")
    val id: Long,
    @field:Schema(description = "서비스명", example = "부모성장을 위한 심리지원서비스")
    val serviceName: String,
    @field:Schema(description = "서비스 목적 요약", example = "임신부 또는 자녀를 둔 부모를 대상으로 심리지원서비스 제공")
    val servicePurposeSummary: String?,
    @field:Schema(description = "지원 대상", example = "기준 중위소득 140% 이하 가정의 임신부 또는 부모")
    val supportTarget: String?,
    @field:Schema(description = "선정 기준", example = "서비스 욕구와 소득 기준을 종합하여 선정")
    val selectionCriteria: String?,
    @field:Schema(description = "지원 내용", example = "전문상담 서비스 및 사전·사후 평가 제공")
    val supportContent: String?,
    @field:Schema(description = "지원 내용 요약(혜택)", example = "기타(상담)")
    val supportBenefitSummary: String?,
    @field:Schema(description = "지원 유형", example = "기타(상담)")
    val supportType: String?,
    @field:Schema(description = "신청 방법", example = "관할 동주민센터 방문 신청")
    val applicationMethod: String?,
    @field:Schema(description = "신청 기한", example = "접수기관 별 상이")
    val applicationDeadline: String?,
    @field:Schema(description = "구비서류", example = "신분증, 신청서 및 대상자 증빙서류")
    val documents: String?,
    @field:Schema(description = "접수기관명", example = "관할 동주민센터")
    val receivingAgencyName: String?,
    @field:Schema(description = "문의처", example = "아동여성과/02-820-9259")
    val contact: String?,
    @field:Schema(description = "온라인 신청 사이트 URL", example = "https://www.gov.kr/portal/rcvfvrSvc/main")
    val onlineApplicationUrl: String?,
    @field:Schema(description = "정책 수정일", example = "2026-04-29", type = "string", format = "date")
    val updatedDate: LocalDate?,
    @field:Schema(description = "소관기관명", example = "서울특별시 동작구")
    val competentAgency: String?,
    @field:Schema(description = "관련 행정규칙", example = "사회서비스 이용권 관련 지침")
    val administrativeRules: String?,
    @field:Schema(description = "관련 자치법규", example = "서울특별시 동작구 사회서비스 조례")
    val localOrdinances: String?,
    @field:Schema(description = "관련 법령", example = "사회서비스 이용 및 이용권 관리에 관한 법률")
    val statutes: String?,
) : Serializable

@Schema(description = "Cursor 기반 페이지 응답")
data class CursorPageDto<T>(
    @field:Schema(description = "현재 페이지의 정책 목록")
    val items: List<T>,
    @field:Schema(description = "다음 페이지 조회에 사용할 cursor. 다음 페이지가 없으면 null")
    val nextCursor: String?,
    @field:Schema(description = "다음 페이지 존재 여부", example = "true")
    val hasNext: Boolean,
)

@Schema(description = "시도·시군구 카탈로그")
data class RegionCatalogDto(
    @field:Schema(description = "시도 목록")
    val sido: List<SidoDto>,
)

@Schema(description = "시도와 소속 시군구")
data class SidoDto(
    @field:Schema(description = "시도명", example = "서울특별시")
    val sidoName: String,
    @field:Schema(description = "시군구 목록")
    val sigungu: List<SigunguDto>,
)

@Schema(description = "시군구")
data class SigunguDto(
    @field:Schema(description = "시군구명", example = "마포구")
    val sigunguName: String,
)

data class CacheEnvelope<T : Serializable>(
    val value: T,
    val validUntil: Instant,
) : Serializable
