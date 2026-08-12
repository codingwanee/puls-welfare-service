package com.hwgeneralins.dch.puls.welfare.backend.policy

import com.fasterxml.jackson.databind.ObjectMapper
import com.hwgeneralins.dch.puls.welfare.backend.policyRecord
import com.hwgeneralins.dch.puls.welfare.backend.policy.service.PolicyDtoMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PolicyDtoMapperTest {
    private val mapper = PolicyDtoMapper()

    @Test
    fun `maps only the selected list and detail response fields`() {
        val detail = mapper.toDetail(policyRecord(id = 42))
        assertThat(detail.id).isEqualTo(42)
        assertThat(detail.serviceName).isEqualTo("정책 42")
        assertThat(detail.supportBenefitSummary).isEqualTo("30만원")
        assertThat(detail.onlineApplicationUrl).isEqualTo("https://apply.example/42")
        assertThat(detail.siteUrl).isEqualTo("https://site.example/42")
        assertThat(detail.competentAgency).isEqualTo("소관 기관")
        assertThat(mapper.toListItem(policyRecord()).competentAgency).isEqualTo("소관 기관")
        assertThat(ObjectMapper().findAndRegisterModules().writeValueAsString(detail))
            .contains("servicePurposeSummary", "receivingAgencyName", "administrativeRules")
            .doesNotContain(
                "isRead", "category", "tabs", "title", "oneLineDescription", "supportBenefit\"",
                "serviceId", "dataType", "regionLabel", "landingUrl", "shareUrl",
            )
    }

    @Test
    fun `serializes null values for selected source fields`() {
        val source = policyRecord().copy(
            supportContent = " ", supportTarget = null, selectionCriteria = "", applicationMethod = null,
            applicationDeadline = null, documents = null, receivingAgencyName = null, contact = null,
            onlineApplicationUrl = null, competentAgency = null, administrativeRules = null,
            localOrdinances = null, statutes = null, updatedDate = null, siteUrl = null,
        )
        val detail = mapper.toDetail(source)
        val json = ObjectMapper().findAndRegisterModules().writeValueAsString(detail)
        assertThat(json)
            .contains(
                "\"supportTarget\":null",
                "\"selectionCriteria\":null",
                "\"receivingAgencyName\":null",
                "\"onlineApplicationUrl\":null",
                "\"siteUrl\":null",
            )
            .doesNotContain("landingUrl", "shareUrl")
    }
}
