package com.hwgeneralins.dch.puls.welfare.backend.cache

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.hwgeneralins.dch.puls.welfare.backend.config.PolicyCacheProperties
import com.hwgeneralins.dch.puls.welfare.backend.config.PolicyProperties
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
import com.hwgeneralins.dch.puls.welfare.backend.monitoring.ServingMonitoringMetrics
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.core.io.ClassPathResource
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.util.UUID

class HazelcastPolicyCacheTest {
    @Test
    fun `two embedded members share generation-qualified entries`() {
        val properties = PolicyCacheProperties(
            clusterName = "test-${UUID.randomUUID()}", members = listOf("127.0.0.1:5921", "127.0.0.1:5922"),
            port = 5921, portAutoIncrement = true, detailTtlSeconds = 60,
        )
        val node1 = HazelcastConfig().hazelcastInstance(properties)
        val node2 = HazelcastConfig().hazelcastInstance(properties)
        try {
            await().atMost(Duration.ofSeconds(15)).untilAsserted {
                assertThat(node1.cluster.members).hasSize(2)
            }
            val metrics1 = SimpleMeterRegistry()
            val metrics2 = SimpleMeterRegistry()
            val cache1 = HazelcastPolicyCache(node1, ServingMonitoringMetrics(metrics1, Clock.systemUTC()), Clock.systemUTC())
            val cache2 = HazelcastPolicyCache(node2, ServingMonitoringMetrics(metrics2, Clock.systemUTC()), Clock.systemUTC())
            val detail = PolicyDtoMapper().toDetail(policyRecord())
            cache1.putDetail("v4:policy:1", CacheEnvelope(detail, Instant.now().plusSeconds(60)))
            await().untilAsserted { assertThat(cache2.getDetail("v4:policy:1")?.value).isEqualTo(detail) }
            assertThat(metrics2.counter("policy.cache.requests", "map", PolicyCacheMaps.DETAIL_V4, "result", "hit").count())
                .isGreaterThanOrEqualTo(1.0)
            assertThat(cache1.getDetail("v4:e42:policy:1")).isNull()
        } finally {
            node2.shutdown()
            node1.shutdown()
        }
    }

    @Test
    fun `hazelcast outage is a cache miss and increments fallback metric`() {
        val properties = PolicyCacheProperties(
            clusterName = "test-${UUID.randomUUID()}", members = emptyList(), port = 5931, tcpIpEnabled = false,
        )
        val node = HazelcastConfig().hazelcastInstance(properties)
        val metrics = SimpleMeterRegistry()
        val monitoring = ServingMonitoringMetrics(metrics, Clock.systemUTC())
        val cache = HazelcastPolicyCache(node, monitoring, Clock.systemUTC())
        node.shutdown()
        assertThat(cache.getDetail("v4:policy:1")).isNull()
        assertThat(metrics.counter("policy.cache.fallback", "map", PolicyCacheMaps.DETAIL_V4).count()).isEqualTo(1.0)

        val objectMapper = jacksonObjectMapper().findAndRegisterModules()
        val policyProperties = PolicyProperties()
        val repository = object : PolicyReadRepository {
            override fun findById(id: Long): PolicyRecord = policyRecord(id = id)
            override fun findVisiblePage(
                region: RegionRequest,
                now: LocalDateTime,
                asOf: LocalDateTime,
                after: PolicySortKey?,
                limit: Int,
                internalFirst: Boolean,
            ) = emptyList<PolicyRecord>()
            override fun findRegionCatalog(): Map<String, List<String>> = emptyMap()
        }
        val service = PolicyQueryService(
            repository, PolicyDtoMapper(), PolicySortStrategy(policyProperties), CursorCodec(objectMapper),
            cache, CacheLoadCoordinator(monitoring), PolicyCacheGeneration(monitoring, properties).apply { activate(1) }, properties, policyProperties,
            RegionCatalogService(objectMapper, ClassPathResource("regions/korea_sido_sigungu_20260630.json")), Clock.systemUTC(), monitoring,
        )
        assertThat(service.detail(1).serviceName).isEqualTo("정책 1")
    }
}
