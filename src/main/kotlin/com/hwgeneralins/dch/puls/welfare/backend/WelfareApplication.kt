package com.hwgeneralins.dch.puls.welfare.backend

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@EnableScheduling
@ConfigurationPropertiesScan
@SpringBootApplication
class WelfareApplication

fun main(args: Array<String>) {
    runApplication<WelfareApplication>(*args)
}
