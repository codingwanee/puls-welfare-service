package com.hwgeneralins.dch.puls.welfare.backend.trace

import org.aspectj.lang.JoinPoint
import org.aspectj.lang.annotation.AfterReturning
import org.aspectj.lang.annotation.AfterThrowing
import org.aspectj.lang.annotation.Aspect
import org.aspectj.lang.annotation.Before
import org.aspectj.lang.reflect.MethodSignature
import org.springframework.stereotype.Component

@Aspect
@Component
class ControllerTraceLoggingAspect {
    @Before(
        value = "within(com.hwgeneralins.dch.puls.welfare.backend..*Controller)" +
            " && !@annotation(com.hwgeneralins.dch.puls.welfare.backend.trace.TraceIgnore)",
    )
    fun endpointBefore(joinPoint: JoinPoint) {
        val signature = joinPoint.signature as MethodSignature
        RestApiTraceLog.requestLog(joinPoint, signature.method.getAnnotation(TraceRequest::class.java))
    }

    @AfterReturning(
        value = "within(com.hwgeneralins.dch.puls.welfare.backend..*Controller)" +
            " && !@annotation(com.hwgeneralins.dch.puls.welfare.backend.trace.TraceIgnore)",
        returning = "returnValue",
    )
    fun endpointAfter(joinPoint: JoinPoint, returnValue: Any?) {
        val signature = joinPoint.signature as MethodSignature
        RestApiTraceLog.responseLog(joinPoint, signature.method.getAnnotation(TraceResponse::class.java), returnValue)
    }

    @AfterThrowing(
        value = "within(com.hwgeneralins.dch.puls.welfare.backend..*Controller)" +
            " && !@annotation(com.hwgeneralins.dch.puls.welfare.backend.trace.TraceIgnore)",
        throwing = "throwable",
    )
    fun endpointException(joinPoint: JoinPoint, throwable: Throwable) {
        RestApiTraceLog.exceptionLog(joinPoint, throwable)
    }
}
