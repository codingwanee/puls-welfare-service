package com.hwgeneralins.dch.puls.welfare.backend.trace

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import jakarta.servlet.http.HttpServletRequest
import org.aspectj.lang.JoinPoint
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.expression.ExpressionParser
import org.springframework.expression.spel.SpelEvaluationException
import org.springframework.expression.spel.standard.SpelExpressionParser
import org.springframework.http.ResponseEntity
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import java.util.UUID

object RestApiTraceLog {
    private const val LOGGER_NAME_TRACE = "TRACE_LOGGER"
    private const val MDC_KEY_TRACE_ID = "TRACE_ID"
    private const val TRACE_ID_HEADER = "X-TRACE-ID"
    private const val REQ_ATTRIBUTE_NAME_START_TIME = "START_TIME"

    private val traceLog = LoggerFactory.getLogger(LOGGER_NAME_TRACE)
    private val log = LoggerFactory.getLogger(RestApiTraceLog::class.java)
    private val mapper = ObjectMapper()
        .findAndRegisterModules()
        .enable(SerializationFeature.INDENT_OUTPUT)
        .configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false)

    fun requestLog(joinPoint: JoinPoint, traceRequest: TraceRequest?) {
        val request = currentRequest()
        request.setAttribute(REQ_ATTRIBUTE_NAME_START_TIME, System.currentTimeMillis())
        prepareTraceId(request)

        val keyPathValues = traceRequest?.let { extractKeyPathValues(joinPoint.args, it.keyPaths) }
        traceLog.info(
            "REQ [{}] {} {} {} {}; {}{}",
            currentTraceId(),
            request.method,
            requestUriWithDecodedQuery(request),
            joinPoint.target.javaClass.simpleName,
            joinPoint.signature.name,
            clientIp(request),
            keyPathValues?.let { ", $it" }.orEmpty(),
        )

        if (traceRequest?.bodyIgnore == true) {
            log.debug("REQ_BODY omitted due to sensitive information...")
            return
        }
        writeBodyLog("REQ_BODY", joinPoint.args)
    }

    fun responseLog(joinPoint: JoinPoint, traceResponse: TraceResponse?, returnValue: Any?) {
        val request = currentRequest()
        traceLog.info(
            "RES [{}] {} {} {} {}; {}, {}ms",
            currentTraceId(),
            request.method,
            requestUriWithDecodedQuery(request),
            joinPoint.target.javaClass.simpleName,
            joinPoint.signature.name,
            responseStatusCode(returnValue),
            elapsedMillis(request),
        )

        if (traceResponse?.bodyIgnore == true) {
            log.debug("RES_BODY omitted due to sensitive information...")
        } else {
            writeBodyLog("RES_BODY", returnValue)
        }
        MDC.clear()
    }

    fun exceptionLog(joinPoint: JoinPoint, throwable: Throwable) {
        val request = currentRequest()
        traceLog.info(
            "RES [{}] {} {} {} {}; 500, {}, {}ms",
            currentTraceId(),
            request.method,
            requestUriWithDecodedQuery(request),
            joinPoint.target.javaClass.simpleName,
            joinPoint.signature.name,
            "${throwable.javaClass.simpleName}: ${throwable.message.orEmpty()}",
            elapsedMillis(request),
        )
        MDC.clear()
    }

    private fun currentRequest(): HttpServletRequest =
        (RequestContextHolder.currentRequestAttributes() as ServletRequestAttributes).request

    private fun prepareTraceId(request: HttpServletRequest) {
        if (MDC.get(MDC_KEY_TRACE_ID).isNullOrBlank()) {
            MDC.put(MDC_KEY_TRACE_ID, request.getHeader(TRACE_ID_HEADER)?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString())
        }
    }

    private fun currentTraceId(): String = MDC.get(MDC_KEY_TRACE_ID).orEmpty()

    private fun requestUriWithDecodedQuery(request: HttpServletRequest): String {
        val query = request.parameterMap.entries.joinToString("&") { (name, values) ->
            if (values.isEmpty()) {
                sanitizeLogValue(name)
            } else {
                values.joinToString("&") { value ->
                    "${sanitizeLogValue(name)}=${sanitizeLogValue(value)}"
                }
            }
        }
        return if (query.isEmpty()) request.requestURI else "${request.requestURI}?$query"
    }

    private fun sanitizeLogValue(value: String): String =
        value.replace('\r', ' ').replace('\n', ' ')

    private fun clientIp(request: HttpServletRequest): String =
        request.getHeader("X-FORWARDED-FOR")?.takeIf { it.isNotBlank() } ?: request.remoteAddr

    private fun extractKeyPathValues(args: Array<Any?>, keyPaths: Array<String>): List<Any?>? {
        val parser: ExpressionParser = SpelExpressionParser()
        return try {
            keyPaths.filter { it.isNotBlank() }
                .map { path -> parser.parseExpression(path).getValue(args) }
                .takeIf { it.isNotEmpty() }
        } catch (_: SpelEvaluationException) {
            log.error("Invalid @TraceRequest keyPath. keyPaths={}", keyPaths.contentToString())
            null
        } catch (_: ArrayIndexOutOfBoundsException) {
            log.error("Invalid @TraceRequest keyPath index. keyPaths={}", keyPaths.contentToString())
            null
        }
    }

    private fun responseStatusCode(returnValue: Any?): Int =
        if (returnValue is ResponseEntity<*>) returnValue.statusCode.value() else 200

    private fun elapsedMillis(request: HttpServletRequest): Long {
        val startTime = request.getAttribute(REQ_ATTRIBUTE_NAME_START_TIME) as? Long ?: return -1L
        return System.currentTimeMillis() - startTime
    }

    private fun writeBodyLog(label: String, value: Any?) {
        try {
            log.debug("{} \n{}", label, mapper.writeValueAsString(value))
        } catch (exception: JsonProcessingException) {
            log.error(exception.message)
        }
    }
}
