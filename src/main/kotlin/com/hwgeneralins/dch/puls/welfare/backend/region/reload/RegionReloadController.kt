package com.hwgeneralins.dch.puls.welfare.backend.region.reload

import com.hwgeneralins.dch.puls.welfare.backend.policy.api.ApiErrorResponse
import com.hwgeneralins.dch.puls.welfare.backend.region.RegionCatalogService
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.*
import org.springframework.web.filter.OncePerRequestFilter
import java.security.MessageDigest
import java.time.OffsetDateTime

@RestController
@ConditionalOnProperty(prefix = "region.catalog.reload", name = ["enabled"], havingValue = "true")
@RequestMapping("/internal/regions")
class RegionReloadController(private val catalog: RegionCatalogService) {
    @PostMapping("/reload")
    fun reload(): ResponseEntity<ReloadResponse> = ResponseEntity.ok().header("Cache-Control", "no-store").body(catalog.runtime.reload())

    @GetMapping("/status")
    fun status(): ResponseEntity<CatalogStatus> = ResponseEntity.ok().header("Cache-Control", "no-store").body(catalog.runtime.status())

    @ExceptionHandler(RegionReloadException::class)
    fun error(ex: RegionReloadException, request: HttpServletRequest): ResponseEntity<ApiErrorResponse> =
        ResponseEntity.status(ex.status).header("Cache-Control", "no-store").body(errorBody(ex, request))
}

internal fun errorBody(ex: RegionReloadException, request: HttpServletRequest) = ApiErrorResponse(
    timestamp = OffsetDateTime.now(), status = ex.status, code = ex.code,
    message = ex.message.orEmpty(), path = request.requestURI,
)

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
@ConditionalOnProperty(prefix = "region.catalog.reload", name = ["enabled"], havingValue = "true")
class RegionReloadAuthFilter(private val options: RegionReloadOptions, private val mapper: ObjectMapper) : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        val path = org.springframework.web.util.UrlPathHelper.defaultInstance.getLookupPathForRequest(request)
        return path != "/internal/regions" && !path.startsWith("/internal/regions/")
    }

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        response.setHeader("Cache-Control", "no-store")
        val expected = "Bearer ${options.token}".toByteArray(Charsets.UTF_8)
        val actual = request.getHeader("Authorization").orEmpty().toByteArray(Charsets.UTF_8)
        if (options.token.isBlank() || !MessageDigest.isEqual(expected, actual)) {
            response.status = 401
            response.contentType = "application/json"
            response.setHeader("WWW-Authenticate", "Bearer")
            mapper.writeValue(response.outputStream, errorBody(
                RegionReloadException(401, "REGION_RELOAD_UNAUTHORIZED", "Region operation authentication required"), request))
            return
        }
        chain.doFilter(request, response)
    }
}
