package com.hwgeneralins.dch.puls.welfare.backend.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("app.policy")
data class PolicyProperties(
    val defaultSido: String = "서울특별시",
    val sort: SortProperties = SortProperties(),
) {
    data class SortProperties(
        val version: String = "v1",
        val internalFirst: Boolean = true,
    )
}

@ConfigurationProperties("app.cache")
data class PolicyCacheProperties(
    val enabled: Boolean = true,
    val clusterName: String = "clovr-welfare-local",
    val tcpIpEnabled: Boolean = true,
    val members: List<String> = listOf("127.0.0.1:5701", "127.0.0.1:5702"),
    val port: Int = 5701,
    val portAutoIncrement: Boolean = true,
    val backupCount: Int = 1,
    val maxSize: Int = 10_000,
    val detailTtlSeconds: Long = 300,
    val epochPollMillis: Long = 1_000,
    val epochPollMaxStaleMillis: Long = 5_000,
)
