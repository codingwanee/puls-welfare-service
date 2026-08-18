package com.hwgeneralins.dch.puls.welfare.backend.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SecretEncryptCliTest {
    @Test
    fun `generated ENC value can be decrypted by application encryptor`() {
        val encryptedProperty = encryptSecretPropertyValue(TEST_MASTER_KEY, TEST_SECRET)

        assertTrue(encryptedProperty.startsWith("ENC(") && encryptedProperty.endsWith(")"))
        val cipherText = encryptedProperty.removePrefix("ENC(").removeSuffix(")")
        assertEquals(
            TEST_SECRET,
            JasyptConfig(TEST_MASTER_KEY).jasyptStringEncryptor().decrypt(cipherText),
        )
    }

    @Test
    fun `blank inputs are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            encryptSecretPropertyValue("", TEST_SECRET)
        }
        assertFailsWith<IllegalArgumentException> {
            encryptSecretPropertyValue(TEST_MASTER_KEY, "")
        }
    }

    private companion object {
        const val TEST_MASTER_KEY = "welfare-serving-cli-test-key"
        const val TEST_SECRET = "test-secret"
    }
}
