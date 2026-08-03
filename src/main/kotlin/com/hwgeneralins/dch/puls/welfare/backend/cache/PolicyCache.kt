package com.hwgeneralins.dch.puls.welfare.backend.cache

import com.hwgeneralins.dch.puls.welfare.backend.policy.domain.CacheEnvelope
import com.hwgeneralins.dch.puls.welfare.backend.policy.domain.PolicyDetailDto

interface PolicyCache {
    fun getDetail(key: String): CacheEnvelope<PolicyDetailDto>?
    fun putDetail(key: String, value: CacheEnvelope<PolicyDetailDto>)
}
