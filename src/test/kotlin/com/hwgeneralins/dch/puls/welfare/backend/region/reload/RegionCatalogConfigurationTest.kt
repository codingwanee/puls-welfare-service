package com.hwgeneralins.dch.puls.welfare.backend.region.reload

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.io.ClassPathResource
import org.springframework.core.io.DefaultResourceLoader
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class RegionCatalogConfigurationTest {
    @TempDir lateinit var dir: Path
    private val key = "region.catalog.sido-sigungu-resource"
    private fun config(name: String = "application.yml", active: String, next: String? = null) {
        Files.writeString(dir.resolve(name), "$key: '$active'\n" +
            (next?.let { "region.catalog.next-sido-sigungu-resource: '$it'\n" } ?: ""))
    }
    private fun environment(vararg overrides: Pair<String, String>): StandardEnvironment {
        val env = StandardEnvironment()
        env.propertySources.addFirst(MapPropertySource("testOverrides", mapOf(
            "spring.config.location" to dir.toUri().toString(), *overrides)))
        ConfigDataEnvironmentPostProcessor.applyTo(env)
        return env
    }

    @Test fun `IDE reload reads source yaml instead of stale Gradle classpath copy`() {
        val compiled = Files.createDirectories(dir.resolve("build/resources/main"))
        val source = Files.createDirectories(dir.resolve("src/main/resources"))
        Files.writeString(compiled.resolve("application.yml"), "$key: file:/compiled.json\n")
        Files.writeString(source.resolve("application.yml"), "$key: file:/source.json\n")
        object : URLClassLoader(arrayOf(compiled.toUri().toURL()), javaClass.classLoader) {
            override fun getResource(name: String): java.net.URL? = findResource(name) ?: super.getResource(name)
        }.use { classLoader ->
            val loader = DefaultResourceLoader(classLoader)
            val env = StandardEnvironment()
            env.propertySources.addFirst(MapPropertySource("testOverrides", mapOf("spring.config.location" to "classpath:/")))
            ConfigDataEnvironmentPostProcessor.applyTo(env, loader, null, emptyList())
            assertEquals("file:/compiled.json", env.getProperty(key))
            val configuration = RegionCatalogConfiguration(env, loader, dir)
            assertEquals("source.json", configuration.resources(false).first.filename)
            Files.writeString(source.resolve("application.yml"), "$key: file:/changed.json\n")
            assertEquals("changed.json", configuration.resources(false).first.filename)
            Files.writeString(source.resolve("application.yml"), "region: [invalid")
            assertEquals(422, assertFailsWith<RegionReloadException> { configuration.resources(false) }.status)
        }
    }

    @Test fun `reloading yaml paths uses profiles and preserves live settings`() {
        config(active = "file:/old.json")
        config("application-test.yml", "file:/profile-old.json")
        val env = environment("spring.profiles.active" to "test")
        val config = RegionCatalogConfiguration(env)
        config("application-test.yml", "file:/profile-new.json")
        assertEquals("profile-new.json", config.resources(false).first.filename)
        assertEquals("file:/profile-old.json", env.getProperty(key))
    }

    @Test fun `higher priority overrides and yaml placeholders are preserved`() {
        config(active = "\${CATALOG_PATH:file:/old.json}")
        val env = environment("CATALOG_PATH" to "file:/env.json")
        config(active = "\${CATALOG_PATH:file:/new.json}")
        assertEquals("env.json", RegionCatalogConfiguration(env).resources(false).first.filename)
        val overridden = environment(key to "file:/argument.json")
        assertEquals("argument.json", RegionCatalogConfiguration(overridden).resources(false).first.filename)
    }

    @Test fun `current and next paths switch atomically and next can be added or removed`() {
        val bytes = ClassPathResource("regions/korea_sido_sigungu_20260630.json").inputStream.use { it.readBytes() }
        val original = dir.resolve("old.json")
        val release = dir.resolve("new.json")
        Files.write(original, bytes)
        Files.writeString(release, bytes.toString(Charsets.UTF_8).replace("종로구", "종로신구"))
        config(active = original.toUri().toString())
        val source = RegionCatalogConfiguration(environment())
        val (active, next) = source.resources(true)
        val runtime = RegionCatalogRuntime(jacksonObjectMapper(), active, next,
            RegionReloadOptions(true, "token"), resolveResources = { source.resources(true) }) { root, upcoming ->
            root["sido"][0]["sigungu"][0]["sigunguName"].asText() to
                upcoming?.get("sido")?.get(0)?.get("sigungu")?.get(0)?.get("sigunguName")?.asText()
        }
        val previous = runtime.snapshot()
        config(active = release.toUri().toString(), next = dir.resolve("missing.json").toUri().toString())
        assertEquals(503, assertFailsWith<RegionReloadException> { runtime.reload() }.status)
        assertSame(previous, runtime.snapshot())
        config(active = release.toUri().toString(), next = original.toUri().toString())
        runtime.reload()
        assertEquals("종로신구" to "종로구", runtime.snapshot().value)
        config(active = original.toUri().toString())
        runtime.reload()
        assertEquals("종로구" to null, runtime.snapshot().value)
        config(active = "classpath:regions/korea_sido_sigungu_20260630.json")
        assertEquals(422, assertFailsWith<RegionReloadException> { runtime.reload() }.status)
        assertEquals("종로구" to null, runtime.snapshot().value)
    }
}
