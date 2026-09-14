package com.hwgeneralins.dch.puls.welfare.backend.region.reload

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.core.io.ClassPathResource
import org.springframework.core.io.FileSystemResource
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class RegionCatalogRuntimeTest {
    @TempDir lateinit var dir: Path
    private val mapper = jacksonObjectMapper()
    private val options = RegionReloadOptions(true, "test-token", "test", "test-01")
    private fun source() = ClassPathResource("regions/korea_sido_sigungu_20260630.json").inputStream.use { it.readBytes() }
    private fun file(name: String = "current.json") = dir.resolve(name).also { Files.write(it, source()) }
    private fun runtime(path: Path, next: Path? = null) = RegionCatalogRuntime(mapper, FileSystemResource(path), next?.let(::FileSystemResource), options) { root, upcoming ->
        root["sido"][0]["sigungu"][0]["sigunguName"].asText() to upcoming?.get("sido")?.get(0)?.get("sigungu")?.get(0)?.get("sigunguName")?.asText()
    }
    private fun changed() = source().toString(Charsets.UTF_8).replace("종로구", "종로신구").toByteArray()

    @Test fun `reload switches snapshot and rollback restores original without restarting`() {
        val path = file()
        val runtime = runtime(path)
        val old = runtime.snapshot()
        assertNull(runtime.status().lastReloadAttemptAt)
        Files.write(path, changed())
        val result = runtime.reload()
        assertEquals("RELOADED", result.result)
        assertEquals("종로신구", runtime.snapshot().value.first)
        assertEquals("종로구", old.value.first)
        assertEquals(MessageDigest.getInstance("SHA-256").digest(changed()).joinToString("") { "%02x".format(it) }, result.active.sha256)
        assertEquals("UNCHANGED", runtime.reload().result)
        assertEquals(result.snapshotAppliedAt, runtime.snapshot().appliedAt)
        Files.write(path, source())
        runtime.reload()
        assertEquals(old.active.sha256, runtime.snapshot().active.sha256)
    }

    @Test fun `invalid catalogs preserve snapshot and expose failure status`() {
        val path = file()
        val runtime = runtime(path)
        val previous = runtime.snapshot()
        val invalid = listOf(
            "{}".toByteArray(), "{".toByteArray(), source() + " {}".toByteArray(),
            ByteArray(5 * 1024 * 1024 + 1), byteArrayOf(0xc3.toByte(), 0x28),
            source().toString(Charsets.UTF_8).replaceFirst("\"meta\" : {", "\"meta\" : {}, \"meta\" : {").toByteArray(),
        )
        invalid.forEach { bytes ->
            Files.write(path, bytes)
            val error = assertFailsWith<RegionReloadException> { runtime.reload() }
            assertEquals(422, error.status)
            assertSame(previous, runtime.snapshot())
            assertEquals("REGION_CATALOG_INVALID", runtime.status().lastReloadResult)
        }
    }

    @Test fun `structurally invalid metadata codes and aliases are rejected`() {
        val path = file()
        val runtime = runtime(path)
        val mutations: List<(ObjectNode) -> Unit> = listOf(
            { it.putArray("sido") },
            { (it["meta"] as ObjectNode).put("sidoCount", 0) },
            { (it["sido"][0] as ObjectNode).put("sidoCode", "123") },
            { (it["sido"][0]["sigungu"][0] as ObjectNode).put("sigunguCode", "9900000000") },
            { (it["sido"][0]["sigungu"][1] as ObjectNode).put("sigunguCode", it["sido"][0]["sigungu"][0]["sigunguCode"].asText()) },
            { (it["sido"][0]["sigungu"][0] as ObjectNode).put("fullName", "wrong") },
            { it.putObject("aliases").put("옛이름", "없는시도") },
            { it.putObject("sigunguAliases").put("서울특별시|옛구", "없는구") },
        )
        mutations.forEach { mutation ->
            val node = mapper.readTree(source()) as ObjectNode
            mutation(node)
            Files.write(path, mapper.writeValueAsBytes(node))
            assertEquals(422, assertFailsWith<RegionReloadException> { runtime.reload() }.status)
        }
    }

    @Test fun `missing file fails without replacing memory`() {
        val path = file()
        val runtime = runtime(path)
        val previous = runtime.snapshot()
        Files.delete(path)
        assertEquals(503, assertFailsWith<RegionReloadException> { runtime.reload() }.status)
        assertSame(previous, runtime.snapshot())
    }

    @Test fun `current and next publish together`() {
        val path = file()
        val next = file("next.json")
        val runtime = runtime(path, next)
        val previous = runtime.snapshot()
        Files.write(path, changed())
        Files.writeString(next, "invalid")
        assertFailsWith<RegionReloadException> { runtime.reload() }
        assertSame(previous, runtime.snapshot())
        Files.write(next, changed())
        runtime.reload()
        assertEquals("종로신구" to "종로신구", runtime.snapshot().value)
    }

    @Test fun `concurrent reload is rejected and readers see fully built snapshots`() {
        val path = file()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val runtime = RegionCatalogRuntime(mapper, FileSystemResource(path), null, options) { root, _ ->
            val name = root["sido"][0]["sigungu"][0]["sigunguName"].asText()
            if (name == "종로신구") {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
            }
            name
        }
        val executor = Executors.newSingleThreadExecutor()
        try {
            Files.write(path, changed())
            val pending = executor.submit<ReloadResponse> { runtime.reload() }
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            assertEquals("종로구", runtime.snapshot().value)
            assertEquals(409, assertFailsWith<RegionReloadException> { runtime.reload() }.status)
            release.countDown()
            assertEquals("RELOADED", pending.get(10, TimeUnit.SECONDS).result)
            assertEquals("종로신구", runtime.snapshot().value)
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test fun `startup validates resources and reload configuration`() {
        val path = file()
        assertFailsWith<IllegalArgumentException> {
            RegionCatalogRuntime(mapper, FileSystemResource(path), null, RegionReloadOptions(true)) { _, _ -> Unit }
        }
        assertFailsWith<IllegalArgumentException> {
            RegionCatalogRuntime(mapper, ClassPathResource("regions/korea_sido_sigungu_20260630.json"), null, options) { _, _ -> Unit }
        }
        val disabled = RegionCatalogRuntime(mapper, FileSystemResource(path), null, RegionReloadOptions()) { _, _ -> Unit }
        assertEquals(404, assertFailsWith<RegionReloadException> { disabled.reload() }.status)
        Files.writeString(path, "invalid")
        assertFailsWith<RegionReloadException> { runtime(path) }
    }
}
