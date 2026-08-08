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
 *
 * [supplier] required, matches the physical cover sheet.
 *
 * [vatApplicable] nullable Boolean, not a plain Boolean — `null` means
 * "buyer hasn't answered yet", distinct from `false`. No default is
 * allowed to masquerade as an unanswered question. Save is blocked until
 * this is explicitly set.
 *
 * [vatAmount]/[amountExclVat] are calculated, never typed by the buyer:
 * amount is always VAT-inclusive (what the slip shows). When VATable,
 * vatAmount = amount * 15/115, amountExclVat = amount - vatAmount. Both
 * stay null when not VATable — never 0 — so "not asked" stays
 * distinguishable from "VAT was zero" (see TRANSACTIONS.md).
 *
 * [slipNumber] generated client-side at capture time, per buyer,
 * sequential — seeded from the buyer's MAX(slip_number) on Supabase at
 * session start so a reinstall/new device doesn't collide with prior
 * submissions (see TRANSACTIONS.md "Slip numbering"). 0 is a placeholder
 * only — the ViewModel always assigns a real value before insert.
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
    val createdAt: Long = System.currentTimeMillis(),
    val supplier: String = "",
    val vatApplicable: Boolean? = null,
    val vatAmount: Double? = null,
    val amountExclVat: Double? = null,
    val slipNumber: Int = 0
)
