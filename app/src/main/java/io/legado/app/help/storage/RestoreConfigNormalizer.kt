package io.legado.app.help.storage

import io.legado.app.constant.PreferKey

internal data class RestoreConfigNormalization(
    val values: Map<String, Any>,
    val ignoredKeys: Set<String> = emptySet(),
    val unsupportedKeys: Set<String> = emptySet(),
    val decryptionFailedKeys: Set<String> = emptySet(),
)

/**
 * 把 config.xml 解析出的原始键值规整为可直接批量写入设置存储的 map（纯函数，便于 JVM 单测）：
 * - [PreferKey.webDavPassword]：AES 解密；仅旧备份且本地无密码时容许明文兜底，否则丢弃；
 * - Double 转 Float（JSON 数字会被解析为 Double）；
 * - 旧版 [PreferKey.bookInfoBackgroundBlur] 拆分补写两个新背景键（备份中缺新键且未被忽略时）；
 * - 过滤忽略键与 null 值。
 */
internal fun normalizeConfigMap(
    map: Map<String, Any?>,
    keyIsNotIgnore: (String) -> Boolean,
    decryptWebDavPassword: (String) -> String?,
    hasLocalWebDavPassword: Boolean,
    allowLegacyPlaintextFallback: Boolean = false,
): Map<String, Any> {
    return normalizeConfigMapWithReport(
        map, keyIsNotIgnore, decryptWebDavPassword, hasLocalWebDavPassword, allowLegacyPlaintextFallback,
    ).values
}

internal fun normalizeConfigMapWithReport(
    map: Map<String, Any?>,
    keyIsNotIgnore: (String) -> Boolean,
    decryptWebDavPassword: (String) -> String?,
    hasLocalWebDavPassword: Boolean,
    allowLegacyPlaintextFallback: Boolean = false,
): RestoreConfigNormalization {
    val finalMap = mutableMapOf<String, Any>()
    val ignoredKeys = linkedSetOf<String>()
    val unsupportedKeys = linkedSetOf<String>()
    val decryptionFailedKeys = linkedSetOf<String>()
    map.forEach { (key, value) ->
        if (!keyIsNotIgnore(key)) {
            ignoredKeys += key
            return@forEach
        }
        // 旧书架分组和亮度键仅作兼容输入，不能写回 main 已迁移的存储键。
        if (key == PreferKey.saveTabPosition ||
            key == PreferKey.showBrightnessView || key == PreferKey.brightnessVwPos) {
            ignoredKeys += key
            return@forEach
        }
        when (key) {
            PreferKey.webDavPassword -> {
                val decrypted = decryptWebDavPassword(value.toString())
                val password = decrypted
                    ?: value.toString().takeIf {
                        allowLegacyPlaintextFallback && !hasLocalWebDavPassword &&
                            !BackupSensitiveEnvelope.isEncrypted(it)
                    }
                if (password == null) decryptionFailedKeys += key
                password?.let { finalMap[key] = it }
            }

            else -> when (value) {
                is Double -> finalMap[key] = value.toFloat()
                is Int, is Boolean, is Long, is Float, is String,
                is Set<*> -> finalMap[key] = value
                else -> unsupportedKeys += key
            }
        }
    }
    if (PreferKey.bookshelfSelectedGroupId !in map &&
        keyIsNotIgnore(PreferKey.saveTabPosition) &&
        keyIsNotIgnore(PreferKey.bookshelfSelectedGroupId)
    ) {
        (map[PreferKey.saveTabPosition] as? Long)?.let {
            finalMap[PreferKey.bookshelfSelectedGroupId] = it
        }
    }
    mapOf(
        PreferKey.showBrightnessView to PreferKey.readBrightnessMode,
        PreferKey.brightnessVwPos to PreferKey.readBrightnessControlPosition,
    ).forEach { (legacyKey, newKey) ->
        if (newKey !in map && keyIsNotIgnore(legacyKey) && keyIsNotIgnore(newKey)) {
            when (val legacy = map[legacyKey]) {
                is String -> finalMap[newKey] = legacy
                is Boolean -> finalMap[newKey] = if (legacy) "1" else "0"
            }
        }
    }
    val legacyBookInfoBackground = map[PreferKey.bookInfoBackgroundBlur] as? String
    if (legacyBookInfoBackground != null && keyIsNotIgnore(PreferKey.bookInfoBackgroundBlur)) {
        listOf(
            PreferKey.bookInfoNetworkCoverBackground,
            PreferKey.bookInfoDefaultCoverBackground,
        ).forEach { newKey ->
            if (newKey !in map && keyIsNotIgnore(newKey)) {
                finalMap[newKey] = legacyBookInfoBackground
            }
        }
    }
    return RestoreConfigNormalization(finalMap, ignoredKeys, unsupportedKeys, decryptionFailedKeys)
}
