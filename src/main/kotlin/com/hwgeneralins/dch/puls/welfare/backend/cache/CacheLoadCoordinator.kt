package com.hwgeneralins.dch.puls.welfare.backend.cache

import org.springframework.stereotype.Component
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ConcurrentHashMap

/**
 * Collapses concurrent cache misses for the same generation-qualified key inside one app instance.
 * Hazelcast remains the shared cache; this only prevents a local burst from issuing duplicate DB reads.
 */
@Component
class CacheLoadCoordinator {
    private val inFlight = ConcurrentHashMap<String, CompletableFuture<Any>>()

    fun <T : Any> getOrLoad(key: String, cached: () -> T?, loader: () -> T): T {
        cached()?.let { return it }

        val candidate = CompletableFuture<Any>()
        val existing = inFlight.putIfAbsent(key, candidate)
        if (existing != null) {
            return await(existing)
        }

        return try {
            val value = cached() ?: loader()
            candidate.complete(value)
            value
        } catch (cause: Throwable) {
            candidate.completeExceptionally(cause)
            throw cause
        } finally {
            inFlight.remove(key, candidate)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> await(future: CompletableFuture<Any>): T = try {
        future.join() as T
    } catch (exception: CompletionException) {
        throw exception.cause ?: exception
    }
}
