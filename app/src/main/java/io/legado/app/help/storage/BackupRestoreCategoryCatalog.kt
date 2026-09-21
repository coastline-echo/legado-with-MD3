package io.legado.app.help.storage

/** 备份、预览和 planner 共用的数据库类别文件目录，避免类别集合漂移。 */
object BackupRestoreCategoryCatalog {
    val databaseFiles: Map<String, String> = linkedMapOf(
        "bookshelf" to "bookshelf.json",
        "bookmark" to "bookmark.json",
        "bookGroup" to "bookGroup.json",
        "bookSource" to "bookSource.json",
        "rssSource" to "rssSources.json",
        "rssStar" to "rssStar.json",
        "replaceRule" to "replaceRule.json",
        "searchHistory" to "searchHistory.json",
        "readRecord" to "readRecord.json",
        "server" to "servers.json",
        "sourceSub" to "sourceSub.json",
        "txtTocRule" to "txtTocRule.json",
        "httpTTS" to "httpTTS.json",
        "keyboardAssists" to "keyboardAssists.json",
        "dictRule" to "dictRule.json",
        "homepageModules" to "homepageModules.json",
        "homepageCustomSets" to "homepageCustomSets.json",
        "highlightRule" to "highlightRule.json",
        "highlightTagRule" to "highlightTagRule.json",
        "tagGroupRule" to "tagGroupRule.json",
    )

    val configurationCategories: Set<String> = setOf(
        "settings", "readConfig", "themeConfig", "coverConfig",
        "directLinkUploadRule", "resources", "readSettings",
    )

    val allCategories: Set<String> = databaseFiles.keys + configurationCategories
}
