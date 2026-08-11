package com.production.slippery.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.production.slippery.Category
import com.production.slippery.SupabaseClientInstance
import com.production.slippery.data.AppDatabase
import com.production.slippery.data.DraftTransaction
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
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
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant

@Serializable
private data class SlipNumberRow(@SerialName("slip_number") val slipNumber: Int)

@Serializable
private data class EnvelopeRow(val id: String, @SerialName("float_amount") val floatAmount: Double)

data class EnvelopeInfo(val id: String, val floatAmount: Double)

/**
 * Close Envelope progress — separate from EnvelopeUiState because Close is
 * an action with its own lifecycle (uploading → done/failed), not a screen
 * state. See TRANSACTIONS.md "Closing an envelope".
 */
sealed class CloseState {
    object Idle : CloseState()
    data class InProgress(val completed: Int, val total: Int) : CloseState()
    object Success : CloseState()
    data class Error(val message: String) : CloseState()
}

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

    // Real signed-in buyer, not a stub — replaces the hardcoded
    // CURRENT_BUYER_ID constant that existed before QR login was built
    // (2026-08-09). MainActivity only ever shows CaptureScreen (and
    // therefore creates this ViewModel) once a session exists, so this
    // should never actually be null in practice — the exception exists
    // to surface that invariant breaking loudly, not silently.
    private val currentBuyerId: String =
        SupabaseClientInstance.client.auth.currentUserOrNull()?.id
            ?: error("CaptureViewModel created with no signed-in user")

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

    // Presign worker — see TRANSACTIONS.md "Photo backup" / infra docs.
    // Constant, not configurable: one worker per R2 bucket, same for all buyers.
    private val presignWorkerUrl =
        "https://slippery-r2-presign-worker.shauncampbell10.workers.dev"

    private val httpClient = HttpClient(Android)

    private val _closeState = MutableStateFlow<CloseState>(CloseState.Idle)
    val closeState: StateFlow<CloseState> = _closeState.asStateFlow()

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
                            eq("buyer_id", currentBuyerId)
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
            backupDraft(envelope.id)
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
            backupDraft(draft.envelopeId)
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
                            eq("buyer_id", currentBuyerId)
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
                    buyerId = currentBuyerId,
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

    /**
     * Close the current envelope: upload every unsubmitted draft's photo to
     * R2, insert its transaction row, mark it submitted. Only once ALL succeed
     * does the envelope flip to 'submitted'. Retry-safe via
     * client_submission_id — see TRANSACTIONS.md "Closing an envelope".
     */
    fun closeEnvelope() {
        val envelope = (envelopeState.value as? EnvelopeUiState.Ready)?.envelope ?: return
        _closeState.value = CloseState.InProgress(0, 0)
        viewModelScope.launch {
            try {
                val allDrafts = dao.getByEnvelopeOnce(envelope.id)
                val unsubmitted = allDrafts.filter { !it.submitted }
                if (unsubmitted.isEmpty()) {
                    // Nothing to upload — just flip the envelope.
                    updateEnvelopeToSubmitted(envelope.id)
                    _closeState.value = CloseState.Success
                    return@launch
                }
                val accessToken = SupabaseClientInstance.client.auth
                    .currentSessionOrNull()?.accessToken
                    ?: error("No session for Close — buyer must be signed in")

                for ((index, draft) in unsubmitted.withIndex()) {
                    _closeState.value =
                        CloseState.InProgress(index, unsubmitted.size)
                    uploadAndInsert(draft, envelope.id, accessToken)
                    // Per-draft: mark submitted locally + delete its ping.
                    // A partial failure above throws before reaching here,
                    // so only genuinely-succeeded drafts get marked.
                    dao.markDraftSubmitted(draft.id)
                    deleteCapturePing(draft.clientSubmissionId)
                }

                // Every draft succeeded — flip the envelope itself.
                _closeState.value =
                    CloseState.InProgress(unsubmitted.size, unsubmitted.size)
                updateEnvelopeToSubmitted(envelope.id)
                _closeState.value = CloseState.Success
            } catch (e: Exception) {
                _closeState.value = CloseState.Error(
                    e.message ?: "Close failed. Check your connection and try again."
                )
            }
        }
    }

    /** Reset Close state back to Idle — called by the UI when dismissing
     *  the success/error screen. */
    fun resetCloseState() {
        _closeState.value = CloseState.Idle
    }

    // Upload photo to R2 via the presign worker, then insert the transaction
    // row. Both must succeed before the draft is marked submitted — R2 first
    // (file-then-record pattern, same as the original per-transaction design).
    private suspend fun uploadAndInsert(
        draft: DraftTransaction,
        envelopeId: String,
        accessToken: String
    ) {
        val receiptPath = "$currentBuyerId/${draft.clientSubmissionId}.jpg"
        uploadToR2(receiptPath, draft.photoPath, accessToken)
        insertTransaction(draft, receiptPath, envelopeId)
    }

    // POST to presign worker for a presigned PUT URL, then PUT the raw JPEG.
    // Re-uploads on retry are harmless — same filename, overwrites, no orphan.
    private suspend fun uploadToR2(
        filePath: String,
        photoPath: String?,
        accessToken: String
    ) {
        if (photoPath == null) return
        val presignBody = """{"filePath":"$filePath","contentType":"image/jpeg"}"""
        val presignResponse = httpClient.post(presignWorkerUrl) {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            contentType(ContentType.Application.Json)
            setBody(presignBody)
        }
        if (presignResponse.status.value !in 200..299) {
            error("R2 presign failed: ${presignResponse.status}")
        }
        val presignedUrl = Json.decodeFromString<PresignResponse>(
            presignResponse.bodyAsText()
        ).url

        val bytes = File(photoPath).readBytes()
        val putResponse = httpClient.put(presignedUrl) {
            contentType(ContentType.Image.JPEG)
            setBody(bytes)
        }
        if (putResponse.status.value !in 200..299) {
            error("R2 upload failed: ${putResponse.status}")
        }
    }

    // Insert the transaction row. On retry, a unique-constraint violation
    // (Postgres 23505) means the draft was already inserted in a prior attempt
    // — treat as success, not error. Any other exception propagates and aborts.
    private suspend fun insertTransaction(
        draft: DraftTransaction,
        receiptUrl: String,
        envelopeId: String
    ) {
        val row = TransactionInsertRow(
            buyerId = currentBuyerId,
            categoryId = draft.categoryId
                ?: error("Draft has no category — can't submit"),
            amount = draft.amount,
            description = draft.description,
            receiptUrl = receiptUrl,
            spentAt = Instant.ofEpochMilli(draft.spentAt).toString(),
            clientSubmissionId = draft.clientSubmissionId,
            supplier = draft.supplier,
            vatApplicable = draft.vatApplicable
                ?: error("Draft has no VAT answer — can't submit"),
            vatAmount = draft.vatAmount,
            amountExclVat = draft.amountExclVat,
            slipNumber = draft.slipNumber,
            envelopeId = envelopeId
        )
        try {
            SupabaseClientInstance.client.postgrest["transactions"].insert(row)
        } catch (e: PostgrestRestException) {
            // 23505 = unique_violation — already inserted in a prior Close
            // attempt. This is the idempotency key doing its job, not an error.
            if (e.code != "23505") throw e
        }
    }

    // Flip the envelope to 'submitted' server-side. submitted_at from the
    // device clock (ISO 8601) — the schema column has its own DEFAULT now()
    // too, but we set it explicitly so the value is deterministic from the
    // app's perspective, not dependent on which DB defaults fire.
    private suspend fun updateEnvelopeToSubmitted(envelopeId: String) {
        SupabaseClientInstance.client.postgrest["envelopes"]
            .update({
                set("status", "submitted")
                set("submitted_at", Instant.now().toString())
            }) {
                filter { eq("id", envelopeId) }
            }
    }

    // Non-fatal — capture_pings are disposable by design (see TRANSACTIONS.md
    // "Live burn-rate estimate"). A stale ping just means the dashboard's
    // estimate is briefly slightly high; nothing depends on it being exact.
    private suspend fun deleteCapturePing(clientDraftId: String) {
        try {
            SupabaseClientInstance.client.postgrest["capture_pings"]
                .delete {
                    filter {
                        eq("buyer_id", currentBuyerId)
                        eq("client_draft_id", clientDraftId)
                    }
                }
        } catch (e: Exception) {
            // See comment above — non-fatal by design.
        }
    }

    // Fire-and-forget disaster-recovery backup — see TRANSACTIONS.md
    // "Photo + CSV backup". Runs after every successful local draft
    // save (insert or edit). Photo backup is a copy of the already-local
    // photo, separate from the receipt upload that only happens at Close.
    // CSV is rebuilt from scratch and re-uploaded every time — always a
    // complete, current snapshot, never partial. Both silently swallow
    // failures by design: no retry loop, no persisted state — the next
    // capture is another chance to catch up.
    private suspend fun backupDraft(envelopeId: String) {
        val accessToken = SupabaseClientInstance.client.auth
            .currentSessionOrNull()?.accessToken ?: return
        try {
            val allDrafts = dao.getByEnvelopeOnce(envelopeId)
            val latest = allDrafts.lastOrNull { it.envelopeId == envelopeId }
            latest?.photoPath?.let { photoPath ->
                val backupPhotoPath = "$currentBuyerId/backups/${latest.clientSubmissionId}.jpg"
                uploadToR2(backupPhotoPath, photoPath, accessToken)
            }
            uploadBackupCsv(envelopeId, allDrafts, accessToken)
        } catch (e: Exception) {
            // Silent by design — see comment above.
        }
    }

    // Rebuilds the FULL envelope CSV from every current local draft, in
    // slip_number order (same order as photo capture), and overwrites the
    // previous version. Never partial — always regenerated from scratch.
    private suspend fun uploadBackupCsv(
        envelopeId: String,
        drafts: List<com.production.slippery.data.DraftTransaction>,
        accessToken: String
    ) {
        val header = "envelope_id,client_submission_id,slip_number,supplier,description,category,amount,vat_applicable,vat_amount,amount_excl_vat,captured_at"
        val rows = drafts.joinToString("\n") { d ->
            listOf(
                d.envelopeId,
                d.clientSubmissionId,
                d.slipNumber.toString(),
                csvEscape(d.supplier),
                csvEscape(d.description),
                csvEscape(d.categoryName),
                d.amount.toString(),
                d.vatApplicable?.toString() ?: "",
                d.vatAmount?.toString() ?: "",
                d.amountExclVat?.toString() ?: "",
                Instant.ofEpochMilli(d.createdAt).toString()
            ).joinToString(",")
        }
        val csvContent = "$header\n$rows"
        val filePath = "$currentBuyerId/backups/$envelopeId.csv"

        val presignBody = """{"filePath":"$filePath","contentType":"text/csv"}"""
        val presignResponse = httpClient.post(presignWorkerUrl) {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
            contentType(ContentType.Application.Json)
            setBody(presignBody)
        }
        if (presignResponse.status.value !in 200..299) {
            error("R2 CSV presign failed: ${presignResponse.status}")
        }
        val presignedUrl = Json.decodeFromString<PresignResponse>(
            presignResponse.bodyAsText()
        ).url

        val putResponse = httpClient.put(presignedUrl) {
            contentType(ContentType.Text.CSV)
            setBody(csvContent.toByteArray())
        }
        if (putResponse.status.value !in 200..299) {
            error("R2 CSV upload failed: ${putResponse.status}")
        }
    }

    private fun csvEscape(value: String): String {
        return if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else value
    }

    @Serializable
    private data class PresignResponse(val url: String)

    // Exact column set per TRANSACTIONS.md — fields NOT set here (id, status,
    // recon_status, active, submitted_at, created_at, updated_at,
    // original_transaction_id) all have DB defaults or are irrelevant.
    @Serializable
    private data class TransactionInsertRow(
        @SerialName("buyer_id") val buyerId: String,
        @SerialName("category_id") val categoryId: String,
        val amount: Double,
        val description: String,
        @SerialName("receipt_url") val receiptUrl: String,
        @SerialName("spent_at") val spentAt: String,
        @SerialName("client_submission_id") val clientSubmissionId: String,
        val supplier: String,
        @SerialName("vat_applicable") val vatApplicable: Boolean,
        @SerialName("vat_amount") val vatAmount: Double? = null,
        @SerialName("amount_excl_vat") val amountExclVat: Double? = null,
        @SerialName("slip_number") val slipNumber: Int,
        @SerialName("envelope_id") val envelopeId: String
    )
}