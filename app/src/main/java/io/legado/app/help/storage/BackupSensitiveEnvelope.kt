package io.legado.app.help.storage

/** 敏感备份载荷的统一标记；无标记内容保留旧版 AES 兼容读取。 */
object BackupSensitiveEnvelope {
    private const val PREFIX = "LEGADO_SENSITIVE_V1:"

    fun encrypt(aes: BackupAES, plainText: String): String =
        PREFIX + aes.encryptBase64(plainText)

    fun decrypt(aes: BackupAES, value: String): String =
        aes.decryptStr(value.removePrefix(PREFIX))

    fun isEncrypted(value: String): Boolean = value.startsWith(PREFIX)
}
