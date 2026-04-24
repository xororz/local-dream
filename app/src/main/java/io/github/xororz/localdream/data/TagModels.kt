package io.github.xororz.localdream.data

enum class TagMatchType {
    Prefix,
    Alias,
    Translation,
    Correction
}

data class TagEntry(
    val english: String,
    val translation: String?,
    val category: Int,
    val postCount: Int,
    val aliases: List<String>,
    val normalizedEnglish: String,
    val normalizedAliases: List<String>,
    val normalizedTranslation: String?
)

data class TagSuggestion(
    val replacementTag: String,
    val primaryText: String,
    val secondaryText: String?,
    val matchType: TagMatchType,
    val category: Int,
    val postCount: Int,
    val score: Int
)

data class ActiveTagContext(
    val token: String,
    val trimmedStart: Int,
    val trimmedEnd: Int,
    val segmentEnd: Int
)

data class DictionaryState(
    val mainImported: Boolean = false,
    val mainFileName: String? = null,
    val mainEntryCount: Int = 0,
    val translationImported: Boolean = false,
    val translationFileName: String? = null,
    val translationEntryCount: Int = 0
)

sealed class ImportResult {
    data class Success(val lineCount: Int) : ImportResult()
    data class Error(val reason: String) : ImportResult()
}
