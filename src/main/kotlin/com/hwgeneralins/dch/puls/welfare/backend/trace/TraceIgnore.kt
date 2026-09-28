package com.hwgeneralins.dch.puls.welfare.backend.trace

@Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY_GETTER, AnnotationTarget.PROPERTY_SETTER)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
annotation class TraceIgnore(val enable: Boolean = true)
