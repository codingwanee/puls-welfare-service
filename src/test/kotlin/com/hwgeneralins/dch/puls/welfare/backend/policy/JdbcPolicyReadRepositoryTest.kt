package com.hwgeneralins.dch.puls.welfare.backend.policy

import com.hwgeneralins.dch.puls.welfare.backend.policy.domain.RegionRequest
import com.hwgeneralins.dch.puls.welfare.backend.policy.repository.JdbcPolicyReadRepository
import com.hwgeneralins.dch.puls.welfare.backend.policy.service.PolicySortKey
import com.hwgeneralins.dch.puls.welfare.backend.monitoring.ServingMonitoringMetrics
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.time.LocalDateTime
import java.time.Clock

class JdbcPolicyReadRepositoryTest {
    private val jdbc = JdbcTemplate(DriverManagerDataSource("jdbc:h2:mem:region-test;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""))
    private val repository = JdbcPolicyReadRepository(
        NamedParameterJdbcTemplate(jdbc),
        ServingMonitoringMetrics(SimpleMeterRegistry(), Clock.systemUTC()),
    )

    @BeforeEach
    fun setUp() {
        jdbc.execute("DROP TABLE IF EXISTS SL_PBC_PREG_WMN_WEFR_SRV")
        jdbc.execute(CREATE_TABLE)
        insert(1, null, " ", "PUBLISHED")
        insert(2, "서울특별시", null, "PUBLISHED")
        insert(3, "서울특별시", "마포구", "PUBLISHED")
        insert(4, "서울특별시", "강남구", "PUBLISHED")
        insert(5, "서울특별시", "마포구", "PRIVATE")
        insert(6, "서울특별시", "마포구", "PUBLISHED")
        jdbc.update("UPDATE SL_PBC_PREG_WMN_WEFR_SRV SET EXPO_STR_DTHMS = TIMESTAMP '2026-08-01 00:00:00' WHERE ID = 6")
        insert(7, "서울특별시", "마포구", "PUBLISHED")
        jdbc.update("UPDATE SL_PBC_PREG_WMN_WEFR_SRV SET EXPO_ND_DTHMS = TIMESTAMP '2026-07-01 00:00:00' WHERE ID = 7")
        insert(8, "부산광역시", "해운대구", "PUBLISHED")
    }

    @Test
    fun `region includes nationwide sido-wide and exact sigungu only`() {
        val now = LocalDateTime.of(2026, 7, 30, 12, 0)
        assertThat(
            repository.findVisiblePage(
                RegionRequest("서울특별시", "마포구"), now, now,
                after = null, limit = 100, internalFirst = true,
            ).map { it.id },
        )
            .containsExactlyInAnyOrder(1, 2, 3)
    }

    @Test
    fun `sido without sigungu includes every policy under the sido`() {
        val now = LocalDateTime.of(2026, 7, 30, 12, 0)

        assertThat(
            repository.findVisiblePage(
                RegionRequest("서울특별시", null), now, now,
                after = null, limit = 100, internalFirst = true,
            ).map { it.id },
        ).containsExactlyInAnyOrder(1, 2, 3, 4)
    }

    @Test
    fun `empty region includes every visible policy`() {
        val now = LocalDateTime.of(2026, 7, 30, 12, 0)

        assertThat(
            repository.findVisiblePage(
                RegionRequest(null, null), now, now,
                after = null, limit = 100, internalFirst = true,
            ).map { it.id },
        ).containsExactlyInAnyOrder(1, 2, 3, 4, 8)
    }

    @Test
    fun `database applies deterministic keyset ordering and limit`() {
        val now = LocalDateTime.of(2026, 7, 30, 12, 0)
        jdbc.update("UPDATE SL_PBC_PREG_WMN_WEFR_SRV SET POL_DAT_TYP = 'INTERNAL' WHERE ID = 1")

        val first = repository.findVisiblePage(
            RegionRequest("서울특별시", "마포구"), now, now,
            after = null, limit = 2, internalFirst = true,
        )
        val second = repository.findVisiblePage(
            RegionRequest("서울특별시", "마포구"), now, now,
            after = PolicySortKey(
                sourceRank = 1,
                deadline = null,
                deadlineGroup = null,
                inputAt = LocalDateTime.of(2026, 7, 1, 0, 0),
                id = 3,
            ),
            limit = 2,
            internalFirst = true,
        )

        assertThat(first.map { it.id }).containsExactly(1, 3)
        assertThat(second.map { it.id }).containsExactly(2)
    }

    private fun insert(id: Long, sido: String?, sigungu: String?, status: String) {
        jdbc.update(
            """INSERT INTO SL_PBC_PREG_WMN_WEFR_SRV
                (ID, SRV_ID, SRVNM, AP_ARE_SD, AP_ARE_SGNG, POL_DAT_TYP, POL_ST_VL, INP_DTHMS)
                VALUES (?, ?, ?, ?, ?, 'EXTERNAL', ?, TIMESTAMP '2026-07-01 00:00:00')""",
            id, "S-$id", "정책 $id", sido, sigungu, status,
        )
    }

    companion object {
        private val CREATE_TABLE = """CREATE TABLE SL_PBC_PREG_WMN_WEFR_SRV (
            ID BIGINT PRIMARY KEY, SRV_ID VARCHAR(64), SRVNM VARCHAR(255), SRV_OJ_SUMM_CN VARCHAR(255),
            SUPT_BJ_CN CLOB, SLCT_ST_CN CLOB, SUPT_CN CLOB, SUPT_BNFT_SUMM_CN VARCHAR(255), SUPT_TYP VARCHAR(255),
            RQ_MTD_CN CLOB, RQ_PERD_CN VARCHAR(512), RQ_PERD_ORDR_GP_ORDR INT, RQ_PERD_ORDR_DT DATE,
            FMLZ_DCU_CN CLOB, RCP_ORG_NM VARCHAR(255), INQU_PLAC_NM CLOB, ONL_RQ_SITE_URL VARCHAR(512),
            FNL_ORTX_MDF_DTHMS DATE, JRDT_ORG_NM VARCHAR(255), AMS_RULE_NM VARCHAR(512), AUTNM_RGLT_NM VARCHAR(512),
            RGLT_NM VARCHAR(1000), AP_ARE_SD VARCHAR(64), AP_ARE_SGNG VARCHAR(64), POL_DAT_TYP VARCHAR(16),
            POL_ST_VL VARCHAR(32), EXPO_STR_DTHMS TIMESTAMP, EXPO_ND_DTHMS TIMESTAMP, SITE_URL VARCHAR(512),
            INP_DTHMS TIMESTAMP, MDF_DTHMS TIMESTAMP)"""
    }
}
