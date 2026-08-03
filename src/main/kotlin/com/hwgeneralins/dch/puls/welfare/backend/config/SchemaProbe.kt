package com.hwgeneralins.dch.puls.welfare.backend.config

import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/** JDBC read model을 쓰더라도 운영 시작 시 필수 스키마 계약을 실제 DB에 검증한다. */
@Component
class SchemaProbe(private val jdbc: JdbcTemplate) : ApplicationRunner {
    override fun run(args: ApplicationArguments) {
        jdbc.query(
            """SELECT ID, SRV_ID, SRVNM, SRV_OJ_SUMM_CN, SUPT_BJ_CN, SLCT_ST_CN, SUPT_CN,
                SUPT_BNFT_SUMM_CN, SUPT_TYP, RQ_MTD_CN, RQ_PERD_CN, RQ_PERD_ORDR_GP_ORDR,
                RQ_PERD_ORDR_DT, FMLZ_DCU_CN, RCP_ORG_NM, INQU_PLAC_NM, ONL_RQ_SITE_URL,
                FNL_ORTX_MDF_DTHMS, JRDT_ORG_NM, AMS_RULE_NM, AUTNM_RGLT_NM, RGLT_NM,
                AP_ARE_SD, AP_ARE_SGNG, POL_DAT_TYP, POL_ST_VL, EXPO_STR_DTHMS,
                EXPO_ND_DTHMS, SITE_URL, INP_DTHMS, MDF_DTHMS
                FROM SL_PBC_PREG_WMN_WEFR_SRV WHERE 1 = 0""",
        ) { _ -> Unit }
    }
}

/** 캐시 사용 시에만 durable epoch 스키마 계약을 검증한다. */
@Component
@ConditionalOnProperty(prefix = "app.cache", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class CacheEpochSchemaProbe(private val jdbc: JdbcTemplate) : ApplicationRunner {
    override fun run(args: ApplicationArguments) {
        jdbc.query("SELECT CACHE_NAME, EPOCH, MDF_DTHMS FROM SL_PBC_POLICY_CACHE_EPOCH WHERE 1 = 0") { _ -> Unit }
    }
}
