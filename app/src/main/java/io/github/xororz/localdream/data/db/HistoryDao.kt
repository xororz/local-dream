package io.github.xororz.localdream.data.db

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

@Dao
interface HistoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: HistoryEntity): Long

    @Query("DELETE FROM generation_history WHERE id = :id")
    suspend fun deleteById(id: Long): Int

    @Query("DELETE FROM generation_history WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>): Int

    @Query("DELETE FROM generation_history WHERE modelId = :modelId")
    suspend fun deleteAllForModel(modelId: String): Int

    @Query("DELETE FROM generation_history")
    suspend fun deleteAll(): Int

    // Repoint every row of a model to a new id, rewriting the stored image path
    // prefix (history/<oldId>/... -> history/<newId>/...) to match the moved
    // files on disk. Used when a custom model is renamed.
    @Query(
        "UPDATE generation_history SET modelId = :newId, " +
            "imagePath = REPLACE(imagePath, 'history/' || :oldId || '/', 'history/' || :newId || '/') " +
            "WHERE modelId = :oldId",
    )
    suspend fun renameModelId(oldId: String, newId: String): Int

    @Query("SELECT * FROM generation_history WHERE id = :id")
    suspend fun getById(id: Long): HistoryEntity?

    @Query("UPDATE generation_history SET favorite = :favorite WHERE id = :id")
    suspend fun setFavorite(id: Long, favorite: Boolean): Int

    @RawQuery(observedEntities = [HistoryEntity::class])
    fun query(q: SupportSQLiteQuery): Flow<List<HistoryEntity>>

    @RawQuery(observedEntities = [HistoryEntity::class])
    fun queryPaged(q: SupportSQLiteQuery): PagingSource<Int, HistoryEntity>

    @RawQuery(observedEntities = [HistoryEntity::class])
    fun queryCount(q: SupportSQLiteQuery): Flow<Int>

    @RawQuery
    suspend fun queryOnce(q: SupportSQLiteQuery): List<HistoryEntity>

    @RawQuery
    suspend fun queryIds(q: SupportSQLiteQuery): List<Long>

    @Query("SELECT * FROM generation_history WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<HistoryEntity>

    @Query("SELECT favorite FROM generation_history WHERE id = :id")
    fun observeFavorite(id: Long): Flow<Boolean?>

    @Query("SELECT COUNT(*) FROM generation_history WHERE modelId = :modelId AND timestamp = :timestamp")
    suspend fun countByKey(modelId: String, timestamp: Long): Int

    @Query("SELECT DISTINCT modelId FROM generation_history ORDER BY modelId")
    fun observeKnownModelIds(): Flow<List<String>>

    @Query("SELECT DISTINCT scheduler FROM generation_history ORDER BY scheduler")
    fun observeKnownSchedulers(): Flow<List<String>>

    @Query("SELECT DISTINCT (width || 'x' || height) FROM generation_history ORDER BY width * height DESC")
    fun observeKnownSizes(): Flow<List<String>>
}
