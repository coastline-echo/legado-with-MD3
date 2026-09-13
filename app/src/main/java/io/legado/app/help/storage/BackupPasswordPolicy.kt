package io.legado.app.help.storage

data class BackupPasswordPolicy(
    val passwordConfigured: Boolean,
    val weakProtection: Boolean,
    val warning: String? = null,
)

object BackupPasswordPolicyResolver {

    fun resolve(password: String?): BackupPasswordPolicy {
        val configured = !password.isNullOrEmpty()
        return BackupPasswordPolicy(
            passwordConfigured = configured,
            weakProtection = !configured,
            warning = if (configured) null else "备份密码为空，敏感信息仅受弱保护",
        )
    }
}
