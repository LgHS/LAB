package be.lghs.lab.data

import be.lghs.lab.Config
import org.json.JSONObject

data class Weather(
    val temperature: Double,
    val min: Double,
    val max: Double,
    val windKmh: Double,
    val rainChance: Int,
    val code: Int,
    val isDay: Boolean,
) {
    val icon: String get() = wmo(code, isDay).first
    val label: String get() = wmo(code, isDay).second
}

object WeatherRepository {
    private val url = "https://api.open-meteo.com/v1/forecast" +
        "?latitude=${Config.LATITUDE}&longitude=${Config.LONGITUDE}" +
        "&current=temperature_2m,weather_code,wind_speed_10m,is_day" +
        "&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
        "&forecast_days=1&timezone=Europe%2FBrussels"

    suspend fun fetch(): Weather {
        val json = JSONObject(Http.getText(url))
        val current = json.getJSONObject("current")
        val daily = json.getJSONObject("daily")
        return Weather(
            temperature = current.getDouble("temperature_2m"),
            min = daily.getJSONArray("temperature_2m_min").getDouble(0),
            max = daily.getJSONArray("temperature_2m_max").getDouble(0),
            windKmh = current.getDouble("wind_speed_10m"),
            rainChance = daily.getJSONArray("precipitation_probability_max").optInt(0),
            code = current.getInt("weather_code"),
            isDay = current.getInt("is_day") == 1,
        )
    }
}

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
