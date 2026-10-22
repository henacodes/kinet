package com.kirakos.kinet

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class SongProject(
    val id: String,
    val title: String,
    val durationSeconds: Float,
    val vocalsFile: File,
    val accompanimentFile: File,
    val createdAt: Long
)

class ProjectRepository(private val context: Context) {

    private val projectsDir = File(context.filesDir, "projects").apply {
        if (!exists()) mkdirs()
    }

    fun getAllProjects(): List<SongProject> {
        val list = mutableListOf<SongProject>()
        val projectFolders = projectsDir.listFiles { file -> file.isDirectory } ?: return emptyList()

        for (folder in projectFolders) {
            val metaFile = File(folder, "meta.json")
            val vocals = File(folder, "vocals.wav")
            val accomp = File(folder, "accompaniment.wav")

            if (metaFile.exists() && vocals.exists() && accomp.exists()) {
                try {
                    val json = JSONObject(metaFile.readText())
                    list.add(
                        SongProject(
                            id = json.getString("id"),
                            title = json.optString("title", folder.name),
                            durationSeconds = json.optDouble("durationSeconds", 0.0).toFloat(),
                            vocalsFile = vocals,
                            accompanimentFile = accomp,
                            createdAt = json.optLong("createdAt", folder.lastModified())
                        )
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        return list.sortedByDescending { it.createdAt }
    }

    fun saveProject(
        title: String,
        durationSeconds: Float,
        tempVocals: File,
        tempAccomp: File
    ): SongProject {
        val id = UUID.randomUUID().toString()
        val folder = File(projectsDir, id).apply { mkdirs() }

        val destVocals = File(folder, "vocals.wav")
        val destAccomp = File(folder, "accompaniment.wav")

        tempVocals.copyTo(destVocals, overwrite = true)
        tempAccomp.copyTo(destAccomp, overwrite = true)

        val createdAt = System.currentTimeMillis()
        val metaJson = JSONObject().apply {
            put("id", id)
            put("title", title)
            put("durationSeconds", durationSeconds.toDouble())
            put("createdAt", createdAt)
        }

        File(folder, "meta.json").writeText(metaJson.toString())

        return SongProject(
            id = id,
            title = title,
            durationSeconds = durationSeconds,
            vocalsFile = destVocals,
            accompanimentFile = destAccomp,
            createdAt = createdAt
        )
    }

    fun deleteProject(id: String): Boolean {
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
}
