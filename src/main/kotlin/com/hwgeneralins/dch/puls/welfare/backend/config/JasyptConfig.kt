package com.hwgeneralins.dch.puls.welfare.backend.config

import com.ulisesbocchio.jasyptspringboot.annotation.EnableEncryptableProperties
import org.jasypt.encryption.StringEncryptor
import org.jasypt.encryption.pbe.PooledPBEStringEncryptor
import org.jasypt.encryption.pbe.config.SimpleStringPBEConfig
import org.jasypt.iv.RandomIvGenerator
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
@EnableEncryptableProperties
class JasyptConfig(
    private val kmsPassword: String?,
) {
    @Bean
    fun jasyptStringEncryptor(): StringEncryptor {
        val password = kmsPassword?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("kms.password must be configured")
        val encryptor = PooledPBEStringEncryptor()
        val config = copyConfig().apply {
            this.password = password
            poolSize = 2
        }
        encryptor.setConfig(config)
        return encryptor
    }

    companion object {
        fun copyConfig(): SimpleStringPBEConfig =
            SimpleStringPBEConfig().apply {
                algorithm = "PBEWithHMACSHA512AndAES_256"
                keyObtentionIterations = 100_000
                stringOutputType = "base64"
                ivGenerator = RandomIvGenerator()
                providerName = "SunJCE"
            }
    }
}
