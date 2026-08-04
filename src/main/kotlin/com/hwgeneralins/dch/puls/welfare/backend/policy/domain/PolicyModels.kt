package com.hwgeneralins.dch.puls.welfare.backend.policy.domain

import com.fasterxml.jackson.annotation.JsonInclude
import java.io.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

data class RegionRequest(val sido: String, val sigungu: String?)

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
data class PolicyListItemDto(
    val id: Long,
    val serviceName: String,
    val servicePurposeSummary: String?,
    val competentAgency: String?,
) : Serializable

@JsonInclude(JsonInclude.Include.ALWAYS)
data class PolicyDetailDto(
    val id: Long,
    val serviceName: String,
    val servicePurposeSummary: String?,
    val supportTarget: String?,
    val selectionCriteria: String?,
    val supportContent: String?,
    val supportBenefitSummary: String?,
    val supportType: String?,
    val applicationMethod: String?,
    val applicationDeadline: String?,
    val documents: String?,
    val receivingAgencyName: String?,
    val contact: String?,
    val onlineApplicationUrl: String?,
    val updatedDate: LocalDate?,
    val competentAgency: String?,
    val administrativeRules: String?,
    val localOrdinances: String?,
    val statutes: String?,
) : Serializable

data class CursorPageDto<T>(
    val items: List<T>,
    val nextCursor: String?,
    val hasNext: Boolean,
)

data class RegionCatalogDto(val sido: List<SidoDto>)
data class SidoDto(val name: String, val sigungu: List<String>)

data class CacheEnvelope<T : Serializable>(
    val value: T,
    val validUntil: Instant,
) : Serializable
