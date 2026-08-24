package com.hwgeneralins.dch.puls.welfare.backend.monitoring

import com.hwgeneralins.dch.puls.welfare.backend.cache.PolicyCacheGeneration
import com.hwgeneralins.dch.puls.welfare.backend.config.PolicyCacheProperties
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class ServingMonitoringMetricsTest {
    private val now = Instant.parse("2026-08-12T01:02:03Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    @Test
    fun `records policy repository and cache metrics with bounded tags`() {
        val registry = SimpleMeterRegistry()
        val metrics = ServingMonitoringMetrics(registry, clock)

        metrics.recordPolicyList(
            metrics.start(), PolicyListScope.SIGUNGU, PolicyPage.NEXT, PolicyListOutcome.SUCCESS, itemCount = 17,
        )
        metrics.recordPolicyDetail(metrics.start(), DetailSource.COALESCED, PolicyDetailOutcome.NOT_FOUND)
        metrics.recordPolicyUpdateCount(
            metrics.start(), PolicyListScope.SIGUNGU, PolicyUpdateCountOutcome.SUCCESS,
        )
        assertThatThrownBy {
            metrics.repository(RepositoryOperation.DETAIL) { error("database unavailable") }
        }.isInstanceOf(IllegalStateException::class.java)
        metrics.recordCacheRequest(CacheRequestResult.HIT)
        assertThatThrownBy {
            metrics.cacheOperation(CacheOperation.GET) { error("hazelcast unavailable") }
        }.isInstanceOf(IllegalStateException::class.java)

        assertThat(registry.get("welfare.serving.policy.list").tags(
            "scope", "sigungu", "page", "next", "outcome", "success",
        ).timer().count()).isEqualTo(1)
        assertThat(registry.get("welfare.serving.policy.list.items").tags(
            "scope", "sigungu", "page", "next",
        ).summary().totalAmount()).isEqualTo(17.0)
        assertThat(registry.get("welfare.serving.policy.detail").tags(
            "source", "coalesced", "outcome", "not_found",
        ).timer().count()).isEqualTo(1)
        assertThat(registry.get("welfare.serving.policy.recent.update.count").tags(
            "scope", "sigungu", "outcome", "success",
        ).timer().count()).isEqualTo(1)
        assertThat(registry.get("welfare.serving.repository.query").tags(
            "operation", "detail", "outcome", "failure",
        ).timer().count()).isEqualTo(1)
        assertThat(registry.get("welfare.serving.cache.fallback").tag("operation", "get").counter().count())
            .isEqualTo(1.0)
        assertThat(registry.get("policy.cache.fallback").counter().count()).isEqualTo(1.0)
        assertThat(registry.get("policy.cache.requests").tags(
            "map", "app-policy-detail-v4", "result", "hit",
        ).counter().count()).isEqualTo(1.0)
    }

    @Test
    fun `tracks cache state generation epoch freshness and transition lag`() {
        val registry = SimpleMeterRegistry()
        val metrics = ServingMonitoringMetrics(registry, clock)
        val generation = PolicyCacheGeneration(metrics, PolicyCacheProperties())

        assertThat(registry.get("welfare.serving.cache.state").tag("state", "bypass").gauge().value()).isEqualTo(1.0)
        generation.activate(42)
        metrics.recordEpochPoll(metrics.start(), EpochPollOutcome.SUCCESS)
        metrics.recordGenerationTransition(Duration.ofMillis(125))

        assertThat(registry.get("welfare.serving.cache.generation").gauge().value()).isEqualTo(42.0)
        assertThat(registry.get("welfare.serving.cache.state").tag("state", "active").gauge().value()).isEqualTo(1.0)
        assertThat(registry.get("welfare.serving.cache.epoch.last.success.time").gauge().value())
            .isEqualTo(now.epochSecond.toDouble())
        assertThat(registry.get("welfare.serving.cache.invalidation").timer().totalTime(java.util.concurrent.TimeUnit.MILLISECONDS))
            .isEqualTo(125.0)
        assertThat(registry.get("policy.cache.generation.transitions").counter().count()).isEqualTo(1.0)
    }

    @Test
    fun `prometheus scrape exposes the dashboard contract and application tag`() {
        val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
        registry.config().commonTags("application", "wsbd")
        val metrics = ServingMonitoringMetrics(registry, clock)
        metrics.recordPolicyList(
            metrics.start(), PolicyListScope.ALL, PolicyPage.FIRST, PolicyListOutcome.SUCCESS, itemCount = 1,
        )
        metrics.recordEpochPoll(metrics.start(), EpochPollOutcome.SUCCESS)

        val scrape = registry.scrape()
        assertThat(scrape).contains(
            "welfare_serving_policy_list_seconds_count{application=\"wsbd\",outcome=\"success\",page=\"first\",scope=\"all\"} 1",
            "welfare_serving_policy_list_items_count{application=\"wsbd\",page=\"first\",scope=\"all\"} 1",
            "welfare_serving_cache_epoch_last_success_time_seconds{application=\"wsbd\"}",
            "welfare_serving_cache_state{application=\"wsbd\",state=\"bypass\"}",
        )
    }
}
