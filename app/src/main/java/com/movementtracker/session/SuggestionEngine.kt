package com.movementtracker.session

import java.util.Locale
import kotlin.math.sqrt

/**
 * Turns a recorded session into concrete coaching suggestions. Every
 * suggestion is computed from the session's own measurements — thresholds
 * and the numbers quoted back to the athlete come from their data, never
 * generic filler.
 */
object SuggestionEngine {

    fun suggestionsFor(session: SessionRecord): List<String> {
        val out = mutableListOf<String>()
        out += soccerSuggestions(session.events.filter { it.type == ActivityType.SOCCER_SHOT })
        out += bowlingSuggestions(session.events.filter { it.type == ActivityType.CRICKET_BOWL })
        out += placementSuggestions(session)
        out += sprintSuggestions(session)
        return out
    }

    // --- Soccer -----------------------------------------------------------

    private fun soccerSuggestions(shots: List<RecordedEvent>): List<String> {
        if (shots.isEmpty()) return emptyList()
        val out = mutableListOf<String>()

        val speeds = shots.map { it.peakBallKmh }
        val best = speeds.max()
        val avg = speeds.average()
        out += fmt(
            "Soccer — %d shot(s): best %.1f km/h, average %.1f km/h.",
            shots.size, best, avg,
        )

        // Ball speed relative to approach: a slow ball off a fast approach
        // usually means poor contact or no follow-through.
        val withApproach = shots.filter { (it.extras["approachKmh"] ?: 0.0) > 3.0 }
        if (withApproach.isNotEmpty()) {
            val ratios = withApproach.map { it.peakBallKmh / it.extras.getValue("approachKmh") }
            val avgRatio = ratios.average()
            if (avgRatio < 4.0) {
                out += fmt(
                    "Your ball speed is only %.1f× your approach speed — strong strikers reach 5–6×. Plant your standing foot beside the ball and follow through with your hips, not just the leg.",
                    avgRatio,
                )
            }
        }

        // Foot swing vs ball speed: energy transfer quality.
        val withFoot = shots.filter { (it.extras["footKmh"] ?: 0.0) > 5.0 }
        if (withFoot.isNotEmpty()) {
            val transfer = withFoot.map { it.peakBallKmh / it.extras.getValue("footKmh") }.average()
            if (transfer < 1.1) {
                out += fmt(
                    "The ball leaves at %.1f× your foot speed — clean instep contact typically gives 1.2–1.5×. Strike through the ball's centre with a locked ankle.",
                    transfer,
                )
            }
        }

        // Technique at the strike moment, measured from the pose skeleton.
        val kneeAngles = shots.mapNotNull { it.extras["kneeAngleDeg"] }
        if (kneeAngles.isNotEmpty()) {
            val avgKnee = kneeAngles.average()
            if (avgKnee < 140) {
                out += fmt(
                    "Your kicking knee is bent to about %.0f° at contact — power comes from striking as the knee snaps toward full extension (150–170°). Plant slightly further from the ball and let the leg whip through.",
                    avgKnee,
                )
            }
        }
        val followThroughs = shots.mapNotNull { it.extras["followThroughPct"] }
        if (followThroughs.isNotEmpty()) {
            val avgFollow = followThroughs.average()
            if (avgFollow < 25) {
                out += fmt(
                    "Your follow-through stops low — the kicking foot rises to only %.0f%% of body height after contact. Swing through the ball toward the target, not just to it.",
                    avgFollow,
                )
            }
        }

        if (speeds.size >= 3) {
            val consistency = stdDev(speeds) / avg
            if (consistency > 0.20) {
                out += fmt(
                    "Shot speeds vary a lot (±%.0f%% around your average). Repeat the same run-up length and contact point to tighten your technique before chasing power.",
                    consistency * 100,
                )
            } else {
                out += fmt(
                    "Very consistent striking (±%.0f%%) — you can safely work on adding power toward your %.1f km/h best.",
                    consistency * 100, best,
                )
            }
        }
        return out
    }

    // --- Cricket ------------------------------------------------------------

    private fun bowlingSuggestions(bowls: List<RecordedEvent>): List<String> {
        if (bowls.isEmpty()) return emptyList()
        val out = mutableListOf<String>()

        val speeds = bowls.map { it.peakBallKmh }
        val best = speeds.max()
        val avg = speeds.average()
        out += fmt(
            "Cricket — %d delivery(ies): best %.1f km/h, average %.1f km/h.",
            bowls.size, best, avg,
        )

        if (speeds.size >= 3) {
            val gapPct = (best - avg) / best * 100
            if (gapPct > 12) {
                out += fmt(
                    "Your average is %.0f%% below your best delivery. That gap is usually release timing — focus on repeating the same release point every ball rather than bowling harder.",
                    gapPct,
                )
            } else {
                out += fmt(
                    "Deliveries are tightly grouped (within %.0f%% of your best) — a repeatable action. Gradually lengthen your run-up to add pace.",
                    gapPct,
                )
            }
        }

        val withArm = bowls.filter { (it.extras["armSwingKmh"] ?: 0.0) > 5.0 }
        if (withArm.isNotEmpty()) {
            val armAvg = withArm.map { it.extras.getValue("armSwingKmh") }.average()
            val ballAvg = withArm.map { it.peakBallKmh }.average()
            if (ballAvg < armAvg * 1.2) {
                out += fmt(
                    "Arm speed averages %.1f km/h but the ball only %.1f km/h — you're losing energy at release. Snap the wrist and release slightly later at the top of the arc.",
                    armAvg, ballAvg,
                )
            }
        }

        val withApproach = bowls.filter { (it.extras["approachKmh"] ?: 0.0) > 1.0 }
        if (withApproach.isNotEmpty()) {
            val approachAvg = withApproach.map { it.extras.getValue("approachKmh") }.average()
            if (approachAvg < 10.0) {
                out += fmt(
                    "Your run-up averages %.1f km/h — fast bowlers approach at 18–25 km/h. A quicker, rhythmical run-up is the easiest pace you'll ever add.",
                    approachAvg,
                )
            }
        }

        // Technique near release, measured from the pose skeleton.
        val elbowAngles = bowls.mapNotNull { it.extras["elbowAngleDeg"] }
        if (elbowAngles.isNotEmpty()) {
            val avgElbow = elbowAngles.average()
            if (avgElbow < 150) {
                out += fmt(
                    "Your bowling arm is bent to about %.0f° near release — a straighter arm arc converts more shoulder speed into ball speed (and keeps the action legal).",
                    avgElbow,
                )
            }
        }
        val releaseHeights = bowls.mapNotNull { it.extras["releaseHeightPct"] }
        if (releaseHeights.isNotEmpty()) {
            val avgRelease = releaseHeights.average()
            if (avgRelease < 15) {
                out += fmt(
                    "Your wrist only gets %.0f%% of your height above your head at the top of the arc — reach up through the delivery for a taller release; it adds both pace and bounce.",
                    avgRelease,
                )
            }
        }
        return out
    }

    // --- Shot placement -------------------------------------------------------

    /** Zone names match the 3×3 target grid, row-major from top-left. */
    private val zoneNames = listOf(
        "top left", "top centre", "top right",
        "middle left", "centre", "middle right",
        "bottom left", "bottom centre", "bottom right",
    )

    private fun placementSuggestions(session: SessionRecord): List<String> {
        val placed = session.events.mapNotNull { it.extras["placementZone"]?.toInt() }
        if (placed.size < 3) return emptyList()
        val out = mutableListOf<String>()

        val onTarget = placed.filter { it in 0..8 }
        out += fmt(
            "Target practice — %d of %d attempts on target (%.0f%%).",
            onTarget.size, placed.size, onTarget.size * 100.0 / placed.size,
        )

        val favourite = onTarget.groupingBy { it }.eachCount().maxByOrNull { it.value }
        if (favourite != null && onTarget.size >= 3 && favourite.value * 2 >= onTarget.size) {
            out += fmt(
                "Most of your on-target attempts land %s — practise picking other zones so keepers can't read you.",
                zoneNames[favourite.key],
            )
        }
        return out
    }

    // --- Sprinting ----------------------------------------------------------

    private fun sprintSuggestions(session: SessionRecord): List<String> {
        val sprints = session.events.filter { it.type == ActivityType.SPRINT }
        if (sprints.isEmpty()) return emptyList()
        val out = mutableListOf<String>()

        val bestPeak = sprints.maxOf { it.playerKmh }
        val avgDuration = sprints.map { it.durationSec }.average()
        out += fmt(
            "Sprinting — %d sprint(s): top %.1f km/h, average burst %.1f s, %.0f m covered this session.",
            sprints.size, bestPeak, avgDuration, session.distanceMeters,
        )

        // Acceleration: steepest sustained speed gain in the timeline.
        val accel = peakAcceleration(session.samples)
        if (accel != null) {
            if (accel < 6.0) {
                out += fmt(
                    "You gain about %.1f km/h per second when accelerating — explosive starts (short hill sprints, 10 m accelerations) will lift this fastest.",
                    accel,
                )
            } else {
                out += fmt(
                    "Strong acceleration (%.1f km/h per second). Work on holding top speed longer — your bursts average only %.1f s.",
                    accel, avgDuration,
                )
            }
        }

        if (avgDuration < 2.5 && sprints.size >= 2) {
            out += fmt(
                "Your sprints are short (%.1f s average). Add 40–60 m efforts to train maintaining %.0f+ km/h instead of only reaching it.",
                avgDuration, bestPeak * 0.9,
            )
        }
        return out
    }

    /** Largest speed gain over any ~1 s span of the timeline, in km/h per second. */
    private fun peakAcceleration(samples: List<SpeedSample>): Double? {
        if (samples.size < 4) return null
        var best = 0.0
        var i = 0
        for (j in samples.indices) {
            while (samples[j].tOffsetSec - samples[i].tOffsetSec > 1.2) i++
            val dt = samples[j].tOffsetSec - samples[i].tOffsetSec
            if (dt in 0.5..1.2) {
                val gain = (samples[j].playerKmh - samples[i].playerKmh) / dt
                if (gain > best) best = gain
            }
        }
        return if (best > 0.5) best else null
    }

    private fun stdDev(values: List<Double>): Double {
        val mean = values.average()
        return sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size)
    }

    private fun fmt(template: String, vararg args: Any) =
        String.format(Locale.US, template, *args)
}
