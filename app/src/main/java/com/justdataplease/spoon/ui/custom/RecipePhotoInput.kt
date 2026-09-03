@file:android.annotation.SuppressLint("ExifInterface")

package com.justdataplease.spoon.ui.custom

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import androidx.core.graphics.scale
import coil.compose.AsyncImage
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MaxPhotoDimension = 1_280
private const val MaxEncodedPhotoBytes = 480_000

@Composable
internal fun RecipePhotoInput(
    imageDataUrl: String,
    onImageChanged: (String) -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var processing by remember { mutableStateOf(false) }
    var pendingCameraUri by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingCameraFile by rememberSaveable { mutableStateOf<String?>(null) }

    fun readPhoto(uri: Uri, cleanup: () -> Unit = {}) {
        scope.launch {
            processing = true
            val result = try {
                withContext(Dispatchers.IO) {
                    runCatching { context.compactImageDataUrl(uri) }
                }
            } finally {
                cleanup()
            }
            processing = false
            result.fold(
                onSuccess = { data ->
                    if (data == null) onError("Δεν μπορέσαμε να διαβάσουμε αυτή τη φωτογραφία.")
                    else onImageChanged(data)
                },
                onFailure = { onError("Η φωτογραφία δεν μπόρεσε να προστεθεί. Δοκίμασε ξανά.") },
            )
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(::readPhoto)
    }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val uri = pendingCameraUri?.let(Uri::parse)
        val file = pendingCameraFile?.let(::File)
        if (success && uri != null) {
            readPhoto(uri) { file?.delete() }
        } else {
            file?.delete()
        }
        pendingCameraUri = null
        pendingCameraFile = null
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (imageDataUrl.isNotBlank()) {
            AsyncImage(
                model = imageDataUrl,
                contentDescription = "Φωτογραφία της δικής μου συνταγής",
                modifier = Modifier.fillMaxWidth().height(210.dp).clip(RoundedCornerShape(20.dp)),
                contentScale = ContentScale.Crop,
            )
        } else {
            Surface(
                modifier = Modifier.fillMaxWidth().height(150.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(20.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Outlined.PhotoLibrary, contentDescription = null)
                        Text("Πρόσθεσε μια φωτογραφία", modifier = Modifier.padding(top = 7.dp))
                    }
                }
            }
        }

        if (processing) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator()
                Text("Προετοιμασία φωτογραφίας…", modifier = Modifier.padding(start = 10.dp))
            }
        } else {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                OutlinedButton(
                    onClick = {
                        galleryLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Outlined.PhotoLibrary, contentDescription = null)
                    Text("Συλλογή", modifier = Modifier.padding(start = 7.dp))
                }
                OutlinedButton(
                    onClick = {
                        runCatching {
                            val file = File.createTempFile("spoon_recipe_", ".jpg", context.cacheDir)
                            file to FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                        }.fold(
                            onSuccess = { (file, uri) ->
                                pendingCameraFile = file.absolutePath
                                pendingCameraUri = uri.toString()
                                runCatching { cameraLauncher.launch(uri) }
                                    .onFailure {
                                        file.delete()
                                        pendingCameraFile = null
                                        pendingCameraUri = null
                                        onError("Δεν βρέθηκε διαθέσιμη εφαρμογή κάμερας.")
                                    }
                            },
                            onFailure = { onError("Δεν ήταν δυνατό να ανοίξει η κάμερα.") },
                        )
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Outlined.CameraAlt, contentDescription = null)
                    Text("Κάμερα", modifier = Modifier.padding(start = 7.dp))
                }
            }
            if (imageDataUrl.isNotBlank()) {
                OutlinedButton(onClick = { onImageChanged("") }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.DeleteOutline, contentDescription = null)
                    Text("Αφαίρεση φωτογραφίας", modifier = Modifier.padding(start = 7.dp))
                }
            }
        }
    }
}

// The platform EXIF reader is available on Spoon's API 26 minimum.
private fun Context.compactImageDataUrl(uri: Uri): String? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    var sampleSize = 1
    while (max(bounds.outWidth / sampleSize, bounds.outHeight / sampleSize) > MaxPhotoDimension * 2) {
        sampleSize *= 2
    }
    val decoded = contentResolver.openInputStream(uri)?.use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sampleSize })
    } ?: return null

    val rotation = runCatching {
        contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
            when (ExifInterface(descriptor.fileDescriptor).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } ?: 0f
    }.getOrDefault(0f)
    var bitmap = if (rotation == 0f) decoded else {
        Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, Matrix().apply { postRotate(rotation) }, true)
            .also { if (it !== decoded) decoded.recycle() }
    }

    val maxSide = max(bitmap.width, bitmap.height)
    if (maxSide > MaxPhotoDimension) {
        val scale = MaxPhotoDimension.toFloat() / maxSide
        val original = bitmap
        bitmap = original.scale(
            (original.width * scale).toInt().coerceAtLeast(1),
            (original.height * scale).toInt().coerceAtLeast(1),
        )
        if (bitmap !== original) original.recycle()
    }

    var quality = 86
    var bytes = bitmap.toJpeg(quality)
    while (bytes.size > MaxEncodedPhotoBytes && quality > 42) {
        quality -= 8
        bytes = bitmap.toJpeg(quality)
    }
    while (bytes.size > MaxEncodedPhotoBytes && max(bitmap.width, bitmap.height) > 640) {
        val original = bitmap
        bitmap = original.scale(original.width * 3 / 4, original.height * 3 / 4)
        if (bitmap !== original) original.recycle()
        bytes = bitmap.toJpeg(72)
    }
    bitmap.recycle()
    if (bytes.size > MaxEncodedPhotoBytes) return null
    return "data:image/jpeg;base64,${Base64.encodeToString(bytes, Base64.NO_WRAP)}"
}

private fun Bitmap.toJpeg(quality: Int): ByteArray = ByteArrayOutputStream().use { output ->
    compress(Bitmap.CompressFormat.JPEG, quality, output)
    output.toByteArray()
}
