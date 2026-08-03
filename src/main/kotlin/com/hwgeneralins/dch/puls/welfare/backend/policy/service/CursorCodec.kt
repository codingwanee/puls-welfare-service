package com.hwgeneralins.dch.puls.welfare.backend.policy.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.JsonNode
import com.hwgeneralins.dch.puls.welfare.backend.policy.api.BusinessErrorCode
import com.hwgeneralins.dch.puls.welfare.backend.policy.api.PolicyApiException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Base64

data class PolicyCursor(
    val sortVersion: String,
    val asOf: LocalDateTime,
    val sourceRank: Int,
    val deadline: LocalDate?,
    val deadlineGroup: Int?,
    val inputAt: LocalDateTime?,
    val id: Long,
) {
    fun sortKey() = PolicySortKey(sourceRank, deadline, deadlineGroup, inputAt, id)
}

@Component
class CursorCodec(private val objectMapper: ObjectMapper) {
    fun encode(cursor: PolicyCursor): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(
            objectMapper.writeValueAsBytes(
                listOf(
                    cursor.sortVersion,
                    cursor.asOf.toEpochSecond(ZoneOffset.UTC),
                    cursor.asOf.nano,
                    cursor.sourceRank,
                    cursor.deadline?.toEpochDay(),
                    cursor.deadlineGroup,
                    cursor.inputAt?.toEpochSecond(ZoneOffset.UTC),
                    cursor.inputAt?.nano,
                    cursor.id,
                ),
            ),
        )

    fun decode(value: String): PolicyCursor = try {
        val node = objectMapper.readTree(Base64.getUrlDecoder().decode(value))
        if (node.isArray) decodeCompact(node) else objectMapper.treeToValue(node, PolicyCursor::class.java)
    } catch (_: Exception) {
        throw PolicyApiException(HttpStatus.BAD_REQUEST, BusinessErrorCode.INVALID_CURSOR, "유효하지 않은 페이지 커서입니다.")
    }

    private fun decodeCompact(node: JsonNode): PolicyCursor {
        require(node.size() == COMPACT_FIELD_COUNT)
        val sortVersion = node[0].takeIf { it.isTextual }?.textValue()?.takeIf { it.isNotBlank() }
            ?: error("Missing sort version")
        val inputAtSecond = optionalLong(node, 6)
        val inputAtNano = optionalInt(node, 7)
        require((inputAtSecond == null) == (inputAtNano == null))

        return PolicyCursor(
            sortVersion = sortVersion,
            asOf = localDateTime(requiredLong(node, 1), requiredInt(node, 2)),
            sourceRank = requiredInt(node, 3),
            deadline = optionalLong(node, 4)?.let(LocalDate::ofEpochDay),
            deadlineGroup = optionalInt(node, 5),
            inputAt = inputAtSecond?.let { localDateTime(it, inputAtNano!!) },
            id = requiredLong(node, 8),
        )
    }

    private fun localDateTime(epochSecond: Long, nano: Int): LocalDateTime {
        require(nano in 0..999_999_999)
        return LocalDateTime.ofEpochSecond(epochSecond, nano, ZoneOffset.UTC)
    }

    private fun requiredLong(node: JsonNode, index: Int): Long =
        node[index].takeIf { it.isIntegralNumber }?.longValue() ?: error("Invalid cursor number")

    private fun requiredInt(node: JsonNode, index: Int): Int =
        node[index].takeIf { it.isIntegralNumber && it.canConvertToInt() }?.intValue()
            ?: error("Invalid cursor number")

    private fun optionalLong(node: JsonNode, index: Int): Long? =
        if (node[index].isNull) null else requiredLong(node, index)

    private fun optionalInt(node: JsonNode, index: Int): Int? =
        if (node[index].isNull) null else requiredInt(node, index)

    private companion object {
        const val COMPACT_FIELD_COUNT = 9
    }
}
