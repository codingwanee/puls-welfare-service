package com.hwgeneralins.dch.puls.welfare.backend.policy

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.hwgeneralins.dch.puls.welfare.backend.config.PolicyProperties
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
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

class PolicyQueryServiceTest {
    private val now = Instant.parse("2026-07-30T03:00:00Z")
    private val zone = ZoneId.of("Asia/Seoul")
    private val clock = Clock.fixed(now, zone)
    private val objectMapper = jacksonObjectMapper().findAndRegisterModules()
    private val metrics = ServingMonitoringMetrics(SimpleMeterRegistry())
    private val policyProperties = PolicyProperties()
    private val catalog = RegionCatalogService(
        objectMapper,
        ClassPathResource("regions/korea_sido_sigungu_20260630.json"),
    )

    @Test
    fun `cursor pages follow the deterministic sort without duplicates`() {
        val repository = StubRepository(
            records = listOf(
                policyRecord(id = 3, type = "EXTERNAL"),
                policyRecord(id = 1, type = "INTERNAL"),
                policyRecord(id = 2, type = "INTERNAL"),
            ),
        )
        val service = service(repository)

        val first = service.list("서울특별시", "마포구", cursor = null, limit = 2)
        val second = service.list("서울특별시", "마포구", cursor = first.nextCursor, limit = 2)

        assertThat(first.items.map { it.id }).containsExactly(2, 1)
        assertThat(first.hasNext).isTrue()
        assertThat(second.items.map { it.id }).containsExactly(3)
        assertThat(second.hasNext).isFalse()
        assertThat((first.items + second.items).map { it.id }).doesNotHaveDuplicates()
    }

    @Test
    fun `blank region request is passed to repository without a default sido`() {
        val repository = StubRepository(records = listOf(policyRecord()))
        val service = service(repository)

        val result = service.list("  ", "", cursor = null, limit = 20)

        assertThat(repository.requestedRegion).isEqualTo(RegionRequest(null, null))
        assertThat(result.items.single().sido).isEqualTo("서울특별시")
        assertThat(result.items.single().sigungu).isEqualTo("마포구")
    }

    private fun service(repository: PolicyReadRepository) = PolicyQueryService(
        repository = repository,
        mapper = PolicyDtoMapper(),
        sort = PolicySortStrategy(policyProperties),
        cursorCodec = CursorCodec(objectMapper),
        policyProperties = policyProperties,
        regionCatalog = catalog,
        clock = clock,
        metrics = metrics,
    )
}

private class StubRepository(
    private val records: List<PolicyRecord>,
) : PolicyReadRepository {
    var requestedRegion: RegionRequest? = null

    override fun findById(id: Long): PolicyRecord? = records.firstOrNull { it.id == id }

    override fun findVisiblePage(
        region: RegionRequest,
        now: LocalDateTime,
        asOf: LocalDateTime,
        after: PolicySortKey?,
        limit: Int,
        internalFirst: Boolean,
    ): List<PolicyRecord> {
        requestedRegion = region
        return records.take(limit)
    }

    override fun countVisibleRecentlyUpdated(
        region: RegionRequest,
        now: LocalDateTime,
        updatedFrom: LocalDateTime,
        updatedThrough: LocalDateTime,
    ): Long = 0

    override fun findRegionCatalog(): Map<String, List<String>> = emptyMap()
}
