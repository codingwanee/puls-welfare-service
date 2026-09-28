package com.hwgeneralins.dch.puls.welfare.backend.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment

@Configuration
class KmsConfig(
    private val environment: Environment,
) {
    @Bean
    fun kmsPassword(): String? = environment.getProperty("kms.password")
}
