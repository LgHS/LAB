package be.lghs.lab.ui

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.view.View
import be.lghs.lab.R
import be.lghs.lab.data.Delivery
import be.lghs.lab.data.Weather
import be.lghs.lab.databinding.PanelInfoBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Haut-droite : météo + heure, et code Deliveroo au-dessus de la musique s'il y a une commande. */
class InfoPanel(private val b: PanelInfoBinding) {
    private val hhmm = SimpleDateFormat("HH:mm", Locale.FRANCE)

    fun showClock(nowMs: Long) {
        b.clock.text = hhmm.format(Date(nowMs))
    }

    fun showWeather(w: Weather) {
        b.weatherIcon.text = w.icon
        b.weatherTemp.text = "${w.temperature.roundToInt()}°"
        b.weatherLabel.text = w.label
        b.weatherDetails.text = "↓${w.min.roundToInt()}° ↑${w.max.roundToInt()}° · 💧${w.rainChance}%"
    }

    private var delivery: Delivery? = null
    private var deliveryPulse: ObjectAnimator? = null
    private val context = b.root.context

    /** Moment où l'app a vu la commande passer à « livrée » (`maj` = heure de lecture, inutilisable). */
    private var deliveredSeenAt: Long? = null

    fun showDelivery(delivery: Delivery?, nowSec: Long) {
        val wasDelivered = this.delivery?.let { it.delivered && it.code == delivery?.code } == true
        if (delivery?.delivered == true && !wasDelivered) deliveredSeenAt = nowSec
        if (delivery?.delivered != true) deliveredSeenAt = null
        this.delivery = delivery
        renderDelivery(nowSec)
    }

    /** Appelé chaque seconde : compte à rebours et masquage d'une commande périmée. */
    fun renderDelivery(nowSec: Long) {
        val d = delivery
        val age = d?.updatedEpochSec?.let { nowSec - it }
        val visible = d != null && when {
            // L'endpoint renvoie la commande livrée jusqu'à la suivante : 5 min puis on masque
            d.delivered -> nowSec - (deliveredSeenAt ?: nowSec) < DELIVERED_SHOWN_SEC
            else -> age == null || age < STALE_SEC // au cas où `maj` cesserait d'avancer
        }
        b.deliveryCard.visibility = if (visible) View.VISIBLE else View.GONE
        if (!visible || d == null) {
            setNear(false)
            return
        }

        b.deliveryCode.text = d.code
        b.deliveryRestaurant.text = d.restaurant
        b.deliveryRestaurant.visibility = if (d.restaurant.isEmpty()) View.GONE else View.VISIBLE
        b.deliveryStatus.text = d.status
        b.deliveryLabel.text = if (d.near) "🛵 Livreur proche" else "🛵 Code livreur"
        b.deliveryWindow.text = when {
            d.delivered -> "✓ Livrée"
            d.etaEpochSec != null -> {
                val min = ceil((d.etaEpochSec - nowSec) / 60.0).toInt()
                if (min <= 0) "Imminent" else "Dans $min min"
            }
            else -> d.window.ifEmpty { "En cours" }
        }
        b.deliveryCard.alpha = if (d.delivered) 0.7f else 1f
        setNear(d.near)
    }

    /** Livreur à proximité : carte turquoise pleine qui pulse, c'est le moment du code. */
    private fun setNear(near: Boolean) {
        val bg = if (near) R.color.deliveroo else R.color.deliveroo_bg
        b.deliveryCard.backgroundTintList = ColorStateList.valueOf(context.getColor(bg))
        val onCard = if (near) NEAR_TEXT else context.getColor(R.color.text)
        b.deliveryCode.setTextColor(onCard)
        b.deliveryRestaurant.setTextColor(onCard)
        b.deliveryLabel.setTextColor(if (near) NEAR_TEXT else context.getColor(R.color.deliveroo))
        b.deliveryWindow.setTextColor(if (near) NEAR_TEXT else context.getColor(R.color.deliveroo))
        b.deliveryStatus.setTextColor(if (near) NEAR_TEXT else context.getColor(R.color.text_dim))
        if (near && deliveryPulse == null) {
            deliveryPulse = ObjectAnimator.ofFloat(b.deliveryCard, View.ALPHA, 1f, 0.6f).apply {
                duration = 900
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                start()
            }
        } else if (!near) {
            deliveryPulse?.cancel()
            deliveryPulse = null
        }
    }

    private companion object {
        const val STALE_SEC = 15 * 60L
        const val DELIVERED_SHOWN_SEC = 5 * 60L
        const val NEAR_TEXT = 0xFF06302C.toInt()
    }
}
