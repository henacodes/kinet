package com.kirakos.kinet

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

enum class ExportFormat(
    val extension: String,
    val mimeType: String,
    val label: String,
    val description: String
) {
    WAV(
        extension = "wav",
        mimeType = "audio/wav",
        label = "WAV",
        description = "Studio lossless • 16-bit 44.1 kHz PCM • Full quality"
    ),
    M4A(
        extension = "m4a",
        mimeType = "audio/mp4",
        label = "M4A",
        description = "Compressed AAC • 256 kbps • ~10× smaller, easy sharing"
    )
}

enum class ExportStemTarget(val displayName: String) {
    VOCALS("Vocals"),
    ACCOMPANIMENT("Instrumental"),
    BOTH("Both stems")
}

object StemExporter {

    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
    }

    private fun getExportsDir(context: Context): File {
        val dir = File(context.cacheDir, "exports")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * Resolves or prepares the audio file in the requested format (WAV or M4A).
     * If M4A is selected, it encodes the WAV file to M4A into the exports cache.
     */
    suspend fun prepareStemFile(
        context: Context,
        rawWavFile: File,
        songTitle: String,
        stemType: String, // "Vocals" or "Instrumental"
        format: ExportFormat,
        onProgress: ((Float) -> Unit)? = null
    ): File = withContext(Dispatchers.IO) {
        val safeTitle = sanitizeFileName(songTitle)
        val exportsDir = getExportsDir(context)
        val targetName = "$safeTitle - $stemType.${format.extension}"
        val targetFile = File(exportsDir, targetName)

        if (format == ExportFormat.WAV) {
            // For WAV, copy to cleanly named file if needed
            if (!targetFile.exists() || targetFile.length() != rawWavFile.length()) {
                rawWavFile.copyTo(targetFile, overwrite = true)
            }
            targetFile
        } else {
            // M4A encoding
            val success = AudioEncoder.encodeWavToM4a(
                wavFile = rawWavFile,
                outputM4aFile = targetFile,
                onProgress = onProgress
            )
            if (success && targetFile.exists()) {
                targetFile
            } else {
                // Fallback to WAV if encoding fails
                rawWavFile
            }
        }
    }

    /**
     * Saves a stem to public storage (Music/Kinet).
     */
    suspend fun saveStemToDevice(
        context: Context,
        file: File,
        displayTitle: String,
        stemType: String,
        format: ExportFormat
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val safeTitle = sanitizeFileName(displayTitle)
            val fileName = "$safeTitle - $stemType.${format.extension}"

            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Audio.Media.MIME_TYPE, format.mimeType)
                put(MediaStore.Audio.Media.TITLE, "$displayTitle ($stemType)")
                put(MediaStore.Audio.Media.ARTIST, "Kinet")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/Kinet")
                    put(MediaStore.Audio.Media.IS_PENDING, 1)
                }
            }

            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                ?: return@withContext false

            resolver.openOutputStream(uri)?.use { out ->
                FileInputStream(file).use { input ->
                    input.copyTo(out)
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Audio.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }

            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Shares a single stem via Android Sharesheet.
     */
    fun shareStem(
        context: Context,
        file: File,
        displayTitle: String,
        stemType: String,
        format: ExportFormat
    ) {
        try {
            val uri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = format.mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "$displayTitle - $stemType")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            val chooser = Intent.createChooser(shareIntent, "Share $stemType stem via")
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, "Could not share file: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Shares both stems simultaneously via Android Sharesheet.
     */
    fun shareBothStems(
        context: Context,
        vocalsFile: File,
        accompFile: File,
        displayTitle: String,
        format: ExportFormat
    ) {
        try {
            val vocUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                vocalsFile
            )
            val accUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                accompFile
            )

            val uris = arrayListOf(vocUri, accUri)

            val shareIntent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = format.mimeType
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                putExtra(Intent.EXTRA_SUBJECT, "$displayTitle - Stems")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            val chooser = Intent.createChooser(shareIntent, "Share stems via")
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, "Could not share stems: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    fun shareMultipleStems(
        context: Context,
        files: List<File>,
        displayTitle: String,
        format: ExportFormat
    ) {
        try {
            val uris = ArrayList(files.map { file ->
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file
                )
            })

            val shareIntent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = format.mimeType
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                putExtra(Intent.EXTRA_SUBJECT, "$displayTitle - All Stems")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            val chooser = Intent.createChooser(shareIntent, "Share stems via")
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(context, "Could not share stems: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
