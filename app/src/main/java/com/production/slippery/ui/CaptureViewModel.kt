package com.production.slippery.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.production.slippery.Category
import com.production.slippery.SupabaseClientInstance
import com.production.slippery.data.AppDatabase
import com.production.slippery.data.DraftTransaction
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File

@Serializable
private data class SlipNumberRow(@SerialName("slip_number") val slipNumber: Int)

class CaptureViewModel(app: Application) : AndroidViewModel(app) {
    private val dao = AppDatabase.getInstance(app).draftTransactionDao()

    val drafts: StateFlow<List<DraftTransaction>> = dao.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _categories = MutableStateFlow<List<Category>>(emptyList())
    val categories: StateFlow<List<Category>> = _categories.asStateFlow()

    // Next slip number to assign locally. Seeded from the buyer's real
    // MAX(slip_number) on Supabase at session start (see init block) so a
    // reinstall/new device never collides with prior submissions.
    // Starts at 1 as a safe fallback if the seed fetch fails — worse case
    // is a local collision caught by the DB's UNIQUE(buyer_id, slip_number)
    // constraint at submit time, never a silent duplicate.
    private var nextSlipNumber = 1

    init {
        viewModelScope.launch {
            try {
                val result = SupabaseClientInstance.client.postgrest["categories"]
                    .select(columns = Columns.list("id", "code", "name"))
                    .decodeList<Category>()
                _categories.value = result
            } catch (e: Exception) {
                // Swallow — capture still works with an empty dropdown, buyer just
                // can't pick a category until connectivity/next launch. Not fatal.
            }
        }
        viewModelScope.launch {
            // Local half first — this is the gap that mattered most: if the
            // app is killed/reopened WHILE OFFLINE, a fresh instance used to
            // start back at 1 even with local drafts already at 1, 2, 3 on
            // this device, producing a real local duplicate. The server only
            // ever knows about SUBMITTED slips, never local-only drafts, so
            // it can't close this gap by itself. Bug found + fixed 2026-08-06.
            val localMax = try {
                dao.getMaxSlipNumber() ?: 0
            } catch (e: Exception) {
                0
            }
            nextSlipNumber = maxOf(nextSlipNumber, localMax + 1)

            // Server half — catches the reinstall/new-device case: local
            // storage is empty/wiped, but this buyer has real submitted
            // history the new device has never seen.
            try {
                val rows = SupabaseClientInstance.client.postgrest["transactions"]
                    .select(columns = Columns.list("slip_number")) {
                        filter { eq("buyer_id", CURRENT_BUYER_ID) }
                        order("slip_number", Order.DESCENDING)
                        limit(1)
                    }
                    .decodeList<SlipNumberRow>()
                // maxOf, not a blind assignment — this fetch is async and can
                // resolve AFTER the buyer has already saved one or more drafts
                // (which advance nextSlipNumber synchronously in addDraft).
                // A blind overwrite here would clobber that progress back
                // down to the server's answer, which is always stale until
                // something's actually been submitted. Bug found + fixed 2026-08-06.
                nextSlipNumber = maxOf(nextSlipNumber, (rows.firstOrNull()?.slipNumber ?: 0) + 1)
            } catch (e: Exception) {
                // No network — nextSlipNumber already reflects the local
                // max from above, which is the best available answer
                // offline. The DB's UNIQUE(buyer_id, slip_number) constraint
                // is the final backstop if this ever still collides at submit.
            }
        }
    }

    fun addDraft(
        photoPath: String?,
        amount: Double,
        category: Category?,
        description: String,
        supplier: String,
        vatApplicable: Boolean
    ) {
        val (vatAmount, amountExclVat) = calcVat(amount, vatApplicable)
        val slipNumber = nextSlipNumber
        nextSlipNumber++
        viewModelScope.launch {
            dao.insert(
                DraftTransaction(
                    categoryId = category?.id,
                    categoryName = category?.name ?: "",
                    photoPath = photoPath,
                    amount = amount,
                    description = description,
                    supplier = supplier,
                    vatApplicable = vatApplicable,
                    vatAmount = vatAmount,
                    amountExclVat = amountExclVat,
                    slipNumber = slipNumber
                )
            )
        }
    }

    /** No-op if [draft] is already submitted — immutable once synced, per SCHEMA.md. */
    fun updateDraft(
        draft: DraftTransaction,
        amount: Double,
        category: Category?,
        description: String,
        supplier: String,
        vatApplicable: Boolean
    ) {
        if (draft.submitted) return
        val (vatAmount, amountExclVat) = calcVat(amount, vatApplicable)
        viewModelScope.launch {
            dao.update(
                // slipNumber deliberately not touched — assigned once at
                // creation, an edit doesn't get a new slip number.
                draft.copy(
                    amount = amount,
                    categoryId = category?.id,
                    categoryName = category?.name ?: draft.categoryName,
                    description = description,
                    supplier = supplier,
                    vatApplicable = vatApplicable,
                    vatAmount = vatAmount,
                    amountExclVat = amountExclVat
                )
            )
        }
    }

    /** No-op if [draft] is already submitted — immutable once synced, per SCHEMA.md. */
    fun deleteDraft(draft: DraftTransaction) {
        if (draft.submitted) return
        viewModelScope.launch {
            dao.delete(draft)
            draft.photoPath?.let { File(it).delete() }
        }
    }

    // amount is always VAT-inclusive (what the slip shows) — see
    // TRANSACTIONS.md "Cover-sheet fields". Null, never 0, when not VATable.
    private fun calcVat(amount: Double, vatApplicable: Boolean): Pair<Double?, Double?> {
        if (!vatApplicable) return null to null
        val vat = amount * 15 / 115
        return vat to (amount - vat)
    }

    companion object {
        // STUB — no login/QR onboarding flow exists yet (see STATE.md
        // outstanding). Real buyer profile row, not fake data, so it
        // satisfies the buyer_id FK — but must be replaced once auth exists.
        const val CURRENT_BUYER_ID = "acf9791e-7da7-4844-9e11-9f872698a492"
    }
}
