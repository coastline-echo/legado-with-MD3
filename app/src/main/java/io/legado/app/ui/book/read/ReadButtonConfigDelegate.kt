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
 *
 * 顶栏/底栏按钮配置与「更多操作」统一存 DataStore（ReadSettings），随 readSettings.json
 * 一起备份恢复；旧版存在 SharedPreferences 中的配置在首次启动时迁移一次。
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

    /** 从 DataStore 读回按钮配置，同时把旧 SP 数据迁一次。VM 构造时调一次。 */
    fun refresh() {
        val settings = readSettingsRepository.currentSettings
        host.updateMenuConfig {
            it.copy(
                titleBarButtons = parseButtonConfig(settings.titleBarButtons).toImmutableList(),
                bottomBarButtons = parseButtonConfig(settings.bottomBarButtons).toImmutableList(),
            )
        }
        // 旧版 SP 迁移：只跑一次，迁完清空 SP
        migrateLegacySharedPrefs()
    }

    fun saveTitleBarButtons(items: List<ReadBookButtonConfigItem>) {
        val normalized = normalizeButtonConfig(items)
        scope.launch {
            readSettingsRepository.update { settings ->
                settings.copy(titleBarButtons = encodeButtonConfig(normalized))
            }
        }
        host.updateMenuConfig { it.copy(titleBarButtons = normalized.toImmutableList()) }
    }

    fun saveMenuButtons(items: List<ReadBookButtonConfigItem>) {
        val normalized = normalizeButtonConfig(items)
        scope.launch {
            readSettingsRepository.update { settings ->
                settings.copy(bottomBarButtons = encodeButtonConfig(normalized))
            }
        }
        host.updateMenuConfig { it.copy(bottomBarButtons = normalized.toImmutableList()) }
    }

    /**
     * 「更多操作」菜单项配置。与顶栏/底栏按钮一样存 DataStore（ReadSettings），
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

private fun parseButtonConfig(raw: String): List<ReadBookButtonConfigItem> {
        if (raw.isBlank()) {
            return ReadBookButtonIds.map { id ->
                ReadBookButtonConfigItem(
                    id = id,
                    enabled = id in DEFAULT_ENABLED_BUTTON_IDS,
                )
            }
        }
        val seen = mutableSetOf<String>()
        val items = raw.split(";").mapNotNull { token ->
            val parts = token.split(",")
            val id = parts.getOrNull(0)?.takeIf { it in ReadBookButtonIds && seen.add(it) }
            val enabled = parts.getOrNull(1)?.toBooleanStrictOrNull()
            if (id != null && enabled != null) ReadBookButtonConfigItem(id, enabled) else null
        }.toMutableList()
        ReadBookButtonIds.forEach { id ->
            if (seen.add(id)) items.add(ReadBookButtonConfigItem(id, false))
        }
        return items
    }

    private fun normalizeButtonConfig(
        items: List<ReadBookButtonConfigItem>,
    ): List<ReadBookButtonConfigItem> {
        val seen = mutableSetOf<String>()
        val normalized = items.mapNotNull { item ->
            val id = item.id
            if (id in ReadBookButtonIds && seen.add(id)) {
                ReadBookButtonConfigItem(id, item.enabled)
            } else {
                null
            }
        }.toMutableList()
        ReadBookButtonIds.forEach { id ->
            if (seen.add(id)) {
                normalized.add(ReadBookButtonConfigItem(id, false))
            }
        }
        return normalized
    }

    private fun encodeButtonConfig(items: List<ReadBookButtonConfigItem>): String =
        items.joinToString(";") { "${it.id},${it.enabled}" }

    /** 旧版顶栏/底栏按钮配置存在 SharedPreferences 中，首次启动迁到 DataStore 后清空。 */
    private fun migrateLegacySharedPrefs() {
        val migrated = context
            .getSharedPreferences(MIGRATED_FLAG_PREFS, Context.MODE_PRIVATE)
            .getBoolean(MIGRATED_FLAG_KEY, false)
        if (migrated) return
        val titleBarRaw = context
            .getSharedPreferences(TITLE_BAR_PREFS, Context.MODE_PRIVATE)
            .getString(TITLE_BAR_KEY, null)
        val bottomBarRaw = context
            .getSharedPreferences(TOOL_BUTTON_PREFS, Context.MODE_PRIVATE)
            .getString(TOOL_BUTTON_KEY, null)
        if (titleBarRaw != null || bottomBarRaw != null) {
            scope.launch {
                readSettingsRepository.update { settings ->
                    settings.copy(
                        titleBarButtons = titleBarRaw ?: settings.titleBarButtons,
                        bottomBarButtons = bottomBarRaw ?: settings.bottomBarButtons,
                    )
                }
            }
        }
        context
            .getSharedPreferences(MIGRATED_FLAG_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(MIGRATED_FLAG_KEY, true)
            .apply()
    }

    private companion object {
        const val TITLE_BAR_PREFS = "title_bar_icons"
        const val TITLE_BAR_KEY = "icons"
        const val TOOL_BUTTON_PREFS = "tool_button_config"
        const val TOOL_BUTTON_KEY = "tool_buttons"
        const val MIGRATED_FLAG_PREFS = "read_menu_button_migration"
        const val MIGRATED_FLAG_KEY = "done"
        val DEFAULT_ENABLED_BUTTON_IDS = setOf(
            "search",
            "auto_page",
            "catalog",
            "read_aloud",
            "setting",
        )
    }
}