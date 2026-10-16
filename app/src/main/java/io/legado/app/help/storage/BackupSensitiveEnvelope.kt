package io.legado.app.help.storage

/** 当前分支敏感载荷标记；无标记内容仅为 main 既有备份的 AES 兼容读取路径。 */
object BackupSensitiveEnvelope {
    private const val PREFIX = "LEGADO_SENSITIVE_V1:"

    fun encrypt(aes: BackupAES, plainText: String): String =
        PREFIX + aes.encryptBase64(plainText)

    fun decrypt(aes: BackupAES, value: String): String =
        aes.decryptStr(value.removePrefix(PREFIX))

    fun isEncrypted(value: String): Boolean = value.startsWith(PREFIX)
}
