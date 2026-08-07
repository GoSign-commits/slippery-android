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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

class CaptureViewModel(app: Application) : AndroidViewModel(app) {
    private val dao = AppDatabase.getInstance(app).draftTransactionDao()

    val drafts: StateFlow<List<DraftTransaction>> = dao.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Live fetch, no local cache/sync yet — deliberate stub, see STATE.md.
    // Categories are server-owned; buyers pick from this list, never create one.
    private val _categories = MutableStateFlow<List<Category>>(emptyList())
    val categories: StateFlow<List<Category>> = _categories.asStateFlow()

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
    }

    fun addDraft(photoPath: String?, amount: Double, category: Category?, description: String) {
        viewModelScope.launch {
            dao.insert(
                DraftTransaction(
                    categoryId = category?.id,
                    categoryName = category?.name ?: "",
                    photoPath = photoPath,
                    amount = amount,
                    description = description
                )
            )
        }
    }

    /** No-op if [draft] is already submitted — immutable once synced, per SCHEMA.md. */
    fun updateDraft(draft: DraftTransaction, amount: Double, category: Category?, description: String) {
        if (draft.submitted) return
        viewModelScope.launch {
            dao.update(
                draft.copy(
                    amount = amount,
                    categoryId = category?.id,
                    categoryName = category?.name ?: draft.categoryName,
                    description = description
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
}
