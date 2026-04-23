package io.github.xororz.localdream.data

enum class TagMatchType {
    Prefix,
    Alias,
    Chinese,
    Correction
}

data class TagEntry(
    val english: String,
    val zhCn: String?,
    val category: Int,
    val postCount: Int,
    val aliases: List<String>,
    val normalizedEnglish: String,
    val normalizedAliases: List<String>,
    val normalizedZhCn: String?
)

data class TagSuggestion(
    val replacementTag: String,
    val primaryText: String,
    val secondaryText: String?,
    val matchType: TagMatchType,
    val postCount: Int,
    val score: Int
)

data class ActiveTagContext(
    val token: String,
    val trimmedStart: Int,
    val trimmedEnd: Int,
    val segmentEnd: Int
)
