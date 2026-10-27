package com.kirakos.kinet

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

data class SongProject(
    val id: String,
    val title: String,
    val durationSeconds: Float,
    val vocalsFile: File,
    val accompanimentFile: File,
    val chordsFile: File,
    val createdAt: Long,
    val category: String = "General",
    val isFavorite: Boolean = false,
    val artist: String = "",
    val album: String = "",
    val genre: String = "",
    val year: String = "",
    val bitrateKbps: Int = 0,
    val sampleRate: Int = 44100,
    val originalFormat: String = "",
    val originalFileSizeBytes: Long = 0L,
    val drumsFile: File? = null,
    val bassFile: File? = null,
    val otherFile: File? = null,
    val isFourStem: Boolean = false
)

data class Playlist(
    val id: String,
    val name: String,
    val createdAt: Long,
    val trackIds: List<String> = emptyList()
)

class ProjectRepository(private val context: Context) {

    private val projectsDir = File(context.filesDir, "projects").apply {
        if (!exists()) mkdirs()
    }

    private val playlistsFile = File(context.filesDir, "playlists.json")

    fun getAllProjects(): List<SongProject> {
        val list = mutableListOf<SongProject>()
        val projectFolders = projectsDir.listFiles { file -> file.isDirectory } ?: return emptyList()

        for (folder in projectFolders) {
            val metaFile = File(folder, "meta.json")
            val vocals = File(folder, "vocals.wav")
            val accomp = File(folder, "accompaniment.wav")
            val drums = File(folder, "drums.wav")
            val bass = File(folder, "bass.wav")
            val other = File(folder, "other.wav")
            val chords = File(folder, "chords.json")

            if (metaFile.exists() && vocals.exists() && accomp.exists()) {
                val isFourStem = drums.exists() && bass.exists() && other.exists()
                try {
                    val json = JSONObject(metaFile.readText())
                    list.add(
                        SongProject(
                            id = json.getString("id"),
                            title = json.optString("title", folder.name),
                            durationSeconds = json.optDouble("durationSeconds", 0.0).toFloat(),
                            vocalsFile = vocals,
                            accompanimentFile = accomp,
                            drumsFile = if (isFourStem) drums else null,
                            bassFile = if (isFourStem) bass else null,
                            otherFile = if (isFourStem) other else null,
                            isFourStem = isFourStem,
                            chordsFile = chords,
                            createdAt = json.optLong("createdAt", folder.lastModified()),
                            category = json.optString("category", "General"),
                            isFavorite = json.optBoolean("isFavorite", false),
                            artist = json.optString("artist", ""),
                            album = json.optString("album", ""),
                            genre = json.optString("genre", ""),
                            year = json.optString("year", ""),
                            bitrateKbps = json.optInt("bitrateKbps", 0),
                            sampleRate = json.optInt("sampleRate", 44100),
                            originalFormat = json.optString("originalFormat", "WAV Audio"),
                            originalFileSizeBytes = json.optLong("originalFileSizeBytes", 0L)
                        )
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        return list.sortedByDescending { it.createdAt }
    }

    fun getProject(id: String): SongProject? {
        return getAllProjects().find { it.id == id }
    }

    private fun readAndSumWavFiles(f1: File, f2: File): Pair<FloatArray, FloatArray> {
        val b1 = f1.readBytes()
        val b2 = f2.readBytes()
        val len1 = maxOf(0, (b1.size - 44) / 4)
        val len2 = maxOf(0, (b2.size - 44) / 4)
        val totalFrames = minOf(len1, len2)

        val bb1 = ByteBuffer.wrap(b1, 44, totalFrames * 4).order(ByteOrder.LITTLE_ENDIAN)
        val bb2 = ByteBuffer.wrap(b2, 44, totalFrames * 4).order(ByteOrder.LITTLE_ENDIAN)

        val c0 = FloatArray(totalFrames)
        val c1 = FloatArray(totalFrames)

        for (i in 0 until totalFrames) {
            val s1L = bb1.short.toFloat() / 32768f
            val s1R = bb1.short.toFloat() / 32768f
            val s2L = bb2.short.toFloat() / 32768f
            val s2R = bb2.short.toFloat() / 32768f

            c0[i] = (s1L + s2L).coerceIn(-1.0f, 1.0f)
            c1[i] = (s1R + s2R).coerceIn(-1.0f, 1.0f)
        }
        return Pair(c0, c1)
    }

    suspend fun upgradeProjectToFourStems(
        project: SongProject,
        separator: AudioSeparator,
        onProgress: (String) -> Unit = {}
    ): SongProject = withContext(Dispatchers.IO) {
        val folder = File(projectsDir, project.id)
        val vocFile = project.vocalsFile
        val accFile = project.accompanimentFile

        if (!vocFile.exists() || !accFile.exists()) {
            throw IllegalStateException("Stem files not found for project ${project.title}")
        }

        onProgress("Reconstructing stereo mix...")
        val (c0, c1) = readAndSumWavFiles(vocFile, accFile)

        val result = separator.separatePcm(
            c0 = c0,
            c1 = c1,
            inputSize = project.originalFileSizeBytes,
            isFourStem = true,
            onProgress = onProgress
        )

        val destVocals = File(folder, "vocals.wav")
        val destAccomp = File(folder, "accompaniment.wav")
        val destDrums = File(folder, "drums.wav")
        val destBass = File(folder, "bass.wav")
        val destOther = File(folder, "other.wav")

        result.vocalsFile.copyTo(destVocals, overwrite = true)
        result.accompanimentFile.copyTo(destAccomp, overwrite = true)
        result.drumsFile?.copyTo(destDrums, overwrite = true)
        result.bassFile?.copyTo(destBass, overwrite = true)
        result.otherFile?.copyTo(destOther, overwrite = true)

        val metaFile = File(folder, "meta.json")
        val metaJson = if (metaFile.exists()) JSONObject(metaFile.readText()) else JSONObject()
        metaJson.put("isFourStem", true)
        metaFile.writeText(metaJson.toString())

        getProject(project.id) ?: project.copy(
            vocalsFile = destVocals,
            accompanimentFile = destAccomp,
            drumsFile = destDrums,
            bassFile = destBass,
            otherFile = destOther,
            isFourStem = true
        )
    }

    fun saveProject(
        title: String,
        durationSeconds: Float,
        tempVocals: File,
        tempAccomp: File,
        tempDrums: File? = null,
        tempBass: File? = null,
        tempOther: File? = null,
        category: String = "General",
        artist: String = "",
        album: String = "",
        genre: String = "",
        year: String = "",
        bitrateKbps: Int = 0,
        sampleRate: Int = 44100,
        originalFormat: String = "",
        originalFileSizeBytes: Long = 0L
    ): SongProject {
        val id = UUID.randomUUID().toString()
        val folder = File(projectsDir, id).apply { mkdirs() }

        val destVocals = File(folder, "vocals.wav")
        val destAccomp = File(folder, "accompaniment.wav")
        val destDrums = File(folder, "drums.wav")
        val destBass = File(folder, "bass.wav")
        val destOther = File(folder, "other.wav")
        val destChords = File(folder, "chords.json")

        tempVocals.copyTo(destVocals, overwrite = true)
        tempAccomp.copyTo(destAccomp, overwrite = true)
        tempDrums?.let { if (it.exists()) it.copyTo(destDrums, overwrite = true) }
        tempBass?.let { if (it.exists()) it.copyTo(destBass, overwrite = true) }
        tempOther?.let { if (it.exists()) it.copyTo(destOther, overwrite = true) }

        val isFourStem = destDrums.exists() && destBass.exists() && destOther.exists()

        try {
            val chords = ChordDetector().detectChordsFromWav(destAccomp)
            if (chords.isNotEmpty()) {
                destChords.writeText(ChordDetector.serializeChords(chords))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        val createdAt = System.currentTimeMillis()
        val metaJson = JSONObject().apply {
            put("id", id)
            put("title", title)
            put("durationSeconds", durationSeconds.toDouble())
            put("createdAt", createdAt)
            put("category", category)
            put("isFavorite", false)
            put("artist", artist)
            put("album", album)
            put("genre", genre)
            put("year", year)
            put("bitrateKbps", bitrateKbps)
            put("sampleRate", sampleRate)
            put("originalFormat", originalFormat)
            put("originalFileSizeBytes", originalFileSizeBytes)
            put("isFourStem", isFourStem)
        }

        File(folder, "meta.json").writeText(metaJson.toString())

        return SongProject(
            id = id,
            title = title,
            durationSeconds = durationSeconds,
            vocalsFile = destVocals,
            accompanimentFile = destAccomp,
            drumsFile = if (isFourStem) destDrums else null,
            bassFile = if (isFourStem) destBass else null,
            otherFile = if (isFourStem) destOther else null,
            isFourStem = isFourStem,
            chordsFile = destChords,
            createdAt = createdAt,
            category = category,
            artist = artist,
            album = album,
            genre = genre,
            year = year,
            bitrateKbps = bitrateKbps,
            sampleRate = sampleRate,
            originalFormat = originalFormat,
            originalFileSizeBytes = originalFileSizeBytes
        )
    }

    fun deleteProject(id: String): Boolean {
        // Also remove from all playlists
        removeTrackFromAllPlaylists(id)
        val folder = File(projectsDir, id)
        return if (folder.exists()) {
            folder.deleteRecursively()
        } else false
    }

    fun renameProject(id: String, newTitle: String): Boolean {
        val folder = File(projectsDir, id)
        val metaFile = File(folder, "meta.json")
        if (!metaFile.exists()) return false
        return try {
            val json = JSONObject(metaFile.readText())
            json.put("title", newTitle.trim())
            metaFile.writeText(json.toString())
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun updateProjectCategory(id: String, category: String): Boolean {
        val folder = File(projectsDir, id)
        val metaFile = File(folder, "meta.json")
        if (!metaFile.exists()) return false
        return try {
            val json = JSONObject(metaFile.readText())
            json.put("category", category.trim())
            metaFile.writeText(json.toString())
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun toggleFavorite(id: String): Boolean {
        val folder = File(projectsDir, id)
        val metaFile = File(folder, "meta.json")
        if (!metaFile.exists()) return false
        return try {
            val json = JSONObject(metaFile.readText())
            val current = json.optBoolean("isFavorite", false)
            json.put("isFavorite", !current)
            metaFile.writeText(json.toString())
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    // --- Playlist Management ---

    @Synchronized
    fun getAllPlaylists(): List<Playlist> {
        if (!playlistsFile.exists()) return emptyList()
        return try {
            val arr = JSONArray(playlistsFile.readText())
            val list = mutableListOf<Playlist>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val trackIdsArr = obj.optJSONArray("trackIds") ?: JSONArray()
                val trackIds = mutableListOf<String>()
                for (j in 0 until trackIdsArr.length()) {
                    trackIds.add(trackIdsArr.getString(j))
                }
                list.add(
                    Playlist(
                        id = obj.getString("id"),
                        name = obj.getString("name"),
                        createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
                        trackIds = trackIds
                    )
                )
            }
            list.sortedBy { it.name.lowercase() }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    @Synchronized
    private fun savePlaylists(playlists: List<Playlist>) {
        try {
            val arr = JSONArray()
            for (pl in playlists) {
                val obj = JSONObject().apply {
                    put("id", pl.id)
                    put("name", pl.name)
                    put("createdAt", pl.createdAt)
                    val tArr = JSONArray()
                    pl.trackIds.forEach { tArr.put(it) }
                    put("trackIds", tArr)
                }
                arr.put(obj)
            }
            playlistsFile.writeText(arr.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun createPlaylist(name: String): Playlist {
        val cleanName = name.trim().ifBlank { "Untitled Playlist" }
        val pl = Playlist(
            id = UUID.randomUUID().toString(),
            name = cleanName,
            createdAt = System.currentTimeMillis(),
            trackIds = emptyList()
        )
        val current = getAllPlaylists().toMutableList()
        current.add(pl)
        savePlaylists(current)
        return pl
    }

    fun renamePlaylist(id: String, newName: String): Boolean {
        val current = getAllPlaylists().toMutableList()
        val index = current.indexOfFirst { it.id == id }
        if (index == -1) return false
        current[index] = current[index].copy(name = newName.trim().ifBlank { current[index].name })
        savePlaylists(current)
        return true
    }

    fun deletePlaylist(id: String): Boolean {
        val current = getAllPlaylists().toMutableList()
        val removed = current.removeAll { it.id == id }
        if (removed) {
            savePlaylists(current)
        }
        return removed
    }

    fun addTrackToPlaylist(playlistId: String, trackId: String): Boolean {
        val current = getAllPlaylists().toMutableList()
        val index = current.indexOfFirst { it.id == playlistId }
        if (index == -1) return false
        val pl = current[index]
        if (!pl.trackIds.contains(trackId)) {
            val updatedTracks = pl.trackIds + trackId
            current[index] = pl.copy(trackIds = updatedTracks)
            savePlaylists(current)
            return true
        }
        return false
    }

    fun removeTrackFromPlaylist(playlistId: String, trackId: String): Boolean {
        val current = getAllPlaylists().toMutableList()
        val index = current.indexOfFirst { it.id == playlistId }
        if (index == -1) return false
        val pl = current[index]
        if (pl.trackIds.contains(trackId)) {
            val updatedTracks = pl.trackIds - trackId
            current[index] = pl.copy(trackIds = updatedTracks)
            savePlaylists(current)
            return true
        }
        return false
    }

    private fun removeTrackFromAllPlaylists(trackId: String) {
        val current = getAllPlaylists().toMutableList()
        var modified = false
        for (i in current.indices) {
            val pl = current[i]
            if (pl.trackIds.contains(trackId)) {
                current[i] = pl.copy(trackIds = pl.trackIds - trackId)
                modified = true
            }
        }
        if (modified) {
            savePlaylists(current)
        }
    }
}
