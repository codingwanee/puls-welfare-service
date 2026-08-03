package com.hwgeneralins.dch.puls.welfare.backend.policy.api

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.time.Instant

enum class BusinessErrorCode { POLICY_NOT_FOUND, POLICY_NOT_AVAILABLE, INVALID_CURSOR, INVALID_REGION }

class PolicyApiException(
    val status: HttpStatus,
    val businessCode: BusinessErrorCode,
    override val message: String,
) : RuntimeException(message)

data class ApiError(val businessCode: String, val message: String, val timestamp: Instant = Instant.now())

@RestControllerAdvice
class GlobalExceptionHandler {
    @ExceptionHandler(PolicyApiException::class)
    fun policyException(exception: PolicyApiException): ResponseEntity<ApiError> =
        ResponseEntity.status(exception.status)
            .body(ApiError(exception.businessCode.name, exception.message))
}
