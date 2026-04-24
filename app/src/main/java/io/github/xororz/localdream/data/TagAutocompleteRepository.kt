package io.github.xororz.localdream.data

import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import kotlin.math.abs

class TagAutocompleteRepository private constructor(private val context: Context) {
    private val loadMutex = Mutex()

    @Volatile
    private var loadedData: TagData? = null

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val dictDir = File(context.filesDir, DICT_DIR).also { it.mkdirs() }
    private val mainFile = File(dictDir, MAIN_FILE_NAME)
    private val translationFile = File(dictDir, TRANSLATION_FILE_NAME)

    private val _state = MutableStateFlow(readStateFromDisk())
    val state: StateFlow<DictionaryState> = _state.asStateFlow()

    suspend fun suggest(query: String, limit: Int = 12): List<TagSuggestion> {
        if (!state.value.mainImported) return emptyList()
        val isTranslationQuery = containsNonAsciiLetter(query)
        if (isTranslationQuery && !state.value.translationImported) return emptyList()

        val normalizedQuery = normalizeQuery(query)
        if (normalizedQuery.isEmpty()) return emptyList()

        val data = ensureLoaded() ?: return emptyList()

        return withContext(Dispatchers.Default) {
            if (isTranslationQuery) {
                suggestByTranslation(data, normalizeTranslation(query), limit)
            } else {
                suggestByEnglish(data, normalizedQuery, limit)
            }
        }
    }

    suspend fun importMainCsv(uri: Uri, displayName: String?): ImportResult =
        withContext(Dispatchers.IO) {
            runCatching {
                val lineCount = copyUriToFile(uri, mainFile) { cells ->
                    cells.getOrNull(0)?.trim()?.removePrefix("﻿").isNullOrEmpty().not()
                }
                if (lineCount == 0) {
                    mainFile.delete()
                    return@withContext ImportResult.Error("empty")
                }
                invalidate()
                prefs.edit {
                    putString(KEY_MAIN_NAME, displayName ?: MAIN_FILE_NAME)
                    putInt(KEY_MAIN_LINES, lineCount)
                }
                _state.value = readStateFromDisk()
                ImportResult.Success(lineCount)
            }.getOrElse {
                mainFile.delete()
                ImportResult.Error(it.message ?: "unknown")
            }
        }

    suspend fun importTranslationCsv(uri: Uri, displayName: String?): ImportResult =
        withContext(Dispatchers.IO) {
            runCatching {
                val lineCount = copyUriToFile(uri, translationFile) { cells ->
                    if (cells.size < 2) return@copyUriToFile false
                    val key = cells[0].trim().removePrefix("﻿")
                    if (key.isEmpty()) return@copyUriToFile false
                    extractTranslation(cells) != null
                }
                if (lineCount == 0) {
                    translationFile.delete()
                    return@withContext ImportResult.Error("empty")
                }
                invalidate()
                prefs.edit {
                    putString(KEY_TRANSLATION_NAME, displayName ?: TRANSLATION_FILE_NAME)
                    putInt(KEY_TRANSLATION_LINES, lineCount)
                }
                _state.value = readStateFromDisk()
                ImportResult.Success(lineCount)
            }.getOrElse {
                translationFile.delete()
                ImportResult.Error(it.message ?: "unknown")
            }
        }

    fun clearMainCsv() {
        mainFile.delete()
        prefs.edit {
            remove(KEY_MAIN_NAME)
            remove(KEY_MAIN_LINES)
        }
        invalidate()
        _state.value = readStateFromDisk()
    }

    fun clearTranslationCsv() {
        translationFile.delete()
        prefs.edit {
            remove(KEY_TRANSLATION_NAME)
            remove(KEY_TRANSLATION_LINES)
        }
        invalidate()
        _state.value = readStateFromDisk()
    }

    private fun invalidate() {
        loadedData = null
    }

    private fun readStateFromDisk(): DictionaryState {
        val mainImported = mainFile.exists() && mainFile.length() > 0
        val translationImported = translationFile.exists() && translationFile.length() > 0
        return DictionaryState(
            mainImported = mainImported,
            mainFileName = if (mainImported) prefs.getString(KEY_MAIN_NAME, null) else null,
            mainEntryCount = if (mainImported) prefs.getInt(KEY_MAIN_LINES, 0) else 0,
            translationImported = translationImported,
            translationFileName = if (translationImported) prefs.getString(
                KEY_TRANSLATION_NAME,
                null
            ) else null,
            translationEntryCount = if (translationImported) prefs.getInt(
                KEY_TRANSLATION_LINES,
                0
            ) else 0
        )
    }

    private suspend fun ensureLoaded(): TagData? {
        loadedData?.let { return it }

        return loadMutex.withLock {
            loadedData?.let { return@withLock it }
            val loaded = withContext(Dispatchers.IO) { loadData() }
            loadedData = loaded
            loaded
        }
    }

    private fun loadData(): TagData? {
        if (!mainFile.exists()) return null
        val translationMap = if (translationFile.exists()) loadTranslationMap() else emptyMap()
        val entries = mutableListOf<TagEntry>()
        val englishSet = HashSet<String>()

        mainFile.inputStream().use { input ->
            BufferedReader(InputStreamReader(input)).useLines { lines ->
                lines.forEach { rawLine ->
                    val line = rawLine.trim()
                    if (line.isEmpty()) return@forEach
                    val cells = parseCsvLine(line)
                    if (cells.isEmpty()) return@forEach

                    val english = cells.getOrNull(0)?.trim()?.removePrefix("﻿").orEmpty()
                    if (english.isEmpty()) return@forEach
                    if (!englishSet.add(english)) return@forEach

                    val category = cells.getOrNull(1)?.trim()?.toIntOrNull() ?: 0
                    val postCount = cells.getOrNull(2)?.trim()?.toIntOrNull() ?: 0
                    val aliases = cells.getOrNull(3)
                        ?.split(',')
                        ?.map { it.trim() }
                        ?.filter { it.isNotEmpty() }
                        .orEmpty()

                    val translation = translationMap[english]
                    entries += TagEntry(
                        english = english,
                        translation = translation,
                        category = category,
                        postCount = postCount,
                        aliases = aliases,
                        normalizedEnglish = normalizeQuery(english),
                        normalizedAliases = aliases.map(::normalizeQuery)
                            .filter { it.isNotEmpty() },
                        normalizedTranslation = translation?.let(::normalizeTranslation)
                    )
                }
            }
        }

        val englishByHead = HashMap<Char, MutableList<TagEntry>>()
        val aliasByHead = HashMap<Char, MutableList<AliasRef>>()
        val translationByHead = HashMap<Char, MutableList<TagEntry>>()
        val translationEntries = mutableListOf<TagEntry>()

        for (entry in entries) {
            entry.normalizedEnglish.firstOrNull()?.let { head ->
                englishByHead.getOrPut(head) { mutableListOf() } += entry
            }
            for (alias in entry.normalizedAliases) {
                alias.firstOrNull()?.let { head ->
                    aliasByHead.getOrPut(head) { mutableListOf() } += AliasRef(entry, alias)
                }
            }
            val normTr = entry.normalizedTranslation
            if (!normTr.isNullOrEmpty()) {
                translationEntries += entry
                translationByHead.getOrPut(normTr.first()) { mutableListOf() } += entry
            }
        }

        return TagData(
            entries = entries,
            englishByHead = englishByHead,
            aliasByHead = aliasByHead,
            translationEntries = translationEntries,
            translationByHead = translationByHead
        )
    }

    private fun loadTranslationMap(): Map<String, String> {
        val map = HashMap<String, String>()
        translationFile.inputStream().use { input ->
            BufferedReader(InputStreamReader(input)).useLines { lines ->
                lines.forEach { rawLine ->
                    val line = rawLine.trim()
                    if (line.isEmpty()) return@forEach
                    val cells = parseCsvLine(line)
                    if (cells.size < 2) return@forEach
                    val english = cells[0].trim().removePrefix("﻿")
                    if (english.isEmpty()) return@forEach
                    val translation = extractTranslation(cells) ?: return@forEach
                    map[english] = translation
                }
            }
        }
        return map
    }

    private fun extractTranslation(cells: List<String>): String? {
        for (i in 1 until cells.size) {
            val raw = cells[i].trim().removePrefix("﻿")
            if (raw.isEmpty()) continue
            if (raw.toIntOrNull() != null) continue
            if (raw.toDoubleOrNull() != null) continue
            return raw
        }
        return null
    }

    private fun suggestByTranslation(
        data: TagData,
        normalizedQuery: String,
        limit: Int
    ): List<TagSuggestion> {
        if (normalizedQuery.isEmpty()) return emptyList()
        val head = normalizedQuery.first()
        val prefix = mutableListOf<TagSuggestion>()

        for (entry in data.translationByHead[head].orEmpty()) {
            val normalized = entry.normalizedTranslation ?: continue
            if (!normalized.startsWith(normalizedQuery)) continue
            val scoreBase = popularityScore(entry.postCount)
            prefix += TagSuggestion(
                replacementTag = entry.english,
                primaryText = entry.english,
                secondaryText = entry.translation,
                matchType = TagMatchType.Translation,
                category = entry.category,
                postCount = entry.postCount,
                score = 5000 + scoreBase - normalized.length
            )
        }

        val contains = if (prefix.size < limit) {
            val collector = mutableListOf<TagSuggestion>()
            for (entry in data.translationEntries) {
                val normalized = entry.normalizedTranslation ?: continue
                if (normalized.startsWith(normalizedQuery)) continue
                val idx = normalized.indexOf(normalizedQuery)
                if (idx < 0) continue
                val scoreBase = popularityScore(entry.postCount)
                collector += TagSuggestion(
                    replacementTag = entry.english,
                    primaryText = entry.english,
                    secondaryText = entry.translation,
                    matchType = TagMatchType.Translation,
                    category = entry.category,
                    postCount = entry.postCount,
                    score = 4200 + scoreBase - idx
                )
            }
            collector
        } else {
            emptyList()
        }

        return (prefix + contains)
            .distinctBy { it.replacementTag }
            .sortedWith(compareByDescending<TagSuggestion> { it.score }.thenByDescending { it.postCount })
            .take(limit)
    }

    private fun suggestByEnglish(
        data: TagData,
        normalizedQuery: String,
        limit: Int
    ): List<TagSuggestion> {
        if (normalizedQuery.isEmpty()) return emptyList()
        val head = normalizedQuery.first()
        val prefix = mutableListOf<TagSuggestion>()
        val alias = mutableListOf<TagSuggestion>()
        val correction = mutableListOf<TagSuggestion>()

        val englishCandidates = data.englishByHead[head].orEmpty()
        val aliasCandidates = data.aliasByHead[head].orEmpty()

        for (entry in englishCandidates) {
            if (!entry.normalizedEnglish.startsWith(normalizedQuery)) continue
            val scoreBase = popularityScore(entry.postCount)
            prefix += buildSuggestion(
                entry,
                TagMatchType.Prefix,
                6000 + scoreBase - entry.normalizedEnglish.length
            )
        }

        val seenAliasEntries = HashSet<String>()
        for (ref in aliasCandidates) {
            if (!ref.alias.startsWith(normalizedQuery)) continue
            if (ref.entry.normalizedEnglish.startsWith(normalizedQuery)) continue
            if (!seenAliasEntries.add(ref.entry.english)) continue
            val scoreBase = popularityScore(ref.entry.postCount)
            alias += buildSuggestion(
                ref.entry,
                TagMatchType.Alias,
                5200 + scoreBase - ref.alias.length,
                ref.alias
            )
        }

        if (prefix.size + alias.size < limit) {
            val threshold = correctionThreshold(normalizedQuery.length)
            val correctionCandidates = HashSet<TagEntry>()
            correctionCandidates += englishCandidates
            for (ref in aliasCandidates) correctionCandidates += ref.entry

            for (entry in correctionCandidates) {
                if (entry.normalizedEnglish.startsWith(normalizedQuery)) continue

                val englishDistance =
                    if (abs(entry.normalizedEnglish.length - normalizedQuery.length) <= threshold &&
                        entry.normalizedEnglish.firstOrNull() == head
                    ) {
                        damerauLevenshtein(normalizedQuery, entry.normalizedEnglish, threshold)
                    } else {
                        threshold + 1
                    }
                var bestDistance = englishDistance
                var matchedAlias: String? = null

                if (bestDistance > threshold) {
                    for (aliasValue in entry.normalizedAliases) {
                        if (abs(aliasValue.length - normalizedQuery.length) > threshold) continue
                        if (aliasValue.firstOrNull() != head) continue
                        val aliasDistance =
                            damerauLevenshtein(normalizedQuery, aliasValue, threshold)
                        if (aliasDistance < bestDistance) {
                            bestDistance = aliasDistance
                            matchedAlias = aliasValue
                        }
                    }
                }

                if (bestDistance <= threshold) {
                    val scoreBase = popularityScore(entry.postCount)
                    correction += buildSuggestion(
                        entry,
                        TagMatchType.Correction,
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
        score: Int,
        aliasValue: String? = null
    ): TagSuggestion {
        val secondary = when (matchType) {
            TagMatchType.Alias -> aliasValue?.replace('_', ' ')
            else -> entry.translation
        }
        return TagSuggestion(
            replacementTag = entry.english,
            primaryText = entry.english,
            secondaryText = secondary,
            matchType = matchType,
            category = entry.category,
            postCount = entry.postCount,
            score = score
        )
    }

    private fun copyUriToFile(
        uri: Uri,
        target: File,
        isValidRow: (List<String>) -> Boolean
    ): Int {
        val input: InputStream = context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("cannot open uri")
        var validRows = 0
        input.use { stream ->
            target.outputStream().use { out ->
                val reader = BufferedReader(InputStreamReader(stream))
                val writer = out.bufferedWriter()
                reader.forEachLine { rawLine ->
                    val line = rawLine.trim()
                    if (line.isEmpty()) return@forEachLine
                    val cells = parseCsvLine(line)
                    if (!isValidRow(cells)) return@forEachLine
                    writer.write(rawLine)
                    writer.newLine()
                    validRows++
                }
                writer.flush()
            }
        }
        return validRows
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

    private fun normalizeTranslation(value: String): String =
        value.trim().replace(" ", "").lowercase()

    private fun normalizeQuery(value: String): String {
        return value
            .trim()
            .lowercase()
            .replace(' ', '_')
            .replace('-', '_')
            .replace(Regex("_+"), "_")
    }

    private fun containsNonAsciiLetter(value: String): Boolean =
        value.any { it.code > 127 && it.isLetter() }

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
        val entries: List<TagEntry>,
        val englishByHead: Map<Char, List<TagEntry>>,
        val aliasByHead: Map<Char, List<AliasRef>>,
        val translationEntries: List<TagEntry>,
        val translationByHead: Map<Char, List<TagEntry>>
    )

    private data class AliasRef(val entry: TagEntry, val alias: String)

    companion object {
        private const val PREFS_NAME = "tag_autocomplete_prefs"
        private const val DICT_DIR = "tagcomplete"
        private const val MAIN_FILE_NAME = "main.csv"
        private const val TRANSLATION_FILE_NAME = "translation.csv"
        private const val KEY_MAIN_NAME = "main_csv_name"
        private const val KEY_MAIN_LINES = "main_csv_lines"
        private const val KEY_TRANSLATION_NAME = "translation_csv_name"
        private const val KEY_TRANSLATION_LINES = "translation_csv_lines"

        @Volatile
        private var instance: TagAutocompleteRepository? = null

        fun getInstance(context: Context): TagAutocompleteRepository {
            return instance ?: synchronized(this) {
                instance ?: TagAutocompleteRepository(context.applicationContext).also {
                    instance = it
                }
            }
        }

        fun extractActiveTag(text: String, selection: Int): ActiveTagContext? {
            if (selection < 0 || selection > text.length) return null
            val segmentStart =
                text.lastIndexOf(',', startIndex = (selection - 1).coerceAtLeast(0)).let {
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

        fun applySuggestion(
            text: String,
            selection: Int,
            suggestion: TagSuggestion
        ): Pair<String, Int> {
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
