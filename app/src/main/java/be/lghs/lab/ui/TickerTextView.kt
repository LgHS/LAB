package be.lghs.lab.ui

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView

/**
 * Texte sur une ligne qui défile comme un bandeau s'il ne tient pas : pause au début, sortie
 * complète par la gauche, puis retour par la droite. Contrairement au marquee Android, pas de
 * retour brusque au début, même quand le texte dépasse de peu.
 */
class TickerTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : AppCompatTextView(context, attrs) {

    private val speedPxPerSec = SPEED_DP_PER_SEC * resources.displayMetrics.density
    private var startNs = 0L

    init {
        setSingleLine(true)
        ellipsize = null
        setHorizontallyScrolling(true)
    }

    override fun onTextChanged(text: CharSequence?, start: Int, lengthBefore: Int, lengthAfter: Int) {
        super.onTextChanged(text, start, lengthBefore, lengthAfter)
        startNs = 0L // nouveau texte : on repart de la pause au début
    }

    override fun onDraw(canvas: Canvas) {
        val layout = layout
        val available = width - compoundPaddingLeft - compoundPaddingRight
        val textWidth = layout?.getLineWidth(0) ?: 0f
        if (layout == null || textWidth <= available) {
            super.onDraw(canvas)
            return
        }
        val now = System.nanoTime()
        if (startNs == 0L) startNs = now
        val travel = (textWidth + available) / speedPxPerSec
        val t = ((now - startNs) / 1e9f) % (PAUSE_SEC + travel)
        // Décalage du texte vers la gauche : 0 → sortie complète, puis entrée depuis le bord droit
        val offset = if (t < PAUSE_SEC) 0f else {
            val d = (t - PAUSE_SEC) * speedPxPerSec
            if (d < textWidth) d else d - textWidth - available
        }

        canvas.save()
        canvas.clipRect(compoundPaddingLeft, 0, width - compoundPaddingRight, height)
        canvas.translate(compoundPaddingLeft - offset, extendedPaddingTop.toFloat())
        paint.color = currentTextColor
        paint.drawableState = drawableState
        layout.draw(canvas)
        canvas.restore()
        postInvalidateOnAnimation()
    }

    private companion object {
        const val SPEED_DP_PER_SEC = 40f
        const val PAUSE_SEC = 2.5f
    }
}
