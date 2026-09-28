package com.hwgeneralins.dch.puls.welfare.backend.config

import io.swagger.v3.oas.models.media.StringSchema
import io.swagger.v3.oas.models.parameters.Parameter
import org.springdoc.core.customizers.OpenApiCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class OpenApiConfig {
    @Bean
    fun traceIdHeaderOpenApiCustomizer(): OpenApiCustomizer = OpenApiCustomizer { openApi ->
        openApi.paths.orEmpty().values
            .flatMap { it.readOperations() }
            .forEach { operation ->
                val parameters = operation.parameters
                    ?: mutableListOf<Parameter>().also { operation.parameters = it }
                if (parameters.none { it.`in` == HEADER && it.name.equals(TRACE_ID_HEADER, ignoreCase = true) }) {
                    parameters += Parameter()
                        .name(TRACE_ID_HEADER)
                        .`in`(HEADER)
                        .required(false)
                        .description("요청 추적 ID. 생략하면 로그에 빈 값으로 기록됩니다.")
                        .example("client-trace-id")
                        .schema(StringSchema())
                }
            }
    }

    private companion object {
        const val TRACE_ID_HEADER = "X-TRACE-ID"
        const val HEADER = "header"
    }
}
