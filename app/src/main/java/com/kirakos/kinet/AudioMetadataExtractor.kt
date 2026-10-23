package com.kirakos.kinet

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns

data class ExtractedMediaMetadata(
    val title: String,
    val artist: String,
    val album: String,
    val genre: String,
    val year: String,
    val bitrateKbps: Int,
    val sampleRate: Int,
    val mimeType: String,
    val fileSizeBytes: Long
)

object AudioMetadataExtractor {

    fun extract(context: Context, uri: Uri, fallbackName: String): ExtractedMediaMetadata {
        val retriever = MediaMetadataRetriever()
        var artist = ""
        var album = ""
        var genre = ""
        var year = ""
        var bitrateKbps = 0
        var sampleRate = 44100
        var mimeType = "audio/mpeg"
        var title = fallbackName
        var fileSizeBytes = 0L

        try {
            retriever.setDataSource(context, uri)

            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() }?.let {
                title = it.trim()
            }
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.takeIf { it.isNotBlank() }?.let {
                artist = it.trim()
            }
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.takeIf { it.isNotBlank() }?.let {
                album = it.trim()
            }
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE)?.takeIf { it.isNotBlank() }?.let {
                genre = it.trim()
            }
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR)?.takeIf { it.isNotBlank() }?.let {
                year = it.trim()
            }
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull()?.let {
                bitrateKbps = it / 1000
            }
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)?.takeIf { it.isNotBlank() }?.let {
                mimeType = it.trim()
            }
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)?.toIntOrNull()?.let {
                sampleRate = it
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }

        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex != -1 && cursor.moveToFirst()) {
                    fileSizeBytes = cursor.getLong(sizeIndex)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return ExtractedMediaMetadata(
            title = title,
            artist = artist,
            album = album,
            genre = genre,
            year = year,
            bitrateKbps = bitrateKbps,
            sampleRate = sampleRate,
            mimeType = mimeType,
            fileSizeBytes = fileSizeBytes
        )
    }
}
