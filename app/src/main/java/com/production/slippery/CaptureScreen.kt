package com.production.slippery

import android.app.Activity
import android.app.Application
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.production.slippery.data.DraftTransaction
import com.production.slippery.ui.CaptureViewModel
import com.production.slippery.ui.CloseState
import com.production.slippery.ui.EnvelopeUiState
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
    val envelopeState by vm.envelopeState.collectAsState()
    val closeState by vm.closeState.collectAsState()

    var showCloseConfirm by remember { mutableStateOf(false) }

    var receiptMenuFor by remember { mutableStateOf<Long?>(null) }
    var editingDraft by remember { mutableStateOf<DraftTransaction?>(null) }
    var draftPendingDelete by remember { mutableStateOf<DraftTransaction?>(null) }

    var pendingSourceFile by remember { mutableStateOf<File?>(null) }
    var showAmountDialog by remember { mutableStateOf(false) }
    var amountInput by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf<Category?>(null) }
    var categoryMenuExpanded by remember { mutableStateOf(false) }
    var noteInput by remember { mutableStateOf("") }
    var supplierInput by remember { mutableStateOf("") }
    // null = buyer hasn't answered yet — no default is allowed to
    // masquerade as "No", per TRANSACTIONS.md's VAT design.
    var vatApplicable by remember { mutableStateOf<Boolean?>(null) }

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
                // Defensive reset — belt + braces alongside the
                // onDismissRequest fix below, so a fresh scan never
                // inherits a previous (possibly dismissed-without-cancel)
                // entry's leftover field values.
                editingDraft = null
                amountInput = ""
                selectedCategory = null
                noteInput = ""
                supplierInput = ""
                vatApplicable = null
                pendingSourceFile = destFile
                showAmountDialog = true
            }
        }
    }

    Column(modifier.fillMaxSize().padding(16.dp)) {
        Text("Slippery", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))

        // Three real outcomes here, not just loading/loaded — see
        // TRANSACTIONS.md "Envelope lifecycle" and "Zero-local-records case".
        // Capture is only ever shown in the Ready branch.
        when (val state = envelopeState) {
            is EnvelopeUiState.Loading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            is EnvelopeUiState.NoOpenEnvelope -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "No active float assigned — check with accounts.",
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
            is EnvelopeUiState.Verifying -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(12.dp))
                        Text("Checking slip numbering...")
                    }
                }
            }
            is EnvelopeUiState.Error -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            state.message,
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { vm.checkEnvelope() }) { Text("Retry") }
                    }
                }
            }
            is EnvelopeUiState.Ready -> {
                Button(onClick = {
                    scannerClient.getStartScanIntent(activity)
                        .addOnSuccessListener { intentSender ->
                            scanLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
                        }
                }) {
                    Text("Scan slip")
                }

                Spacer(Modifier.height(16.dp))
                Text("Float: R${"%.2f".format(state.envelope.floatAmount)}")
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
                                supplierInput = draft.supplier
                                vatApplicable = draft.vatApplicable
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

                // Close button — only enabled when there's at least one
                // unsubmitted draft. Submitted drafts mean a prior partial
                // Close; the button stays enabled for the remaining ones.
                val hasUnsubmitted = drafts.any { !it.submitted }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { showCloseConfirm = true },
                    enabled = hasUnsubmitted &&
                        closeState !is CloseState.InProgress,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Close envelope")
                }

                // Close progress / result — inline, not a separate dialog,
                // so the buyer sees the draft list while it uploads.
                when (val cs = closeState) {
                    is CloseState.InProgress -> {
                        Spacer(Modifier.height(8.dp))
                        val fraction = if (cs.total > 0) cs.completed.toFloat() / cs.total else 0f
                        LinearProgressIndicator(
                            progress = { fraction },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(4.dp))
                        Text("Uploading ${cs.completed} of ${cs.total}...")
                    }
                    is CloseState.Error -> {
                        Spacer(Modifier.height(8.dp))
                        Text(cs.message, color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(4.dp))
                        Button(onClick = { vm.closeEnvelope() }) {
                            Text("Retry close")
                        }
                    }
                    is CloseState.Success -> {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Envelope closed. All slips uploaded.",
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(4.dp))
                        Button(onClick = {
                            vm.resetCloseState()
                            vm.checkEnvelope()
                        }) {
                            Text("Done")
                        }
                    }
                    is CloseState.Idle -> { /* nothing — button is always visible above */ }
                }
            }
        }
    }

    if (showCloseConfirm) {
        AlertDialog(
            onDismissRequest = { showCloseConfirm = false },
            title = { Text("Close this envelope?") },
            text = {
                Text("Uploads everything and locks it — this can't be undone.")
            },
            confirmButton = {
                TextButton(onClick = {
                    showCloseConfirm = false
                    vm.closeEnvelope()
                }) { Text("Close") }
            },
            dismissButton = {
                TextButton(onClick = { showCloseConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
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

    fun resetDialogState() {
        amountInput = ""
        selectedCategory = null
        noteInput = ""
        supplierInput = ""
        vatApplicable = null
        pendingSourceFile = null
        editingDraft = null
        showAmountDialog = false
    }

    if (showAmountDialog) {
        val isEditing = editingDraft != null
        val hasPhoto = if (isEditing) editingDraft?.photoPath != null else pendingSourceFile != null
        val amountValue = amountInput.toDoubleOrNull()
        val canSave = amountValue != null && selectedCategory != null && hasPhoto &&
            supplierInput.isNotBlank() && vatApplicable != null && noteInput.isNotBlank()

        // Calculated live from amountInput — never typed by the buyer.
        // amount is always VAT-inclusive (what the slip shows).
        val vatAmount = if (vatApplicable == true && amountValue != null) amountValue * 15 / 115 else null
        val amountExclVat = if (vatApplicable == true && amountValue != null) amountValue - (vatAmount ?: 0.0) else null

        // Custom Dialog, not AlertDialog — AlertDialog's confirm/cancel
        // buttons live in a fixed footer slot that doesn't scroll with the
        // content and doesn't reliably respect imePadding, which is exactly
        // why Save kept getting hidden behind the keyboard. Putting the
        // buttons inside the same scrollable Column as everything else
        // guarantees they're reachable by scrolling, regardless of
        // keyboard height.
        Dialog(
            onDismissRequest = {
                if (editingDraft == null) pendingSourceFile?.delete()
                resetDialogState()
            },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth(0.95f)
                    .heightIn(max = 560.dp),
                shape = MaterialTheme.shapes.large,
                tonalElevation = 6.dp
            ) {
                Column(
                    Modifier
                        .verticalScroll(rememberScrollState())
                        .imePadding()
                        .padding(24.dp)
                ) {
                    Text(
                        if (isEditing) "Edit slip" else "Slip details",
                        style = MaterialTheme.typography.titleLarge
                    )
                    Spacer(Modifier.height(16.dp))

                    OutlinedTextField(
                        value = noteInput,
                        onValueChange = { noteInput = it },
                        label = { Text("Description *") },
                        modifier = Modifier.fillMaxWidth()
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
                            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable, true)
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
                        value = supplierInput,
                        onValueChange = { supplierInput = it },
                        label = { Text("Supplier *") }
                    )
                    Spacer(Modifier.height(8.dp))

                    Text("VATable? *", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = vatApplicable == true,
                            onClick = { vatApplicable = true },
                            label = { Text("Yes") }
                        )
                        FilterChip(
                            selected = vatApplicable == false,
                            onClick = { vatApplicable = false },
                            label = { Text("No") }
                        )
                    }
                    Spacer(Modifier.height(8.dp))

                    // Calculated, read-only — kept visible as an in-the-moment
                    // sanity check (catch a Total typo before saving), but
                    // lightweight so it doesn't read as "more required inputs".
                    // Shown under Total, matching the physical cover sheet's
                    // reading order (Total is the input, VAT/Less VAT follow).
                    OutlinedTextField(
                        value = amountInput,
                        onValueChange = { amountInput = it },
                        label = { Text("Total (R) *") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (vatApplicable == true) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "VAT: ${vatAmount?.let { "R%.2f".format(it) } ?: "—"}   " +
                                "Less VAT: ${amountExclVat?.let { "R%.2f".format(it) } ?: "—"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(Modifier.height(24.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = {
                            if (editingDraft == null) pendingSourceFile?.delete()
                            resetDialogState()
                        }) { Text("Cancel") }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            enabled = canSave,
                            onClick = {
                                val amt = amountValue
                                val vat = vatApplicable
                                if (amt != null && selectedCategory != null && vat != null) {
                                    val existing = editingDraft
                                    if (existing != null) {
                                        vm.updateDraft(
                                            existing, amt, selectedCategory, noteInput.trim(),
                                            supplierInput.trim(), vat
                                        )
                                    } else {
                                        val path = pendingSourceFile?.absolutePath
                                        vm.addDraft(
                                            path, amt, selectedCategory, noteInput.trim(),
                                            supplierInput.trim(), vat
                                        )
                                    }
                                }
                                resetDialogState()
                            }
                        ) { Text("Save") }
                    }
                    // Generous bottom padding — was the other half of the
                    // "have to minimise the keyboard" complaint: even with
                    // the button now inside the scroll, it sat flush against
                    // the bottom edge with the keyboard up. This guarantees
                    // clear space below it once scrolled fully into view.
                    Spacer(Modifier.height(32.dp))
                }
            }
        }
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
        // Slip number only — no "Slip #" label, no thumbnail. Thumbnail's
        // still viewable in the edit dialog, just not worth the row space.
        Box(modifier = Modifier.width(32.dp), contentAlignment = Alignment.Center) {
            Text("${draft.slipNumber}", style = MaterialTheme.typography.bodyMedium)
        }

        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                draft.description.ifBlank { draft.categoryName },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            draft.categoryId?.let {
                Text(draft.categoryName, style = MaterialTheme.typography.bodySmall)
            }
            Text(
                "R${"%.2f".format(draft.amount)}",
                style = MaterialTheme.typography.titleMedium
            )
            if (draft.submitted) {
                Text(
                    "Submitted",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
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
