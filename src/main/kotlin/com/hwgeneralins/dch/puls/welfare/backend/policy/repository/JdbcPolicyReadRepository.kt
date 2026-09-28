package com.hwgeneralins.dch.puls.welfare.backend.policy.repository

import com.hwgeneralins.dch.puls.welfare.backend.policy.domain.PolicyRecord
import com.hwgeneralins.dch.puls.welfare.backend.policy.domain.RegionRequest
import com.hwgeneralins.dch.puls.welfare.backend.policy.service.PolicySortKey
import com.hwgeneralins.dch.puls.welfare.backend.monitoring.RepositoryOperation
import com.hwgeneralins.dch.puls.welfare.backend.monitoring.ServingMonitoringMetrics
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.sql.ResultSet
import java.time.LocalDate
import java.time.LocalDateTime

@Repository
@Transactional(readOnly = true)
class JdbcPolicyReadRepository(
    private val jdbc: NamedParameterJdbcTemplate,
    private val metrics: ServingMonitoringMetrics,
) : PolicyReadRepository {
    override fun findById(id: Long): PolicyRecord? = metrics.repository(RepositoryOperation.DETAIL) {
        jdbc.query(
            "$SELECT_COLUMNS WHERE ID = :id",
            mapOf("id" to id),
            mapper,
        ).firstOrNull()
    }

    override fun findVisiblePage(
        region: RegionRequest,
        now: LocalDateTime,
        asOf: LocalDateTime,
        after: PolicySortKey?,
        limit: Int,
        internalFirst: Boolean,
    ): List<PolicyRecord> = metrics.repository(RepositoryOperation.LIST) {
        val sourceRank = if (internalFirst) {
            "CASE WHEN POL_DAT_TYP = 'INTERNAL' THEN 0 ELSE 1 END"
        } else {
            "CASE WHEN POL_DAT_TYP = 'INTERNAL' THEN 1 ELSE 0 END"
        }
        val deadlineGroup = "COALESCE(RQ_PERD_ORDR_GP_ORDR, :maxDeadlineGroup)"
        val deadlineDate = "COALESCE(RQ_PERD_ORDR_DT, :maxDeadlineDate)"
        val inputAt = "COALESCE(INP_DTHMS, :minInputAt)"
        val regionCondition = if (region.sido == null) "" else "AND $REGION"
        val keyset = if (after == null) {
            ""
        } else {
            """
            AND (
                $sourceRank > :afterSourceRank
                OR ($sourceRank = :afterSourceRank AND $deadlineGroup > :afterDeadlineGroup)
                OR ($sourceRank = :afterSourceRank AND $deadlineGroup = :afterDeadlineGroup
                    AND $deadlineDate > :afterDeadlineDate)
                OR ($sourceRank = :afterSourceRank AND $deadlineGroup = :afterDeadlineGroup
                    AND $deadlineDate = :afterDeadlineDate AND $inputAt < :afterInputAt)
                OR ($sourceRank = :afterSourceRank AND $deadlineGroup = :afterDeadlineGroup
                    AND $deadlineDate = :afterDeadlineDate AND $inputAt = :afterInputAt AND ID < :afterId)
            )
            """.trimIndent()
        }
        val params = parameters(region, now)
            .addValue("asOf", asOf)
            .addValue("maxDeadlineGroup", Int.MAX_VALUE)
            .addValue("maxDeadlineDate", MAX_DEADLINE_DATE)
            .addValue("minInputAt", MIN_INPUT_AT)
            .addValue("limit", limit)
        after?.let {
            params
                .addValue("afterSourceRank", it.sourceRank)
                .addValue("afterDeadlineGroup", it.deadlineGroup ?: Int.MAX_VALUE)
                .addValue("afterDeadlineDate", it.deadline ?: MAX_DEADLINE_DATE)
                .addValue("afterInputAt", it.inputAt ?: MIN_INPUT_AT)
                .addValue("afterId", it.id)
        }

        jdbc.query(
            """
            $SELECT_COLUMNS
             WHERE $VISIBLE
               $regionCondition
               AND (INP_DTHMS IS NULL OR INP_DTHMS <= :asOf)
               $keyset
             ORDER BY $sourceRank ASC,
                      $deadlineGroup ASC,
                      $deadlineDate ASC,
                      $inputAt DESC,
                      ID DESC
             LIMIT :limit
            """.trimIndent(),
            params,
            mapper,
        )
    }

    override fun findRegionCatalog(): Map<String, List<String>> = jdbc.query(
        """SELECT DISTINCT AP_ARE_SD, AP_ARE_SGNG FROM SL_PBC_PREG_WMN_WEFR_SRV
            WHERE AP_ARE_SD IS NOT NULL AND TRIM(AP_ARE_SD) <> '' ORDER BY AP_ARE_SD, AP_ARE_SGNG""",
    ) { rs, _ -> rs.getString(1) to rs.getString(2) }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, values) -> values.filterNotNull().filter { it.isNotBlank() }.distinct() }

    override fun countVisibleRecentlyUpdated(
        region: RegionRequest,
        now: LocalDateTime,
        updatedFrom: LocalDateTime,
        updatedThrough: LocalDateTime,
    ): Long = metrics.repository(RepositoryOperation.RECENT_UPDATE_COUNT) {
        val regionCondition = if (region.sido == null) "" else "AND $REGION"
        val params = parameters(region, now)
            .addValue("updatedFrom", updatedFrom)
            .addValue("updatedThrough", updatedThrough)

        jdbc.queryForObject(
            """
            SELECT COUNT(*)
              FROM SL_PBC_PREG_WMN_WEFR_SRV
             WHERE $VISIBLE
               $regionCondition
               AND MDF_DTHMS >= :updatedFrom
               AND MDF_DTHMS <= :updatedThrough
            """.trimIndent(),
            params,
            Long::class.java,
        ) ?: 0L
    }

    private fun parameters(region: RegionRequest, now: LocalDateTime) = MapSqlParameterSource()
        .addValue("sido", region.sido)
        .addValue("sigungu", region.sigungu)
        .addValue("now", now)

    private val mapper = RowMapper { rs: ResultSet, _: Int ->
        PolicyRecord(
            id = rs.getLong("ID"), serviceId = rs.getString("SRV_ID"), serviceName = rs.getString("SRVNM"),
            servicePurposeSummary = rs.getString("SRV_OJ_SUMM_CN"), supportTarget = rs.getString("SUPT_BJ_CN"),
            selectionCriteria = rs.getString("SLCT_ST_CN"), supportContent = rs.getString("SUPT_CN"),
            supportBenefitSummary = rs.getString("SUPT_BNFT_SUMM_CN"), supportType = rs.getString("SUPT_TYP"),
            applicationMethod = rs.getString("RQ_MTD_CN"), applicationDeadline = rs.getString("RQ_PERD_CN"),
            applicationDeadlineSortGroup = rs.getObject("RQ_PERD_ORDR_GP_ORDR", Int::class.java),
            applicationDeadlineSortDate = rs.getDate("RQ_PERD_ORDR_DT")?.toLocalDate(), documents = rs.getString("FMLZ_DCU_CN"),
            receivingAgencyName = rs.getString("RCP_ORG_NM"), contact = rs.getString("INQU_PLAC_NM"),
            onlineApplicationUrl = rs.getString("ONL_RQ_SITE_URL"), updatedDate = rs.getDate("FNL_ORTX_MDF_DTHMS")?.toLocalDate(),
            competentAgency = rs.getString("JRDT_ORG_NM"), administrativeRules = rs.getString("AMS_RULE_NM"),
            localOrdinances = rs.getString("AUTNM_RGLT_NM"), statutes = rs.getString("RGLT_NM"),
            sido = rs.getString("AP_ARE_SD"), sigungu = rs.getString("AP_ARE_SGNG"),
            policyDataType = rs.getString("POL_DAT_TYP"), policyStatus = rs.getString("POL_ST_VL"),
            exposureStartAt = rs.getTimestamp("EXPO_STR_DTHMS")?.toLocalDateTime(),
            exposureEndAt = rs.getTimestamp("EXPO_ND_DTHMS")?.toLocalDateTime(), siteUrl = rs.getString("SITE_URL"),
            inputAt = rs.getTimestamp("INP_DTHMS")?.toLocalDateTime(), modifiedAt = rs.getTimestamp("MDF_DTHMS")?.toLocalDateTime(),
        )
    }

    companion object {
        private val MAX_DEADLINE_DATE: LocalDate = LocalDate.of(9999, 12, 31)
        private val MIN_INPUT_AT: LocalDateTime = LocalDateTime.of(1000, 1, 1, 0, 0)
        private const val SELECT_COLUMNS = """SELECT ID, SRV_ID, SRVNM, SRV_OJ_SUMM_CN, SUPT_BJ_CN,
            SLCT_ST_CN, SUPT_CN, SUPT_BNFT_SUMM_CN, SUPT_TYP, RQ_MTD_CN, RQ_PERD_CN,
            RQ_PERD_ORDR_GP_ORDR, RQ_PERD_ORDR_DT, FMLZ_DCU_CN, RCP_ORG_NM, INQU_PLAC_NM,
            ONL_RQ_SITE_URL, FNL_ORTX_MDF_DTHMS, JRDT_ORG_NM, AMS_RULE_NM, AUTNM_RGLT_NM,
            RGLT_NM, AP_ARE_SD, AP_ARE_SGNG, POL_DAT_TYP, POL_ST_VL, EXPO_STR_DTHMS,
            EXPO_ND_DTHMS, SITE_URL, INP_DTHMS, MDF_DTHMS FROM SL_PBC_PREG_WMN_WEFR_SRV"""
        private const val VISIBLE = """POL_ST_VL = 'PUBLISHED'
            AND (EXPO_STR_DTHMS IS NULL OR EXPO_STR_DTHMS <= :now)
            AND (EXPO_ND_DTHMS IS NULL OR EXPO_ND_DTHMS > :now)"""
        private const val REGION = """(((AP_ARE_SD IS NULL OR TRIM(AP_ARE_SD) = '')
            AND (AP_ARE_SGNG IS NULL OR TRIM(AP_ARE_SGNG) = ''))
            OR (AP_ARE_SD = :sido AND (:sigungu IS NULL
                OR AP_ARE_SGNG IS NULL OR TRIM(AP_ARE_SGNG) = '' OR AP_ARE_SGNG = :sigungu)))"""
    }
}
