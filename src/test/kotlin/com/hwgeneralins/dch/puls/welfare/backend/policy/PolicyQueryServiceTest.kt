package com.hwgeneralins.dch.puls.welfare.backend.policy

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.hwgeneralins.dch.puls.welfare.backend.cache.PolicyCache
import com.hwgeneralins.dch.puls.welfare.backend.cache.PolicyCacheGeneration
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
import org.junit.jupiter.api.Test
import org.springframework.core.io.ClassPathResource
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class PolicyQueryServiceTest {
    private val now = Instant.parse("2026-07-30T03:00:00Z")
    private val zone = ZoneId.of("Asia/Seoul")
    private val clock = Clock.fixed(now, zone)
    private val objectMapper = jacksonObjectMapper().findAndRegisterModules()
    private val metrics = ServingMonitoringMetrics(SimpleMeterRegistry(), clock)
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
        val service = service(repository, CapturingCache(), PolicyCacheProperties(enabled = false))

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
        val service = service(repository, CapturingCache(), PolicyCacheProperties(enabled = false))

        val result = service.list("  ", "", cursor = null, limit = 20)

        assertThat(repository.requestedRegion).isEqualTo(RegionRequest(null, null))
        assertThat(result.items.single().sido).isEqualTo("서울특별시")
        assertThat(result.items.single().sigungu).isEqualTo("마포구")
    }

    @Test
    fun `load started on old generation cannot repopulate the active generation after switch`() {
        val properties = PolicyCacheProperties()
        val generation = PolicyCacheGeneration(metrics, properties).apply { activate(41) }
        val cache = GenerationRaceCache()
        val repository = BlockingDetailRepository(
            current = policyRecord(id = 1).copy(serviceName = "old policy"),
        )
        val service = service(
            repository = repository,
            cache = cache,
            cacheProperties = properties,
            cacheGeneration = generation,
        )
        val executor = Executors.newSingleThreadExecutor()
        try {
            val oldRequest = executor.submit<PolicyDetailDto> { service.detail(1) }
            assertThat(repository.oldReadCompleted.await(5, TimeUnit.SECONDS)).isTrue()

            // admin commit/epoch=42 -> poller atomically activates generation 42
            generation.activate(42)
            repository.current = policyRecord(id = 1).copy(serviceName = "new policy")
            repository.allowOldRequestToPut.countDown()

            assertThat(oldRequest.get(5, TimeUnit.SECONDS).serviceName).isEqualTo("old policy")
            // The late old put is unreachable because all new requests capture generation 42.
            assertThat(service.detail(1).serviceName).isEqualTo("new policy")
            assertThat(cache.keys()).contains("v4:e41:policy:1", "v4:e42:policy:1")
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `concurrent misses for the same generation key issue one database read`() {
        val repository = BlockingCountingDetailRepository(policyRecord(id = 1))
        val service = service(repository, GenerationRaceCache(), PolicyCacheProperties())
        val workers = 20
        val ready = CountDownLatch(workers)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(workers)
        try {
            val futures = (1..workers).map {
                executor.submit<PolicyDetailDto> {
                    ready.countDown()
                    check(start.await(5, TimeUnit.SECONDS))
                    service.detail(1)
                }
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue()
            start.countDown()
            assertThat(repository.readStarted.await(5, TimeUnit.SECONDS)).isTrue()
            repository.allowRead.countDown()

            futures.forEach { assertThat(it.get(5, TimeUnit.SECONDS).id).isEqualTo(1) }
            assertThat(repository.readCount.get()).isEqualTo(1)
        } finally {
            executor.shutdownNow()
        }
    }

    private fun service(
        repository: PolicyReadRepository,
        cache: PolicyCache,
        cacheProperties: PolicyCacheProperties,
        cacheGeneration: PolicyCacheGeneration = PolicyCacheGeneration(metrics, cacheProperties).apply { activate(1) },
    ) = PolicyQueryService(
        repository = repository,
        mapper = PolicyDtoMapper(),
        sort = PolicySortStrategy(policyProperties),
        cursorCodec = CursorCodec(objectMapper),
        cache = cache,
        cacheLoadCoordinator = com.hwgeneralins.dch.puls.welfare.backend.cache.CacheLoadCoordinator(metrics),
        cacheGeneration = cacheGeneration,
        cacheProperties = cacheProperties,
        policyProperties = policyProperties,
        regionCatalog = catalog,
        clock = clock,
        metrics = metrics,
    )
}

private class BlockingDetailRepository(
    @Volatile var current: PolicyRecord,
) : PolicyReadRepository {
    val oldReadCompleted = CountDownLatch(1)
    val allowOldRequestToPut = CountDownLatch(1)
    private val firstRead = AtomicBoolean(true)

    override fun findById(id: Long): PolicyRecord {
        val snapshot = current
        if (firstRead.compareAndSet(true, false)) {
            oldReadCompleted.countDown()
            check(allowOldRequestToPut.await(5, TimeUnit.SECONDS))
        }
        return snapshot
    }

    override fun findVisiblePage(
        region: RegionRequest,
        now: LocalDateTime,
        asOf: LocalDateTime,
        after: PolicySortKey?,
        limit: Int,
        internalFirst: Boolean,
    ): List<PolicyRecord> = emptyList()

    override fun findRegionCatalog(): Map<String, List<String>> = emptyMap()
}

private class GenerationRaceCache : PolicyCache {
    private val details = ConcurrentHashMap<String, CacheEnvelope<PolicyDetailDto>>()

    override fun getDetail(key: String): CacheEnvelope<PolicyDetailDto>? = details[key]

    override fun putDetail(key: String, value: CacheEnvelope<PolicyDetailDto>) {
        details[key] = value
    }

    fun keys(): Set<String> = details.keys
}

private class BlockingCountingDetailRepository(
    private val record: PolicyRecord,
) : PolicyReadRepository {
    val readCount = AtomicInteger()
    val readStarted = CountDownLatch(1)
    val allowRead = CountDownLatch(1)

    override fun findById(id: Long): PolicyRecord {
        readCount.incrementAndGet()
        readStarted.countDown()
        check(allowRead.await(5, TimeUnit.SECONDS))
        return record
    }

    override fun findVisiblePage(
        region: RegionRequest,
        now: LocalDateTime,
        asOf: LocalDateTime,
        after: PolicySortKey?,
        limit: Int,
        internalFirst: Boolean,
    ): List<PolicyRecord> = emptyList()

    override fun findRegionCatalog(): Map<String, List<String>> = emptyMap()
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

    override fun findRegionCatalog(): Map<String, List<String>> = emptyMap()
}

private class CapturingCache : PolicyCache {
    override fun getDetail(key: String): CacheEnvelope<PolicyDetailDto>? = null
    override fun putDetail(key: String, value: CacheEnvelope<PolicyDetailDto>) = Unit
}
