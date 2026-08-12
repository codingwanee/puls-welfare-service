package com.hwgeneralins.dch.puls.welfare.backend.policy.service

import com.hwgeneralins.dch.puls.welfare.backend.cache.PolicyCache
import com.hwgeneralins.dch.puls.welfare.backend.cache.CacheLoadCoordinator
import com.hwgeneralins.dch.puls.welfare.backend.cache.PolicyCacheGeneration
import com.hwgeneralins.dch.puls.welfare.backend.cache.PolicyCacheMaps
import com.hwgeneralins.dch.puls.welfare.backend.config.PolicyCacheProperties
import com.hwgeneralins.dch.puls.welfare.backend.config.PolicyProperties
import com.hwgeneralins.dch.puls.welfare.backend.policy.api.BusinessErrorCode
import com.hwgeneralins.dch.puls.welfare.backend.policy.api.PolicyApiException
import com.hwgeneralins.dch.puls.welfare.backend.policy.domain.CacheEnvelope
import com.hwgeneralins.dch.puls.welfare.backend.policy.domain.CursorPageDto
import com.hwgeneralins.dch.puls.welfare.backend.policy.domain.PolicyDetailDto
import com.hwgeneralins.dch.puls.welfare.backend.policy.domain.PolicyListItemDto
import com.hwgeneralins.dch.puls.welfare.backend.policy.domain.RegionRequest
import com.hwgeneralins.dch.puls.welfare.backend.policy.repository.PolicyReadRepository
import com.hwgeneralins.dch.puls.welfare.backend.region.RegionCatalogService
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime

@Service
class PolicyQueryService(
    private val repository: PolicyReadRepository,
    private val mapper: PolicyDtoMapper,
    private val sort: PolicySortStrategy,
    private val cursorCodec: CursorCodec,
    private val cache: PolicyCache,
    private val cacheLoadCoordinator: CacheLoadCoordinator,
    private val cacheGeneration: PolicyCacheGeneration,
    private val cacheProperties: PolicyCacheProperties,
    private val policyProperties: PolicyProperties,
    private val regionCatalog: RegionCatalogService,
    private val clock: Clock,
) {
    fun list(sido: String?, sigungu: String?, cursor: String?, limit: Int): CursorPageDto<PolicyListItemDto> {
        if (limit !in 1..MAX_PAGE_SIZE) {
            throw PolicyApiException(HttpStatus.BAD_REQUEST, BusinessErrorCode.INVALID_CURSOR, "페이지 크기는 1~$MAX_PAGE_SIZE 범위여야 합니다.")
        }
        val region = region(sido, sigungu)
        val decoded = cursor?.let(cursorCodec::decode)
        if (decoded != null && decoded.sortVersion != sort.version) {
            throw PolicyApiException(HttpStatus.BAD_REQUEST, BusinessErrorCode.INVALID_CURSOR, "현재 정렬 정책과 호환되지 않는 페이지 커서입니다.")
        }
        val asOf = decoded?.asOf ?: LocalDateTime.now(clock)
        val now = LocalDateTime.now(clock)
        val after = decoded?.sortKey()
        val ordered = repository.findVisiblePage(
            region = region,
            now = now,
            asOf = asOf,
            after = after,
            limit = limit + 1,
            internalFirst = policyProperties.sort.internalFirst,
        ).asSequence()
            .filter { it.isVisibleAt(now) }
            .sortedWith(sort.comparator)
            .filter { after == null || sort.compare(sort.key(it), after) > 0 }
            .take(limit + 1)
            .toList()
        val page = ordered.take(limit)
        val hasNext = ordered.size > limit
        val nextCursor = if (hasNext && page.isNotEmpty()) {
            val key = sort.key(page.last())
            cursorCodec.encode(PolicyCursor(sort.version, asOf, key.sourceRank, key.deadline, key.deadlineGroup, key.inputAt, key.id))
        } else null
        return CursorPageDto(page.map(mapper::toListItem), nextCursor, hasNext)
    }

    fun detail(id: Long): PolicyDetailDto {
        val generation = cacheGeneration.capture()
        val key = generation?.let { "${PolicyCacheMaps.KEY_PREFIX}e$it:policy:$id" }
        if (key != null) {
            return cacheLoadCoordinator.getOrLoad(
                key = key,
                cached = { cache.getDetail(key)?.value },
                loader = { loadDetail(id, key) },
            )
        }
        return loadDetail(id, null)
    }

    private fun loadDetail(id: Long, cacheKey: String?): PolicyDetailDto {
        val policy = repository.findById(id)
            ?: throw PolicyApiException(HttpStatus.NOT_FOUND, BusinessErrorCode.POLICY_NOT_FOUND, "정책을 찾을 수 없습니다.")
        val now = LocalDateTime.now(clock)
        if (!policy.isVisibleAt(now)) {
            throw PolicyApiException(HttpStatus.GONE, BusinessErrorCode.POLICY_NOT_AVAILABLE, "현재 이용할 수 없는 정책입니다.")
        }
        val detail = mapper.toDetail(policy)
        val ttlBoundary = clock.instant().plusSeconds(cacheProperties.detailTtlSeconds)
        val exposureBoundary = policy.exposureEndAt?.atZone(clock.zone)?.toInstant()
        cacheKey?.let { cache.putDetail(it, CacheEnvelope(detail, minOfNotNull(ttlBoundary, exposureBoundary))) }
        return detail
    }

    private fun region(sido: String?, sigungu: String?): RegionRequest {
        val normalizedSido = sido?.trim()?.takeIf { it.isNotEmpty() }
        val normalizedSigungu = sigungu?.trim()?.takeIf { it.isNotEmpty() }
        val invalid = if (normalizedSido == null) {
            normalizedSigungu != null
        } else {
            !regionCatalog.contains(normalizedSido, normalizedSigungu)
        }
        if (invalid) {
            throw PolicyApiException(HttpStatus.BAD_REQUEST, BusinessErrorCode.INVALID_REGION, "유효하지 않은 지역입니다.")
        }
        return RegionRequest(normalizedSido, normalizedSigungu)
    }

    private fun minOfNotNull(default: Instant, candidate: Instant?): Instant =
        if (candidate != null && candidate.isBefore(default)) candidate else default

    companion object {
        const val MAX_PAGE_SIZE = 100
    }
}
