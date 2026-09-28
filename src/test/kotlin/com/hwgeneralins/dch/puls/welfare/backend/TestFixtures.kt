package com.hwgeneralins.dch.puls.welfare.backend

import com.hwgeneralins.dch.puls.welfare.backend.policy.domain.PolicyRecord
import java.time.LocalDate
import java.time.LocalDateTime

fun policyRecord(
    id: Long = 1,
    type: String = "EXTERNAL",
    status: String = "PUBLISHED",
    group: Int? = 1,
    deadline: LocalDate? = LocalDate.of(2026, 8, 1),
    inputAt: LocalDateTime? = LocalDateTime.of(2026, 7, 1, 9, 0),
    start: LocalDateTime? = null,
    end: LocalDateTime? = null,
) = PolicyRecord(
    id, "S-$id", "정책 $id", "한 줄 설명", "지원 대상", "선정 기준", "지원 내용", "30만원", "현금",
    "온라인 신청", "2026-08-01", group, deadline, "신분증", "접수 기관", "02-1234-5678",
    "https://apply.example/$id", LocalDate.of(2026, 7, 1), "소관 기관", "행정 규칙", "자치 법규", "법령",
    "서울특별시", "마포구", type, status, start, end, "https://site.example/$id", inputAt, inputAt,
)
