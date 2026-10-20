package io.legado.app.ui.book.read

import android.content.Context
import android.net.Uri
import io.legado.app.data.repository.ReadSettingsRepository
import io.legado.app.help.coroutine.Coroutine
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

/**
 * 阅读菜单按钮配置域（R2.2 续批）。
 *
 * 管顶栏/底栏按钮与「更多操作」菜单项的排序与开关，以及两处自定义图标的落盘。
 * 按钮列表本身仍住在 [ReadMenuConfig] 里（菜单栏各处直读，搬出去要改一大片 UI），
 * 故本 delegate **无自持状态**，和 [ReadConfigUpdateDelegate] 同形：一切读写经 [Host]。
 */
class ReadButtonConfigDelegate(
    private val context: Context,
    private val scope: CoroutineScope,
    private val readSettingsRepository: ReadSettingsRepository,
    private val host: Host,
) {

    interface Host {
        fun updateMenuConfig(transform: (ReadMenuConfig) -> ReadMenuConfig)

        /** 自定义图标落盘后走配置更新分发，通知菜单重读。 */
        fun applyConfigUpdate(update: ConfigUpdate)
    }

    /** 旧 SP 迁移与备份导入在 Repository 串行化，迁移完成后再读取 DataStore。 */
    fun refresh() {
        scope.launch {
            readSettingsRepository.migrateLegacyMenuButtons(context)
            val settings = readSettingsRepository.currentSettings
            host.updateMenuConfig {
                it.copy(
                    titleBarButtons = parseButtonConfig(settings.titleBarButtons).toImmutableList(),
                    bottomBarButtons = parseButtonConfig(settings.bottomBarButtons).toImmutableList(),
                    titleBarActions = parseButtonConfig(
                        settings.titleBarActions, ReadMenuActionIds, DEFAULT_TITLE_BAR_ACTION_IDS,
                    ).toImmutableList(),
                    moreActionItems = parseMoreActions(settings.moreActionsConfig).toImmutableList(),
                )
            }
        }
    }

    fun saveTitleBarButtons(items: List<ReadBookButtonConfigItem>) {
        val normalized = normalizeButtonConfig(items, ReadBookButtonIds)
        scope.launch {
            readSettingsRepository.update { settings ->
                settings.copy(titleBarButtons = encodeButtonConfig(normalized))
            }
        }
        host.updateMenuConfig { it.copy(titleBarButtons = normalized.toImmutableList()) }
    }

    fun saveMenuButtons(items: List<ReadBookButtonConfigItem>) {
        val normalized = normalizeButtonConfig(items, ReadBookButtonIds)
        scope.launch {
            readSettingsRepository.update { settings ->
                settings.copy(bottomBarButtons = encodeButtonConfig(normalized))
            }
        }
        host.updateMenuConfig { it.copy(bottomBarButtons = normalized.toImmutableList()) }
    }

    /** 保存顶栏按钮的顺序与开关。 */
    fun saveTitleBarActions(items: List<ReadBookButtonConfigItem>) {
        val normalized = normalizeButtonConfig(items, ReadMenuActionIds)
        scope.launch {
            readSettingsRepository.update { settings ->
                settings.copy(titleBarActions = encodeButtonConfig(normalized))
            }
        }
        host.updateMenuConfig { it.copy(titleBarActions = normalized.toImmutableList()) }
    }

    /**
     * 「更多操作」菜单项配置。与顶栏/底栏不同：存 DataStore（`moreActionsConfig`），
     * 未出现在配置串里的项默认**开启**（顶栏/底栏是默认关闭）。
     */
    fun saveMoreActions(items: List<ReadBookButtonConfigItem>) {
        val normalized = normalizeMoreActions(items)
        scope.launch {
            readSettingsRepository.update { settings ->
                settings.copy(
                    moreActionsConfig = normalized.joinToString(";") { "${it.id},${it.enabled}" }
                )
            }
        }
        host.updateMenuConfig { it.copy(moreActionItems = normalized.toImmutableList()) }
    }

    fun parseMoreActions(raw: String): List<ReadBookButtonConfigItem> {
        if (raw.isBlank()) return MoreActionIds.map { ReadBookButtonConfigItem(it, true) }
        val seen = mutableSetOf<String>()
        val items = raw.split(";").mapNotNull { token ->
            val parts = token.split(",")
            val id = parts.getOrNull(0)?.takeIf { it in MoreActionIds && seen.add(it) }
            val enabled = parts.getOrNull(1)?.toBooleanStrictOrNull()
            if (id != null && enabled != null) ReadBookButtonConfigItem(id, enabled) else null
        }.toMutableList()
        MoreActionIds.forEach { id ->
            if (seen.add(id)) items.add(ReadBookButtonConfigItem(id, true))
        }
        return items
    }

    private fun normalizeMoreActions(
        items: List<ReadBookButtonConfigItem>,
    ): List<ReadBookButtonConfigItem> {
        val seen = mutableSetOf<String>()
        val normalized = items.mapNotNull { item ->
            item.takeIf { it.id in MoreActionIds && seen.add(it.id) }
        }.toMutableList()
        MoreActionIds.forEach { id ->
            if (seen.add(id)) normalized.add(ReadBookButtonConfigItem(id, true))
        }
        return normalized
    }

    fun saveMenuCustomIcon(id: String, uri: Uri) {
        copyCustomIcon(id, uri, "read_menu_icons") { path ->
            ConfigUpdate.MenuCustomIcon(id, path)
        }
    }

    fun saveTitleBarCustomIcon(id: String, uri: Uri) {
        copyCustomIcon(id, uri, "title_bar_icons") { path ->
            ConfigUpdate.TitleBarCustomIcon(id, path)
        }
    }

    private fun copyCustomIcon(
        id: String,
        uri: Uri,
        directoryName: String,
        update: (path: String) -> ConfigUpdate,
    ) {
        Coroutine.async(scope, Dispatchers.IO) {
            val iconFile = File(context.filesDir, "$directoryName/$id.png")
            iconFile.parentFile?.mkdirs()
            context.contentResolver.openInputStream(uri)?.use { input ->
                iconFile.outputStream().use { output -> input.copyTo(output) }
            }
            host.applyConfigUpdate(update(iconFile.absolutePath))
        }
    }

    private fun normalizeButtonConfig(
        items: List<ReadBookButtonConfigItem>,
        ids: List<String> = ReadMenuActionIds,
    ): List<ReadBookButtonConfigItem> {
        val seen = mutableSetOf<String>()
        val normalized = items.mapNotNull { item ->
            val id = item.id
            if (id in ids && seen.add(id)) {
                ReadBookButtonConfigItem(id, item.enabled)
            } else {
                null
            }
        }.toMutableList()
        ids.forEach { id ->
            if (seen.add(id)) {
                normalized.add(ReadBookButtonConfigItem(id, false))
            }
        }
        return normalized
    }

    private fun encodeButtonConfig(items: List<ReadBookButtonConfigItem>): String =
        items.joinToString(";") { "${it.id},${it.enabled}" }

    private fun parseButtonConfig(
        raw: String,
        ids: List<String> = ReadBookButtonIds,
        defaultEnabled: Set<String> = DEFAULT_ENABLED_BUTTON_IDS,
    ): List<ReadBookButtonConfigItem> {
        if (raw.isBlank()) return ids.map { ReadBookButtonConfigItem(it, it in defaultEnabled) }
        val seen = mutableSetOf<String>()
        val result = raw.split(";").mapNotNull { token ->
            val parts = token.split(",")
            val id = parts.getOrNull(0)?.takeIf { it in ids && seen.add(it) }
            val enabled = parts.getOrNull(1)?.toBooleanStrictOrNull()
            if (id != null && enabled != null) ReadBookButtonConfigItem(id, enabled) else null
        }.toMutableList()
        ids.forEach { id -> if (seen.add(id)) result.add(ReadBookButtonConfigItem(id, false)) }
        return result
    }

    private companion object {
        val DEFAULT_ENABLED_BUTTON_IDS = setOf(
            "search",
            "auto_page",
            "catalog",
            "read_aloud",
            "setting",
        )

        /**
         * 顶栏默认：下载、刷新、换源、更多菜单；
         * 目录规则（仅 TXT）与字符编码只对本地书生效，所以本地书默认仍是旧版那套。
         */
        val DEFAULT_TITLE_BAR_ACTION_IDS = setOf(
            "download",
            "refresh",
            "change_source",
            "toc_rule",
            "charset",
            "more_actions",
        )
    }
}
