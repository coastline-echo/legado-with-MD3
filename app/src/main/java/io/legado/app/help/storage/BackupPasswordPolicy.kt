package io.legado.app.help.storage

data class BackupPasswordPolicy(
    val passwordConfigured: Boolean,
    val weakProtection: Boolean,
    val warning: String? = null,
)

object BackupPasswordPolicyResolver {

    /** 空字符串表示用户明确设置了独立空密码，不能与未设置（null）混淆。 */
    fun resolve(
        password: String?,
        explicitlyConfigured: Boolean,
    ): BackupPasswordPolicy {
        val configured = explicitlyConfigured && !password.isNullOrEmpty()
        return BackupPasswordPolicy(
            passwordConfigured = configured,
            weakProtection = !configured,
            warning = if (configured) null else "本地密码为空，敏感信息仅受弱保护",
        )
    }

    fun resolve(password: String?): BackupPasswordPolicy {
        val configured = !password.isNullOrEmpty()
        return BackupPasswordPolicy(
            passwordConfigured = configured,
            weakProtection = !configured,
            warning = if (configured) null else "本地密码为空，敏感信息仅受弱保护",
        )
    }
}
