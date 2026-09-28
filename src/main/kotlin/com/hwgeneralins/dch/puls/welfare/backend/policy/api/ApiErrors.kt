package com.hwgeneralins.dch.puls.welfare.backend.policy.api

import com.fasterxml.jackson.annotation.JsonInclude
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.ConstraintViolationException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.validation.BindException
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.bind.MissingRequestHeaderException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.HandlerMethodValidationException
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.servlet.NoHandlerFoundException
import org.springframework.web.servlet.resource.NoResourceFoundException
import java.time.OffsetDateTime

enum class BusinessErrorCode {
    POLICY_NOT_FOUND,
    POLICY_NOT_AVAILABLE,
    INVALID_CURSOR,
    INVALID_REGION,
    INVALID_UPDATE_DAYS,
}

class PolicyApiException(
    val status: HttpStatus,
    val businessCode: BusinessErrorCode,
    override val message: String,
) : RuntimeException(message)

@JsonInclude(JsonInclude.Include.ALWAYS)
@Schema(description = "API 오류 응답")
data class ApiErrorResponse(
    @field:Schema(description = "오류 발생시각", example = "2026-08-04T10:30:00+09:00", type = "string", format = "date-time")
    val timestamp: OffsetDateTime,
    @field:Schema(description = "HTTP 상태 코드", example = "400")
    val status: Int,
    @field:Schema(description = "오류 코드", example = "INVALID_REGION")
    val code: String,
    @field:Schema(description = "오류 메시지", example = "유효하지 않은 지역입니다.")
    val message: String,
    @field:Schema(description = "오류 상세 메시지", nullable = true)
    val detail: String? = null,
    @field:Schema(description = "요청 경로", example = "/api/wsbd/v1/welfare-policies")
    val path: String,
    @field:Schema(description = "필드별 검증 오류")
    val errors: List<ApiFieldError> = emptyList(),
)

@JsonInclude(JsonInclude.Include.ALWAYS)
@Schema(description = "필드 검증 오류")
data class ApiFieldError(
    @field:Schema(description = "필드명", example = "limit")
    val field: String,
    @field:Schema(description = "거절된 값", nullable = true, example = "101")
    val rejectedValue: Any? = null,
    @field:Schema(description = "검증 메시지", example = "페이지 크기는 1~100 범위여야 합니다.")
    val message: String,
)

@RestControllerAdvice
class GlobalExceptionHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(PolicyApiException::class)
    fun handlePolicyApiException(
        exception: PolicyApiException,
        request: HttpServletRequest,
    ): ResponseEntity<ApiErrorResponse> =
        response(
            status = exception.status,
            code = exception.businessCode.name,
            message = exception.message,
            request = request,
        )

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleMethodArgumentNotValidException(
        exception: MethodArgumentNotValidException,
        request: HttpServletRequest,
    ): ResponseEntity<ApiErrorResponse> = bindingValidationResponse(exception, request)

    @ExceptionHandler(BindException::class)
    fun handleBindException(
        exception: BindException,
        request: HttpServletRequest,
    ): ResponseEntity<ApiErrorResponse> = bindingValidationResponse(exception, request)

    @ExceptionHandler(HandlerMethodValidationException::class)
    fun handleMethodValidationException(
        exception: HandlerMethodValidationException,
        request: HttpServletRequest,
    ): ResponseEntity<ApiErrorResponse> =
        validationResponse(
            errors = exception.parameterValidationResults.flatMap { result ->
                result.resolvableErrors.map { error ->
                    ApiFieldError(
                        field = result.methodParameter.parameterName ?: "parameter",
                        message = error.defaultMessage ?: INVALID_FIELD_MESSAGE,
                    )
                }
            },
            request = request,
        )

    @ExceptionHandler(ConstraintViolationException::class)
    fun handleConstraintViolationException(
        exception: ConstraintViolationException,
        request: HttpServletRequest,
    ): ResponseEntity<ApiErrorResponse> =
        validationResponse(
            errors = exception.constraintViolations.map { violation ->
                ApiFieldError(
                    field = violation.propertyPath.toString().substringAfterLast('.'),
                    rejectedValue = violation.invalidValue,
                    message = violation.message,
                )
            },
            request = request,
        )

    @ExceptionHandler(
        MethodArgumentTypeMismatchException::class,
        MissingRequestHeaderException::class,
        MissingServletRequestParameterException::class,
        HttpMessageNotReadableException::class,
    )
    fun handleInvalidRequest(
        exception: Exception,
        request: HttpServletRequest,
    ): ResponseEntity<ApiErrorResponse> =
        response(
            status = HttpStatus.BAD_REQUEST,
            code = INVALID_REQUEST,
            message = INVALID_REQUEST_MESSAGE,
            detail = invalidRequestDetail(exception),
            request = request,
        )

    @ExceptionHandler(NoResourceFoundException::class, NoHandlerFoundException::class)
    fun handleNotFound(
        exception: Exception,
        request: HttpServletRequest,
    ): ResponseEntity<ApiErrorResponse> =
        response(
            status = HttpStatus.NOT_FOUND,
            code = NOT_FOUND,
            message = NOT_FOUND_MESSAGE,
            detail = exception.message,
            request = request,
        )

    @ExceptionHandler(HttpRequestMethodNotSupportedException::class)
    fun handleMethodNotSupported(
        exception: HttpRequestMethodNotSupportedException,
        request: HttpServletRequest,
    ): ResponseEntity<ApiErrorResponse> =
        response(
            status = HttpStatus.METHOD_NOT_ALLOWED,
            code = METHOD_NOT_ALLOWED,
            message = METHOD_NOT_ALLOWED_MESSAGE,
            detail = exception.message,
            request = request,
        )

    @ExceptionHandler(Exception::class)
    fun handleUnexpectedException(
        exception: Exception,
        request: HttpServletRequest,
    ): ResponseEntity<ApiErrorResponse> {
        log.error("Unhandled API exception. method={}, path={}", request.method, request.requestURI, exception)
        return response(
            status = HttpStatus.INTERNAL_SERVER_ERROR,
            code = INTERNAL_SERVER_ERROR,
            message = INTERNAL_SERVER_ERROR_MESSAGE,
            request = request,
        )
    }

    private fun invalidRequestDetail(exception: Exception): String =
        when (exception) {
            is MethodArgumentTypeMismatchException ->
                "${exception.name} 값 '${exception.value}'의 형식이 올바르지 않습니다."
            is MissingRequestHeaderException ->
                "필수 요청 헤더 '${exception.headerName}'가 없습니다."
            is MissingServletRequestParameterException ->
                "필수 요청 파라미터 '${exception.parameterName}'가 없습니다."
            is HttpMessageNotReadableException -> "요청 본문을 읽을 수 없습니다. JSON 형식을 확인해 주세요."
            else -> INVALID_REQUEST_MESSAGE
        }

    private fun validationResponse(
        errors: List<ApiFieldError>,
        request: HttpServletRequest,
    ): ResponseEntity<ApiErrorResponse> =
        response(
            status = HttpStatus.BAD_REQUEST,
            code = VALIDATION_FAILED,
            message = VALIDATION_FAILED_MESSAGE,
            errors = errors,
            request = request,
        )

    private fun bindingValidationResponse(
        exception: BindException,
        request: HttpServletRequest,
    ): ResponseEntity<ApiErrorResponse> =
        validationResponse(
            errors = exception.bindingResult.fieldErrors.map { error ->
                ApiFieldError(
                    field = error.field,
                    rejectedValue = error.rejectedValue,
                    message = error.defaultMessage ?: INVALID_FIELD_MESSAGE,
                )
            },
            request = request,
        )

    private fun response(
        status: HttpStatus,
        code: String,
        message: String,
        request: HttpServletRequest,
        detail: String? = null,
        errors: List<ApiFieldError> = emptyList(),
    ): ResponseEntity<ApiErrorResponse> =
        ResponseEntity
            .status(status)
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                ApiErrorResponse(
                    timestamp = OffsetDateTime.now(),
                    status = status.value(),
                    code = code,
                    message = message,
                    detail = detail,
                    path = request.requestURI,
                    errors = errors,
                ),
            )

    private companion object {
        const val INVALID_REQUEST = "INVALID_REQUEST"
        const val VALIDATION_FAILED = "VALIDATION_FAILED"
        const val NOT_FOUND = "NOT_FOUND"
        const val METHOD_NOT_ALLOWED = "METHOD_NOT_ALLOWED"
        const val INTERNAL_SERVER_ERROR = "INTERNAL_SERVER_ERROR"
        const val INVALID_REQUEST_MESSAGE = "요청값을 확인해 주세요."
        const val VALIDATION_FAILED_MESSAGE = "요청값 검증에 실패했습니다."
        const val NOT_FOUND_MESSAGE = "요청한 리소스를 찾을 수 없습니다."
        const val METHOD_NOT_ALLOWED_MESSAGE = "지원하지 않는 HTTP 메서드입니다."
        const val INTERNAL_SERVER_ERROR_MESSAGE = "서버 처리 중 오류가 발생했습니다."
        const val INVALID_FIELD_MESSAGE = "유효하지 않은 값입니다."
    }
}
