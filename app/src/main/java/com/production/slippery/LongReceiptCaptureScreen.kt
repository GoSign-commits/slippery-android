package com.production.slippery

import android.Manifest
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Rect
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import java.util.concurrent.Executors

@Composable
fun LongReceiptCaptureScreen(
    onCaptured: (Bitmap) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    val executor = remember { Executors.newSingleThreadExecutor() }
    
    var capturedBitmaps by remember { mutableStateOf(listOf<Bitmap>()) }
    var showPreview by remember { mutableStateOf(false) }
    var retakeIndex by remember { mutableStateOf<Int?>(null) }
    
    var hasCameraPermission by remember { mutableStateOf(false) }
    var permissionDenied by remember { mutableStateOf(false) }
    
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) hasCameraPermission = true
        else permissionDenied = true
    }

    LaunchedEffect(Unit) {
        permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    fun rotateBitmap(bitmap: Bitmap, rotationDegrees: Int): Bitmap {
        if (rotationDegrees == 0) return bitmap
        val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    // Helper to create reference strip: bottom 20% of the last captured bitmap
    val referenceStrip: androidx.compose.ui.graphics.ImageBitmap? = remember(capturedBitmaps, retakeIndex) {
        if (capturedBitmaps.isNotEmpty() && retakeIndex == null) {
            val lastBitmap = capturedBitmaps.last()
            val stripHeight = (lastBitmap.height * 0.2f).toInt().coerceAtLeast(1)
            val srcRect = Rect(0, lastBitmap.height - stripHeight, lastBitmap.width, lastBitmap.height)
            val dstBitmap = android.graphics.Bitmap.createBitmap(lastBitmap.width, stripHeight, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = Canvas(dstBitmap)
            canvas.drawBitmap(lastBitmap, srcRect, Rect(0, 0, lastBitmap.width, stripHeight), null)
            dstBitmap.asImageBitmap()
        } else {
            null
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        if (permissionDenied) {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Camera permission is required for this feature.")
                Spacer(Modifier.height(16.dp))
                Button(onClick = onCancel) { Text("Back") }
            }
        } else if (!hasCameraPermission) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (showPreview) {
            val stitchedBitmap = remember(capturedBitmaps) { stitchBitmaps(capturedBitmaps) }
            
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Preview", style = MaterialTheme.typography.headlineSmall)
                    IconButton(onClick = onCancel) { Icon(Icons.Filled.Close, contentDescription = "Cancel") }
                }
                
                LazyRow(Modifier.weight(0.3f).fillMaxWidth()) {
                    itemsIndexed(capturedBitmaps) { index, bitmap ->
                        Column(Modifier.padding(4.dp)) {
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = "Shot ${index + 1}",
                                modifier = Modifier.size(100.dp).clickable { 
                                    retakeIndex = index
                                    showPreview = false 
                                }
                            )
                            Text("Shot ${index + 1}")
                        }
                    }
                }

                Image(
                    bitmap = stitchedBitmap.asImageBitmap(),
                    contentDescription = "Stitched receipt",
                    modifier = Modifier.weight(0.7f).fillMaxWidth()
                )
                
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { showPreview = false }, modifier = Modifier.weight(1f)) { Text("Add more") }
                    Button(onClick = { onCaptured(stitchedBitmap) }, modifier = Modifier.weight(1f)) { Text("Confirm") }
                }
            }
        } else {
            Box(Modifier.fillMaxSize()) {
                AndroidView(
                    factory = { ctx ->
                        PreviewView(ctx).apply {
                            val preview = Preview.Builder().build()
                            val selector = CameraSelector.DEFAULT_BACK_CAMERA
                            imageCapture = ImageCapture.Builder().build()
                            preview.setSurfaceProvider(surfaceProvider)
                            
                            try {
                                cameraProviderFuture.get().bindToLifecycle(
                                    lifecycleOwner, selector, preview, imageCapture
                                )
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
                
                // Reference strip at top when taking next shot (not retaking, not first shot)
                if (referenceStrip != null) {
                    Image(
                        bitmap = referenceStrip,
                        contentDescription = "Reference strip",
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 80.dp)
                            .align(Alignment.TopCenter)
                    )
                }
                
                IconButton(onClick = onCancel, modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = "Cancel", tint = androidx.compose.ui.graphics.Color.White)
                }
                
                Column(Modifier.align(Alignment.BottomCenter).padding(16.dp)) {
                    Text(if (retakeIndex != null) "Retaking shot ${retakeIndex!! + 1}" else "Shots: ${capturedBitmaps.size}", color = androidx.compose.ui.graphics.Color.White)
                    Row {
                        Button(onClick = {
                            imageCapture?.takePicture(executor, object : ImageCapture.OnImageCapturedCallback() {
                                override fun onCaptureSuccess(image: ImageProxy) {
                                    val rotation = image.imageInfo.rotationDegrees
                                    val bitmap = image.toBitmap()
                                    val rotatedBitmap = rotateBitmap(bitmap, rotation)
                                    
                                    if (retakeIndex != null) {
                                        val newList = capturedBitmaps.toMutableList()
                                        newList[retakeIndex!!] = rotatedBitmap
                                        capturedBitmaps = newList
                                        retakeIndex = null
                                    } else {
                                        capturedBitmaps = capturedBitmaps + rotatedBitmap
                                    }
                                    image.close()
                                }
                                override fun onError(exception: ImageCaptureException) {
                                    exception.printStackTrace()
                                }
                            })
                        }) { Text(if (retakeIndex != null) "Retake" else "Shutter") }
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = { showPreview = true }, enabled = capturedBitmaps.isNotEmpty()) { Text("Finish") }
                    }
                }
            }
        }
    }
}

private fun stitchBitmaps(bitmaps: List<Bitmap>): Bitmap {
    if (bitmaps.isEmpty()) return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    val width = bitmaps.maxOf { it.width }
    val height = bitmaps.sumOf { it.height }
    val stitched = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(stitched)
    var currentTop = 0
    for (bitmap in bitmaps) {
        canvas.drawBitmap(bitmap, 0f, currentTop.toFloat(), null)
        currentTop += bitmap.height
    }
    return stitched
}
