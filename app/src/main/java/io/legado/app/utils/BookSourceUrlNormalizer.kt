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
    if (cleaned.contains(Regex("^https\\?://", RegexOption.IGNORE_CASE)) ||
        cleaned.contains("(?!") || cleaned.contains(".*")
    ) return null
    val hasScheme = Regex("^[A-Za-z][A-Za-z0-9+.-]*://").containsMatchIn(cleaned)
    val parseValue = if (hasScheme) cleaned else "https://$cleaned"
    val uri = runCatching { URI(parseValue) }.getOrNull() ?: return null
    val scheme = uri.scheme ?: return null
    val authority = uri.rawAuthority?.substringAfterLast('@') ?: return null
    val host = authority.substringBefore(':').trim('[', ']').lowercase(Locale.ROOT)
    if (host.isEmpty()) return null
    val normalizedAuthority = buildString {
        uri.userInfo?.let { append(it).append('@') }
        append(host)
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

/** 导入兼容性检查。为兼容旧书源只要求地址非空；严格 URI 解析留给判重提示和运行时请求校验。 */
fun isUsableBookSourceUrl(value: String): Boolean {
    val cleaned = value.trim()
    return cleaned.isNotEmpty() &&
        !cleaned.contains(Regex("^https\\?://", RegexOption.IGNORE_CASE)) &&
        !cleaned.contains("(?!") && !cleaned.contains(".*")
}

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
