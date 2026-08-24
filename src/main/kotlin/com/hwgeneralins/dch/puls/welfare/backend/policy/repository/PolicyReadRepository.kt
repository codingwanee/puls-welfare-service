package com.hwgeneralins.dch.puls.welfare.backend.policy.repository

import com.hwgeneralins.dch.puls.welfare.backend.policy.domain.PolicyRecord
import com.hwgeneralins.dch.puls.welfare.backend.policy.domain.RegionRequest
import com.hwgeneralins.dch.puls.welfare.backend.policy.service.PolicySortKey
import java.time.LocalDateTime

interface PolicyReadRepository {
    fun findById(id: Long): PolicyRecord?
    fun findVisiblePage(
        region: RegionRequest,
        now: LocalDateTime,
        asOf: LocalDateTime,
        after: PolicySortKey?,
        limit: Int,
        internalFirst: Boolean,
    ): List<PolicyRecord>
    fun countVisibleRecentlyUpdated(
        region: RegionRequest,
        now: LocalDateTime,
        updatedFrom: LocalDateTime,
        updatedThrough: LocalDateTime,
    ): Long
    fun findRegionCatalog(): Map<String, List<String>>
}
