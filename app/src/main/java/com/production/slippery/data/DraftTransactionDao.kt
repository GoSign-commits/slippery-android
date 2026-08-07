package com.production.slippery.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface DraftTransactionDao {
    @Query("SELECT * FROM draft_transactions ORDER BY createdAt DESC")
    fun getAll(): Flow<List<DraftTransaction>>

    @Insert
    suspend fun insert(draft: DraftTransaction): Long

    // Editing/deleting a submitted draft is blocked at the ViewModel layer,
    // not here — Room has no concept of "immutable after a flag flips".
    @Update
    suspend fun update(draft: DraftTransaction)

    @Delete
    suspend fun delete(draft: DraftTransaction)

    @Query("UPDATE draft_transactions SET submitted = 1 WHERE id = :id")
    suspend fun markSubmitted(id: Long)
}
