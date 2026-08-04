package com.hwgeneralins.dch.puls.welfare.backend.config

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.Operation
import io.swagger.v3.oas.models.PathItem
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OpenApiConfigTest {
    @Test
    fun `adds optional trace id header to every operation`() {
        val listOperation = Operation()
        val detailOperation = Operation()
        val openApi = OpenAPI().path(
            "/v1/policies",
            PathItem().get(listOperation),
        ).path(
            "/v1/policies/{id}",
            PathItem().get(detailOperation),
        )

        OpenApiConfig().traceIdHeaderOpenApiCustomizer().customise(openApi)

        listOf(listOperation, detailOperation).forEach { operation ->
            val traceId = operation.parameters.single { it.name == "X-TRACE-ID" }
            assertThat(traceId.`in`).isEqualTo("header")
            assertThat(traceId.required).isFalse()
            assertThat(traceId.schema.type).isEqualTo("string")
        }
    }
}
