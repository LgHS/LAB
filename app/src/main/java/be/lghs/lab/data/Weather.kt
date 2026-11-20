package be.lghs.lab.data

import be.lghs.lab.Config
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Pluie prévue sur le quart d'heure qui commence à [startSec], en mm. */
data class RainSlot(val startSec: Long, val mm: Double) {
    val raining get() = mm > 0.0
}

data class Weather(
    val temperature: Double,
    val min: Double,
    val max: Double,
    val windKmh: Double,
    val code: Int,
    val isDay: Boolean,
    /** Les 2 prochaines heures, par quart d'heure (le premier = le quart d'heure en cours). */
    val rain: List<RainSlot> = emptyList(),
) {
    val icon: String get() = wmo(code, isDay).first
    val label: String get() = wmo(code, isDay).second
}

object WeatherRepository {
    private val url = "https://api.open-meteo.com/v1/forecast" +
        "?latitude=${Config.LATITUDE}&longitude=${Config.LONGITUDE}" +
        "&current=temperature_2m,weather_code,wind_speed_10m,is_day" +
        "&daily=temperature_2m_max,temperature_2m_min" +
        "&minutely_15=precipitation&forecast_minutely_15=$RAIN_SLOTS" +
        "&forecast_days=1&timezone=Europe%2FBrussels&timeformat=unixtime"

    suspend fun fetch(): Weather {
        val json = JSONObject(Http.getText(url))
        val current = json.getJSONObject("current")
        val daily = json.getJSONObject("daily")
        val minutely = json.getJSONObject("minutely_15")
        val times = minutely.getJSONArray("time")
        val mm = minutely.getJSONArray("precipitation")
        return Weather(
            temperature = current.getDouble("temperature_2m"),
            min = daily.getJSONArray("temperature_2m_min").getDouble(0),
            max = daily.getJSONArray("temperature_2m_max").getDouble(0),
            windKmh = current.getDouble("wind_speed_10m"),
            code = current.getInt("weather_code"),
            isDay = current.getInt("is_day") == 1,
            rain = (0 until times.length()).map { RainSlot(times.getLong(it), mm.optDouble(it, 0.0)) },
        )
    }

    /** 2 h par quart d'heure. */
    const val RAIN_SLOTS = 8
}

/**
 * Pluie des 2 prochaines heures en une phrase : « Pluie vers 19:00 », « Pluie jusqu'à 19:15 »,
 * « Pluie pendant 2 h » ou « Sec jusqu'à 20:30 ». Heures fixes plutôt que « dans 30 min » :
 * la prévision n'est relue que toutes les 10 min.
 */
fun rainSummary(slots: List<RainSlot>): String? {
    if (slots.isEmpty()) return null
    val end = slots.last().startSec + 15 * 60
    return if (slots.first().raining) {
        val stop = slots.firstOrNull { !it.raining }
        if (stop == null) "Pluie pendant 2 h" else "Pluie jusqu'à ${hhmm(stop.startSec)}"
    } else {
        val start = slots.firstOrNull { it.raining }
        if (start == null) "Sec jusqu'à ${hhmm(end)}" else "Pluie vers ${hhmm(start.startSec)}"
    }
}

/** Début de la prochaine pluie dans les 2 h (« 19:00 »), null s'il ne pleut pas ou déjà. */
fun nextRain(slots: List<RainSlot>): String? =
    slots.takeUnless { it.firstOrNull()?.raining == true }?.firstOrNull { it.raining }?.let { hhmm(it.startSec) }

internal fun hhmm(epochSec: Long): String =
    SimpleDateFormat("HH:mm", Locale.FRANCE).apply { timeZone = TimeZone.getTimeZone("Europe/Brussels") }
        .format(Date(epochSec * 1000))

/** Codes météo WMO → (icône, libellé). */
private fun wmo(code: Int, day: Boolean): Pair<String, String> = when (code) {
    0 -> (if (day) "☀️" else "🌙") to "Ciel dégagé"
    1 -> (if (day) "🌤️" else "🌙") to "Peu nuageux"
    2 -> "⛅" to "Éclaircies"
    3 -> "☁️" to "Couvert"
    45, 48 -> "🌫️" to "Brouillard"
    51, 53, 55 -> "🌦️" to "Bruine"
    56, 57 -> "🌧️" to "Bruine gelée"
    61 -> "🌦️" to "Pluie faible"
    63 -> "🌧️" to "Pluie"
    65 -> "🌧️" to "Forte pluie"
    66, 67 -> "🌧️" to "Pluie gelée"
    71, 73, 75, 77 -> "🌨️" to "Neige"
    80, 81 -> "🌦️" to "Averses"
    82 -> "⛈️" to "Fortes averses"
    85, 86 -> "🌨️" to "Averses de neige"
    95, 96, 99 -> "⛈️" to "Orage"
    else -> "🌡️" to "—"
}
