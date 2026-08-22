package io.legado.app.utils

import java.net.URI
import java.util.Locale

data class BookSourceUrlIdentity(
    val normalizedUrl: String,
    val host: String,
)

/**
 * 构造导入阶段用于判重的比较身份。
 * 返回值不会写回 bookSourceUrl，因为该字段是书籍、Cookie、变量和缓存使用的存储关联键。
 * 因此，`##@名称` 之类的 URL 标记只在比较时移除，不会改写原始书源地址。
 */
fun normalizeBookSourceUrl(value: String): BookSourceUrlIdentity? {
    val cleaned = value
        .filterNot { it.isWhitespace() || it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() }
        .replace('＃', '#')
        .replace("\\://", "://")
        .replace(Regex("(?:已校验|已验证|已整理)$"), "")
        .substringBefore("##")
        .substringBefore("#")
        .trimEnd('/')
    if (cleaned.isEmpty()) return null
    // 旧书源可能使用显示名称作为 bookSourceUrl。只有看起来像 URL 的值参与
    // URL 身份比较；任意自定义主键都必须按原文保存，不能猜测成主机名。
    val hasExplicitScheme = Regex("^[A-Za-z][A-Za-z0-9+.-]*://").containsMatchIn(cleaned)
    val withoutScheme = cleaned.substringAfter("://", cleaned)
    val looksLikeHost = withoutScheme.startsWith("[") ||
        withoutScheme.substringBefore('/').substringBefore('?').contains('.')
    if (!hasExplicitScheme && !looksLikeHost) return null
    if (cleaned.contains(Regex("^https\\?://", RegexOption.IGNORE_CASE)) ||
        cleaned.contains("(?!") || cleaned.contains(".*")
    ) return null
    val parseValue = if (hasExplicitScheme) cleaned else "https://$cleaned"
    val uri = runCatching { URI(parseValue) }.getOrNull() ?: return null
    val scheme = uri.scheme ?: return null
    val authority = uri.rawAuthority?.substringAfterLast('@') ?: return null
    val authorityHost = authority.substringBefore('/').substringBefore('?')
        .substringAfterLast('@')
    val host = when {
        authorityHost.startsWith("[") -> authorityHost.substringBefore(']').trimStart('[')
        else -> authorityHost.substringBeforeLast(':').takeIf {
            authorityHost.count { char -> char == ':' } == 1
        } ?: authorityHost
    }.lowercase(Locale.ROOT)
    if (host.isEmpty()) return null
    val normalizedAuthority = buildString {
        uri.userInfo?.let { append(it).append('@') }
        if (host.contains(':')) append('[').append(host).append(']') else append(host)
        if (uri.port != -1) append(':').append(uri.port)
    }
    val path = uri.rawPath.orEmpty().trimEnd('/')
    val normalized = buildString {
        append(scheme.lowercase(Locale.ROOT)).append("://").append(normalizedAuthority)
        if (path.isNotEmpty()) append(path)
        uri.rawQuery?.let { append('?').append(it) }
    }
    return BookSourceUrlIdentity(normalized, host)
}

/**
 * 返回导入文件内部判重使用的键。
 * URL 型标识按规范化地址比较；名称型标识按原始值比较，避免猜测其网络含义。
 */
fun bookSourceImportDuplicateKey(value: String): String =
    normalizeBookSourceUrl(value)?.normalizedUrl ?: value

/** 识别不能作为书源地址保存的正则式地址写法。 */
fun isInvalidBookSourceImportPattern(value: String): Boolean =
    value.contains(Regex("^https\\?://", RegexOption.IGNORE_CASE)) ||
        value.contains("(?!") || value.contains(".*")

enum class BookSourceUrlConflict {
    None, Exact, Normalized, SameHost, Invalid
}

fun classifyBookSourceUrlConflict(
    incoming: String,
    existingUrls: Collection<String>,
): BookSourceUrlConflict {
    val incomingIdentity = normalizeBookSourceUrl(incoming) ?: return BookSourceUrlConflict.Invalid
    existingUrls.forEach { if (it == incoming) return BookSourceUrlConflict.Exact }
    val existingIdentities = existingUrls.mapNotNull(::normalizeBookSourceUrl)
    if (existingIdentities.any { it.normalizedUrl == incomingIdentity.normalizedUrl }) {
        return BookSourceUrlConflict.Normalized
    }
    if (existingIdentities.any { it.host == incomingIdentity.host }) return BookSourceUrlConflict.SameHost
    return BookSourceUrlConflict.None
}
