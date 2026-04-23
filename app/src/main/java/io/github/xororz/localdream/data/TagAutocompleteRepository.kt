package io.github.xororz.localdream.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.math.abs

class TagAutocompleteRepository private constructor(private val context: Context) {
    private val loadMutex = Mutex()
    @Volatile
    private var loadedData: TagData? = null

    suspend fun suggest(query: String, localeIsChinese: Boolean, limit: Int = 12): List<TagSuggestion> {
        val normalizedQuery = normalizeQuery(query)
        if (normalizedQuery.isEmpty()) return emptyList()

        val data = ensureLoaded()
        val isChineseInput = containsChinese(query)

        return withContext(Dispatchers.Default) {
            if (isChineseInput) {
                suggestChinese(data, normalizedQuery, limit)
            } else {
                suggestEnglish(data, normalizedQuery, localeIsChinese, limit)
            }
        }
    }

    private suspend fun ensureLoaded(): TagData {
        loadedData?.let { return it }

        return loadMutex.withLock {
            loadedData?.let { return@withLock it }
            val loaded = withContext(Dispatchers.IO) { loadData() }
            loadedData = loaded
            loaded
        }
    }

    private fun loadData(): TagData {
        val zhMap = loadChineseMap()
        val entries = mutableListOf<TagEntry>()
        val englishSet = LinkedHashSet<String>()

        context.assets.open(ENGLISH_ASSET_PATH).use { input ->
            BufferedReader(InputStreamReader(input)).useLines { lines ->
                lines.forEach { rawLine ->
                    val line = rawLine.trim()
                    if (line.isEmpty()) return@forEach
                    val cells = parseCsvLine(line)
                    if (cells.isEmpty()) return@forEach

                    val english = cells.getOrNull(0)?.trim().orEmpty()
                    if (english.isEmpty()) return@forEach
                    if (!englishSet.add(english)) return@forEach

                    val category = cells.getOrNull(1)?.trim()?.toIntOrNull() ?: 0
                    val postCount = cells.getOrNull(2)?.trim()?.toIntOrNull() ?: 0
                    val aliases = cells.getOrNull(3)
                        ?.split(',')
                        ?.map { it.trim() }
                        ?.filter { it.isNotEmpty() }
                        .orEmpty()

                    val zhCn = zhMap[english]
                    entries += TagEntry(
                        english = english,
                        zhCn = zhCn,
                        category = category,
                        postCount = postCount,
                        aliases = aliases,
                        normalizedEnglish = normalizeQuery(english),
                        normalizedAliases = aliases.map(::normalizeQuery).filter { it.isNotEmpty() },
                        normalizedZhCn = zhCn?.let(::normalizeChinese)
                    )
                }
            }
        }

        return TagData(entries = entries)
    }

    private fun loadChineseMap(): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        context.assets.open(CHINESE_ASSET_PATH).use { input ->
            BufferedReader(InputStreamReader(input)).useLines { lines ->
                lines.forEach { rawLine ->
                    val line = rawLine.trim()
                    if (line.isEmpty()) return@forEach
                    val cells = parseCsvLine(line)
                    if (cells.size < 2) return@forEach
                    val english = cells[0].removePrefix("\uFEFF").trim()
                    val zhCn = cells[1].trim()
                    if (english.isNotEmpty() && zhCn.isNotEmpty()) {
                        map[english] = zhCn
                    }
                }
            }
        }
        return map
    }

    private fun suggestChinese(data: TagData, normalizedQuery: String, limit: Int): List<TagSuggestion> {
        val prefix = mutableListOf<TagSuggestion>()
        val contains = mutableListOf<TagSuggestion>()

        for (entry in data.entries) {
            val normalizedZh = entry.normalizedZhCn ?: continue
            val scoreBase = popularityScore(entry.postCount)
            when {
                normalizedZh.startsWith(normalizedQuery) -> prefix += TagSuggestion(
                    replacementTag = entry.english,
                    primaryText = entry.english,
                    secondaryText = entry.zhCn,
                    matchType = TagMatchType.Chinese,
                    postCount = entry.postCount,
                    score = 5000 + scoreBase - normalizedZh.length
                )

                normalizedZh.contains(normalizedQuery) -> contains += TagSuggestion(
                    replacementTag = entry.english,
                    primaryText = entry.english,
                    secondaryText = entry.zhCn,
                    matchType = TagMatchType.Chinese,
                    postCount = entry.postCount,
                    score = 4200 + scoreBase - normalizedZh.indexOf(normalizedQuery)
                )
            }
        }

        return (prefix + contains)
            .distinctBy { it.replacementTag }
            .sortedWith(compareByDescending<TagSuggestion> { it.score }.thenByDescending { it.postCount })
            .take(limit)
    }

    private fun suggestEnglish(
        data: TagData,
        normalizedQuery: String,
        localeIsChinese: Boolean,
        limit: Int
    ): List<TagSuggestion> {
        val prefix = mutableListOf<TagSuggestion>()
        val alias = mutableListOf<TagSuggestion>()
        val correction = mutableListOf<TagSuggestion>()

        for (entry in data.entries) {
            val scoreBase = popularityScore(entry.postCount)

            if (entry.normalizedEnglish.startsWith(normalizedQuery)) {
                prefix += buildSuggestion(entry, TagMatchType.Prefix, localeIsChinese, 6000 + scoreBase - entry.normalizedEnglish.length)
                continue
            }

            val aliasMatch = entry.normalizedAliases.firstOrNull { it.startsWith(normalizedQuery) }
            if (aliasMatch != null) {
                alias += buildSuggestion(
                    entry,
                    TagMatchType.Alias,
                    localeIsChinese,
                    5200 + scoreBase - aliasMatch.length,
                    aliasMatch
                )
            }
        }

        if (prefix.size + alias.size < limit) {
            val threshold = correctionThreshold(normalizedQuery.length)
            val candidateHead = normalizedQuery.firstOrNull()
            for (entry in data.entries) {
                if (entry.normalizedEnglish.startsWith(normalizedQuery)) continue
                if (abs(entry.normalizedEnglish.length - normalizedQuery.length) > threshold) continue
                if (candidateHead != null && entry.normalizedEnglish.firstOrNull() != candidateHead) continue

                val scoreBase = popularityScore(entry.postCount)
                val englishDistance = damerauLevenshtein(normalizedQuery, entry.normalizedEnglish, threshold)
                var bestDistance = englishDistance
                var matchedAlias: String? = null

                if (bestDistance > threshold) {
                    for (aliasValue in entry.normalizedAliases) {
                        if (abs(aliasValue.length - normalizedQuery.length) > threshold) continue
                        if (candidateHead != null && aliasValue.firstOrNull() != candidateHead) continue
                        val aliasDistance = damerauLevenshtein(normalizedQuery, aliasValue, threshold)
                        if (aliasDistance < bestDistance) {
                            bestDistance = aliasDistance
                            matchedAlias = aliasValue
                        }
                    }
                }

                if (bestDistance <= threshold) {
                    correction += buildSuggestion(
                        entry,
                        TagMatchType.Correction,
                        localeIsChinese,
                        3600 + scoreBase - bestDistance * 100,
                        matchedAlias
                    )
                }
            }
        }

        return (prefix + alias + correction)
            .distinctBy { it.replacementTag }
            .sortedWith(compareByDescending<TagSuggestion> { it.score }.thenByDescending { it.postCount })
            .take(limit)
    }

    private fun buildSuggestion(
        entry: TagEntry,
        matchType: TagMatchType,
        localeIsChinese: Boolean,
        score: Int,
        aliasValue: String? = null
    ): TagSuggestion {
        val secondary = buildString {
            when (matchType) {
                TagMatchType.Alias -> append("Alias")
                TagMatchType.Correction -> append("Correction")
                else -> Unit
            }
            if (localeIsChinese && !entry.zhCn.isNullOrBlank()) {
                if (isNotEmpty()) append(" · ")
                append(entry.zhCn)
            } else if (matchType == TagMatchType.Alias && aliasValue != null) {
                if (isNotEmpty()) append(" · ")
                append(aliasValue)
            }
        }.ifBlank { null }

        return TagSuggestion(
            replacementTag = entry.english,
            primaryText = entry.english,
            secondaryText = secondary,
            matchType = matchType,
            postCount = entry.postCount,
            score = score
        )
    }

    private fun parseCsvLine(line: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var index = 0

        while (index < line.length) {
            val char = line[index]
            when {
                char == '"' -> {
                    if (inQuotes && index + 1 < line.length && line[index + 1] == '"') {
                        current.append('"')
                        index++
                    } else {
                        inQuotes = !inQuotes
                    }
                }
                char == ',' && !inQuotes -> {
                    result += current.toString()
                    current.setLength(0)
                }
                else -> current.append(char)
            }
            index++
        }
        result += current.toString()
        return result
    }

    private fun normalizeChinese(value: String): String = value.trim().replace(" ", "")

    private fun normalizeQuery(value: String): String {
        return value
            .trim()
            .lowercase()
            .replace(' ', '_')
            .replace('-', '_')
            .replace(Regex("_+"), "_")
    }

    private fun containsChinese(value: String): Boolean = value.any { it.code in 0x4E00..0x9FFF }

    private fun popularityScore(postCount: Int): Int = when {
        postCount >= 1_000_000 -> 300
        postCount >= 100_000 -> 220
        postCount >= 10_000 -> 140
        postCount >= 1_000 -> 80
        else -> 20
    }

    private fun correctionThreshold(length: Int): Int = when {
        length <= 4 -> 1
        length <= 8 -> 2
        else -> 3
    }

    private fun damerauLevenshtein(source: String, target: String, maxDistance: Int): Int {
        if (source == target) return 0
        if (abs(source.length - target.length) > maxDistance) return maxDistance + 1

        val rows = source.length + 1
        val cols = target.length + 1
        val dp = Array(rows) { IntArray(cols) }

        for (i in 0 until rows) dp[i][0] = i
        for (j in 0 until cols) dp[0][j] = j

        for (i in 1 until rows) {
            var rowMin = Int.MAX_VALUE
            for (j in 1 until cols) {
                val cost = if (source[i - 1] == target[j - 1]) 0 else 1
                var value = minOf(
                    dp[i - 1][j] + 1,
                    dp[i][j - 1] + 1,
                    dp[i - 1][j - 1] + cost
                )
                if (i > 1 && j > 1 && source[i - 1] == target[j - 2] && source[i - 2] == target[j - 1]) {
                    value = minOf(value, dp[i - 2][j - 2] + cost)
                }
                dp[i][j] = value
                if (value < rowMin) rowMin = value
            }
            if (rowMin > maxDistance) return maxDistance + 1
        }

        return dp[source.length][target.length]
    }

    private data class TagData(
        val entries: List<TagEntry>
    )

    companion object {
        private const val ENGLISH_ASSET_PATH = "tagcomplete/danbooru.csv"
        private const val CHINESE_ASSET_PATH = "tagcomplete/danbooru.zh_CN_SFW.csv"

        @Volatile
        private var instance: TagAutocompleteRepository? = null

        fun getInstance(context: Context): TagAutocompleteRepository {
            return instance ?: synchronized(this) {
                instance ?: TagAutocompleteRepository(context.applicationContext).also { instance = it }
            }
        }

        fun extractActiveTag(text: String, selection: Int): ActiveTagContext? {
            if (selection < 0 || selection > text.length) return null
            val segmentStart = text.lastIndexOf(',', startIndex = (selection - 1).coerceAtLeast(0)).let {
                if (it == -1) 0 else it + 1
            }
            val segmentEnd = text.indexOf(',', startIndex = selection).let {
                if (it == -1) text.length else it
            }
            var trimmedStart = segmentStart
            while (trimmedStart < segmentEnd && text[trimmedStart].isWhitespace()) trimmedStart++
            val token = text.substring(trimmedStart, selection).trim()
            if (token.isEmpty()) return null
            return ActiveTagContext(
                token = token,
                trimmedStart = trimmedStart,
                trimmedEnd = selection,
                segmentEnd = segmentEnd
            )
        }

        fun applySuggestion(text: String, selection: Int, suggestion: TagSuggestion): Pair<String, Int> {
            val context = extractActiveTag(text, selection) ?: return text to selection
            val prefix = text.substring(0, context.trimmedStart)
            val suffix = text.substring(context.segmentEnd)
            val separator = if (suffix.startsWith(",")) "" else ", "
            val replacement = suggestion.replacementTag.replace('_', ' ') + separator
            val updated = prefix + replacement + suffix.trimStart()
            return updated to (prefix.length + replacement.length)
        }
    }
}
