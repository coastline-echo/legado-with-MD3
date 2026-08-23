package io.legado.app.domain.usecase

import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.Book
import io.legado.app.domain.gateway.BookSourceCheckState
import io.legado.app.domain.gateway.BookSourceCheckStatus
import io.legado.app.utils.BookSourceUrlIdentity
import io.legado.app.utils.normalizeBookSourceUrl
import java.util.Locale

enum class BookSourceMatchType {
    NormalizedUrl,
    SameHost,
    SameSearchEntry,
    SimilarStructure,
    SimilarName,
}

enum class BookSourceRuleStatus {
    NotConfigured,
    ConfiguredUnverified,
    ValidationSucceeded,
    ValidationFailed,
}

enum class BookSourceRecommendationReason {
    SearchValidated,
    ExploreValidated,
    InfoValidated,
    TocValidated,
    ContentValidated,
    ReferencedBooks,
    RulesConfigured,
    NoValidatedAdvantage,
}

data class BookSourceRuleProfile(
    val search: BookSourceRuleStatus,
    val explore: BookSourceRuleStatus,
    val info: BookSourceRuleStatus,
    val toc: BookSourceRuleStatus,
    val content: BookSourceRuleStatus,
    val login: BookSourceRuleStatus,
)

data class BookSourceValueProfile(
    val source: BookSource,
    val identity: BookSourceUrlIdentity?,
    val rules: BookSourceRuleProfile,
    val referencedBookCount: Int,
    val hasCookie: Boolean,
    val hasVariablesOrCache: Boolean,
    val lastCheckStatus: BookSourceRuleStatus,
    val responseTime: Long,
    val lastUpdateTime: Long,
    val score: Int,
    val reasons: List<BookSourceRecommendationReason>,
)

data class BookSourceRecommendation(
    val sourceUrl: String,
    val score: Int,
    val reasons: List<BookSourceRecommendationReason>,
)

data class BookSourceCandidateGroup(
    val matchType: BookSourceMatchType,
    val sources: List<BookSourceValueProfile>,
    val recommendation: BookSourceRecommendation,
)

data class BookSourceDeletePreview(
    val source: BookSource,
    val referencedBooks: List<Book>,
    val hasCookie: Boolean,
    val hasVariablesOrCache: Boolean,
) {
    val requiresBookDecision: Boolean
        get() = referencedBooks.isNotEmpty()
}

/** 书源重复检测和价值比较。只读分析，不修改任何书源或关联数据。 */
class BookSourceDedupUseCase(
    private val database: AppDatabase,
) {

    /** 导入预览使用的静态比较分数；未经过网络校验，不代表书源可用。 */
    fun importValueScore(source: BookSource): Int = listOf(
        source.ruleSearch,
        source.ruleExplore,
        source.ruleBookInfo,
        source.ruleToc,
        source.ruleContent,
    ).count { it != null } * 10 +
        if (!source.loginUrl.isNullOrBlank()) 2 else 0

    suspend fun previewDelete(sourceUrl: String): BookSourceDeletePreview? {
        val source = database.bookSourceDao.getBookSource(sourceUrl) ?: return null
        return BookSourceDeletePreview(
            source = source,
            referencedBooks = database.bookDao.getByOrigin(sourceUrl),
            hasCookie = database.cookieDao.hasUrl(sourceUrl),
            hasVariablesOrCache = database.cacheDao.hasSourceData(sourceUrl),
        )
    }

    suspend fun scan(checkState: BookSourceCheckState = BookSourceCheckState()): List<BookSourceCandidateGroup> {
        val sources = database.bookSourceDao.all
        val profiles = sources.map { source ->
            val check = checkState.results[source.bookSourceUrl]
            BookSourceValueProfile(
                source = source,
                identity = normalizeBookSourceUrl(source.bookSourceUrl),
                rules = source.ruleProfile(check?.status),
                referencedBookCount = database.bookDao.countByOrigin(source.bookSourceUrl),
                hasCookie = database.cookieDao.hasUrl(source.bookSourceUrl),
                hasVariablesOrCache = database.cacheDao.hasSourceData(source.bookSourceUrl),
                lastCheckStatus = check?.status.toRuleStatus(),
                responseTime = source.respondTime,
                lastUpdateTime = source.lastUpdateTime,
                score = 0,
                reasons = emptyList(),
            ).let { profile -> profile.copy(score = score(profile), reasons = reasons(profile)) }
        }
        val groups = buildList {
            profiles.filter { it.identity != null }.groupBy { it.identity!!.normalizedUrl }.values
                .filter { it.size > 1 }
                .forEach { add(BookSourceMatchType.NormalizedUrl to it) }
            profiles.filter { it.identity != null }.groupBy { it.identity?.host }.values
                .filter { it.size > 1 }
                .forEach { add(BookSourceMatchType.SameHost to it) }
            profiles.filter { !it.source.searchUrl.isNullOrBlank() }
                .groupBy { normalizeSearchHint(it.source.searchUrl) }.values
                .filter { it.size > 1 }
                .forEach { add(BookSourceMatchType.SameSearchEntry to it) }
            profiles.groupBy { it.source.bookSourceType to ruleShape(it.source) }.values
                .filter { it.size > 1 && ruleShape(it.first().source) != 0 }
                .forEach { add(BookSourceMatchType.SimilarStructure to it) }
            profiles.groupBy { normalizeSourceName(it.source.bookSourceName) }.values
                .filter { it.size > 1 && it.first().source.bookSourceName.isNotBlank() }
                .forEach { add(BookSourceMatchType.SimilarName to it) }
        }
        return groups.map { (type, items) ->
            val sorted = items.sortedWith(compareByDescending<BookSourceValueProfile> { it.score }
                .thenBy { it.responseTime }
                .thenByDescending { it.lastUpdateTime })
            BookSourceCandidateGroup(type, sorted, recommendation(sorted.first()))
        }
    }

    private fun recommendation(profile: BookSourceValueProfile): BookSourceRecommendation =
        BookSourceRecommendation(profile.source.bookSourceUrl, score(profile), reasons(profile))

    private fun score(profile: BookSourceValueProfile): Int = buildList {
        add(profile.rules.search.weight())
        add(profile.rules.explore.weight())
        add(profile.rules.info.weight())
        add(profile.rules.toc.weight())
        add(profile.rules.content.weight())
        add(profile.rules.login.weight())
        if (profile.lastCheckStatus == BookSourceRuleStatus.ValidationSucceeded) add(20)
        if (profile.referencedBookCount > 0) add(10)
        if (profile.hasCookie) add(2)
        if (profile.hasVariablesOrCache) add(2)
        add((180000L - profile.responseTime).coerceIn(0, 180000L).toInt() / 1000)
    }.sum()

    private fun reasons(profile: BookSourceValueProfile): List<BookSourceRecommendationReason> = buildList {
        if (profile.rules.search == BookSourceRuleStatus.ValidationSucceeded) add(BookSourceRecommendationReason.SearchValidated)
        if (profile.rules.explore == BookSourceRuleStatus.ValidationSucceeded) add(BookSourceRecommendationReason.ExploreValidated)
        if (profile.rules.info == BookSourceRuleStatus.ValidationSucceeded) add(BookSourceRecommendationReason.InfoValidated)
        if (profile.rules.toc == BookSourceRuleStatus.ValidationSucceeded) add(BookSourceRecommendationReason.TocValidated)
        if (profile.rules.content == BookSourceRuleStatus.ValidationSucceeded) add(BookSourceRecommendationReason.ContentValidated)
        if (profile.referencedBookCount > 0) add(BookSourceRecommendationReason.ReferencedBooks)
        if (isConfigured(profile.rules)) add(BookSourceRecommendationReason.RulesConfigured)
        if (isEmpty()) add(BookSourceRecommendationReason.NoValidatedAdvantage)
    }

    private fun isConfigured(rules: BookSourceRuleProfile) = listOf(
        rules.search, rules.explore, rules.info, rules.toc, rules.content
    ).count { it != BookSourceRuleStatus.NotConfigured } >= 4
}

internal fun ruleShape(source: BookSource): Int {
    var shape = 0
    if (source.ruleSearch != null) shape = shape or 1
    if (source.ruleExplore != null) shape = shape or 2
    if (source.ruleBookInfo != null) shape = shape or 4
    if (source.ruleToc != null) shape = shape or 8
    if (source.ruleContent != null) shape = shape or 16
    return shape
}

internal fun normalizeSourceName(value: String): String = value
    .filterNot { it.isWhitespace() || it.isISOControl() }
    .lowercase(Locale.ROOT)
    .replace(Regex("[书源源站站点网站\\-_.()（）【】\\[\\]]"), "")

private fun BookSource.ruleProfile(checkStatus: BookSourceCheckStatus?): BookSourceRuleProfile =
    BookSourceRuleProfile(
        search = ruleStatus(ruleSearch != null, checkStatus),
        explore = ruleStatus(ruleExplore != null, checkStatus),
        info = ruleStatus(ruleBookInfo != null, checkStatus),
        toc = ruleStatus(ruleToc != null, checkStatus),
        content = ruleStatus(ruleContent != null, checkStatus),
        login = ruleStatus(!loginUrl.isNullOrBlank(), checkStatus),
    )

private fun ruleStatus(configured: Boolean, checkStatus: BookSourceCheckStatus?): BookSourceRuleStatus =
    when {
        !configured -> BookSourceRuleStatus.NotConfigured
        checkStatus == BookSourceCheckStatus.Succeeded -> BookSourceRuleStatus.ValidationSucceeded
        checkStatus == BookSourceCheckStatus.Failed -> BookSourceRuleStatus.ValidationFailed
        else -> BookSourceRuleStatus.ConfiguredUnverified
    }

private fun BookSourceCheckStatus?.toRuleStatus() = when (this) {
    BookSourceCheckStatus.Succeeded -> BookSourceRuleStatus.ValidationSucceeded
    BookSourceCheckStatus.Failed -> BookSourceRuleStatus.ValidationFailed
    else -> BookSourceRuleStatus.ConfiguredUnverified
}

private fun BookSourceRuleStatus.weight() = when (this) {
    BookSourceRuleStatus.NotConfigured -> 0
    BookSourceRuleStatus.ConfiguredUnverified -> 5
    BookSourceRuleStatus.ValidationSucceeded -> 12
    BookSourceRuleStatus.ValidationFailed -> 1
}

private fun normalizeSearchHint(value: String?): String? = value
    ?.filterNot { it.isWhitespace() || it.isISOControl() }
    ?.trim()
    ?.lowercase(Locale.ROOT)
    ?.takeIf { it.isNotEmpty() }
