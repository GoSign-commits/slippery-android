package com.production.slippery.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * A local, unsynced expense draft. Belongs to exactly one envelope
 * (petty cash float) — everything stays fully local while that envelope
 * is `open`. Closing the envelope is the one and only moment anything
 * uploads or gets written server-side (see TRANSACTIONS.md "Closing an
 * envelope" — revised 2026-08-08, replaces the earlier per-transaction
 * Submit design).
 *
 * [clientSubmissionId] is generated once, at draft creation — the same
 * UUID becomes both the R2 object filename and the DB idempotency key,
 * and is what makes envelope Close resumable on retry (see
 * TRANSACTIONS.md "Retry behavior"). Not a new concept, not two IDs —
 * one UUID, two uses.
 *
 * [envelopeId] the open envelope this draft belongs to. Fetched from
 * Supabase at launch (see CaptureViewModel) — the app never creates an
 * envelope itself, only reads which one is currently open for this buyer.
 *
 * [categoryId]/[categoryName] are denormalized from the live Supabase
 * fetch (no local category cache/sync yet — stubbed, see STATE.md).
 *
 * [submitted] is a local-only UI flag. Once true, this draft mirrors an
 * immutable server-side transaction — edit/delete must be disabled in
 * the UI from that point on. Now set when the ENVELOPE closes
 * successfully, not per-draft — every draft in a closed envelope flips
 * together.
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
 * [slipNumber] generated client-side at capture time, per ENVELOPE (not
 * per-buyer-lifetime — revised 2026-08-08), sequential — seeded from
 * max(local Room, server) at session start so neither a reinstall nor an
 * offline restart collides with prior submissions (see TRANSACTIONS.md
 * "Slip numbering" / "Zero-local-records case"). 0 is a placeholder
 * only — the ViewModel always assigns a real value before insert.
 */
@Entity(tableName = "draft_transactions")
data class DraftTransaction(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val clientSubmissionId: String = UUID.randomUUID().toString(),
    val envelopeId: String,
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
