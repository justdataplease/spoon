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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
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
import com.justdataplease.spoon.ui.components.recipeImageModel
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MaxPhotoDimension = 1_280
private const val MaxEncodedPhotoBytes = 480_000

@Composable
internal fun RecipePhotoInput(
    selectedPhotoPath: String,
    retainedImageUrl: String,
    onImageChanged: (String) -> Unit,
    onError: (String) -> Unit,
    preparation: RecipePhotoPreparation,
    onProcessingStarted: () -> Unit,
    onCancelPendingPhoto: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val latestOnImageChanged by rememberUpdatedState(onImageChanged)
    val latestOnError by rememberUpdatedState(onError)
    val latestOnProcessingStarted by rememberUpdatedState(onProcessingStarted)
    var activeRead by remember { mutableStateOf(false) }
    val processing = preparation == RecipePhotoPreparation.PROCESSING
    var pendingCameraUri by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingCameraFile by rememberSaveable { mutableStateOf<String?>(null) }

    fun readPhoto(uri: Uri, cleanup: () -> Unit = {}) {
        latestOnProcessingStarted()
        activeRead = true
        scope.launch {
            try {
                val path = withContext(Dispatchers.IO) { context.compactImageToDraftPath(uri) }
                if (path == null) {
                    latestOnError("Δεν μπορέσαμε να διαβάσουμε αυτή τη φωτογραφία. Διάλεξέ την ξανά.")
                } else {
                    latestOnImageChanged(path)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                latestOnError("Η φωτογραφία δεν μπόρεσε να προστεθεί. Δοκίμασε ξανά.")
            } finally {
                cleanup()
                activeRead = false
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            if (activeRead) latestOnError("Η προετοιμασία της φωτογραφίας διακόπηκε. Διάλεξέ την ξανά.")
        }
    }
    LaunchedEffect(Unit) {
        // A restored editor cannot resume a coroutine belonging to a destroyed composition.
        if (preparation == RecipePhotoPreparation.PROCESSING && !activeRead) {
            latestOnError("Η προετοιμασία της φωτογραφίας διακόπηκε. Διάλεξέ την ξανά.")
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
        val previewModel: Any? = remember(selectedPhotoPath, retainedImageUrl) {
            selectedPhotoPath.takeIf(String::isNotBlank)?.let(::File)
                ?: recipeImageModel(retainedImageUrl)
        }
        if (previewModel != null) {
            AsyncImage(
                model = previewModel,
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

        if (preparation == RecipePhotoPreparation.FAILED) {
            Text(
                "Διάλεξε ξανά τη φωτογραφία ή συνέχισε με την προηγούμενη φωτογραφία, αν υπάρχει.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(onClick = onCancelPendingPhoto, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                Text("Συνέχεια χωρίς τη νέα φωτογραφία")
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
                    enabled = enabled,
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
                                        latestOnError("Δεν βρέθηκε διαθέσιμη εφαρμογή κάμερας.")
                                    }
                            },
                            onFailure = { latestOnError("Δεν ήταν δυνατό να ανοίξει η κάμερα.") },
                        )
                    },
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Outlined.CameraAlt, contentDescription = null)
                    Text("Κάμερα", modifier = Modifier.padding(start = 7.dp))
                }
            }
            if (previewModel != null) {
                OutlinedButton(onClick = { latestOnImageChanged("") }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.DeleteOutline, contentDescription = null)
                    Text("Αφαίρεση φωτογραφίας", modifier = Modifier.padding(start = 7.dp))
                }
            }
        }
    }
}

// The platform EXIF reader is available on Spoon's API 26 minimum.
private fun Context.compactImageToDraftPath(uri: Uri): String? {
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
    val directory = File(filesDir, DraftPhotoDirectoryName)
    if (!directory.exists() && !directory.mkdirs()) return null
    val file = File.createTempFile("spoon_recipe_draft_", ".jpg", directory)
    return runCatching {
        file.writeBytes(bytes)
        file.absolutePath
    }.getOrElse {
        file.delete()
        null
    }
}

internal fun Context.draftPhotoDataUri(path: String): String? {
    val file = safeDraftPhoto(path) ?: return null
    if (file.length() !in 1..MaxEncodedPhotoBytes.toLong()) return null
    val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
    if (bytes.isEmpty() || bytes.size > MaxEncodedPhotoBytes) return null
    return "data:image/jpeg;base64,${Base64.encodeToString(bytes, Base64.NO_WRAP)}"
}

internal fun Context.deleteDraftPhoto(path: String) {
    safeDraftPhoto(path)?.delete()
}

private fun Context.safeDraftPhoto(path: String): File? {
    if (path.isBlank()) return null
    val directory = File(filesDir, DraftPhotoDirectoryName).canonicalFile
    val candidate = runCatching { File(path).canonicalFile }.getOrNull() ?: return null
    val insideDirectory = candidate.path.startsWith(
        directory.path + File.separator,
        ignoreCase = true,
    )
    return candidate.takeIf { insideDirectory && it.isFile }
}

private const val DraftPhotoDirectoryName = "custom_recipe_drafts"

private fun Bitmap.toJpeg(quality: Int): ByteArray = ByteArrayOutputStream().use { output ->
    compress(Bitmap.CompressFormat.JPEG, quality, output)
    output.toByteArray()
}
