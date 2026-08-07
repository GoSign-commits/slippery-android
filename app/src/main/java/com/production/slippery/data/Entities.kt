package com.production.slippery.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * A local, unsynced expense draft. Everything here lives only on-device
 * until Submit — R2 upload must succeed before the matching server-side
 * `transactions` row is inserted (file-then-record, see TRANSACTIONS.md).
 *
 * [clientSubmissionId] is generated once, at draft creation — the same
 * UUID becomes both the R2 object filename and the DB idempotency key.
 * Not a new concept, not two IDs — one UUID, two uses.
 *
 * [categoryId]/[categoryName] are denormalized from the live Supabase
 * fetch (no local category cache/sync yet — stubbed, see STATE.md).
 *
 * [submitted] is a local-only UI flag. Once true, this draft mirrors an
 * immutable server-side transaction — edit/delete must be disabled in
 * the UI from that point on, per SCHEMA.md's immutability rule.
 */
@Entity(tableName = "draft_transactions")
data class DraftTransaction(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val clientSubmissionId: String = UUID.randomUUID().toString(),
    val categoryId: String?,
    val categoryName: String,
    val photoPath: String?,
    val amount: Double,
    val description: String = "",
    val spentAt: Long = System.currentTimeMillis(),
    val submitted: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)
