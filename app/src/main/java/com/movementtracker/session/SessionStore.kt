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
    }
}
