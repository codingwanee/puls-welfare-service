package com.hwgeneralins.dch.puls.welfare.backend.policy.service

import com.hwgeneralins.dch.puls.welfare.backend.config.PolicyProperties
import com.hwgeneralins.dch.puls.welfare.backend.policy.domain.PolicyDetailDto
import com.hwgeneralins.dch.puls.welfare.backend.policy.domain.PolicyListItemDto
import com.hwgeneralins.dch.puls.welfare.backend.policy.domain.PolicyRecord
import org.springframework.stereotype.Component

@Component
class PolicyDtoMapper(private val properties: PolicyProperties) {
    fun toListItem(policy: PolicyRecord) = PolicyListItemDto(
        id = policy.id,
        serviceName = policy.serviceName,
        servicePurposeSummary = policy.servicePurposeSummary.clean(),
//        supportBenefitSummary = policy.supportBenefitSummary.clean(),
//        applicationDeadline = policy.applicationDeadline.clean(),
        competentAgency = policy.competentAgency.clean(),
//        sido = policy.sido.clean(),
//        sigungu = policy.sigungu.clean(),
//        regionLabel = regionLabel(policy),
//        dataType = policy.policyDataType,
    )

    fun toDetail(policy: PolicyRecord): PolicyDetailDto = PolicyDetailDto(
        id = policy.id,
//        serviceId = policy.serviceId, // 서비스아이디
//        dataType = policy.policyDataType,
        serviceName = policy.serviceName,  // 서비스명
        servicePurposeSummary = policy.servicePurposeSummary.clean(), // 서비스 목적 요약
        supportTarget = policy.supportTarget.clean(), // 지원대상
        selectionCriteria = policy.selectionCriteria.clean(), // 선정기준
        supportContent = policy.supportContent.clean(), // 지원내용
        supportBenefitSummary = policy.supportBenefitSummary.clean(), // 지원내용 (혜택)
        supportType = policy.supportType.clean(), // 지원유형
        applicationMethod = policy.applicationMethod.clean(), // 신청방법
        applicationDeadline = policy.applicationDeadline.clean(), // 신청기한
        documents = policy.documents.clean(), // 구비서류
        receivingAgencyName = policy.receivingAgencyName.clean(), // 접수기관명
        contact = policy.contact.clean(), // 문의처
        onlineApplicationUrl = policy.onlineApplicationUrl.clean(), // 온라인신청사이트URL
//        siteUrl = policy.siteUrl.clean(), // 사이트URL
        updatedDate = policy.updatedDate, // 정책수정일시
        competentAgency = policy.competentAgency.clean(), // 소관기관
        administrativeRules = policy.administrativeRules.clean(), // 행정규칙
        localOrdinances = policy.localOrdinances.clean(), // 자치법규
        statutes = policy.statutes.clean(), // 법령
//        sido = policy.sido.clean(), // 시도
//        sigungu = policy.sigungu.clean(), // 시군구
//        regionLabel = regionLabel(policy),
//        landingUrl = policy.onlineApplicationUrl.clean() ?: policy.siteUrl.clean(),
//        shareUrl = properties.shareUrlTemplate.replace("{policyVersionId}", policy.id.toString()),
    )

    private fun regionLabel(policy: PolicyRecord): String? = when {
        policy.sido.isNullOrBlank() && policy.sigungu.isNullOrBlank() -> "전국"
        policy.sigungu.isNullOrBlank() -> policy.sido.clean()
        else -> listOfNotNull(policy.sido.clean(), policy.sigungu.clean()).joinToString(" ")
    }

    private fun String?.clean(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
}
