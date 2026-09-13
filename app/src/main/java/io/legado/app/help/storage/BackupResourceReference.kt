package io.legado.app.help.storage

/** 将备份中的逻辑资源别名解析为当前设备路径。 */
object BackupResourceReference {

    fun localPath(
        reference: String?,
        aliases: Map<String, String>,
        localRoot: String,
    ): String? {
        if (reference.isNullOrBlank()) return null
        val alias = reference.substringAfterLast('/').substringAfterLast('\\')
        val relative = aliases[alias] ?: return null
        return localRoot.trimEnd('/', '\\') + "/" + relative.substringAfterLast('/')
    }
}
