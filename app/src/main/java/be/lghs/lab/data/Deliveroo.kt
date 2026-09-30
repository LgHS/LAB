package be.lghs.lab.data

import org.json.JSONObject
import org.json.JSONTokener
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale

/** Commande Deliveroo en cours : [code] à donner au livreur. */
data class Delivery(
    val code: String,
    val restaurant: String,
    /** Statut sans le nom du restaurant répété en tête. */
    val status: String,
    /** « 20:55–21:10 », « Environ 7 minutes »… vide une fois livrée. */
    val window: String,
    /** Heure d'arrivée estimée si [window] est une durée (maj + minutes), sinon null. */
    val etaEpochSec: Long?,
    /** Livreur à proximité : il va sonner, c'est le moment du code. */
    val near: Boolean,
    val delivered: Boolean,
    /** Horodatage `maj` = heure de lecture côté lghs.be (sert de référence au décompte). */
    val updatedEpochSec: Long?,
)

/**
 * https://lghs.be/deliveroo/?json : `null` hors commande, sinon
 * `{"restaurant", "livraison", "code_livreur", "statut", "maj"}`.
 *
 * Pas de champ d'étape : « livrée » et « à proximité » sont déduits des textes Deliveroo
 * (français), le reste (durée, horodatage) des valeurs.
 */
object DeliverooRepository {
    private const val URL = "https://lghs.be/deliveroo/?json"
    private const val NEAR_MINUTES = 2
    private val MINUTES = Regex("""(\d+)\s*min""", RegexOption.IGNORE_CASE)

    suspend fun fetch(): Delivery? = parse(Http.getText(URL))

    internal fun parse(body: String): Delivery? {
        val json = JSONTokener(body).nextValue() as? JSONObject ?: return null
        fun field(name: String) = if (json.isNull(name)) "" else json.optString(name).trim()
        val code = field("code_livreur").ifEmpty { return null }
        val restaurant = field("restaurant")
        val window = field("livraison")
        val rawStatus = field("statut")
        val updated = parseDate(field("maj"))

        val statusLower = rawStatus.lowercase(Locale.FRENCH)
        val delivered = window.isEmpty() &&
            ("appétit" in statusLower || "a mis" in statusLower || "livrée" in statusLower)
        val minutes = etaMinutes(window)
        val near = !delivered && ("proximité" in statusLower || (minutes != null && minutes <= NEAR_MINUTES))

        return Delivery(
            code = code,
            restaurant = restaurant,
            status = stripRestaurant(rawStatus, restaurant),
            window = window,
            etaEpochSec = if (minutes != null && updated != null) updated + minutes * 60L else null,
            near = near,
            delivered = delivered,
            updatedEpochSec = updated,
        )
    }

    /** « Environ 7 minutes » → 7, « 1 minute » → 1, « Moins d'une minute » → 0, créneau → null. */
    private fun etaMinutes(window: String): Int? {
        val lower = window.lowercase(Locale.FRENCH)
        if ("moins d'une minute" in lower || "moins d’une minute" in lower) return 0
        if (':' in window) return null // créneau « 20:55–21:10 »
        return MINUTES.find(window)?.groupValues?.get(1)?.toIntOrNull()
    }

    /** « Bro's Burger Kitchen - Liège prépare votre commande » → « Prépare votre commande ». */
    private fun stripRestaurant(status: String, restaurant: String): String {
        if (restaurant.isEmpty() || !status.startsWith(restaurant)) return status
        val rest = status.removePrefix(restaurant).trim()
        return rest.replaceFirstChar { it.titlecase(Locale.FRENCH) }.ifEmpty { status }
    }

    private fun parseDate(value: String): Long? = try {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.ROOT).parse(value)?.time?.div(1000)
    } catch (_: ParseException) {
        null
    }
}
