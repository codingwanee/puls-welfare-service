package com.hwgeneralins.dch.puls.welfare.backend.region.reload

import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.io.DefaultResourceLoader
import org.springframework.core.io.Resource
import org.springframework.core.io.ResourceLoader
import org.springframework.core.io.FileSystemResource
import java.nio.file.Path

/** Reload config data in isolation; never refresh unrelated application beans or settings. */
class RegionCatalogConfiguration(
    private val environment: ConfigurableEnvironment,
    private val resourceLoader: ResourceLoader = DefaultResourceLoader(),
    private val projectDirectory: Path = Path.of("").toAbsolutePath(),
) {
    // Gradle/IDE runs use a copied classpath YAML. Read its source counterpart on
    // reload, while preserving Boot's external config/profile/argument precedence.
    private fun configurationLoader(): ResourceLoader {
        val compiled = projectDirectory.resolve("build/resources/main").normalize()
        val source = projectDirectory.resolve("src/main/resources").normalize()
        val application = resourceLoader.getResource("classpath:application.yml")
        val isLocalBuild = runCatching {
            application.uri.scheme == "file" && Path.of(application.uri).normalize() == compiled.resolve("application.yml")
        }.getOrDefault(false)
        if (!isLocalBuild || !source.resolve("application.yml").toFile().isFile) return resourceLoader
        return object : ResourceLoader {
            override fun getClassLoader(): ClassLoader? = resourceLoader.classLoader
            override fun getResource(location: String): Resource {
                if (location.startsWith("classpath:")) {
                    val relative = location.removePrefix("classpath:").trimStart('/')
                    val candidate = source.resolve(relative).normalize()
                    val original = resourceLoader.getResource(location)
                    val fromBuild = runCatching {
                        original.uri.scheme == "file" && Path.of(original.uri).normalize().startsWith(compiled)
                    }.getOrDefault(false)
                    if (candidate.startsWith(source) && (candidate.toFile().exists() || fromBuild)) {
                        return FileSystemResource(candidate)
                    }
                }
                return resourceLoader.getResource(location)
            }
        }
    }

    fun resources(includeNext: Boolean): Pair<Resource, Resource?> {
        try {
            val fresh = StandardEnvironment()
            fresh.propertySources.toList().forEach { fresh.propertySources.remove(it.name) }
            environment.propertySources.filterNot {
                it.name == "configurationProperties" || it.name.startsWith("Config resource '") ||
                    it.name.startsWith("Config tree '")
            }.forEach { fresh.propertySources.addLast(it) }
            // Retain the running instance's profiles, arguments and environment precedence.
            ConfigDataEnvironmentPostProcessor.applyTo(fresh, configurationLoader(), null, environment.activeProfiles.toList())
            val active = fresh.getRequiredProperty("region.catalog.sido-sigungu-resource").trim()
            require(active.isNotEmpty())
            val next = if (includeNext) fresh.getProperty("region.catalog.next-sido-sigungu-resource")
                ?.trim()?.takeIf { it.isNotEmpty() } else null
            return resourceLoader.getResource(active) to next?.let(resourceLoader::getResource)
        } catch (ex: Exception) {
            throw RegionReloadException(422, "REGION_CATALOG_CONFIG_INVALID", "Could not reload region catalog configuration")
        }
    }
}
