package be.lghs.lab.ui

import be.lghs.lab.data.Weather
import be.lghs.lab.databinding.PanelInfoBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** Carte météo + heure (haut-droite, au-dessus de la musique). */
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
}
