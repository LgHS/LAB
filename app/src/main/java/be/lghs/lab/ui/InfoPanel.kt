package be.lghs.lab.ui

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
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

    private var leet = false

    /** Appelé chaque seconde. À 13:37, l'heure passe en vert terminal avec un curseur qui clignote. */
    fun showClock(nowMs: Long) {
        val time = hhmm.format(Date(nowMs))
        val isLeet = time == LEET_TIME
        if (isLeet != leet) {
            leet = isLeet
            styleClock(isLeet)
        }
        // Monospace : « _ » et l'espace ont la même largeur, l'heure ne bouge pas
        b.clock.text = if (isLeet) time + (if ((nowMs / 1000) % 2 == 0L) "_" else " ") else time
    }

    private fun styleClock(leet: Boolean) {
        b.clock.typeface = Typeface.create(if (leet) Typeface.MONOSPACE else Typeface.SANS_SERIF, Typeface.BOLD)
        b.clock.setTextColor(if (leet) LEET_GREEN else context.getColor(R.color.text))
        if (leet) b.clock.setShadowLayer(18f, 0f, 0f, LEET_GREEN) else b.clock.setShadowLayer(0f, 0f, 0f, 0)
        // Le halo est coupé aux bords de la vue : on l'agrandit, sans changer la taille de la carte
        val glow = if (leet) (10 * context.resources.displayMetrics.density).toInt() else 0
        b.clock.setPadding(glow, glow, glow, glow)
        (b.clock.layoutParams as ViewGroup.MarginLayoutParams).setMargins(-glow, -glow, -glow, -glow)
        b.clock.requestLayout()
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
    private val prefs = context.getSharedPreferences("delivery", android.content.Context.MODE_PRIVATE)

    /** Moment où l'app a vu la commande passer à « livrée » (`maj` = heure de lecture, inutilisable). */
    private var deliveredSeenAt: Long? = null

    fun showDelivery(delivery: Delivery?, nowSec: Long) {
        val wasDelivered = this.delivery?.let { it.delivered && it.code == delivery?.code } == true
        if (delivery?.delivered == true && !wasDelivered) deliveredSeenAt = deliveredSince(delivery.code, nowSec)
        if (delivery?.delivered != true) {
            deliveredSeenAt = null
            // Commande en cours ou aucune : la prochaine livraison repart de zéro, même avec le même code
            if (prefs.contains(KEY_DELIVERED_CODE)) prefs.edit().remove(KEY_DELIVERED_CODE).remove(KEY_DELIVERED_AT).apply()
        }
        this.delivery = delivery
        renderDelivery(nowSec)
    }

    /**
     * Moment où la livraison a été vue la première fois, gardé sur disque : sans ça, chaque
     * redémarrage de l'app (mise à jour, coupure) réaffichait la commande livrée 15 min de plus.
     */
    private fun deliveredSince(code: String, nowSec: Long): Long {
        if (prefs.getString(KEY_DELIVERED_CODE, null) == code) {
            return prefs.getLong(KEY_DELIVERED_AT, nowSec)
        }
        prefs.edit().putString(KEY_DELIVERED_CODE, code).putLong(KEY_DELIVERED_AT, nowSec).apply()
        return nowSec
    }

    /** Appelé chaque seconde : compte à rebours et masquage d'une commande périmée. */
    fun renderDelivery(nowSec: Long) {
        val d = delivery
        val age = d?.updatedEpochSec?.let { nowSec - it }
        val visible = d != null && when {
            // L'endpoint renvoie la commande livrée jusqu'à la suivante : 15 min après l'arrivée
            // (heure fournie par lghs.be, sinon moment où l'app l'a vue livrée) puis on masque
            d.delivered -> nowSec - (d.deliveredEpochSec ?: deliveredSeenAt ?: nowSec) < DELIVERED_SHOWN_SEC
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
        const val LEET_TIME = "13:37"
        const val LEET_GREEN = 0xFF39FF14.toInt()
        const val DELIVERED_SHOWN_SEC = 15 * 60L
        const val KEY_DELIVERED_CODE = "delivered_code"
        const val KEY_DELIVERED_AT = "delivered_at"
        const val NEAR_TEXT = 0xFF06302C.toInt()
    }
}
