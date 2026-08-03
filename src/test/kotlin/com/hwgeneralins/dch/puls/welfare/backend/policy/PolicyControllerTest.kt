package com.hwgeneralins.dch.puls.welfare.backend.policy

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.hwgeneralins.dch.puls.welfare.backend.cache.PolicyCache
import com.hwgeneralins.dch.puls.welfare.backend.cache.PolicyCacheGeneration
import com.hwgeneralins.dch.puls.welfare.backend.config.PolicyCacheProperties
import com.hwgeneralins.dch.puls.welfare.backend.config.PolicyProperties
import com.hwgeneralins.dch.puls.welfare.backend.policy.api.GlobalExceptionHandler
import com.hwgeneralins.dch.puls.welfare.backend.policy.api.PolicyController
import com.hwgeneralins.dch.puls.welfare.backend.policy.domain.CacheEnvelope
import com.hwgeneralins.dch.puls.welfare.backend.policy.domain.PolicyDetailDto
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
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import org.springframework.core.io.ClassPathResource
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

class PolicyControllerTest {
    private val repository = MutablePolicyRepository()
    private val objectMapper = jacksonObjectMapper().findAndRegisterModules()
    private val properties = PolicyProperties("https://share/{policyVersionId}")
    private val catalog = RegionCatalogService(objectMapper, ClassPathResource("regions/korea_sido_sigungu_20260630.json"))
    private val service = PolicyQueryService(
        repository, PolicyDtoMapper(properties), PolicySortStrategy(properties), CursorCodec(objectMapper),
        EmptyCache(), com.hwgeneralins.dch.puls.welfare.backend.cache.CacheLoadCoordinator(),
        PolicyCacheGeneration(SimpleMeterRegistry()).apply { activate(1) },
        PolicyCacheProperties(enabled = false), properties, catalog,
        Clock.fixed(Instant.parse("2026-07-30T03:00:00Z"), ZoneId.of("Asia/Seoul")),
    )
    private val mvc = MockMvcBuilders.standaloneSetup(PolicyController(service, catalog))
        .setControllerAdvice(GlobalExceptionHandler()).build()

    @Test
    fun `home endpoint is removed and regular list supports home page size`() {
        repository.detail = policyRecord()

        mvc.get("/v1/policies/home")
            .andExpect { status { isNotFound() } }
        mvc.get("/v1/policies") { param("limit", "10") }
            .andExpect {
                status { isOk() }
                jsonPath("$.items.length()") { value(1) }
                jsonPath("$.items[0].id") { value(1) }
            }
    }

    @Test
    fun `detail exposes canonical fields including nulls and preferred button URL`() {
        repository.detail = policyRecord().copy(
            receivingAgencyName = null,
            onlineApplicationUrl = null,
        )

        mvc.get("/v1/policies/1")
            .andExpect {
                status { isOk() }
                jsonPath("$.id") { value(1) }
                jsonPath("$.serviceName") { value("정책 1") }
                jsonPath("$.supportBenefitSummary") { value("30만원") }
                jsonPath("$.receivingAgencyName") { value(null) }
                jsonPath("$.onlineApplicationUrl") { value(null) }
                jsonPath("$.siteUrl") { value("https://site.example/1") }
                jsonPath("$.landingUrl") { value("https://site.example/1") }
                jsonPath("$.tabs") { doesNotExist() }
                jsonPath("$.title") { doesNotExist() }
            }
    }

    @Test
    fun `true missing row is 404 with stable code`() {
        repository.detail = null
        mvc.get("/v1/policies/999")
            .andExpect { status { isNotFound() }; jsonPath("$.businessCode") { value("POLICY_NOT_FOUND") } }
    }

    @Test
    fun `existing invisible row is generic 410 without leaking internal status`() {
        repository.detail = policyRecord(status = "REVIEW")
        mvc.get("/v1/policies/1")
            .andExpect {
                status { isGone() }
                jsonPath("$.businessCode") { value("POLICY_NOT_AVAILABLE") }
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
            mvc.get("/v1/policies/1")
                .andExpect {
                    status { isGone() }
                    jsonPath("$.businessCode") { value("POLICY_NOT_AVAILABLE") }
                    content { string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(policy.policyStatus))) }
                }
        }
    }
}

private class MutablePolicyRepository : PolicyReadRepository {
    var detail: PolicyRecord? = null
    override fun findById(id: Long) = detail
    override fun findVisiblePage(
        region: RegionRequest,
        now: LocalDateTime,
        asOf: LocalDateTime,
        after: PolicySortKey?,
        limit: Int,
        internalFirst: Boolean,
    ) = listOfNotNull(detail).take(limit)
    override fun findRegionCatalog(): Map<String, List<String>> = emptyMap()
}

private class EmptyCache : PolicyCache {
    override fun getDetail(key: String): CacheEnvelope<PolicyDetailDto>? = null
    override fun putDetail(key: String, value: CacheEnvelope<PolicyDetailDto>) = Unit
}
