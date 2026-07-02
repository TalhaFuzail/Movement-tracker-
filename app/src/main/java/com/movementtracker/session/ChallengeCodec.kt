package com.movementtracker.session

import org.json.JSONObject
import java.util.Base64

/** One person's drill scorecard, as carried inside a challenge code. */
data class ChallengeResult(
    val attempts: Int,
    val targetKmh: Double,
    val hitCount: Int,
    val bestKmh: Double,
    val averageKmh: Double,
)

/**
 * Turns a finished drill into a short text code (and back) so a scorecard
 * can be sent over any messaging app — no server, no account. The receiver
 * pastes the code into the drill dialog and plays the same drill against
 * the sender's numbers.
 *
 * Format: "MTC1." + base64url(JSON). The prefix makes the code findable
 * inside a longer pasted message and versions the payload.
 */
object ChallengeCodec {

    private const val PREFIX = "MTC1."

    fun encode(result: ChallengeResult): String {
        val json = JSONObject()
            .put("n", result.attempts)
            .put("t", result.targetKmh)
            .put("h", result.hitCount)
            .put("b", result.bestKmh)
            .put("a", result.averageKmh)
        // java.util.Base64 (fine from minSdk 26) keeps this class JVM-testable.
        return PREFIX + Base64.getUrlEncoder().withoutPadding()
            .encodeToString(json.toString().toByteArray(Charsets.UTF_8))
    }

    /** Accepts the bare code or a whole pasted message containing one. */
    fun decode(text: String): ChallengeResult? {
        val start = text.indexOf(PREFIX)
        if (start < 0) return null
        val body = text.substring(start + PREFIX.length)
            .takeWhile { it.isLetterOrDigit() || it == '-' || it == '_' }
        if (body.isEmpty()) return null
        return runCatching {
            val json = JSONObject(
                String(Base64.getUrlDecoder().decode(body), Charsets.UTF_8)
            )
            ChallengeResult(
                attempts = json.getInt("n").coerceIn(1, 100),
                targetKmh = json.getDouble("t").coerceAtLeast(1.0),
                hitCount = json.getInt("h").coerceAtLeast(0),
                bestKmh = json.getDouble("b").coerceAtLeast(0.0),
                averageKmh = json.getDouble("a").coerceAtLeast(0.0),
            )
        }.getOrNull()
    }
}
