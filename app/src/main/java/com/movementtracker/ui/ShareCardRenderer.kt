package com.movementtracker.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.graphics.Typeface
import com.movementtracker.R
import com.movementtracker.session.ActivityType
import com.movementtracker.session.SessionRecord
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

/**
 * Draws a session's headline numbers onto a portrait card image ready for
 * sharing. When the session has a replay video, a frame from the best moment
 * becomes the (darkened) background; otherwise a flat gradient in the app's
 * accent green. Pure Canvas — nothing leaves the device until the user picks
 * a share target.
 */
object ShareCardRenderer {

    // 4:5 portrait — the largest format the big photo-sharing apps show uncropped.
    private const val WIDTH = 1080
    private const val HEIGHT = 1350

    fun render(context: Context, session: SessionRecord, background: Bitmap?): Bitmap {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawBackground(canvas, background)

        val centerX = WIDTH / 2f
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            color = 0xFFFFFFFF.toInt()
        }

        // Header: app name + session date.
        text.textSize = 42f
        text.letterSpacing = 0.25f
        text.alpha = 210
        canvas.drawText(
            context.getString(R.string.app_name).uppercase(Locale.getDefault()),
            centerX, 130f, text,
        )
        text.textSize = 36f
        text.letterSpacing = 0.02f
        text.alpha = 180
        canvas.drawText(
            SimpleDateFormat("EEEE d MMMM yyyy", Locale.getDefault())
                .format(Date(session.startedAtMillis)),
            centerX, 195f, text,
        )

        // The headline number: the session's single most impressive measurement.
        val headline = headlineFor(context, session)
        text.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        text.textSize = 250f
        text.alpha = 255
        canvas.drawText(headline.value, centerX, 640f, text)
        text.typeface = Typeface.DEFAULT
        text.textSize = 68f
        text.alpha = 220
        canvas.drawText(headline.unit, centerX, 735f, text)
        text.textSize = 46f
        text.letterSpacing = 0.2f
        text.color = 0xFF8FE3BC.toInt()
        canvas.drawText(headline.label, centerX, 830f, text)

        // Supporting stats.
        text.color = 0xFFFFFFFF.toInt()
        text.letterSpacing = 0.02f
        text.textSize = 40f
        text.alpha = 230
        var y = 940f
        for (line in statLines(context, session)) {
            canvas.drawText(line, centerX, y, text)
            y += 62f
        }

        drawSparkline(canvas, session)

        text.textSize = 30f
        text.alpha = 150
        canvas.drawText(context.getString(R.string.share_card_footer), centerX, 1295f, text)
        return bitmap
    }

    private fun drawBackground(canvas: Canvas, background: Bitmap?) {
        if (background != null && background.width > 0 && background.height > 0) {
            val scale = max(
                WIDTH / background.width.toFloat(),
                HEIGHT / background.height.toFloat(),
            )
            val matrix = Matrix().apply {
                setScale(scale, scale)
                postTranslate(
                    (WIDTH - background.width * scale) / 2f,
                    (HEIGHT - background.height * scale) / 2f,
                )
            }
            canvas.drawBitmap(background, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
            canvas.drawColor(0xA6000000.toInt())
        } else {
            val gradient = Paint().apply {
                shader = LinearGradient(
                    0f, 0f, 0f, HEIGHT.toFloat(),
                    0xFF0B231A.toInt(), 0xFF0E6B4A.toInt(), Shader.TileMode.CLAMP,
                )
            }
            canvas.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), gradient)
        }
    }

    private data class Headline(val value: String, val unit: String, val label: String)

    private fun headlineFor(context: Context, session: SessionRecord): Headline {
        val bestBall = session.events
            .filter { it.type != ActivityType.SPRINT && it.type != ActivityType.JUMP }
            .maxByOrNull { it.peakBallKmh }
        if (bestBall != null && bestBall.peakBallKmh > 0) {
            val label = when (bestBall.type) {
                ActivityType.SOCCER_SHOT -> R.string.share_headline_shot
                ActivityType.CRICKET_BOWL -> R.string.share_headline_bowl
                else -> R.string.share_headline_ball
            }
            return Headline(
                String.format(Locale.US, "%.1f", bestBall.peakBallKmh),
                context.getString(R.string.share_unit_kmh),
                context.getString(label),
            )
        }
        val bestJumpCm = session.events
            .filter { it.type == ActivityType.JUMP }
            .maxOfOrNull { (it.extras["heightM"] ?: 0.0) * 100 } ?: 0.0
        if (session.topSpeedKmh <= 0 && bestJumpCm > 0) {
            return Headline(
                String.format(Locale.US, "%.0f", bestJumpCm),
                context.getString(R.string.share_unit_cm),
                context.getString(R.string.share_headline_jump),
            )
        }
        return Headline(
            String.format(Locale.US, "%.1f", session.topSpeedKmh),
            context.getString(R.string.share_unit_kmh),
            context.getString(R.string.share_headline_top_speed),
        )
    }

    private fun statLines(context: Context, session: SessionRecord): List<String> {
        val minutes = (session.durationSec / 60).toInt()
        val seconds = (session.durationSec % 60).toInt()
        val ballEvents = session.events.count {
            it.type != ActivityType.SPRINT && it.type != ActivityType.JUMP
        }
        return buildList {
            add(context.getString(R.string.stat_duration, minutes, seconds))
            if (session.distanceMeters >= 1) {
                add(context.getString(R.string.stat_distance, session.distanceMeters))
            }
            if (session.topSpeedKmh > 0) {
                add(context.getString(R.string.stat_top_speed, session.topSpeedKmh))
            }
            if (ballEvents > 0) add(context.getString(R.string.stat_ball_events, ballEvents))
        }
    }

    private fun drawSparkline(canvas: Canvas, session: SessionRecord) {
        val samples = session.samples
        if (samples.size < 2) return
        val duration = max(session.durationSec, samples.last().tOffsetSec)
        val maxKmh = samples.maxOf { it.playerKmh }
        if (duration <= 0 || maxKmh <= 0) return

        val left = 140f
        val right = WIDTH - 140f
        val top = 1140f
        val bottom = 1230f
        val path = Path()
        samples.forEachIndexed { i, s ->
            val x = left + ((s.tOffsetSec / duration) * (right - left)).toFloat()
            val y = bottom - ((s.playerKmh / maxKmh) * (bottom - top)).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 4f
            strokeJoin = Paint.Join.ROUND
            color = 0x96FFFFFF.toInt()
        })
    }
}
