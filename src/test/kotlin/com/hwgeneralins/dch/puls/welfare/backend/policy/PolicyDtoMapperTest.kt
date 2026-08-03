package com.hwgeneralins.dch.puls.welfare.backend.policy

import com.fasterxml.jackson.databind.ObjectMapper
import com.hwgeneralins.dch.puls.welfare.backend.config.PolicyProperties
import com.hwgeneralins.dch.puls.welfare.backend.policyRecord
import com.hwgeneralins.dch.puls.welfare.backend.policy.service.PolicyDtoMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PolicyDtoMapperTest {
    private val mapper = PolicyDtoMapper(PolicyProperties("https://share.example/p/{policyVersionId}"))

    @Test
    fun `maps canonical admin field names and prefers online application URL for the button`() {
        val detail = mapper.toDetail(policyRecord(id = 42))
        assertThat(detail.id).isEqualTo(42)
        assertThat(detail.serviceName).isEqualTo("정책 42")
        assertThat(detail.supportBenefitSummary).isEqualTo("30만원")
        assertThat(detail.onlineApplicationUrl).isEqualTo("https://apply.example/42")
        assertThat(detail.siteUrl).isEqualTo("https://site.example/42")
        assertThat(detail.landingUrl).isEqualTo("https://apply.example/42")
        assertThat(detail.shareUrl).isEqualTo("https://share.example/p/42")
        assertThat(detail.competentAgency).isEqualTo("소관 기관")
        assertThat(mapper.toListItem(policyRecord()).competentAgency).isEqualTo("소관 기관")
        assertThat(ObjectMapper().findAndRegisterModules().writeValueAsString(detail))
            .contains("servicePurposeSummary", "receivingAgencyName", "administrativeRules")
            .doesNotContain("isRead", "category", "tabs", "title", "oneLineDescription", "supportBenefit\"")
    }

    @Test
    fun `serializes null source fields and falls back to site URL for the button`() {
        val source = policyRecord().copy(
            supportContent = " ", supportTarget = null, selectionCriteria = "", applicationMethod = null,
            applicationDeadline = null, documents = null, receivingAgencyName = null, contact = null,
            onlineApplicationUrl = null, competentAgency = null, administrativeRules = null,
            localOrdinances = null, statutes = null, updatedDate = null, siteUrl = null,
        )
        val detail = mapper.toDetail(source)
        val json = ObjectMapper().findAndRegisterModules().writeValueAsString(detail)
        assertThat(detail.landingUrl).isNull()
        assertThat(json)
            .contains(
                "\"supportTarget\":null",
                "\"selectionCriteria\":null",
                "\"receivingAgencyName\":null",
                "\"onlineApplicationUrl\":null",
                "\"siteUrl\":null",
            )
    }

    @Test
    fun `uses site URL when online application URL is absent`() {
        val detail = mapper.toDetail(policyRecord(id = 42).copy(onlineApplicationUrl = null))

        assertThat(detail.onlineApplicationUrl).isNull()
        assertThat(detail.landingUrl).isEqualTo("https://site.example/42")
    }
}
