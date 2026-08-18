package com.hwgeneralins.dch.puls.welfare.backend.config

import org.jasypt.exceptions.EncryptionOperationNotPossibleException
import org.springframework.beans.factory.BeanCreationException
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.core.env.SystemEnvironmentPropertySource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class JasyptConfigTest {
    @Test
    fun `encryptor decrypts values encrypted with the configured standard`() {
        val encryptor = JasyptConfig(TEST_MASTER_KEY).jasyptStringEncryptor()

        val encrypted = encryptor.encrypt(TEST_SECRET)

        assertEquals(TEST_SECRET, encryptor.decrypt(encrypted))
    }

    @Test
    fun `encrypted environment property is resolved as plaintext`() {
        val encrypted = JasyptConfig(TEST_MASTER_KEY).jasyptStringEncryptor().encrypt(TEST_SECRET)
        val context = AnnotationConfigApplicationContext()
        context.environment.propertySources.addFirst(
            SystemEnvironmentPropertySource(
                "encryptedTestEnvironment",
                mapOf(
                    "KMS_PASSWORD" to TEST_MASTER_KEY,
                    "DB_PASSWORD" to "ENC($encrypted)",
                ),
            ),
        )
        context.register(KmsConfig::class.java, JasyptConfig::class.java)

        context.use {
            it.refresh()
            assertEquals(TEST_SECRET, it.environment.getProperty("DB_PASSWORD"))
        }
    }

    @Test
    fun `missing master key fails context startup`() {
        val context = AnnotationConfigApplicationContext()
        context.register(KmsConfig::class.java, JasyptConfig::class.java)

        context.use {
            assertFailsWith<BeanCreationException> { it.refresh() }
        }
    }

    @Test
    fun `wrong master key cannot decrypt ciphertext`() {
        val encrypted = JasyptConfig(TEST_MASTER_KEY).jasyptStringEncryptor().encrypt(TEST_SECRET)
        val wrongEncryptor = JasyptConfig("different-test-key").jasyptStringEncryptor()

        assertFailsWith<EncryptionOperationNotPossibleException> {
            wrongEncryptor.decrypt(encrypted)
        }
    }

    @Test
    fun `malformed ciphertext cannot be decrypted`() {
        val encryptor = JasyptConfig(TEST_MASTER_KEY).jasyptStringEncryptor()

        assertFailsWith<EncryptionOperationNotPossibleException> {
            encryptor.decrypt("not-valid-ciphertext")
        }
    }

    private companion object {
        const val TEST_MASTER_KEY = "welfare-serving-unit-test-key"
        const val TEST_SECRET = "test-database-password"
    }
}
