package com.hwgeneralins.dch.puls.welfare.backend.cache

import com.hwgeneralins.dch.puls.welfare.backend.config.PolicyCacheProperties
import com.hwgeneralins.dch.puls.welfare.backend.monitoring.CacheState
import com.hwgeneralins.dch.puls.welfare.backend.monitoring.ServingMonitoringMetrics
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicLong

/**
 * Cache generation is the latest durable DB epoch observed by this app instance.
 * Null means cache bypass: startup, missing epoch state, or a poll outage beyond max-stale.
 */
@Component
class PolicyCacheGeneration(
    private val metrics: ServingMonitoringMetrics,
    private val properties: PolicyCacheProperties,
) {
    private val value = AtomicLong(BYPASS)

    init {
        metrics.setGeneration(BYPASS)
        metrics.setCacheState(if (properties.enabled) CacheState.BYPASS else CacheState.DISABLED)
    }

    fun capture(): Long? = value.get().takeUnless { it == BYPASS }

    fun activate(epoch: Long) {
        require(epoch >= 0) { "Cache epoch must be non-negative." }
        value.set(epoch)
        metrics.setGeneration(epoch)
        metrics.setCacheState(if (properties.enabled) CacheState.ACTIVE else CacheState.DISABLED)
    }

    fun bypass() {
        value.set(BYPASS)
        metrics.setGeneration(BYPASS)
        metrics.setCacheState(if (properties.enabled) CacheState.BYPASS else CacheState.DISABLED)
    }

    private companion object {
        const val BYPASS = -1L
    }
}
