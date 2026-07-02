package com.movementtracker.session

/**
 * All-time personal bests and a simple progress trend, aggregated across
 * every stored session. Everything is computed on demand from the JSON
 * files — no extra state to keep in sync.
 */
object ProgressStats {

    data class Bests(
        val topSpeedKmh: Double,
        val bestShotKmh: Double,
        val bestBowlKmh: Double,
        val totalDistanceMeters: Double,
        val sessionCount: Int,
        /**
         * Change in average session top speed, last 30 days vs the 30 days
         * before that; null when there isn't data on both sides to compare.
         */
        val trendPercent: Double?,
    )

    fun compute(sessions: List<SessionRecord>, nowMillis: Long): Bests? {
        if (sessions.isEmpty()) return null

        val bestShot = sessions.flatMap { it.events }
            .filter { it.type == ActivityType.SOCCER_SHOT }
            .maxOfOrNull { it.peakBallKmh } ?: 0.0
        val bestBowl = sessions.flatMap { it.events }
            .filter { it.type == ActivityType.CRICKET_BOWL }
            .maxOfOrNull { it.peakBallKmh } ?: 0.0

        val monthMillis = 30L * 24 * 60 * 60 * 1000
        val recent = sessions.filter { it.startedAtMillis > nowMillis - monthMillis }
        val previous = sessions.filter {
            it.startedAtMillis in (nowMillis - 2 * monthMillis)..(nowMillis - monthMillis)
        }
        val trend = if (recent.isNotEmpty() && previous.isNotEmpty()) {
            val recentAvg = recent.map { it.topSpeedKmh }.average()
            val previousAvg = previous.map { it.topSpeedKmh }.average()
            if (previousAvg > 0.1) (recentAvg - previousAvg) / previousAvg * 100.0 else null
        } else null

        return Bests(
            topSpeedKmh = sessions.maxOf { it.topSpeedKmh },
            bestShotKmh = bestShot,
            bestBowlKmh = bestBowl,
            totalDistanceMeters = sessions.sumOf { it.distanceMeters },
            sessionCount = sessions.size,
            trendPercent = trend,
        )
    }
}
