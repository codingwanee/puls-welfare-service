package com.hwgeneralins.dch.puls.welfare.backend.trace

import org.aspectj.lang.JoinPoint
import org.aspectj.lang.Signature
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mockito
import org.slf4j.MDC
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes

@ExtendWith(OutputCaptureExtension::class)
class RestApiTraceLogTest {
    @AfterEach
    fun cleanUp() {
        RequestContextHolder.resetRequestAttributes()
        MDC.clear()
    }

    @Test
    fun `request trace id is used for request and response logs then cleared`(output: CapturedOutput) {
        val request = MockHttpServletRequest("GET", "/api/wsbd/v1/policies").apply {
            addHeader("X-TRACE-ID", "client-trace-123")
            queryString = "sido=%EC%84%9C%EC%9A%B8%ED%8A%B9%EB%B3%84%EC%8B%9C&sigungu=%EB%A7%88%ED%8F%AC%EA%B5%AC&limit=10"
            addParameter("sido", "서울특별시")
            addParameter("sigungu", "마포구")
            addParameter("limit", "10")
            remoteAddr = "127.0.0.1"
        }
        RequestContextHolder.setRequestAttributes(ServletRequestAttributes(request))
        val joinPoint = Mockito.mock(JoinPoint::class.java)
        val signature = Mockito.mock(Signature::class.java)
        Mockito.`when`(joinPoint.target).thenReturn(this)
        Mockito.`when`(joinPoint.signature).thenReturn(signature)
        Mockito.`when`(joinPoint.args).thenReturn(emptyArray())
        Mockito.`when`(signature.name).thenReturn("list")

        RestApiTraceLog.requestLog(joinPoint, null)
        RestApiTraceLog.responseLog(joinPoint, null, emptyMap<String, Any>())

        val decodedUri = "/api/wsbd/v1/policies?sido=서울특별시&sigungu=마포구&limit=10"
        assertThat(output.out).contains("REQ [client-trace-123] GET $decodedUri")
        assertThat(output.out).contains("RES [client-trace-123] GET $decodedUri")
        assertThat(output.out).doesNotContain("%EC%84%9C")
        assertThat(MDC.get("TRACE_ID")).isNull()
    }

    @Test
    fun `multiple parameter values are preserved and control characters are sanitized`(output: CapturedOutput) {
        val request = MockHttpServletRequest("GET", "/api/wsbd/v1/policies").apply {
            addParameter("region", "서울특별시", "부산광역시")
            addParameter("keyword", "정책\r\n위조로그")
        }
        RequestContextHolder.setRequestAttributes(ServletRequestAttributes(request))
        val joinPoint = Mockito.mock(JoinPoint::class.java)
        val signature = Mockito.mock(Signature::class.java)
        Mockito.`when`(joinPoint.target).thenReturn(this)
        Mockito.`when`(joinPoint.signature).thenReturn(signature)
        Mockito.`when`(joinPoint.args).thenReturn(emptyArray())
        Mockito.`when`(signature.name).thenReturn("list")

        RestApiTraceLog.requestLog(joinPoint, null)
        RestApiTraceLog.responseLog(joinPoint, null, emptyMap<String, Any>())

        assertThat(output.out).contains("REQ [] GET /api/wsbd/v1/policies")
        assertThat(output.out).contains("RES [] GET /api/wsbd/v1/policies")
        assertThat(output.out).contains("region=서울특별시&region=부산광역시&keyword=정책  위조로그")
        assertThat(output.out).doesNotContain("정책\r\n위조로그")
    }
}
