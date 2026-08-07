package com.production.slippery

import android.app.Activity
import android.app.Application
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.production.slippery.data.DraftTransaction
import com.production.slippery.ui.CaptureViewModel
import com.production.slippery.util.PhotoFiles
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaptureScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val activity = context as Activity
    val app = context.applicationContext as Application
    val vm: CaptureViewModel = viewModel(
        factory = object : androidx.lifecycle.ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                CaptureViewModel(app) as T
        }
    )
    val drafts by vm.drafts.collectAsState()
    val categories by vm.categories.collectAsState()

    var receiptMenuFor by remember { mutableStateOf<Long?>(null) }
    var editingDraft by remember { mutableStateOf<DraftTransaction?>(null) }
    var draftPendingDelete by remember { mutableStateOf<DraftTransaction?>(null) }

    var pendingSourceFile by remember { mutableStateOf<File?>(null) }
    var showAmountDialog by remember { mutableStateOf(false) }
    var amountInput by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf<Category?>(null) }
    var categoryMenuExpanded by remember { mutableStateOf(false) }
    var noteInput by remember { mutableStateOf("") }

    val scannerOptions = remember {
        GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(false)
            .setPageLimit(1)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()
    }
    val scannerClient = remember { GmsDocumentScanning.getClient(scannerOptions) }

    val scanLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val scanResult = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
            val pageUri = scanResult?.pages?.firstOrNull()?.imageUri
            if (pageUri != null) {
                val (destFile, _) = PhotoFiles.createReceiptImageFile(context)
                context.contentResolver.openInputStream(pageUri)?.use { input ->
                    destFile.outputStream().use { output -> input.copyTo(output) }
                }
                pendingSourceFile = destFile
                showAmountDialog = true
            }
        }
    }

    Column(modifier.fillMaxSize().padding(16.dp)) {
        Text("Slippery", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))

        Button(onClick = {
            scannerClient.getStartScanIntent(activity)
                .addOnSuccessListener { intentSender ->
                    scanLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
                }
        }) {
            Text("Scan slip")
        }

        Spacer(Modifier.height(16.dp))
        Text("Total captured: R${"%.2f".format(drafts.sumOf { it.amount })}")

        LazyColumn(Modifier.weight(1f)) {
            items(drafts, key = { it.id }) { draft ->
                DraftRow(
                    draft = draft,
                    menuOpen = receiptMenuFor == draft.id,
                    onMenuOpen = { receiptMenuFor = draft.id },
                    onMenuDismiss = { receiptMenuFor = null },
                    onEdit = {
                        receiptMenuFor = null
                        editingDraft = draft
                        amountInput = draft.amount.toString()
                        selectedCategory = categories.find { it.id == draft.categoryId }
                        noteInput = draft.description
                        pendingSourceFile = null
                        showAmountDialog = true
                    },
                    onDelete = {
                        receiptMenuFor = null
                        draftPendingDelete = draft
                    }
                )
            }
        }
    }

    val toDelete = draftPendingDelete
    if (toDelete != null) {
        AlertDialog(
            onDismissRequest = { draftPendingDelete = null },
            title = { Text("Delete this slip?") },
            text = { Text("This removes it from your captured total. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteDraft(toDelete)
                    draftPendingDelete = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { draftPendingDelete = null }) { Text("Cancel") }
            }
        )
    }

    if (showAmountDialog) {
        val isEditing = editingDraft != null
        // Photo is mandatory — no "no slip" path. New entries must have a
        // scanned slip; edits keep whatever photo the draft already has.
        val hasPhoto = if (isEditing) editingDraft?.photoPath != null else pendingSourceFile != null
        val canSave = amountInput.toDoubleOrNull() != null && selectedCategory != null && hasPhoto

        AlertDialog(
            onDismissRequest = { showAmountDialog = false },
            title = { Text(if (isEditing) "Edit slip" else "Slip details") },
            text = {
                Column {
                    OutlinedTextField(
                        value = amountInput,
                        onValueChange = { amountInput = it },
                        label = { Text("Amount (R)") }
                    )
                    Spacer(Modifier.height(8.dp))

                    // Read-only picker — categories are server-owned (accounts/admin
                    // set these up), buyers select, they never type a new one in.
                    ExposedDropdownMenuBox(
                        expanded = categoryMenuExpanded,
                        onExpandedChange = { categoryMenuExpanded = it }
                    ) {
                        OutlinedTextField(
                            value = selectedCategory?.let { "${it.code} - ${it.name}" } ?: "",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Category *") },
                            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable, true),
                            supportingText = {
                                if (selectedCategory == null) Text("Required")
                            }
                        )
                        ExposedDropdownMenu(
                            expanded = categoryMenuExpanded,
                            onDismissRequest = { categoryMenuExpanded = false }
                        ) {
                            categories.forEach { cat ->
                                DropdownMenuItem(
                                    text = { Text("${cat.code} - ${cat.name}") },
                                    onClick = {
                                        selectedCategory = cat
                                        categoryMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = noteInput,
                        onValueChange = { noteInput = it },
                        label = { Text("Description (optional)") }
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = canSave,
                    onClick = {
                        val amt = amountInput.toDoubleOrNull()
                        if (amt != null && selectedCategory != null) {
                            val existing = editingDraft
                            if (existing != null) {
                                vm.updateDraft(existing, amt, selectedCategory, noteInput.trim())
                            } else {
                                val path = pendingSourceFile?.absolutePath
                                vm.addDraft(path, amt, selectedCategory, noteInput.trim())
                            }
                        }
                        amountInput = ""
                        selectedCategory = null
                        noteInput = ""
                        pendingSourceFile = null
                        editingDraft = null
                        showAmountDialog = false
                    }
                ) { Text("Save") }
            },

            dismissButton = {
                TextButton(onClick = {
                    if (editingDraft == null) pendingSourceFile?.delete()
                    pendingSourceFile = null
                    editingDraft = null
                    amountInput = ""
                    selectedCategory = null
                    noteInput = ""
                    showAmountDialog = false
                }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun DraftRow(
    draft: DraftTransaction,
    menuOpen: Boolean,
    onMenuOpen: () -> Unit,
    onMenuDismiss: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    // Envelope lock: greys out only once submitted is true (server-confirmed
    // sync), never optimistically — see FEATURES.md.
    val rowAlpha = if (draft.submitted) 0.5f else 1f

    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp).alpha(rowAlpha),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (draft.photoPath != null) {
            AsyncImage(
                model = draft.photoPath,
                contentDescription = "receipt",
                modifier = Modifier.size(56.dp)
            )
        } else {
            Box(modifier = Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Receipt, contentDescription = "no photo")
            }
        }

        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("R${"%.2f".format(draft.amount)}  •  ${draft.categoryName}")
            if (draft.description.isNotBlank()) {
                Text(draft.description, style = MaterialTheme.typography.bodySmall)
            }
            if (draft.submitted) {
                Text(
                    "Submitted",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        // No menu at all once submitted — nothing to edit or delete on an
        // immutable record. This is enforced in the ViewModel too (belt +
        // braces), but hiding it here avoids offering an action that's a no-op.
        if (!draft.submitted) {
            Box {
                IconButton(onClick = onMenuOpen) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "Slip options")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = onMenuDismiss) {
                    DropdownMenuItem(text = { Text("Edit") }, onClick = onEdit)
                    DropdownMenuItem(text = { Text("Delete") }, onClick = onDelete)
                }
            }
        }
    }
    HorizontalDivider()
}
