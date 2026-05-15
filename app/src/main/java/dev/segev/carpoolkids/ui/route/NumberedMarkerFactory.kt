package dev.segev.carpoolkids.ui.route

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import androidx.core.content.ContextCompat
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import dev.segev.carpoolkids.R

/**
 * Generates a [BitmapDescriptor] for a numbered map pin (a filled circle with a centered number).
 *
 * Drawing happens on the main thread but it's a single small bitmap per stop — cheap enough that
 * we don't need a cache for the n ≤ ~6 stops we ever render.
 */
object NumberedMarkerFactory {

    private const val DIAMETER_DP = 36f
    private const val STROKE_DP = 2f
    private const val TEXT_SIZE_DP = 18f

    fun create(context: Context, number: Int): BitmapDescriptor {
        val density = context.resources.displayMetrics.density
        val size = (DIAMETER_DP * density).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ContextCompat.getColor(context, R.color.button_primary_blue)
            style = Paint.Style.FILL
        }
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = STROKE_DP * density
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            textSize = TEXT_SIZE_DP * density
            isFakeBoldText = true
        }

        val cx = size / 2f
        val cy = size / 2f
        val radius = (size - strokePaint.strokeWidth) / 2f
        canvas.drawCircle(cx, cy, radius, fillPaint)
        canvas.drawCircle(cx, cy, radius, strokePaint)

        val label = number.toString()
        val textBounds = Rect()
        textPaint.getTextBounds(label, 0, label.length, textBounds)
        val baseline = cy - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(label, cx, baseline, textPaint)

        return BitmapDescriptorFactory.fromBitmap(bitmap)
    }
}
