package com.hwgeneralins.dch.puls.welfare.backend.cache

import com.hazelcast.core.HazelcastInstance
import com.hwgeneralins.dch.puls.welfare.backend.policy.domain.CacheEnvelope
import com.hwgeneralins.dch.puls.welfare.backend.policy.domain.PolicyDetailDto
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.time.Clock
import java.util.concurrent.atomic.AtomicInteger

@Component
@ConditionalOnProperty(prefix = "app.cache", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class HazelcastPolicyCache(
    private val hazelcast: HazelcastInstance,
    private val registry: MeterRegistry,
    private val clock: Clock,
) : PolicyCache {
    private val memberCount = AtomicInteger(0)

    init {
        registry.gauge("policy.cache.hazelcast.member.count", memberCount)
    }

    override fun getDetail(key: String): CacheEnvelope<PolicyDetailDto>? = read(PolicyCacheMaps.DETAIL_V2, key)
    override fun putDetail(key: String, value: CacheEnvelope<PolicyDetailDto>) = write(PolicyCacheMaps.DETAIL_V2, key, value)
    private inline fun <reified T : java.io.Serializable> read(mapName: String, key: String): CacheEnvelope<T>? {
        return try {
            updateMemberCount()
            val value = hazelcast.getMap<String, CacheEnvelope<T>>(mapName)[key]
            if (value == null || !value.validUntil.isAfter(clock.instant())) {
                registry.counter("policy.cache.requests", "map", mapName, "result", "miss").increment()
                if (value != null) hazelcast.getMap<String, CacheEnvelope<T>>(mapName).delete(key)
                null
            } else {
                registry.counter("policy.cache.requests", "map", mapName, "result", "hit").increment()
                value
            }
        } catch (_: Exception) {
            registry.counter("policy.cache.fallback", "map", mapName).increment()
            null
        }
    }

    private fun <T : java.io.Serializable> write(mapName: String, key: String, value: CacheEnvelope<T>) {
        try {
            updateMemberCount()
            hazelcast.getMap<String, CacheEnvelope<T>>(mapName)[key] = value
        } catch (_: Exception) {
            registry.counter("policy.cache.fallback", "map", mapName).increment()
        }
    }

    private fun updateMemberCount() {
        memberCount.set(runCatching { hazelcast.cluster.members.size }.getOrDefault(0))
    }
}
