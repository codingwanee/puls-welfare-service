-- App read-query indexes only.
--
-- Canonical epoch table/seed/GRANT DDL lives in the admin repository:
--   ../hgi-dch-clovr-welfare-admin-backend/docs/db/migration/V20260730_01__create_policy_cache_epoch.sql
-- Apply that migration first. Do not define a second copy of the epoch table here.
-- Confirm index names and existing indexes with the DBA before applying this file once.

CREATE INDEX I06_SL_PBC_PREG_WMN_WEFR_SRV_APP
    ON SL_PBC_PREG_WMN_WEFR_SRV
       (POL_ST_VL, AP_ARE_SD, AP_ARE_SGNG, POL_DAT_TYP,
        RQ_PERD_ORDR_GP_ORDR, RQ_PERD_ORDR_DT, INP_DTHMS, ID);

CREATE INDEX I07_SL_PBC_PREG_WMN_WEFR_SRV_EXPO
    ON SL_PBC_PREG_WMN_WEFR_SRV (POL_ST_VL, EXPO_STR_DTHMS, EXPO_ND_DTHMS);
