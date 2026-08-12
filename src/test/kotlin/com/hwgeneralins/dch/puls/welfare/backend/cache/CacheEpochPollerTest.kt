package com.hwgeneralins.dch.puls.welfare.backend.cache

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import com.hwgeneralins.dch.puls.welfare.backend.config.PolicyCacheProperties
import com.hwgeneralins.dch.puls.welfare.backend.monitoring.ServingMonitoringMetrics
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class CacheEpochPollerTest {
    private val jdbc = JdbcTemplate(DriverManagerDataSource("jdbc:h2:mem:epoch-test;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""))

    @BeforeEach
    fun setUp() {
        jdbc.execute("DROP TABLE IF EXISTS SL_PBC_POLICY_CACHE_EPOCH")
        jdbc.execute("CREATE TABLE SL_PBC_POLICY_CACHE_EPOCH (CACHE_NAME VARCHAR(64) PRIMARY KEY, EPOCH BIGINT, MDF_DTHMS TIMESTAMP)")
        jdbc.update("INSERT INTO SL_PBC_POLICY_CACHE_EPOCH VALUES ('APP_POLICY_V1', 1, CURRENT_TIMESTAMP)")
    }

    @Test
    fun `epoch change activates a new generation without clearing maps`() {
        val clock = Clock.fixed(Instant.parse("2026-07-30T00:00:00Z"), ZoneOffset.UTC)
        val properties = PolicyCacheProperties()
        val registry = SimpleMeterRegistry()
        val metrics = ServingMonitoringMetrics(registry, clock)
        val generation = PolicyCacheGeneration(metrics, properties)
        val poller = CacheEpochPoller(
            jdbc, generation, properties, metrics, clock,
        )
        poller.poll()
        assertThat(generation.capture()).isEqualTo(1)
        jdbc.update("UPDATE SL_PBC_POLICY_CACHE_EPOCH SET EPOCH = 2, MDF_DTHMS = CURRENT_TIMESTAMP")
        poller.poll()
        assertThat(generation.capture()).isEqualTo(2)
        assertThat(registry.get("welfare.serving.cache.epoch.poll").tag("outcome", "success").timer().count())
            .isEqualTo(2)
        assertThat(registry.get("welfare.serving.cache.generation.transitions").counter().count()).isEqualTo(1.0)
    }

    @Test
    fun `transient poll failure retains the last verified generation`() {
        val clock = Clock.systemUTC()
        val properties = PolicyCacheProperties()
        val registry = SimpleMeterRegistry()
        val metrics = ServingMonitoringMetrics(registry, clock)
        val generation = PolicyCacheGeneration(metrics, properties)
        val poller = CacheEpochPoller(jdbc, generation, properties, metrics, clock)
        poller.poll()

        jdbc.execute("DROP TABLE SL_PBC_POLICY_CACHE_EPOCH")
        poller.poll()

        assertThat(generation.capture()).isEqualTo(1)
    }

    @Test
    fun `poll failure beyond max stale enters cache bypass mode`() {
        val clock = MutableClock(Instant.parse("2026-07-30T00:00:00Z"))
        val properties = PolicyCacheProperties(epochPollMaxStaleMillis = 5_000)
        val metrics = ServingMonitoringMetrics(SimpleMeterRegistry(), clock)
        val generation = PolicyCacheGeneration(metrics, properties)
        val poller = CacheEpochPoller(
            jdbc, generation, properties, metrics, clock,
        )
        poller.poll()
        jdbc.execute("DROP TABLE SL_PBC_POLICY_CACHE_EPOCH")

        clock.advance(Duration.ofMillis(4_999))
        poller.poll()
        assertThat(generation.capture()).isEqualTo(1)

        clock.advance(Duration.ofMillis(1))
        poller.poll()
        assertThat(generation.capture()).isNull()
    }

    @Test
    fun `missing epoch row enters cache bypass mode`() {
        jdbc.update("DELETE FROM SL_PBC_POLICY_CACHE_EPOCH")
        val clock = Clock.systemUTC()
        val properties = PolicyCacheProperties()
        val registry = SimpleMeterRegistry()
        val metrics = ServingMonitoringMetrics(registry, clock)
        val generation = PolicyCacheGeneration(metrics, properties).apply { activate(1) }
        val poller = CacheEpochPoller(jdbc, generation, properties, metrics, clock)
        poller.poll(); poller.poll()
        assertThat(generation.capture()).isNull()
        assertThat(registry.get("welfare.serving.cache.epoch.poll").tag("outcome", "missing").timer().count())
            .isEqualTo(2)
        assertThat(registry.get("policy.cache.epoch.row.missing").counter().count()).isEqualTo(2.0)
    }
}

private class MutableClock(
    private var current: Instant,
    private val currentZone: ZoneId = ZoneOffset.UTC,
) : Clock() {
    override fun getZone(): ZoneId = currentZone
    override fun withZone(zone: ZoneId): Clock = MutableClock(current, zone)
    override fun instant(): Instant = current
    fun advance(duration: Duration) { current = current.plus(duration) }
}
