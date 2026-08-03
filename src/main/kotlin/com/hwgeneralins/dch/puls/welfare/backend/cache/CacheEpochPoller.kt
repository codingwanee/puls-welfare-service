package com.hwgeneralins.dch.puls.welfare.backend.cache

import io.micrometer.core.instrument.MeterRegistry
import com.hwgeneralins.dch.puls.welfare.backend.config.PolicyCacheProperties
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

@Component
@ConditionalOnProperty(prefix = "app.cache", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class CacheEpochPoller(
    private val jdbc: JdbcTemplate,
    private val generation: PolicyCacheGeneration,
    private val properties: PolicyCacheProperties,
    private val registry: MeterRegistry,
    private val clock: Clock,
) {
    private var observedEpoch: Long? = null
    private var lastSuccessfulPollAt: Instant? = null
    private val invalidationLagMillis = AtomicLong(0)

    init {
        registry.gauge("policy.cache.invalidation.lag.millis", invalidationLagMillis)
    }

    @Scheduled(fixedDelayString = "\${app.cache.epoch-poll-millis:1000}")
    fun poll() {
        try {
            val row = jdbc.query(
                "SELECT EPOCH, MDF_DTHMS FROM SL_PBC_POLICY_CACHE_EPOCH WHERE CACHE_NAME = ?",
                { rs, _ -> rs.getLong("EPOCH") to rs.getTimestamp("MDF_DTHMS")?.toInstant() },
                CACHE_NAME,
            ).firstOrNull()
            if (row == null) {
                registry.counter("policy.cache.epoch.row.missing").increment()
                generation.bypass()
                observedEpoch = null
                return
            }
            val previous = observedEpoch
            if (previous != null && row.first != previous) {
                val lag = row.second?.let { clock.instant().toEpochMilli() - it.toEpochMilli() }?.coerceAtLeast(0) ?: 0
                invalidationLagMillis.set(lag)
                registry.counter("policy.cache.generation.transitions").increment()
            }
            generation.activate(row.first)
            observedEpoch = row.first
            lastSuccessfulPollAt = clock.instant()
        } catch (_: Exception) {
            registry.counter("policy.cache.epoch.poll.failures").increment()
            val staleMillis = lastSuccessfulPollAt?.let { clock.instant().toEpochMilli() - it.toEpochMilli() }
            if (staleMillis == null || staleMillis >= properties.epochPollMaxStaleMillis) {
                generation.bypass()
            }
        }
    }

    companion object { const val CACHE_NAME = "APP_POLICY_V1" }
}
