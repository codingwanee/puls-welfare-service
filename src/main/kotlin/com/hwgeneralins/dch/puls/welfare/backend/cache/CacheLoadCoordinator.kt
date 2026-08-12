package com.hwgeneralins.dch.puls.welfare.backend.cache

import com.hwgeneralins.dch.puls.welfare.backend.monitoring.CacheLoadResult
import com.hwgeneralins.dch.puls.welfare.backend.monitoring.DetailSource
import com.hwgeneralins.dch.puls.welfare.backend.monitoring.ServingMonitoringMetrics
import org.springframework.stereotype.Component
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ConcurrentHashMap

/**
 * Collapses concurrent cache misses for the same generation-qualified key inside one app instance.
 * Hazelcast remains the shared cache; this only prevents a local burst from issuing duplicate DB reads.
 */
@Component
class CacheLoadCoordinator(private val metrics: ServingMonitoringMetrics) {
    private val inFlight = ConcurrentHashMap<String, CompletableFuture<Any>>()

    fun <T : Any> getOrLoad(
        key: String,
        cached: () -> T?,
        loader: () -> T,
        source: (DetailSource) -> Unit = {},
    ): T {
        cached()?.let {
            source(DetailSource.CACHE)
            return it
        }

        val candidate = CompletableFuture<Any>()
        val existing = inFlight.putIfAbsent(key, candidate)
        if (existing != null) {
            source(DetailSource.COALESCED)
            metrics.recordCacheLoad(CacheLoadResult.COALESCED)
            return metrics.waitForLoad { await(existing) }
        }

        metrics.leaderLoadStarted()
        return try {
            val cachedValue = cached()
            val value = if (cachedValue != null) {
                source(DetailSource.CACHE)
                cachedValue
            } else {
                source(DetailSource.DATABASE)
                metrics.recordCacheLoad(CacheLoadResult.LOADER)
                loader()
            }
            candidate.complete(value)
            value
        } catch (cause: Throwable) {
            candidate.completeExceptionally(cause)
            throw cause
        } finally {
            inFlight.remove(key, candidate)
            metrics.leaderLoadFinished()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> await(future: CompletableFuture<Any>): T = try {
        future.join() as T
    } catch (exception: CompletionException) {
        throw exception.cause ?: exception
    }
}
