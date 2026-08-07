package com.production.slippery.util

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

// Ported from Handy Andy's PhotoFiles.kt — proven pattern, only the
// FileProvider authority changed to match this app's package.
object PhotoFiles {
    fun createReceiptImageFile(context: Context): Pair<File, Uri> {
        val dir = File(context.filesDir, "receipts").apply { mkdirs() }
        val file = File(dir, "receipt_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(
            context,
            "com.production.slippery.fileprovider",
            file
        )
        return file to uri
    }
}
