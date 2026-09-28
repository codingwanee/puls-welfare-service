package com.hwgeneralins.dch.puls.welfare.backend.monitoring

import io.micrometer.core.instrument.DistributionSummary
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.stereotype.Component
import java.time.Duration

@Component
class ServingMonitoringMetrics(
    private val registry: MeterRegistry,
) {
    private val policyListTimers = combinations(
        PolicyListScope.entries,
        PolicyPage.entries,
        PolicyListOutcome.entries,
    ).associate { (scope, page, outcome) ->
        Triple(scope, page, outcome) to requestTimer("welfare.serving.policy.list")
            .tags("scope", scope.tag, "page", page.tag, "outcome", outcome.tag)
            .description("End-to-end policy list query duration")
            .register(registry)
    }
    private val policyListItems = PolicyListScope.entries.flatMap { scope ->
        PolicyPage.entries.map { page ->
            (scope to page) to DistributionSummary.builder("welfare.serving.policy.list.items")
                .description("Number of policies returned by a successful list query")
                .baseUnit("items")
                .tags("scope", scope.tag, "page", page.tag)
                .register(registry)
        }
    }.toMap()
    private val policyDetailTimers = DetailSource.entries.flatMap { source ->
        PolicyDetailOutcome.entries.map { outcome ->
            (source to outcome) to requestTimer("welfare.serving.policy.detail")
                .tags("source", source.tag, "outcome", outcome.tag)
                .description("End-to-end policy detail query duration")
                .register(registry)
        }
    }.toMap()
    private val policyUpdateCountTimers = PolicyListScope.entries.flatMap { scope ->
        PolicyUpdateCountOutcome.entries.map { outcome ->
            (scope to outcome) to requestTimer("welfare.serving.policy.recent.update.count")
                .tags("scope", scope.tag, "outcome", outcome.tag)
                .description("End-to-end recent policy update count query duration")
                .register(registry)
        }
    }.toMap()
    private val repositoryTimers = RepositoryOperation.entries.flatMap { operation ->
        OperationOutcome.entries.map { outcome ->
            (operation to outcome) to requestTimer("welfare.serving.repository.query")
                .tags("operation", operation.tag, "outcome", outcome.tag)
                .description("Policy repository query duration")
                .register(registry)
        }
    }.toMap()
    fun start(): Timer.Sample = Timer.start(registry)

    fun recordPolicyList(
        sample: Timer.Sample,
        scope: PolicyListScope,
        page: PolicyPage,
        outcome: PolicyListOutcome,
        itemCount: Int? = null,
    ) {
        sample.stop(policyListTimers.getValue(Triple(scope, page, outcome)))
        if (outcome == PolicyListOutcome.SUCCESS && itemCount != null) {
            policyListItems.getValue(scope to page).record(itemCount.toDouble())
        }
    }

    fun recordPolicyDetail(sample: Timer.Sample, source: DetailSource, outcome: PolicyDetailOutcome) {
        sample.stop(policyDetailTimers.getValue(source to outcome))
    }

    fun recordPolicyUpdateCount(
        sample: Timer.Sample,
        scope: PolicyListScope,
        outcome: PolicyUpdateCountOutcome,
    ) {
        sample.stop(policyUpdateCountTimers.getValue(scope to outcome))
    }

    fun <T> repository(operation: RepositoryOperation, block: () -> T): T {
        val sample = start()
        return try {
            block().also { sample.stop(repositoryTimers.getValue(operation to OperationOutcome.SUCCESS)) }
        } catch (cause: Throwable) {
            sample.stop(repositoryTimers.getValue(operation to OperationOutcome.FAILURE))
            throw cause
        }
    }

    private fun requestTimer(name: String): Timer.Builder = Timer.builder(name).serviceLevelObjectives(*REQUEST_SLOS)

    private fun <A, B, C> combinations(first: Iterable<A>, second: Iterable<B>, third: Iterable<C>) =
        first.flatMap { a -> second.flatMap { b -> third.map { c -> Triple(a, b, c) } } }

    companion object {
        private val REQUEST_SLOS = longArrayOf(50, 100, 250, 500, 1_000, 2_000, 5_000).map(Duration::ofMillis).toTypedArray()
    }
}

enum class PolicyListScope(val tag: String) { ALL("all"), SIDO("sido"), SIGUNGU("sigungu") }
enum class PolicyPage(val tag: String) { FIRST("first"), NEXT("next") }
enum class PolicyListOutcome(val tag: String) { SUCCESS("success"), INVALID_REQUEST("invalid_request"), FAILURE("failure") }
enum class DetailSource(val tag: String) { DATABASE("database") }
enum class PolicyDetailOutcome(val tag: String) {
    SUCCESS("success"), NOT_FOUND("not_found"), NOT_AVAILABLE("not_available"), FAILURE("failure")
}
enum class PolicyUpdateCountOutcome(val tag: String) {
    SUCCESS("success"), INVALID_REQUEST("invalid_request"), FAILURE("failure")
}
enum class RepositoryOperation(val tag: String) {
    LIST("list"), DETAIL("detail"), RECENT_UPDATE_COUNT("recent_update_count")
}
enum class OperationOutcome(val tag: String) { SUCCESS("success"), FAILURE("failure") }
