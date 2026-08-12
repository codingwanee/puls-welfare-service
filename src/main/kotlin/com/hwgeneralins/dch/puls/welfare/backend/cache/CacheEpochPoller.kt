package com.hwgeneralins.dch.puls.welfare.backend.cache

import com.hwgeneralins.dch.puls.welfare.backend.config.PolicyCacheProperties
import com.hwgeneralins.dch.puls.welfare.backend.monitoring.EpochPollOutcome
import com.hwgeneralins.dch.puls.welfare.backend.monitoring.ServingMonitoringMetrics
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant

@Component
@ConditionalOnProperty(prefix = "app.cache", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class CacheEpochPoller(
    private val jdbc: JdbcTemplate,
    private val generation: PolicyCacheGeneration,
    private val properties: PolicyCacheProperties,
    private val metrics: ServingMonitoringMetrics,
    private val clock: Clock,
) {
    private var observedEpoch: Long? = null
    private var lastSuccessfulPollAt: Instant? = null

    @Scheduled(fixedDelayString = "\${app.cache.epoch-poll-millis:1000}")
    fun poll() {
        val sample = metrics.start()
        try {
            val row = jdbc.query(
                "SELECT EPOCH, MDF_DTHMS FROM SL_PBC_POLICY_CACHE_EPOCH WHERE CACHE_NAME = ?",
                { rs, _ -> rs.getLong("EPOCH") to rs.getTimestamp("MDF_DTHMS")?.toInstant() },
                CACHE_NAME,
            ).firstOrNull()
            if (row == null) {
                generation.bypass()
                observedEpoch = null
                metrics.recordEpochPoll(sample, EpochPollOutcome.MISSING)
                return
            }
            val previous = observedEpoch
            if (previous != null && row.first != previous) {
                val lag = row.second?.let { Duration.between(it, clock.instant()) } ?: Duration.ZERO
                metrics.recordGenerationTransition(lag)
            }
            generation.activate(row.first)
            observedEpoch = row.first
            lastSuccessfulPollAt = clock.instant()
            metrics.recordEpochPoll(sample, EpochPollOutcome.SUCCESS)
        } catch (_: Exception) {
            metrics.recordEpochPoll(sample, EpochPollOutcome.FAILURE)
            val staleMillis = lastSuccessfulPollAt?.let { clock.instant().toEpochMilli() - it.toEpochMilli() }
            if (staleMillis == null || staleMillis >= properties.epochPollMaxStaleMillis) {
                generation.bypass()
            }
        }
    }

    companion object { const val CACHE_NAME = "APP_POLICY_V1" }
}
