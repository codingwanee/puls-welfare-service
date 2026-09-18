package com.hwgeneralins.dch.puls.welfare.backend.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("app.policy")
data class PolicyProperties(
    val sort: SortProperties = SortProperties(),
) {
    data class SortProperties(
        val version: String = "v1",
        val internalFirst: Boolean = true,
    )
}
