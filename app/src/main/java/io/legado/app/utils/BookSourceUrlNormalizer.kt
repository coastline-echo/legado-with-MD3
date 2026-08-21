package io.legado.app.utils

import java.net.URI
import java.util.Locale

data class BookSourceUrlIdentity(
    val normalizedUrl: String,
    val host: String,
)

/** Normalization used only for duplicate detection; the original URL is never rewritten. */
fun normalizeBookSourceUrl(value: String): BookSourceUrlIdentity? {
    val cleaned = value
        .trim { it.isWhitespace() || it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() }
        .replace(Regex("##@[^/?#]*$"), "")
    if (cleaned.isEmpty()) return null
    val uri = runCatching { URI(cleaned) }.getOrNull() ?: return null
    val scheme = uri.scheme ?: return null
    val host = uri.host?.lowercase(Locale.ROOT) ?: return null
    if (host.isEmpty()) return null
    val authority = buildString {
        uri.userInfo?.let { append(it).append('@') }
        append(host)
        if (uri.port != -1) append(':').append(uri.port)
    }
    val path = uri.rawPath.orEmpty().trimEnd('/')
    val normalized = URI(scheme.lowercase(Locale.ROOT), authority, path.ifEmpty { null }, uri.rawQuery, null).toString()
    return BookSourceUrlIdentity(normalized, host)
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
