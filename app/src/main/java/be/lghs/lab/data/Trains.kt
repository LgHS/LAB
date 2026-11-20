package be.lghs.lab.data

import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Calendar
import java.util.TimeZone

data class TrainDeparture(
    val epochSec: Long,
    val delaySec: Int,
    val line: String, // "S41", "IC"…
    val platform: String,
    val canceled: Boolean,
    /** Dernier train direct de la journée sur ce trajet. */
    val last: Boolean,
)

data class TrainBoard(val route: TrainRoute, val departures: List<TrainDeparture>)

/** iRail (api.irail.be) : gratuit, sans clé, temps réel SNCB. Limite ~3 req/s. */
object TrainRepository {

    suspend fun fetch(route: TrainRoute, count: Int): TrainBoard {
        val url = Uri.parse("https://api.irail.be/v1/connections/").buildUpon()
            .appendQueryParameter("from", route.from)
            .appendQueryParameter("to", route.to)
            .appendQueryParameter("format", "json")
            .appendQueryParameter("lang", "fr")
            .appendQueryParameter("results", "8")
            .appendQueryParameter("alerts", "false")
            .build().toString()
        val connections = JSONObject(Http.getText(url)).getJSONArray("connection")
        val departures = (0 until connections.length())
            .map { connections.getJSONObject(it) }
            .filter { (it.optJSONObject("vias")?.optInt("number") ?: 0) == 0 } // trains directs
            .map { c ->
                val d = c.getJSONObject("departure")
                RawDeparture(
                    epochSec = d.getString("time").toLong(),
                    delaySec = d.optString("delay", "0").toIntOrNull() ?: 0,
                    line = d.optJSONObject("vehicleinfo")?.optString("shortname")
                        ?.substringBefore(' ').orEmpty(),
                    platform = d.optString("platform", "?"),
                    canceled = d.optString("canceled", "0") != "0",
                )
            }
        // Dernier du jour = le départ direct suivant (déjà dans la réponse) est le lendemain
        val result = departures.mapIndexed { i, d ->
            val next = departures.getOrNull(i + 1)
            TrainDeparture(
                d.epochSec, d.delaySec, d.line, d.platform, d.canceled,
                last = next != null && serviceDay(next.epochSec) != serviceDay(d.epochSec),
            )
        }
        // Trains trop proches pour y arriver à pied (chrono en rouge) + [count] trains atteignables
        val nowSec = System.currentTimeMillis() / 1000
        val (tooLate, reachable) = result.partition {
            reach(it.epochSec + it.delaySec, nowSec, route.minutesFromSpace) == Reach.TOO_LATE
        }
        return TrainBoard(route, tooLate + reachable.take(count))
    }

    private class RawDeparture(
        val epochSec: Long, val delaySec: Int, val line: String, val platform: String, val canceled: Boolean,
    )

    /** Jour de service : la nuit (avant 4 h) compte pour la veille. */
    private fun serviceDay(epochSec: Long): Int =
        Calendar.getInstance(TimeZone.getTimeZone("Europe/Brussels")).run {
            timeInMillis = (epochSec - 4 * 3600) * 1000
            get(Calendar.YEAR) * 1000 + get(Calendar.DAY_OF_YEAR)
        }
}

/**
 * Derniers trains reçus par trajet, gardés sur la box avec leur heure de réception : après un
 * redémarrage (mise à jour, preview), une panne d'iRail n'affiche pas « SNCB indisponible ».
 */
class TrainCache(private val file: File) {

    fun load(): List<Pair<TrainBoard, Long>> = runCatching { decode(file.readText()) }.getOrDefault(emptyList())

    fun save(boards: List<Pair<TrainBoard, Long>>) {
        runCatching {
            val tmp = File(file.path + ".tmp")
            tmp.writeText(encode(boards))
            tmp.renameTo(file)
        }
    }

    internal companion object {
        fun encode(boards: List<Pair<TrainBoard, Long>>): String = JSONArray().apply {
            boards.forEach { (board, receivedSec) ->
                put(JSONObject().apply {
                    put("from", board.route.from)
                    put("to", board.route.to)
                    put("received", receivedSec)
                    put("departures", JSONArray().apply {
                        board.departures.forEach { d ->
                            put(JSONObject().apply {
                                put("time", d.epochSec)
                                put("delay", d.delaySec)
                                put("line", d.line)
                                put("platform", d.platform)
                                put("canceled", d.canceled)
                                put("last", d.last)
                            })
                        }
                    })
                })
            }
        }.toString()

        fun decode(text: String): List<Pair<TrainBoard, Long>> {
            val boards = JSONArray(text)
            return (0 until boards.length()).map { i ->
                val b = boards.getJSONObject(i)
                val deps = b.getJSONArray("departures")
                val departures = (0 until deps.length()).map { j ->
                    val d = deps.getJSONObject(j)
                    TrainDeparture(
                        d.getLong("time"), d.getInt("delay"), d.getString("line"), d.getString("platform"),
                        d.getBoolean("canceled"), d.getBoolean("last"),
                    )
                }
                TrainBoard(TrainRoute(b.getString("from"), b.getString("to")), departures) to b.getLong("received")
            }
        }
    }
}
