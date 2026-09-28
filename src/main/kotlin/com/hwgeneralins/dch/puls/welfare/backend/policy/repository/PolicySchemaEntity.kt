package com.hwgeneralins.dch.puls.welfare.backend.policy.repository

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.Immutable
import java.time.LocalDateTime

/**
 * Hibernate ddl-auto=validate가 핵심 테이블/가시성 컬럼 계약을 검증하기 위한 read-only entity.
 * 조회/API는 이 엔티티가 아니라 [PolicyRecord] JDBC read model과 DTO만 사용한다.
 */
@Entity
@Immutable
@Table(name = "SL_PBC_PREG_WMN_WEFR_SRV")
internal class PolicySchemaEntity(
    @Id @Column(name = "ID") val id: Long = 0,
    @Column(name = "POL_ST_VL") val status: String = "",
    @Column(name = "POL_DAT_TYP") val dataType: String = "",
    @Column(name = "EXPO_STR_DTHMS") val exposureStartAt: LocalDateTime? = null,
    @Column(name = "EXPO_ND_DTHMS") val exposureEndAt: LocalDateTime? = null,
    @Column(name = "AP_ARE_SD") val sido: String? = null,
    @Column(name = "AP_ARE_SGNG") val sigungu: String? = null,
)
