package com.production.slippery.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface DraftTransactionDao {
    // Scoped to the current envelope, not all drafts ever — a buyer
    // shouldn't see last envelope's (already closed, locked) slips mixed
    // in with the one they're currently capturing against.
    @Query("SELECT * FROM draft_transactions WHERE envelopeId = :envelopeId ORDER BY createdAt DESC")
    fun getByEnvelope(envelopeId: String): Flow<List<DraftTransaction>>

    @Insert
    suspend fun insert(draft: DraftTransaction): Long

    // Editing/deleting a submitted draft is blocked at the ViewModel layer,
    // not here — Room has no concept of "immutable after a flag flips".
    @Update
    suspend fun update(draft: DraftTransaction)

    @Delete
    suspend fun delete(draft: DraftTransaction)

    // Envelope Close flips every one of its drafts to submitted together
    // — not per-draft one at a time, see TRANSACTIONS.md "Envelope
    // lifecycle". Individual markSubmitted(id) removed; nothing used it
    // standalone once Close became envelope-wide.
    @Query("UPDATE draft_transactions SET submitted = 1 WHERE envelopeId = :envelopeId")
    suspend fun markEnvelopeSubmitted(envelopeId: String)

    // Local half of the slip-number seed logic, scoped per-envelope
    // (revised 2026-08-08 — was per-buyer-lifetime). The server half only
    // knows about slips from CLOSED envelopes. This covers the gap: local
    // drafts already saved on this device, in the CURRENT envelope, that
    // the server has never seen (offline, or server unreachable at seed
    // time).
    @Query("SELECT MAX(slipNumber) FROM draft_transactions WHERE envelopeId = :envelopeId")
    suspend fun getMaxSlipNumber(envelopeId: String): Int?
}
