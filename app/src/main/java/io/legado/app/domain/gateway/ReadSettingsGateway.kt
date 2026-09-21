package io.legado.app.domain.gateway

import io.legado.app.domain.model.settings.ReadSettings
import kotlinx.coroutines.flow.Flow

interface ReadSettingsGateway {
    val currentSettings: ReadSettings
    val settings: Flow<ReadSettings>

    /**
     * 持久化 [ReadSettings] 的任意字段：transform 里 copy 谁，谁就落盘。
     *
     * R1.5 之前只覆盖 46/102 个键，copy 到未覆盖的字段会被静默丢弃；现在实现侧的映射
     * 与 [ReadSettings] 一一对应，由 `ReadSettingsGatewayCoverageTest` 把关。
     * 只有被 transform 改动的键会产生写入。
     */
    suspend fun update(transform: (ReadSettings) -> ReadSettings)

    /** 导出完整 ReadSettings 供备份。 */
    fun exportJson(): String

    /** 从 JSON 恢复 ReadSettings（仅用于备份恢复）。 */
    suspend fun importJson(json: String)
}
