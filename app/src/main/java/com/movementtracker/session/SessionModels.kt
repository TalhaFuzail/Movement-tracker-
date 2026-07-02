package com.movementtracker.session

import org.json.JSONArray
import org.json.JSONObject

/** What kind of movement an event represents. */
enum class ActivityType {
    SPRINT,
    SOCCER_SHOT,
    CRICKET_BOWL,
    /** A fast ball was tracked but the action couldn't be classified. */
    BALL_EVENT;

    companion object {
        fun fromName(name: String): ActivityType =
            entries.firstOrNull { it.name == name } ?: BALL_EVENT
    }
}

/** One point of the player-speed timeline, relative to session start. */
data class SpeedSample(val tOffsetSec: Double, val playerKmh: Double)

/**
 * A notable moment inside a session: a sprint, a shot, a bowl.
 * [extras] carries per-type measurements used by the suggestion engine
 * (e.g. approach speed for a shot, arm swing speed for a bowl).
 */
data class RecordedEvent(
    val tOffsetSec: Double,
    val type: ActivityType,
    val peakBallKmh: Double,
    val playerKmh: Double,
    val durationSec: Double,
    val extras: Map<String, Double> = emptyMap(),
)

/** A completed, immutable session as persisted on device. */
data class SessionRecord(
    val id: String,
    val startedAtMillis: Long,
    val durationSec: Double,
    /** "live" for camera sessions, "slowmo" for analysed clips. */
    val source: String,
    val topSpeedKmh: Double,
    val avgMovingKmh: Double,
    val distanceMeters: Double,
    val sprintCount: Int,
    val samples: List<SpeedSample>,
    val events: List<RecordedEvent>,
) {

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("startedAtMillis", startedAtMillis)
        put("durationSec", durationSec)
        put("source", source)
        put("topSpeedKmh", topSpeedKmh)
        put("avgMovingKmh", avgMovingKmh)
        put("distanceMeters", distanceMeters)
        put("sprintCount", sprintCount)
        put("samples", JSONArray().also { arr ->
            samples.forEach { s ->
                arr.put(JSONObject().put("t", s.tOffsetSec).put("kmh", s.playerKmh))
            }
        })
        put("events", JSONArray().also { arr ->
            events.forEach { e ->
                val extrasJson = JSONObject()
                e.extras.forEach { (k, v) -> extrasJson.put(k, v) }
                arr.put(
                    JSONObject()
                        .put("t", e.tOffsetSec)
                        .put("type", e.type.name)
                        .put("peakBallKmh", e.peakBallKmh)
                        .put("playerKmh", e.playerKmh)
                        .put("durationSec", e.durationSec)
                        .put("extras", extrasJson)
                )
            }
        })
    }

    companion object {
        fun fromJson(json: JSONObject): SessionRecord {
            val samples = buildList {
                val arr = json.optJSONArray("samples") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(SpeedSample(o.getDouble("t"), o.getDouble("kmh")))
                }
            }
            val events = buildList {
                val arr = json.optJSONArray("events") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val extras = buildMap {
                        val ex = o.optJSONObject("extras") ?: JSONObject()
                        ex.keys().forEach { key -> put(key, ex.getDouble(key)) }
                    }
                    add(
                        RecordedEvent(
                            tOffsetSec = o.getDouble("t"),
                            type = ActivityType.fromName(o.getString("type")),
                            peakBallKmh = o.getDouble("peakBallKmh"),
                            playerKmh = o.getDouble("playerKmh"),
                            durationSec = o.getDouble("durationSec"),
                            extras = extras,
                        )
                    )
                }
            }
            return SessionRecord(
                id = json.getString("id"),
                startedAtMillis = json.getLong("startedAtMillis"),
                durationSec = json.getDouble("durationSec"),
                source = json.optString("source", "live"),
                topSpeedKmh = json.getDouble("topSpeedKmh"),
                avgMovingKmh = json.getDouble("avgMovingKmh"),
                distanceMeters = json.getDouble("distanceMeters"),
                sprintCount = json.getInt("sprintCount"),
                samples = samples,
                events = events,
            )
        }
    }
}
