package io.legado.app.help.storage

/** 配置键在备份链路中的最终状态。 */
enum class BackupConfigKeyStatus {
    WRITTEN,
    IGNORED,
    UNSUPPORTED,
    NOT_SET,
}

data class BackupConfigIntegrity(
    val written: Set<String>,
    val ignored: Set<String>,
    val unsupported: Set<String>,
    val notSet: Set<String>,
) {
    fun status(key: String): BackupConfigKeyStatus = when {
        key in written -> BackupConfigKeyStatus.WRITTEN
        key in ignored -> BackupConfigKeyStatus.IGNORED
        key in unsupported -> BackupConfigKeyStatus.UNSUPPORTED
        else -> BackupConfigKeyStatus.NOT_SET
    }
}

object BackupConfigIntegrityChecker {

    fun check(
        snapshotKeys: Set<String>,
        written: Set<String>,
        ignored: Set<String>,
        unsupported: Set<String>,
        requiredKeys: Set<String> = emptySet(),
    ): BackupConfigIntegrity = BackupConfigIntegrity(
        written = written,
        ignored = ignored,
        unsupported = unsupported,
        notSet = requiredKeys - snapshotKeys,
    )
}
