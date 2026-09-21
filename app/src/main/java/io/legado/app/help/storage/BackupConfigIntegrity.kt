package io.legado.app.help.storage

/** 配置键在备份链路中的最终状态。 */
enum class BackupConfigKeyStatus {
    WRITTEN,
    IGNORED,
    UNSUPPORTED,
    WRITE_FAILED,
    NOT_SET,
}

data class BackupConfigIntegrity(
    val written: Set<String>,
    val ignored: Set<String>,
    val unsupported: Set<String>,
    val failed: Set<String>,
    val notSet: Set<String>,
) {
    fun status(key: String): BackupConfigKeyStatus = when {
        key in written -> BackupConfigKeyStatus.WRITTEN
        key in ignored -> BackupConfigKeyStatus.IGNORED
        key in unsupported -> BackupConfigKeyStatus.UNSUPPORTED
        key in failed -> BackupConfigKeyStatus.WRITE_FAILED
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
    ): BackupConfigIntegrity {
        // 归一化为互斥状态：写入成功优先；对未写入键，类型不支持比“忽略”更具体。
        val normalizedWritten = written.intersect(snapshotKeys)
        val normalizedUnsupported = (unsupported - normalizedWritten).intersect(snapshotKeys)
        val normalizedIgnored = (ignored - normalizedWritten - normalizedUnsupported).intersect(snapshotKeys)
        val failed = snapshotKeys - normalizedWritten - normalizedIgnored - normalizedUnsupported
        return BackupConfigIntegrity(
            written = normalizedWritten,
            ignored = normalizedIgnored,
            unsupported = normalizedUnsupported,
            failed = failed,
            notSet = requiredKeys - snapshotKeys,
        )
    }
}
