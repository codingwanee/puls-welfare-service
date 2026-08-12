package com.hwgeneralins.dch.puls.welfare.backend.cache

import com.hazelcast.core.HazelcastInstance
import com.hwgeneralins.dch.puls.welfare.backend.policy.domain.CacheEnvelope
import com.hwgeneralins.dch.puls.welfare.backend.policy.domain.PolicyDetailDto
import com.hwgeneralins.dch.puls.welfare.backend.monitoring.CacheOperation
import com.hwgeneralins.dch.puls.welfare.backend.monitoring.CacheRequestResult
import com.hwgeneralins.dch.puls.welfare.backend.monitoring.ServingMonitoringMetrics
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.time.Clock

@Component
@ConditionalOnProperty(prefix = "app.cache", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class HazelcastPolicyCache(
    private val hazelcast: HazelcastInstance,
    private val metrics: ServingMonitoringMetrics,
    private val clock: Clock,
) : PolicyCache {
    override fun getDetail(key: String): CacheEnvelope<PolicyDetailDto>? = read(PolicyCacheMaps.DETAIL_V4, key)
    override fun putDetail(key: String, value: CacheEnvelope<PolicyDetailDto>) = write(PolicyCacheMaps.DETAIL_V4, key, value)
    private inline fun <reified T : java.io.Serializable> read(mapName: String, key: String): CacheEnvelope<T>? {
        return try {
            val value = metrics.cacheOperation(CacheOperation.GET) {
                updateMemberCount()
                val map = hazelcast.getMap<String, CacheEnvelope<T>>(mapName)
                map[key].also { cached ->
                    if (cached != null && !cached.validUntil.isAfter(clock.instant())) map.delete(key)
                }
            }
            if (value == null || !value.validUntil.isAfter(clock.instant())) {
                metrics.recordCacheRequest(CacheRequestResult.MISS)
                null
            } else {
                metrics.recordCacheRequest(CacheRequestResult.HIT)
                value
            }
        } catch (_: Exception) {
            metrics.recordCacheRequest(CacheRequestResult.MISS)
            null
        }
    }

    private fun <T : java.io.Serializable> write(mapName: String, key: String, value: CacheEnvelope<T>) {
        try {
            metrics.cacheOperation(CacheOperation.PUT) {
                updateMemberCount()
                hazelcast.getMap<String, CacheEnvelope<T>>(mapName)[key] = value
            }
        } catch (_: Exception) {
            // A cache write failure must not fail the serving request.
        }
    }

    private fun updateMemberCount() {
        metrics.setHazelcastMembers(runCatching { hazelcast.cluster.members.size }.getOrDefault(0))
    }
}
