package com.hwgeneralins.dch.puls.welfare.backend.policy

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.hwgeneralins.dch.puls.welfare.backend.policy.api.BusinessErrorCode
import com.hwgeneralins.dch.puls.welfare.backend.policy.api.PolicyApiException
import com.hwgeneralins.dch.puls.welfare.backend.policy.service.CursorCodec
import com.hwgeneralins.dch.puls.welfare.backend.policy.service.PolicyCursor
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Base64

class CursorCodecTest {
    private val objectMapper = jacksonObjectMapper().findAndRegisterModules()
    private val codec = CursorCodec(objectMapper)

    @Test
    fun `compact cursor preserves every sort value and is substantially shorter`() {
        val cursor = PolicyCursor(
            sortVersion = "v1",
            asOf = LocalDateTime.of(2026, 7, 31, 15, 30, 12, 123_456_789),
            sourceRank = 0,
            deadline = LocalDate.of(2026, 12, 31),
            deadlineGroup = 1,
            inputAt = LocalDateTime.of(2026, 7, 30, 10, 0, 1, 987_654_321),
            id = 123,
        )
        val legacy = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(objectMapper.writeValueAsBytes(cursor))

        val encoded = codec.encode(cursor)

        assertThat(codec.decode(encoded)).isEqualTo(cursor)
        assertThat(objectMapper.readTree(Base64.getUrlDecoder().decode(encoded)).isArray).isTrue()
        assertThat(encoded.length).isLessThan(legacy.length / 2)
    }

    @Test
    fun `legacy object cursor remains readable`() {
        val cursor = PolicyCursor("v1", LocalDateTime.of(2026, 7, 31, 12, 0), 1, null, null, null, 99)
        val legacy = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(objectMapper.writeValueAsBytes(cursor))

        assertThat(codec.decode(legacy)).isEqualTo(cursor)
    }

    @Test
    fun `malformed compact cursor is rejected with stable error`() {
        val malformed = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("[\"v1\",1]".toByteArray())

        assertThatThrownBy { codec.decode(malformed) }
            .isInstanceOfSatisfying(PolicyApiException::class.java) {
                assertThat(it.businessCode).isEqualTo(BusinessErrorCode.INVALID_CURSOR)
            }
    }
}
