package be.lghs.lab.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import be.lghs.lab.R
import be.lghs.lab.data.RainSlot
import be.lghs.lab.data.hhmm
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Pluie des 2 prochaines heures : une barre par quart d'heure (hauteur selon les mm prévus),
 * heures sous la barre toutes les 30 min. Redessinée seulement quand la prévision change.
 */
class RainChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.rain) }
    private val dryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = DRY }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.text_dim)
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 13f, resources.displayMetrics)
        textAlign = Paint.Align.CENTER
    }
    private val rect = RectF()
    private var slots: List<RainSlot> = emptyList()

    fun show(rain: List<RainSlot>) {
        if (rain == slots) return
        slots = rain
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (slots.isEmpty()) return
        val labelHeight = labelPaint.textSize + 4 * density
        val chartBottom = height - labelHeight
        val gap = 4 * density
        val barWidth = (width - gap * (slots.size - 1)) / slots.size
        val radius = 3 * density
        slots.forEachIndexed { i, slot ->
            val left = i * (barWidth + gap)
            // Racine : une petite pluie reste visible, une forte ne dépasse pas la carte
            val level = sqrt(min(slot.mm / HEAVY_MM, 1.0)).toFloat()
            val barHeight = if (slot.raining) maxOf(level * chartBottom, 6 * density) else 3 * density
            rect.set(left, chartBottom - barHeight, left + barWidth, chartBottom)
            canvas.drawRoundRect(rect, radius, radius, if (slot.raining) barPaint else dryPaint)
            // Heure au début de chaque demi-heure
            if (i % 2 == 0) canvas.drawText(hhmm(slot.startSec), left + barWidth / 2, height - 2 * density, labelPaint)
        }
    }

    private companion object {
        /** mm par quart d'heure pour une barre pleine (4 mm/h : forte pluie). */
        const val HEAVY_MM = 1.0
        const val DRY = 0x33FFFFFF
    }
}
