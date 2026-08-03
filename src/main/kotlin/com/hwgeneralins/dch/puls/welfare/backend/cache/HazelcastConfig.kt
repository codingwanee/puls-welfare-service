package com.hwgeneralins.dch.puls.welfare.backend.cache

import com.hazelcast.config.Config
import com.hazelcast.config.EvictionConfig
import com.hazelcast.config.EvictionPolicy
import com.hazelcast.config.MapConfig
import com.hazelcast.config.MaxSizePolicy
import com.hazelcast.core.Hazelcast
import com.hazelcast.core.HazelcastInstance
import com.hwgeneralins.dch.puls.welfare.backend.config.PolicyCacheProperties
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
@ConditionalOnProperty(prefix = "app.cache", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class HazelcastConfig {
    @Bean(destroyMethod = "shutdown")
    fun hazelcastInstance(properties: PolicyCacheProperties): HazelcastInstance {
        val config = Config().apply {
            clusterName = properties.clusterName
            networkConfig.port = properties.port
            networkConfig.isPortAutoIncrement = properties.portAutoIncrement
            networkConfig.join.multicastConfig.isEnabled = false
            networkConfig.join.autoDetectionConfig.isEnabled = false
            networkConfig.join.tcpIpConfig.apply {
                isEnabled = properties.tcpIpEnabled
                members = properties.members
            }
            addMapConfig(mapConfig(PolicyCacheMaps.DETAIL_V2, properties.detailTtlSeconds, properties))
        }
        return Hazelcast.newHazelcastInstance(config)
    }

    private fun mapConfig(name: String, ttlSeconds: Long, properties: PolicyCacheProperties) = MapConfig(name).apply {
        backupCount = properties.backupCount
        timeToLiveSeconds = ttlSeconds.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        evictionConfig = EvictionConfig().apply {
            evictionPolicy = EvictionPolicy.LRU
            maxSizePolicy = MaxSizePolicy.PER_NODE
            size = properties.maxSize
        }
    }
}

object PolicyCacheMaps {
    const val DETAIL_V2 = "app-policy-detail-v2"
    const val KEY_PREFIX = "v2:"
}
