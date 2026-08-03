package com.hwgeneralins.dch.puls.welfare.backend.cache

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicLong

/**
 * Cache generation is the latest durable DB epoch observed by this app instance.
 * Null means cache bypass: startup, missing epoch state, or a poll outage beyond max-stale.
 */
@Component
class PolicyCacheGeneration(meterRegistry: MeterRegistry) {
    private val value = AtomicLong(BYPASS)

    init {
        meterRegistry.gauge("policy.cache.generation", value) { it.get().toDouble() }
    }

    fun capture(): Long? = value.get().takeUnless { it == BYPASS }

    fun activate(epoch: Long) {
        require(epoch >= 0) { "Cache epoch must be non-negative." }
        value.set(epoch)
    }

    fun bypass() {
        value.set(BYPASS)
    }

    private companion object {
        const val BYPASS = -1L
    }
}
