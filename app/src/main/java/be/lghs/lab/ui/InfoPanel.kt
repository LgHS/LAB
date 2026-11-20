package be.lghs.lab.ui

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.view.View
import android.view.ViewGroup
import be.lghs.lab.R
import be.lghs.lab.data.Delivery
import be.lghs.lab.data.SpaceState
import be.lghs.lab.data.Weather
import be.lghs.lab.data.nextRain
import be.lghs.lab.data.rainSummary
import be.lghs.lab.databinding.PanelInfoBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Haut-droite : météo + heure, et code Deliveroo au-dessus de la musique s'il y a une commande. */
class InfoPanel(
    private val b: PanelInfoBinding,
    /** Livreur à proximité : appelé une fois par commande. */
    private val onCourierNear: () -> Unit = {},
) {
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

    /** Feu tricolore : rouge, orange, vert ; seul le feu du statut actuel est allumé. */
    fun showSpace(state: SpaceState) {
        val lit = when (state) {
            SpaceState.CLOSED -> 0
            SpaceState.MEMBERS -> 1
            SpaceState.OPEN -> 2
        }
        val label = when (state) {
            SpaceState.CLOSED -> "Fermé"
            SpaceState.MEMBERS -> "Membres"
            SpaceState.OPEN -> "Ouvert"
        }
        // Pastille teintée de la couleur du statut ; seul le feu actuel est allumé
        listOf(b.spaceLight0, b.spaceLight1, b.spaceLight2).forEachIndexed { i, light ->
            light.backgroundTintList = ColorStateList.valueOf(if (i == lit) SPACE_LIGHTS[i] else LIGHT_OFF)
        }
        b.spaceLabel.text = label
        b.spaceLabel.setTextColor(SPACE_LIGHTS[lit])
        b.spaceStatus.backgroundTintList = ColorStateList.valueOf((SPACE_LIGHTS[lit] and 0x00FFFFFF) or 0x33000000)
        b.spaceStatus.visibility = View.VISIBLE
    }

    private var weather: Weather? = null
    private var weatherExpanded: Boolean? = null

    fun showWeather(w: Weather) {
        weather = w
        b.weatherIcon.text = w.icon
        b.weatherTemp.text = "${w.temperature.roundToInt()}°"
        b.rainChart.show(w.rain)
        b.rainSummary.text = rainSummary(w.rain).orEmpty()
        b.rainSummary.setTextColor(context.getColor(if (w.rain.any { it.raining }) R.color.rain else R.color.text))
        renderWeatherDetails()
    }

    /**
     * Sans commande Deliveroo, la carte météo prend de la place : heure et température en plus
     * grand, pluie des 2 prochaines heures. Avec une commande, une seule ligne comme avant.
     */
    private fun setWeatherExpanded(expanded: Boolean) {
        if (expanded == weatherExpanded) return
        weatherExpanded = expanded
        b.weatherRain.visibility = if (expanded) View.VISIBLE else View.GONE
        b.weatherIcon.textSize = if (expanded) 38f else 30f
        b.weatherTemp.textSize = if (expanded) 46f else 34f
        b.clock.textSize = if (expanded) 60f else 42f
        renderWeatherDetails()
    }

    private fun renderWeatherDetails() {
        val w = weather ?: return
        val minMax = "↓${w.min.roundToInt()}° ↑${w.max.roundToInt()}°"
        // Replié : la pluie tient en « 🌧 19:00 » (ou « 🌧 » s'il pleut déjà)
        val rain = when {
            weatherExpanded != false -> null
            w.rain.firstOrNull()?.raining == true -> "🌧"
            else -> nextRain(w.rain)?.let { "🌧 $it" }
        }
        b.weatherDetails.text = listOfNotNull(minMax, rain).joinToString(" · ")
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
        // Une fois par commande, même après un redémarrage de l'app. Le code livreur revient d'une
        // commande à l'autre : on réarme quand la commande est livrée ou qu'il n'y en a plus.
        if (delivery == null || delivery.delivered) {
            if (prefs.contains(KEY_NEAR_ANNOUNCED)) prefs.edit().remove(KEY_NEAR_ANNOUNCED).apply()
        } else if (delivery.near && !isStale(delivery, nowSec) && !prefs.getBoolean(KEY_NEAR_ANNOUNCED, false)) {
            prefs.edit().putBoolean(KEY_NEAR_ANNOUNCED, true).apply()
            onCourierNear()
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
        val visible = d != null && when {
            // L'endpoint renvoie la commande livrée jusqu'à la suivante : 15 min après l'arrivée
            // (heure fournie par lghs.be, sinon moment où l'app l'a vue livrée) puis on masque
            d.delivered -> nowSec - (d.deliveredEpochSec ?: deliveredSeenAt ?: nowSec) < DELIVERED_SHOWN_SEC
            else -> !isStale(d, nowSec)
        }
        b.deliveryCard.visibility = if (visible) View.VISIBLE else View.GONE
        setWeatherExpanded(!visible)
        if (!visible || d == null) {
            setNear(false)
            return
        }

        b.deliveryCode.text = d.code
        b.deliveryRestaurant.text = d.restaurant
        b.deliveryRestaurant.visibility = if (d.restaurant.isEmpty()) View.GONE else View.VISIBLE
        b.deliveryStatus.text = d.status
        b.deliveryLabel.text = if (d.near) "🛵 Livreur proche" else "🛵 Code livreur"
        // Créneau « 15:15–15:30 » (pas une durée « Environ 7 minutes ») : rappelé sous l'heure
        val slot = d.window.takeIf { ':' in it }
        val (label, big, small) = when {
            d.delivered -> Triple("Commande", "✓ Livrée", d.deliveredEpochSec?.let { "à ${hhmm.format(Date(it * 1000))}" })
            d.etaEpochSec != null -> {
                val min = ceil((d.etaEpochSec - nowSec) / 60.0).toInt()
                Triple("Arrivée prévue", hhmm.format(Date(d.etaEpochSec * 1000)), if (min <= 0) "imminent" else "dans $min min")
            }
            else -> Triple("Livraison", d.window.ifEmpty { "En cours" }, null)
        }
        b.deliveryEtaLabel.text = label
        b.deliveryWindow.text = big
        b.deliveryCountdown.text = small
        b.deliveryCountdown.visibility = if (small.isNullOrEmpty()) View.GONE else View.VISIBLE
        // Le créneau n'est rappelé que sous une heure estimée (sinon il est déjà en gros)
        val slotLine = slot?.takeIf { !d.delivered && d.etaEpochSec != null }?.let { "créneau $it" }
        b.deliverySlot.text = slotLine
        b.deliverySlot.visibility = if (slotLine == null) View.GONE else View.VISIBLE
        b.deliveryCard.alpha = if (d.delivered) 0.7f else 1f
        setNear(d.near)
    }

    /** Au cas où `maj` cesserait d'avancer. */
    private fun isStale(d: Delivery, nowSec: Long) = d.updatedEpochSec?.let { nowSec - it >= STALE_SEC } == true

    /** Livreur à proximité : carte turquoise pleine qui pulse, c'est le moment du code. */
    private fun setNear(near: Boolean) {
        val bg = if (near) R.color.deliveroo else R.color.deliveroo_bg
        b.deliveryCard.backgroundTintList = ColorStateList.valueOf(context.getColor(bg))
        val onCard = if (near) NEAR_TEXT else context.getColor(R.color.text)
        b.deliveryCode.setTextColor(onCard)
        b.deliveryRestaurant.setTextColor(onCard)
        b.deliveryLabel.setTextColor(if (near) NEAR_TEXT else context.getColor(R.color.deliveroo))
        b.deliveryWindow.setTextColor(if (near) NEAR_TEXT else context.getColor(R.color.deliveroo))
        b.deliveryEtaLabel.setTextColor(if (near) NEAR_TEXT else context.getColor(R.color.deliveroo))
        b.deliveryCountdown.setTextColor(onCard)
        b.deliverySlot.setTextColor(if (near) NEAR_TEXT else context.getColor(R.color.text_dim))
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
        /** Feu tricolore du statut : fermé, membres, ouvert. */
        val SPACE_LIGHTS = intArrayOf(0xFFFF4D4D.toInt(), 0xFFFFA726.toInt(), 0xFF5FD38D.toInt())
        const val LIGHT_OFF = 0x33FFFFFF
        const val DELIVERED_SHOWN_SEC = 15 * 60L
        const val KEY_DELIVERED_CODE = "delivered_code"
        const val KEY_DELIVERED_AT = "delivered_at"
        const val KEY_NEAR_ANNOUNCED = "near_announced"
        const val NEAR_TEXT = 0xFF06302C.toInt()
    }
}
