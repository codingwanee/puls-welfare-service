package com.hwgeneralins.dch.puls.welfare.backend.trace

@Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY_GETTER, AnnotationTarget.PROPERTY_SETTER)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
annotation class TraceRequest(
    val message: String = "Trace entity msg",
    val key: String = "",
    val keyPaths: Array<String> = [""],
    val bodyIgnore: Boolean = false,
)
