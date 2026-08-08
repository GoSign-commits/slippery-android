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

    // Local half of the slip-number seed logic — the server half only
    // knows about SUBMITTED slips. This covers the gap: local drafts
    // already saved on this device that the server has never seen
    // (offline, or server unreachable at seed time).
    @Query("SELECT MAX(slipNumber) FROM draft_transactions")
    suspend fun getMaxSlipNumber(): Int?
}
