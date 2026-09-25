package be.lghs.lab.ui

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import be.lghs.lab.R
import kotlin.math.roundToInt

/**
 * Mise en page proportionnelle (TV 16:9 comme tablette) :
 *
 *  ┌──────────────────────┬─────────────┐
 *  │                      │  météo      │
 *  │  Porte (16:9)        │  musique    │
 *  │                  ┌───┴─────────────┤
 *  ├──────────────────┤  SAS (16:9)     │
 *  │  bus │ trains    │  chevauche      │
 *  └──────────────────┴─────────────────┘
 *
 * Même marge [gap] sur les quatre bords de l'écran.
 */
class DashboardLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ViewGroup(context, attrs) {

    private val gap = (16 * resources.displayMetrics.density).roundToInt()
    private val porte = Rect()
    private val sas = Rect()
    private val info = Rect()
    private val transport = Rect()
    private val full = Rect()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        computeRects(w, h)
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val r = rectFor(child) ?: continue
            child.measure(
                MeasureSpec.makeMeasureSpec(r.width(), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(r.height(), MeasureSpec.EXACTLY),
            )
        }
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val rect = rectFor(child) ?: continue
            child.layout(rect.left, rect.top, rect.right, rect.bottom)
        }
    }

    private fun rectFor(child: View): Rect? = when (child.id) {
        R.id.tile_porte -> porte
        R.id.tile_sas -> sas
        R.id.panel_info -> info
        R.id.panel_transport -> transport
        R.id.wallpaper -> full
        else -> null
    }

    private fun computeRects(w: Int, h: Int) {
        full.set(0, 0, w, h)

        // Porte : 64 % de la largeur en 16:9, bornée à 70 % de la hauteur, collée en haut à gauche
        var pw = w * 0.64f
        var ph = pw * 9 / 16
        if (ph > h * 0.70f) { ph = h * 0.70f; pw = ph * 16 / 9 }
        porte.set(gap, gap, gap + pw.roundToInt(), gap + ph.roundToInt())

        // SAS : 40 % en 16:9, collée en bas à droite avec la même marge.
        // Sur un écran 16:9 elle mord sur le coin de la Porte ; sur un écran plus haut (4:3), non.
        var sw = w * 0.40f
        var sh = sw * 9 / 16
        if (sh > h * 0.42f) { sh = h * 0.42f; sw = sh * 16 / 9 }
        sas.set(w - gap - sw.roundToInt(), h - gap - sh.roundToInt(), w - gap, h - gap)

        info.set(porte.right + gap, gap, w - gap, sas.top - gap)
        transport.set(gap, porte.bottom + gap, sas.left - gap, h - gap)
    }
}
