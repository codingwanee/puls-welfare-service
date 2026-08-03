package com.hwgeneralins.dch.puls.welfare.backend.policy

import com.hwgeneralins.dch.puls.welfare.backend.config.PolicyProperties
import com.hwgeneralins.dch.puls.welfare.backend.policyRecord
import com.hwgeneralins.dch.puls.welfare.backend.policy.service.PolicySortStrategy
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalDateTime

class PolicySortStrategyTest {
    private val strategy = PolicySortStrategy(PolicyProperties("https://x/{policyVersionId}"))

    @Test
    fun `internal then group then deadline then newest input and id`() {
        val policies = listOf(
            policyRecord(id = 1, type = "EXTERNAL", group = 1),
            policyRecord(id = 2, type = "INTERNAL", group = 2, deadline = LocalDate.of(2026, 7, 1)),
            policyRecord(id = 3, type = "INTERNAL", group = 1, deadline = LocalDate.of(2026, 9, 1)),
            policyRecord(id = 4, type = "INTERNAL", group = 1, deadline = LocalDate.of(2026, 8, 1), inputAt = LocalDateTime.of(2026, 7, 2, 0, 0)),
            policyRecord(id = 5, type = "INTERNAL", group = 1, deadline = LocalDate.of(2026, 8, 1), inputAt = LocalDateTime.of(2026, 7, 2, 0, 0)),
        )
        assertThat(policies.sortedWith(strategy.comparator).map { it.id }).containsExactly(5, 4, 3, 2, 1)
    }
}
