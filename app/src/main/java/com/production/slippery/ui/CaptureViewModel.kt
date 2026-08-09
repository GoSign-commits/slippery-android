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
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File

@Serializable
private data class SlipNumberRow(@SerialName("slip_number") val slipNumber: Int)

@Serializable
private data class EnvelopeRow(val id: String, @SerialName("float_amount") val floatAmount: Double)

data class EnvelopeInfo(val id: String, val floatAmount: Double)

/**
 * Three real outcomes on launch, not just loading/loaded — see
 * TRANSACTIONS.md "Envelope lifecycle" and "Zero-local-records case".
 */
sealed class EnvelopeUiState {
    object Loading : EnvelopeUiState()
    /** No open envelope for this buyer — a real, valid state, not an error. */
    object NoOpenEnvelope : EnvelopeUiState()
    /** Zero local records for this envelope — must confirm with the server
     *  before it's safe to allocate a slip number (see TRANSACTIONS.md). */
    data class Verifying(val envelope: EnvelopeInfo) : EnvelopeUiState()
    data class Ready(val envelope: EnvelopeInfo) : EnvelopeUiState()
    data class Error(val message: String) : EnvelopeUiState()
}

@Serializable
private data class CapturePingPayload(
    @SerialName("buyer_id") val buyerId: String,
    @SerialName("envelope_id") val envelopeId: String,
    val amount: Double,
    @SerialName("client_draft_id") val clientDraftId: String
)

class CaptureViewModel(app: Application) : AndroidViewModel(app) {
    private val dao = AppDatabase.getInstance(app).draftTransactionDao()

    private val _envelopeState = MutableStateFlow<EnvelopeUiState>(EnvelopeUiState.Loading)
    val envelopeState: StateFlow<EnvelopeUiState> = _envelopeState.asStateFlow()

    // Empty until the envelope is Ready — a buyer shouldn't see any draft
    // list at all while blocked on NoOpenEnvelope/Verifying/Error.
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val drafts: StateFlow<List<DraftTransaction>> = envelopeState
        .flatMapLatest { state ->
            if (state is EnvelopeUiState.Ready) dao.getByEnvelope(state.envelope.id)
            else flowOf(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _categories = MutableStateFlow<List<Category>>(emptyList())
    val categories: StateFlow<List<Category>> = _categories.asStateFlow()

    // Next slip number to assign locally, scoped to whichever envelope is
    // currently Ready. Reset/reseeded every time checkEnvelope() runs.
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
        checkEnvelope()
    }

    /** Public so the UI's retry button can call it directly — same logic
     *  whether this is the first check on launch or a manual retry. */
    fun checkEnvelope() {
        _envelopeState.value = EnvelopeUiState.Loading
        viewModelScope.launch {
            val envelope = try {
                SupabaseClientInstance.client.postgrest["envelopes"]
                    .select(columns = Columns.list("id", "float_amount")) {
                        filter {
                            eq("buyer_id", CURRENT_BUYER_ID)
                            eq("status", "open")
                        }
                        limit(1)
                    }
                    .decodeList<EnvelopeRow>()
                    .firstOrNull()
            } catch (e: Exception) {
                _envelopeState.value = EnvelopeUiState.Error(
                    "Can't reach server to check your float. Check your connection and try again."
                )
                return@launch
            }

            if (envelope == null) {
                _envelopeState.value = EnvelopeUiState.NoOpenEnvelope
                return@launch
            }

            val info = EnvelopeInfo(envelope.id, envelope.floatAmount)
            seedSlipCounter(info)
        }
    }

    // Local-first, but zero local records for THIS envelope genuinely can't
    // be trusted alone — see TRANSACTIONS.md "Zero-local-records case".
    private suspend fun seedSlipCounter(envelope: EnvelopeInfo) {
        val localMax = try {
            dao.getMaxSlipNumber(envelope.id)
        } catch (e: Exception) {
            null
        }

        if (localMax != null) {
            // Any local records for this envelope — safe to proceed
            // immediately. Worst case the number is slightly behind the
            // server; UNIQUE(envelope_id, slip_number) catches a collision
            // at Close.
            nextSlipNumber = localMax + 1
            _envelopeState.value = EnvelopeUiState.Ready(envelope)
            return
        }

        // Zero local records — block and confirm with the server before
        // allowing capture. Distinguishes "this envelope is brand new" from
        // "reinstall/new device mid-envelope, server has slips this device
        // has never seen".
        _envelopeState.value = EnvelopeUiState.Verifying(envelope)
        try {
            val rows = SupabaseClientInstance.client.postgrest["transactions"]
                .select(columns = Columns.list("slip_number")) {
                    filter { eq("envelope_id", envelope.id) }
                    order("slip_number", Order.DESCENDING)
                    limit(1)
                }
                .decodeList<SlipNumberRow>()
            nextSlipNumber = (rows.firstOrNull()?.slipNumber ?: 0) + 1
            _envelopeState.value = EnvelopeUiState.Ready(envelope)
        } catch (e: Exception) {
            _envelopeState.value = EnvelopeUiState.Error(
                "Can't verify slip numbering. Check your connection and try again."
            )
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
        val envelope = (envelopeState.value as? EnvelopeUiState.Ready)?.envelope ?: return
        val (vatAmount, amountExclVat) = calcVat(amount, vatApplicable)
        val slipNumber = nextSlipNumber
        nextSlipNumber++
        val draft = DraftTransaction(
            envelopeId = envelope.id,
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
        viewModelScope.launch {
            dao.insert(draft)
            pingCapture(envelope.id, draft)
        }
    }

    /** No-op if [draft] is already submitted — immutable once the envelope
     *  closes, per TRANSACTIONS.md. */
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
        val updated = draft.copy(
            // slipNumber and envelopeId deliberately not touched — assigned
            // once at creation, an edit doesn't move drafts between envelopes.
            amount = amount,
            categoryId = category?.id,
            categoryName = category?.name ?: draft.categoryName,
            description = description,
            supplier = supplier,
            vatApplicable = vatApplicable,
            vatAmount = vatAmount,
            amountExclVat = amountExclVat
        )
        viewModelScope.launch {
            dao.update(updated)
            // client_draft_id upsert key overwrites the existing ping with
            // the edited amount — see TRANSACTIONS.md "Live burn-rate estimate".
            pingCapture(draft.envelopeId, updated)
        }
    }

    /** No-op if [draft] is already submitted — immutable once the envelope
     *  closes, per TRANSACTIONS.md. */
    fun deleteDraft(draft: DraftTransaction) {
        if (draft.submitted) return
        viewModelScope.launch {
            dao.delete(draft)
            draft.photoPath?.let { File(it).delete() }
            try {
                SupabaseClientInstance.client.postgrest["capture_pings"]
                    .delete {
                        filter {
                            eq("buyer_id", CURRENT_BUYER_ID)
                            eq("client_draft_id", draft.clientSubmissionId)
                        }
                    }
            } catch (e: Exception) {
                // Non-fatal — capture_pings is disposable/non-authoritative
                // by design (see TRANSACTIONS.md). A stale ping just means
                // the live burn-rate estimate is briefly slightly high;
                // nothing depends on it being exact.
            }
        }
    }

    // Fire-and-forget, deliberately swallows failures — capture_pings is
    // disposable/non-authoritative (see TRANSACTIONS.md "Live burn-rate
    // estimate"). The phone reports THIS slip's amount only; the dashboard
    // does the summing, not the app (confirmed 2026-08-08).
    private suspend fun pingCapture(envelopeId: String, draft: DraftTransaction) {
        try {
            SupabaseClientInstance.client.postgrest["capture_pings"].upsert(
                CapturePingPayload(
                    buyerId = CURRENT_BUYER_ID,
                    envelopeId = envelopeId,
                    amount = draft.amount,
                    clientDraftId = draft.clientSubmissionId
                )
            ) {
                onConflict = "buyer_id,client_draft_id"
            }
        } catch (e: Exception) {
            // See comment above — non-fatal by design.
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
