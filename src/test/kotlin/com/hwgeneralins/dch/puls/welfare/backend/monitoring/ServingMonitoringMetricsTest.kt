package com.hwgeneralins.dch.puls.welfare.backend.monitoring

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ServingMonitoringMetricsTest {
    @Test
    fun `records policy and repository metrics with bounded tags`() {
        val registry = SimpleMeterRegistry()
        val metrics = ServingMonitoringMetrics(registry)

        metrics.recordPolicyList(
            metrics.start(), PolicyListScope.SIGUNGU, PolicyPage.NEXT, PolicyListOutcome.SUCCESS, itemCount = 17,
        )
        metrics.recordPolicyDetail(metrics.start(), DetailSource.DATABASE, PolicyDetailOutcome.NOT_FOUND)
        metrics.recordPolicyUpdateCount(
            metrics.start(), PolicyListScope.SIGUNGU, PolicyUpdateCountOutcome.SUCCESS,
        )
        assertThatThrownBy {
            metrics.repository(RepositoryOperation.DETAIL) { error("database unavailable") }
        }.isInstanceOf(IllegalStateException::class.java)

        assertThat(registry.get("welfare.serving.policy.list").tags(
            "scope", "sigungu", "page", "next", "outcome", "success",
        ).timer().count()).isEqualTo(1)
        assertThat(registry.get("welfare.serving.policy.list.items").tags(
            "scope", "sigungu", "page", "next",
        ).summary().totalAmount()).isEqualTo(17.0)
        assertThat(registry.get("welfare.serving.policy.detail").tags(
            "source", "database", "outcome", "not_found",
        ).timer().count()).isEqualTo(1)
        assertThat(registry.get("welfare.serving.policy.recent.update.count").tags(
            "scope", "sigungu", "outcome", "success",
        ).timer().count()).isEqualTo(1)
        assertThat(registry.get("welfare.serving.repository.query").tags(
            "operation", "detail", "outcome", "failure",
        ).timer().count()).isEqualTo(1)
    }

    @Test
    fun `prometheus scrape exposes the dashboard contract and application tag`() {
        val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
        registry.config().commonTags("application", "wsbd")
        val metrics = ServingMonitoringMetrics(registry)
        metrics.recordPolicyList(
            metrics.start(), PolicyListScope.ALL, PolicyPage.FIRST, PolicyListOutcome.SUCCESS, itemCount = 1,
        )
        val scrape = registry.scrape()
        assertThat(scrape).contains(
            "welfare_serving_policy_list_seconds_count{application=\"wsbd\",outcome=\"success\",page=\"first\",scope=\"all\"} 1",
            "welfare_serving_policy_list_items_count{application=\"wsbd\",page=\"first\",scope=\"all\"} 1",
        )
    }
}
