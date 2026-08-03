package com.hwgeneralins.dch.puls.welfare.backend.policy.service

import com.hwgeneralins.dch.puls.welfare.backend.config.PolicyProperties
import com.hwgeneralins.dch.puls.welfare.backend.policy.domain.PolicyRecord
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.LocalDateTime

data class PolicySortKey(
    val sourceRank: Int,
    val deadline: LocalDate?,
    val deadlineGroup: Int?,
    val inputAt: LocalDateTime?,
    val id: Long,
)

@Component
class PolicySortStrategy(private val properties: PolicyProperties) {
    val version: String get() = properties.sort.version

    fun key(policy: PolicyRecord) = PolicySortKey(
        sourceRank = if ((policy.policyDataType == "INTERNAL") == properties.sort.internalFirst) 0 else 1,
        deadline = policy.applicationDeadlineSortDate,
        deadlineGroup = policy.applicationDeadlineSortGroup,
        inputAt = policy.inputAt,
        id = policy.id,
    )

    val comparator: Comparator<PolicyRecord> = Comparator { left, right -> compare(key(left), key(right)) }

    fun compare(left: PolicySortKey, right: PolicySortKey): Int {
        compareValues(left.sourceRank, right.sourceRank).takeIf { it != 0 }?.let { return it }
        compareValues(left.deadlineGroup ?: Int.MAX_VALUE, right.deadlineGroup ?: Int.MAX_VALUE)
            .takeIf { it != 0 }?.let { return it }
        compareValues(left.deadline == null, right.deadline == null).takeIf { it != 0 }?.let { return it }
        compareValues(left.deadline, right.deadline).takeIf { it != 0 }?.let { return it }
        compareValues(right.inputAt, left.inputAt).takeIf { it != 0 }?.let { return it }
        return compareValues(right.id, left.id)
    }
}
