package com.hwgeneralins.dch.puls.welfare.backend.region.reload

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.core.io.Resource
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock

@Component
class RegionReloadOptions(
    @Value("\${region.catalog.reload.enabled:false}") val enabled: Boolean = false,
    @Value("\${region.catalog.reload.token:}") val token: String = "",
    @Value("\${spring.application.name:wsbd}") val application: String = "wsbd",
    @Value("\${region.catalog.reload.instance-id:\${HOSTNAME:}}") val instanceId: String = "",
) {
    val resolvedInstanceId: String = instanceId.ifBlank { java.net.InetAddress.getLocalHost().hostName }
}

class RegionReloadException(val status: Int, val code: String, message: String) : RuntimeException(message)

data class CatalogInfo(val sha256: String, val sourceFile: String, val sidoCount: Int, val sigunguCount: Int)
data class CatalogSnapshot<T>(val value: T, val active: CatalogInfo, val next: CatalogInfo?, val appliedAt: Instant)
@JsonInclude(JsonInclude.Include.ALWAYS)
data class ReloadResponse(
    val application: String, val instanceId: String, val result: String, val checkedAt: Instant,
    val snapshotAppliedAt: Instant, val active: CatalogInfo, val next: CatalogInfo?,
)
@JsonInclude(JsonInclude.Include.ALWAYS)
data class CatalogStatus(
    val application: String, val instanceId: String, val snapshotAppliedAt: Instant,
    val active: CatalogInfo, val next: CatalogInfo?, val lastReloadAttemptAt: Instant?, val lastReloadResult: String?,
)
private data class RuntimeState<T>(val snapshot: CatalogSnapshot<T>, val attemptAt: Instant? = null, val result: String? = null)
private data class LoadedCatalog(val root: JsonNode, val info: CatalogInfo)

/** File loading is bounded; candidates are completely built before a single atomic publication. */
class RegionCatalogRuntime<T>(
    mapper: ObjectMapper,
    private val activeResource: Resource,
    private val nextResource: Resource?,
    private val options: RegionReloadOptions,
    private val build: (JsonNode, JsonNode?) -> T,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val parser = mapper.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
    private val lock = ReentrantLock()
    private val state: AtomicReference<RuntimeState<T>>

    init {
        if (options.enabled) {
            require(options.token.isNotBlank()) { "REGION_CATALOG_RELOAD_TOKEN is required" }
            require(listOfNotNull(activeResource, nextResource).all { it !is org.springframework.core.io.ClassPathResource && it.uri.scheme == "file" }) {
                "Region reload requires external file: resources"
            }
        }
        val active = read(activeResource)
        val next = nextResource?.let(::read)
        state = AtomicReference(RuntimeState(candidate(active, next)))
    }

    fun snapshot(): CatalogSnapshot<T> = state.get().snapshot

    fun status(): CatalogStatus {
        val current = state.get()
        return CatalogStatus(options.application, options.resolvedInstanceId, current.snapshot.appliedAt,
            current.snapshot.active, current.snapshot.next, current.attemptAt, current.result)
    }

    fun reload(): ReloadResponse {
        if (!options.enabled) throw RegionReloadException(404, "NOT_FOUND", "Region reload is disabled")
        if (!lock.tryLock()) throw RegionReloadException(409, "REGION_RELOAD_IN_PROGRESS", "Region reload is in progress")
        val at = Instant.now()
        val started = System.nanoTime()
        val previous = state.get().snapshot
        try {
            val active = read(activeResource)
            val next = nextResource?.let(::read)
            val unchanged = active.info.sha256 == previous.active.sha256 && next?.info?.sha256 == previous.next?.sha256
            val updated = if (unchanged) previous else candidate(active, next)
            val result = if (unchanged) "UNCHANGED" else "RELOADED"
            state.set(RuntimeState(updated, at, result))
            log.info("Region reload application={} instanceId={} previousHash={} activeHash={} nextHash={} result={} elapsedMs={}",
                options.application, options.resolvedInstanceId, previous.active.sha256, updated.active.sha256,
                updated.next?.sha256, result, (System.nanoTime() - started) / 1_000_000)
            return ReloadResponse(options.application, options.resolvedInstanceId, result, at, updated.appliedAt, updated.active, updated.next)
        } catch (ex: Exception) {
            val failure = if (ex is RegionReloadException) ex else
                RegionReloadException(500, "REGION_RELOAD_FAILED", "Could not build region catalog")
            // Publication is the commit point; a later response/logging failure must not undo it.
            state.updateAndGet { it.copy(attemptAt = at, result = failure.code) }
            log.warn("Region reload application={} instanceId={} activeHash={} result={}",
                options.application, options.resolvedInstanceId, state.get().snapshot.active.sha256, failure.code)
            throw failure
        } finally {
            lock.unlock()
        }
    }

    private fun candidate(active: LoadedCatalog, next: LoadedCatalog?) =
        CatalogSnapshot(build(active.root, next?.root), active.info, next?.info, Instant.now())

    private fun read(resource: Resource): LoadedCatalog {
        val bytes = try {
            resource.inputStream.use { it.readNBytes(MAX_BYTES + 1) }
        } catch (ex: IOException) {
            throw RegionReloadException(503, "REGION_CATALOG_READ_FAILED", "Could not read configured region catalog")
        }
        try {
            require(bytes.size <= MAX_BYTES) { "Catalog exceeds 5 MiB" }
            // Reject malformed UTF-8 rather than silently substituting invalid bytes.
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes))
            val root = parser.factory.createParser(bytes).use { input ->
                val tree = parser.readTree<JsonNode>(input)
                require(input.nextToken() == null) { "Trailing JSON content" }
                tree
            }
            validate(root)
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            return LoadedCatalog(root, CatalogInfo(hash, root["meta"]["sourceFile"].asText(),
                root["meta"]["sidoCount"].asInt(), root["meta"]["sigunguCount"].asInt()))
        } catch (ex: Exception) {
            throw RegionReloadException(422, "REGION_CATALOG_INVALID", "Region catalog validation failed")
        }
    }

    private fun validate(root: JsonNode?) {
        require(root != null && root.isObject)
        val meta = root.path("meta")
        require(meta.isObject)
        listOf("sourceFile", "sourceEncoding", "basis").forEach { text(meta, it) }
        val excluded = meta.path("excluded")
        count(excluded, "generalGuCount")
        count(excluded, "jejuAdministrativeCityCount")
        val sido = root.path("sido")
        require(sido.isArray && !sido.isEmpty)
        val sidoNames = mutableSetOf<String>()
        val sidoCodes = mutableSetOf<String>()
        val sigunguCodes = mutableSetOf<String>()
        val names = mutableMapOf<String, Set<String>>()
        var total = 0
        sido.forEach { region ->
            val code = text(region, "sidoCode")
            val name = text(region, "sidoName")
            require(CODE.matches(code) && sidoCodes.add(code) && sidoNames.add(name))
            val children = region.path("sigungu")
            require(children.isArray)
            val childNames = mutableSetOf<String>()
            children.forEach { child ->
                val childCode = text(child, "sigunguCode")
                val childName = text(child, "sigunguName")
                require(CODE.matches(childCode) && childCode.startsWith(code.take(2)))
                require(sigunguCodes.add(childCode) && childNames.add(childName))
                require(text(child, "fullName") == "$name $childName")
            }
            require(count(region, "sigunguCount") == children.size())
            names[name] = childNames
            total += children.size()
        }
        require(count(meta, "sidoCount") == sido.size() && count(meta, "sigunguCount") == total)
        root.get("aliases")?.let { aliases ->
            require(aliases.isObject)
            aliases.properties().forEach { (key, value) ->
                require(key.isNotBlank() && value.isTextual && value.asText() in sidoNames)
            }
        }
        root.get("sigunguAliases")?.let { aliases ->
            require(aliases.isObject)
            aliases.properties().forEach { (key, value) ->
                val parts = key.split('|')
                require(parts.size == 2 && parts.all { it.isNotBlank() } && value.isTextual)
                require(value.asText() in names[parts[0]].orEmpty())
            }
        }
    }

    private fun text(node: JsonNode, key: String): String {
        val value = node.path(key)
        require(value.isTextual && value.asText().isNotBlank() && value.asText() == value.asText().trim())
        return value.asText()
    }
    private fun count(node: JsonNode, key: String): Int {
        val value = node.path(key)
        require(value.isIntegralNumber && value.canConvertToInt() && value.asInt() >= 0)
        return value.asInt()
    }
    private companion object {
        const val MAX_BYTES = 5 * 1024 * 1024
        val CODE = Regex("[0-9]{10}")
    }
}
