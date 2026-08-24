package com.hwgeneralins.dch.puls.welfare.backend.monitoring

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.DistributionSummary
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

@Component
class ServingMonitoringMetrics(
    private val registry: MeterRegistry,
    private val clock: Clock,
) {
    private val generation = AtomicLong(BYPASS_GENERATION)
    private val lastEpochPollSuccess = AtomicLong(0)
    private val invalidationLagMillis = AtomicLong(0)
    private val hazelcastMembers = AtomicInteger(0)
    private val cacheLoadInflight = AtomicInteger(0)
    private val cacheStates = CacheState.entries.associateWith { AtomicLong(0) }

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
    private val cacheRequestCounters = CacheRequestResult.entries.associateWith { result ->
        Counter.builder("welfare.serving.cache.requests")
            .description("Policy detail cache requests")
            .tags("cache", "detail", "result", result.tag)
            .register(registry)
    }
    private val legacyCacheRequestCounters = CacheRequestResult.entries.associateWith { result ->
        registry.counter("policy.cache.requests", "map", DETAIL_CACHE_NAME, "result", result.tag)
    }
    private val cacheOperationTimers = CacheOperation.entries.flatMap { operation ->
        OperationOutcome.entries.map { outcome ->
            (operation to outcome) to requestTimer("welfare.serving.cache.operation")
                .tags("operation", operation.tag, "outcome", outcome.tag)
                .description("Hazelcast cache operation duration")
                .register(registry)
        }
    }.toMap()
    private val cacheFallbackCounters = CacheOperation.entries.associateWith { operation ->
        Counter.builder("welfare.serving.cache.fallback")
            .description("Hazelcast failures that fell back to the database")
            .tag("operation", operation.tag)
            .register(registry)
    }
    private val legacyCacheFallbackCounters = CacheOperation.entries.associateWith {
        registry.counter("policy.cache.fallback", "map", DETAIL_CACHE_NAME)
    }
    private val cacheLoadCounters = CacheLoadResult.entries.associateWith { result ->
        Counter.builder("welfare.serving.cache.load")
            .description("Coordination result for a policy detail cache miss")
            .tag("result", result.tag)
            .register(registry)
    }
    private val cacheLoadWaitTimers = OperationOutcome.entries.associateWith { outcome ->
        requestTimer("welfare.serving.cache.load.wait")
            .description("Time spent waiting for a coalesced cache load")
            .tag("outcome", outcome.tag)
            .register(registry)
    }
    private val epochPollTimers = EpochPollOutcome.entries.associateWith { outcome ->
        propagationTimer("welfare.serving.cache.epoch.poll")
            .description("Cache epoch polling duration")
            .tag("outcome", outcome.tag)
            .register(registry)
    }
    private val generationTransitions = Counter.builder("welfare.serving.cache.generation.transitions")
        .description("Observed cache generation changes")
        .register(registry)
    private val invalidationTimer = propagationTimer("welfare.serving.cache.invalidation")
        .description("Delay from an admin epoch update until it is observed by serving")
        .register(registry)
    private val legacyGenerationTransitions = registry.counter("policy.cache.generation.transitions")
    private val legacyEpochPollFailures = registry.counter("policy.cache.epoch.poll.failures")
    private val legacyEpochRowMissing = registry.counter("policy.cache.epoch.row.missing")

    init {
        Gauge.builder("welfare.serving.cache.generation", generation) { it.get().toDouble() }
            .description("Latest active cache epoch, or -1 while bypassed or disabled")
            .register(registry)
        Gauge.builder("policy.cache.generation", generation) { it.get().toDouble() }.register(registry)
        Gauge.builder("welfare.serving.cache.epoch.last.success.time", lastEpochPollSuccess) { it.get().toDouble() }
            .description("Unix epoch time of the latest successful cache epoch poll")
            .baseUnit("seconds")
            .register(registry)
        Gauge.builder("welfare.serving.cache.invalidation.lag", invalidationLagMillis) { it.get().toDouble() }
            .description("Latest cache invalidation propagation delay")
            .baseUnit("milliseconds")
            .register(registry)
        Gauge.builder("policy.cache.invalidation.lag.millis", invalidationLagMillis) { it.get().toDouble() }
            .register(registry)
        Gauge.builder("welfare.serving.cache.hazelcast.members", hazelcastMembers) { it.get().toDouble() }
            .description("Number of members currently visible in the Hazelcast cluster")
            .register(registry)
        Gauge.builder("policy.cache.hazelcast.member.count", hazelcastMembers) { it.get().toDouble() }
            .register(registry)
        Gauge.builder("welfare.serving.cache.load.inflight", cacheLoadInflight) { it.get().toDouble() }
            .description("Number of leader cache loads in progress in this application instance")
            .register(registry)
        cacheStates.forEach { (state, value) ->
            Gauge.builder("welfare.serving.cache.state", value) { it.get().toDouble() }
                .description("One-hot policy cache runtime state")
                .tag("state", state.tag)
                .register(registry)
        }
    }

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

    fun recordCacheRequest(result: CacheRequestResult) {
        cacheRequestCounters.getValue(result).increment()
        legacyCacheRequestCounters.getValue(result).increment()
    }

    fun <T> cacheOperation(operation: CacheOperation, block: () -> T): T {
        val sample = start()
        return try {
            block().also { sample.stop(cacheOperationTimers.getValue(operation to OperationOutcome.SUCCESS)) }
        } catch (cause: Throwable) {
            sample.stop(cacheOperationTimers.getValue(operation to OperationOutcome.FAILURE))
            cacheFallbackCounters.getValue(operation).increment()
            legacyCacheFallbackCounters.getValue(operation).increment()
            throw cause
        }
    }

    fun recordCacheLoad(result: CacheLoadResult) = cacheLoadCounters.getValue(result).increment()

    fun leaderLoadStarted() = cacheLoadInflight.incrementAndGet()

    fun leaderLoadFinished() = cacheLoadInflight.decrementAndGet()

    fun <T> waitForLoad(block: () -> T): T {
        val sample = start()
        return try {
            block().also { sample.stop(cacheLoadWaitTimers.getValue(OperationOutcome.SUCCESS)) }
        } catch (cause: Throwable) {
            sample.stop(cacheLoadWaitTimers.getValue(OperationOutcome.FAILURE))
            throw cause
        }
    }

    fun recordEpochPoll(sample: Timer.Sample, outcome: EpochPollOutcome) {
        sample.stop(epochPollTimers.getValue(outcome))
        when (outcome) {
            EpochPollOutcome.SUCCESS -> lastEpochPollSuccess.set(clock.instant().epochSecond)
            EpochPollOutcome.MISSING -> legacyEpochRowMissing.increment()
            EpochPollOutcome.FAILURE -> legacyEpochPollFailures.increment()
        }
    }

    fun recordGenerationTransition(lag: Duration) {
        val safeLag = if (lag.isNegative) Duration.ZERO else lag
        generationTransitions.increment()
        legacyGenerationTransitions.increment()
        invalidationTimer.record(safeLag)
        invalidationLagMillis.set(safeLag.toMillis())
    }

    fun setGeneration(value: Long) = generation.set(value)

    fun setCacheState(state: CacheState) {
        cacheStates.forEach { (candidate, value) -> value.set(if (candidate == state) 1 else 0) }
    }

    fun setHazelcastMembers(count: Int) = hazelcastMembers.set(count.coerceAtLeast(0))

    private fun requestTimer(name: String): Timer.Builder = Timer.builder(name).serviceLevelObjectives(*REQUEST_SLOS)

    private fun propagationTimer(name: String): Timer.Builder = Timer.builder(name).serviceLevelObjectives(*PROPAGATION_SLOS)

    private fun <A, B, C> combinations(first: Iterable<A>, second: Iterable<B>, third: Iterable<C>) =
        first.flatMap { a -> second.flatMap { b -> third.map { c -> Triple(a, b, c) } } }

    companion object {
        private const val BYPASS_GENERATION = -1L
        private const val DETAIL_CACHE_NAME = "app-policy-detail-v4"
        private val REQUEST_SLOS = longArrayOf(50, 100, 250, 500, 1_000, 2_000, 5_000).map(Duration::ofMillis).toTypedArray()
        private val PROPAGATION_SLOS = longArrayOf(10, 50, 100, 250, 500, 1_000, 2_000, 5_000).map(Duration::ofMillis).toTypedArray()
    }
}

enum class PolicyListScope(val tag: String) { ALL("all"), SIDO("sido"), SIGUNGU("sigungu") }
enum class PolicyPage(val tag: String) { FIRST("first"), NEXT("next") }
enum class PolicyListOutcome(val tag: String) { SUCCESS("success"), INVALID_REQUEST("invalid_request"), FAILURE("failure") }
enum class DetailSource(val tag: String) { CACHE("cache"), DATABASE("database"), COALESCED("coalesced"), BYPASS("bypass") }
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
enum class CacheRequestResult(val tag: String) { HIT("hit"), MISS("miss") }
enum class CacheOperation(val tag: String) { GET("get"), PUT("put") }
enum class CacheLoadResult(val tag: String) { LOADER("loader"), COALESCED("coalesced") }
enum class CacheState(val tag: String) { ACTIVE("active"), BYPASS("bypass"), DISABLED("disabled") }
enum class EpochPollOutcome(val tag: String) { SUCCESS("success"), MISSING("missing"), FAILURE("failure") }
