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
    // Each recording gets its own temp file (finalization is asynchronous, so
    // a back-to-back session must never reuse the previous session's path);
    // when the session is saved its temp file is renamed to
    // `<startedAtMillis>-<id>.mp4`, pairing with the JSON by naming convention.

    fun newTempVideoFile(): File =
        File(videoDir, "rec-${System.nanoTime()}.tmp.mp4")

    /** Promotes a finished recording to the saved session's replay video. */
    fun finalizeVideo(temp: File, fileName: String) {
        if (temp.exists()) temp.renameTo(File(videoDir, fileName))
    }

    /** Removes orphaned temp recordings. Call only when no recording is active. */
    fun cleanupTempVideos() {
        (videoDir.listFiles() ?: emptyArray())
            .filter { it.name.endsWith(".tmp.mp4") }
            .forEach { it.delete() }
    }

    fun videoFor(id: String): File? =
        (videoDir.listFiles() ?: emptyArray())
            .firstOrNull { it.name.endsWith("-$id.mp4") }
}
