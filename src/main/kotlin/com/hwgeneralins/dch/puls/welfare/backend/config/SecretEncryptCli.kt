package com.hwgeneralins.dch.puls.welfare.backend.config

internal fun encryptSecretPropertyValue(masterKey: String, plainText: String): String {
    require(masterKey.isNotBlank()) { "KMS_PASSWORD must be configured" }
    require(plainText.isNotEmpty()) { "SECRET_TO_ENCRYPT must be configured" }

    val cipherText = JasyptConfig(masterKey).jasyptStringEncryptor().encrypt(plainText)
    return "ENC($cipherText)"
}

fun main() {
    val masterKey = System.getenv("KMS_PASSWORD")
        ?: throw IllegalArgumentException("KMS_PASSWORD must be configured")
    val plainText = System.getenv("SECRET_TO_ENCRYPT")
        ?: throw IllegalArgumentException("SECRET_TO_ENCRYPT must be configured")

    println(encryptSecretPropertyValue(masterKey, plainText))
}
