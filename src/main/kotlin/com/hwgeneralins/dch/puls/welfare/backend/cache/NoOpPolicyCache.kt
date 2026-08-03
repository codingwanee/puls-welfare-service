package com.hwgeneralins.dch.puls.welfare.backend.cache

import com.hwgeneralins.dch.puls.welfare.backend.policy.domain.CacheEnvelope
import com.hwgeneralins.dch.puls.welfare.backend.policy.domain.PolicyDetailDto
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(prefix = "app.cache", name = ["enabled"], havingValue = "false")
class NoOpPolicyCache : PolicyCache {
    override fun getDetail(key: String): CacheEnvelope<PolicyDetailDto>? = null
    override fun putDetail(key: String, value: CacheEnvelope<PolicyDetailDto>) = Unit
}
