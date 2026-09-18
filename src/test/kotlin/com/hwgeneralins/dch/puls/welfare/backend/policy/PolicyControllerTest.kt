package com.hwgeneralins.dch.puls.welfare.backend.policy

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.hwgeneralins.dch.puls.welfare.backend.config.PolicyProperties
import com.hwgeneralins.dch.puls.welfare.backend.policy.api.GlobalExceptionHandler
import com.hwgeneralins.dch.puls.welfare.backend.policy.api.PolicyController
import com.hwgeneralins.dch.puls.welfare.backend.policy.domain.PolicyRecord
import com.hwgeneralins.dch.puls.welfare.backend.policy.domain.RegionRequest
import com.hwgeneralins.dch.puls.welfare.backend.policy.repository.PolicyReadRepository
import com.hwgeneralins.dch.puls.welfare.backend.policy.service.CursorCodec
import com.hwgeneralins.dch.puls.welfare.backend.policy.service.PolicyDtoMapper
import com.hwgeneralins.dch.puls.welfare.backend.policy.service.PolicyQueryService
import com.hwgeneralins.dch.puls.welfare.backend.policy.service.PolicySortStrategy
import com.hwgeneralins.dch.puls.welfare.backend.policy.service.PolicySortKey
import com.hwgeneralins.dch.puls.welfare.backend.policyRecord
import com.hwgeneralins.dch.puls.welfare.backend.region.RegionCatalogService
import com.hwgeneralins.dch.puls.welfare.backend.monitoring.ServingMonitoringMetrics
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.core.io.ClassPathResource
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

class PolicyControllerTest {
    private val repository = MutablePolicyRepository()
    private val objectMapper = jacksonObjectMapper().findAndRegisterModules()
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
    private val properties = PolicyProperties()
    private val catalog = RegionCatalogService(objectMapper, ClassPathResource("regions/korea_sido_sigungu_20260630.json"))
    private val clock = Clock.fixed(Instant.parse("2026-07-30T03:00:00Z"), ZoneId.of("Asia/Seoul"))
    private val metrics = ServingMonitoringMetrics(SimpleMeterRegistry())
    private val service = PolicyQueryService(
        repository, PolicyDtoMapper(), PolicySortStrategy(properties), CursorCodec(objectMapper),
        properties, catalog, clock, metrics,
    )
    private val mvc = MockMvcBuilders.standaloneSetup(PolicyController(service, catalog))
        .setControllerAdvice(GlobalExceptionHandler())
        .setMessageConverters(MappingJackson2HttpMessageConverter(objectMapper))
        .build()

    @Test
    fun `home endpoint is removed and regular list supports home page size`() {
        repository.detail = policyRecord()

        mvc.get("/v1/welfare-policies/home")
            .andExpect { status { isNotFound() } }
        mvc.get("/v1/welfare-policies") { param("limit", "10") }
            .andExpect {
                status { isOk() }
                jsonPath("$.items.length()") { value(1) }
                jsonPath("$.items[0].id") { value(1) }
                jsonPath("$.items[0].sido") { value("서울특별시") }
                jsonPath("$.items[0].sigungu") { value("마포구") }
            }
    }

    @Test
    fun `recent update count defaults to seven calendar days and exposes its range`() {
        repository.recentUpdateCount = 12

        mvc.get("/v1/welfare-policies/recent-updates/count") {
            param("sido", "서울특별시")
            param("sigungu", "마포구")
        }.andExpect {
            status { isOk() }
            jsonPath("$.count") { value(12) }
            jsonPath("$.updateDays") { value(7) }
            jsonPath("$.fromDate") { value("2026-07-24") }
            jsonPath("$.asOfDate") { value("2026-07-30") }
        }

        assertThat(repository.requestedRecentUpdateRegion).isEqualTo(RegionRequest("서울특별시", "마포구"))
        assertThat(repository.requestedUpdatedFrom).isEqualTo(LocalDateTime.of(2026, 7, 24, 0, 0))
        assertThat(repository.requestedUpdatedThrough).isEqualTo(LocalDateTime.of(2026, 7, 30, 12, 0))
    }

    @Test
    fun `recent update count supports arbitrary positive days and rejects non-positive days`() {
        mvc.get("/v1/welfare-policies/recent-updates/count") { param("updateDays", "14") }
            .andExpect {
                status { isOk() }
                jsonPath("$.updateDays") { value(14) }
                jsonPath("$.fromDate") { value("2026-07-17") }
            }

        mvc.get("/v1/welfare-policies/recent-updates/count") { param("updateDays", "0") }
            .andExpect {
                status { isBadRequest() }
                jsonPath("$.code") { value("INVALID_UPDATE_DAYS") }
                jsonPath("$.message") { value("최근 업데이트 조회 일수는 1 이상이어야 합니다.") }
            }
    }

    @Test
    fun `recent update count validates region and numeric update days`() {
        mvc.get("/v1/welfare-policies/recent-updates/count") { param("sigungu", "마포구") }
            .andExpect {
                status { isBadRequest() }
                jsonPath("$.code") { value("INVALID_REGION") }
            }

        mvc.get("/v1/welfare-policies/recent-updates/count") { param("updateDays", "seven") }
            .andExpect {
                status { isBadRequest() }
                jsonPath("$.code") { value("INVALID_REQUEST") }
                jsonPath("$.detail") { value("updateDays 값 'seven'의 형식이 올바르지 않습니다.") }
            }
    }

    @Test
    fun `detail exposes only selected canonical fields including nulls`() {
        repository.detail = policyRecord().copy(
            receivingAgencyName = null,
            onlineApplicationUrl = null,
            siteUrl = null,
        )

        mvc.get("/v1/welfare-policies/1")
            .andExpect {
                status { isOk() }
                jsonPath("$.id") { value(1) }
                jsonPath("$.serviceName") { value("정책 1") }
                jsonPath("$.supportBenefitSummary") { value("30만원") }
                jsonPath("$.receivingAgencyName") { value(null) }
                jsonPath("$.onlineApplicationUrl") { value(null) }
                jsonPath("$.siteUrl") { value(null) }
                jsonPath("$.serviceId") { doesNotExist() }
                jsonPath("$.dataType") { doesNotExist() }
                jsonPath("$.landingUrl") { doesNotExist() }
                jsonPath("$.shareUrl") { doesNotExist() }
                jsonPath("$.tabs") { doesNotExist() }
                jsonPath("$.title") { doesNotExist() }
            }
    }

    @Test
    fun `true missing row is 404 with stable code`() {
        repository.detail = null
        mvc.get("/v1/welfare-policies/999")
            .andExpect {
                status { isNotFound() }
                jsonPath("$.status") { value(404) }
                jsonPath("$.code") { value("POLICY_NOT_FOUND") }
                jsonPath("$.message") { value("정책을 찾을 수 없습니다.") }
                jsonPath("$.detail") { value(null) }
                jsonPath("$.path") { value("/v1/welfare-policies/999") }
                jsonPath("$.errors.length()") { value(0) }
                jsonPath("$.businessCode") { doesNotExist() }
            }
    }

    @Test
    fun `invalid parameter uses admin compatible error envelope`() {
        mvc.get("/v1/welfare-policies") { param("limit", "invalid") }
            .andExpect {
                status { isBadRequest() }
                jsonPath("$.timestamp") { exists() }
                jsonPath("$.status") { value(400) }
                jsonPath("$.code") { value("INVALID_REQUEST") }
                jsonPath("$.message") { value("요청값을 확인해 주세요.") }
                jsonPath("$.detail") { value("limit 값 'invalid'의 형식이 올바르지 않습니다.") }
                jsonPath("$.path") { value("/v1/welfare-policies") }
                jsonPath("$.errors.length()") { value(0) }
            }
    }

    @Test
    fun `existing invisible row is generic 410 without leaking internal status`() {
        repository.detail = policyRecord(status = "REVIEW")
        mvc.get("/v1/welfare-policies/1")
            .andExpect {
                status { isGone() }
                jsonPath("$.code") { value("POLICY_NOT_AVAILABLE") }
                content { string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("REVIEW"))) }
            }
    }

    @Test
    fun `all internal non-public states and exposure windows use the same generic 410 contract`() {
        val invisible = listOf(
            "PRIVATE", "EXPIRED", "REVIEW", "PENDING_APPROVAL", "REJECTED", "CANCLED", "DELETED",
        ).map { status -> policyRecord(status = status) } + listOf(
            policyRecord(start = LocalDateTime.of(2026, 8, 1, 0, 0)),
            policyRecord(end = LocalDateTime.of(2026, 7, 1, 0, 0)),
        )

        invisible.forEach { policy ->
            repository.detail = policy
            mvc.get("/v1/welfare-policies/1")
                .andExpect {
                    status { isGone() }
                    jsonPath("$.code") { value("POLICY_NOT_AVAILABLE") }
                    content { string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(policy.policyStatus))) }
                }
        }
    }
}

private class MutablePolicyRepository : PolicyReadRepository {
    var detail: PolicyRecord? = null
    var recentUpdateCount: Long = 0
    var requestedRecentUpdateRegion: RegionRequest? = null
    var requestedUpdatedFrom: LocalDateTime? = null
    var requestedUpdatedThrough: LocalDateTime? = null
    override fun findById(id: Long) = detail
    override fun findVisiblePage(
        region: RegionRequest,
        now: LocalDateTime,
        asOf: LocalDateTime,
        after: PolicySortKey?,
        limit: Int,
        internalFirst: Boolean,
    ) = listOfNotNull(detail).take(limit)
    override fun countVisibleRecentlyUpdated(
        region: RegionRequest,
        now: LocalDateTime,
        updatedFrom: LocalDateTime,
        updatedThrough: LocalDateTime,
    ): Long {
        requestedRecentUpdateRegion = region
        requestedUpdatedFrom = updatedFrom
        requestedUpdatedThrough = updatedThrough
        return recentUpdateCount
    }
    override fun findRegionCatalog(): Map<String, List<String>> = emptyMap()
}
