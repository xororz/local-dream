package io.github.xororz.localdream.data

import androidx.compose.runtime.Immutable
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery

enum class GenerationMode {
    TXT2IMG,
    IMG2IMG,
    INPAINT,
    EDIT,
    ULTRAFIX,
    UNKNOWN,
    ;

    companion object {
        fun fromString(s: String?): GenerationMode = when (s) {
            "TXT2IMG" -> TXT2IMG
            "IMG2IMG" -> IMG2IMG
            "INPAINT" -> INPAINT
            "EDIT" -> EDIT
            "ULTRAFIX" -> ULTRAFIX
            else -> UNKNOWN
        }
    }
}

enum class DeviceFilter { NPU, CPU, GPU }

enum class FavoriteFilter { FAVORITE, NOT_FAVORITE }

@Immutable
data class HistoryFilter(
    val modelIds: Set<String>? = null,
    val modes: Set<GenerationMode>? = null,
    val from: Long? = null,
    val to: Long? = null,
    val sizes: Set<String>? = null,
    val schedulers: Set<String>? = null,
    val devices: Set<DeviceFilter>? = null,
    val promptSubstring: String? = null,
    val favorites: Set<FavoriteFilter>? = null,
    val descending: Boolean = true,
) {
    // Full rows, newest/oldest first. Used by paged and one-shot list queries.
    fun toSqlQuery(): SupportSQLiteQuery = buildQuery(projection = "*", ordered = true)

    // Just the ids matching the filter, in display order. Used by select-all so
    // the selection covers every match, not only the pages loaded in memory.
    fun toIdQuery(): SupportSQLiteQuery = buildQuery(projection = "id", ordered = true)

    // Row count matching the filter. Used to drive the select-all toggle and the
    // selection counter without materializing the list.
    fun toCountQuery(): SupportSQLiteQuery = buildQuery(projection = "COUNT(*)", ordered = false)

    // Newest matches first, capped at [limit]. Backs the result-page thumbnail
    // strip and the seed-on-open effect without loading the whole history.
    fun toRecentQuery(limit: Int): SupportSQLiteQuery = buildQuery(projection = "*", ordered = true, limit = limit)

    private fun buildQuery(
        projection: String,
        ordered: Boolean,
        limit: Int? = null,
    ): SupportSQLiteQuery {
        val where = mutableListOf<String>()
        val args = mutableListOf<Any>()

        if (!modelIds.isNullOrEmpty()) {
            where += "modelId IN (${modelIds.joinToString(",") { "?" }})"
            args.addAll(modelIds)
        }
        if (!modes.isNullOrEmpty()) {
            // Selecting TXT2IMG also matches UNKNOWN: legacy migrated rows have no mode
            // recorded, and from the user's perspective anything that's not img2img/inpaint
            // is effectively txt2img.
            val expanded =
                if (GenerationMode.TXT2IMG in modes) modes + GenerationMode.UNKNOWN else modes
            where += "mode IN (${expanded.joinToString(",") { "?" }})"
            args.addAll(expanded.map { it.name })
        }
        if (from != null) {
            where += "timestamp >= ?"
            args += from
        }
        if (to != null) {
            where += "timestamp <= ?"
            args += to
        }
        if (!sizes.isNullOrEmpty()) {
            where += "(width || 'x' || height) IN (${sizes.joinToString(",") { "?" }})"
            args.addAll(sizes)
        }
        if (!schedulers.isNullOrEmpty()) {
            where += "scheduler IN (${schedulers.joinToString(",") { "?" }})"
            args.addAll(schedulers)
        }
        if (!devices.isNullOrEmpty()) {
            val parts = mutableListOf<String>()
            // runOnCpu=false → NPU; runOnCpu=true && useOpenCL=false → CPU; runOnCpu=true && useOpenCL=true → GPU
            if (DeviceFilter.NPU in devices) parts += "runOnCpu = 0"
            if (DeviceFilter.CPU in devices) parts += "(runOnCpu = 1 AND useOpenCL = 0)"
            if (DeviceFilter.GPU in devices) parts += "(runOnCpu = 1 AND useOpenCL = 1)"
            if (parts.isNotEmpty()) {
                where += "(${parts.joinToString(" OR ")})"
            }
        }
        if (!promptSubstring.isNullOrBlank()) {
            where += "(INSTR(prompt, ?) > 0 OR INSTR(negativePrompt, ?) > 0)"
            args += promptSubstring
            args += promptSubstring
        }
        if (!favorites.isNullOrEmpty()) {
            val parts = mutableListOf<String>()
            if (FavoriteFilter.FAVORITE in favorites) parts += "favorite = 1"
            if (FavoriteFilter.NOT_FAVORITE in favorites) parts += "favorite = 0"
            where += "(${parts.joinToString(" OR ")})"
        }

        val whereClause = if (where.isEmpty()) "" else "WHERE ${where.joinToString(" AND ")}"
        val orderClause = if (ordered) {
            val direction = if (descending) "DESC" else "ASC"
            "ORDER BY timestamp $direction, id $direction"
        } else {
            ""
        }
        val limitClause = if (limit != null) "LIMIT $limit" else ""

        val sql = "SELECT $projection FROM generation_history $whereClause $orderClause $limitClause"

        return SimpleSQLiteQuery(sql, args.toTypedArray())
    }
}
