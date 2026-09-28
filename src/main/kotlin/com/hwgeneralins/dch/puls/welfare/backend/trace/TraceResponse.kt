package com.hwgeneralins.dch.puls.welfare.backend.trace

@Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY_GETTER, AnnotationTarget.PROPERTY_SETTER)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
annotation class TraceResponse(
    val message: String = "Trace entity msg",
    val bodyIgnore: Boolean = false,
)
