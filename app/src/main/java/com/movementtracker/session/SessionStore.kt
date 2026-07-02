package com.movementtracker.session

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * On-device persistence for sessions. Each session is one JSON file in the
 * app's private storage (`files/sessions/`) — nothing ever leaves the phone.
 * Volumes are tiny (a few KB per session) so plain files beat a database
 * for simplicity and zero dependencies.
 */
class SessionStore(context: Context) {

    private val dir = File(context.filesDir, "sessions").apply { mkdirs() }
    private val videoDir = File(context.filesDir, "videos").apply { mkdirs() }

    fun save(record: SessionRecord) {
        val file = File(dir, "${record.startedAtMillis}-${record.id}.json")
        file.writeText(record.toJson().toString())
    }

    /** All sessions, newest first. Corrupt files are skipped, not fatal. */
    fun listAll(): List<SessionRecord> =
        (dir.listFiles() ?: emptyArray())
            .filter { it.extension == "json" }
            .mapNotNull { file ->
                runCatching { SessionRecord.fromJson(JSONObject(file.readText())) }.getOrNull()
            }
            .sortedByDescending { it.startedAtMillis }

    fun load(id: String): SessionRecord? =
        listAll().firstOrNull { it.id == id }

    fun delete(id: String) {
        (dir.listFiles() ?: emptyArray())
            .filter { it.name.endsWith("-$id.json") }
            .forEach { it.delete() }
        videoFor(id)?.delete()
    }

    // --- Session replay videos ------------------------------------------------
    // The camera records into one temp file while a session runs; when the
    // session is saved the temp file is renamed to `<startedAtMillis>-<id>.mp4`
    // so it pairs with the session's JSON by naming convention.

    fun tempVideoFile(): File = File(videoDir, "recording.tmp.mp4")

    /** Promotes the in-progress recording to the saved session's replay video. */
    fun finalizeVideo(fileName: String) {
        val temp = tempVideoFile()
        if (temp.exists()) temp.renameTo(File(videoDir, fileName))
    }

    fun videoFor(id: String): File? =
        (videoDir.listFiles() ?: emptyArray())
            .firstOrNull { it.name.endsWith("-$id.mp4") }
}
