package be.lghs.lab.data

import android.content.Context
import android.os.SystemClock
import android.util.Log
import be.lghs.lab.Config
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream

/** Comment on va du space au quai ou à la gare (`"travelMode"` dans transport.json). */
enum class TravelMode { FOOT, BIKE }

/**
 * Un quai TEC (un stop_id = un sens) et les lignes à y afficher → libellé de destination.
 * [minutesFromSpace] : minutes pour aller du space au quai, null = inconnu.
 */
data class TecStop(
    val id: String,
    val name: String,
    val lines: Map<String, String>,
    val minutesFromSpace: Int? = null,
    val travelMode: TravelMode = TravelMode.FOOT,
)

/** Trains directs de [from] vers [to] (noms de gare iRail), gare à [minutesFromSpace] du space. */
data class TrainRoute(
    val from: String,
    val to: String,
    val minutesFromSpace: Int? = null,
    val travelMode: TravelMode = TravelMode.FOOT,
)

/** Peut-on encore attraper un départ en quittant le space maintenant ? */
enum class Reach { TOO_LATE, HURRY, OK }

/** Marge après le temps de marche pendant laquelle il faut partir tout de suite. */
const val HURRY_MARGIN_SEC = 2 * 60

/** null si le temps de marche est inconnu. [departureSec] = heure réelle (retard compris). */
fun reach(departureSec: Long, nowSec: Long, minutesFromSpace: Int?): Reach? {
    val walkSec = (minutesFromSpace ?: return null) * 60L
    val left = departureSec - nowSec
    return when {
        left < walkSec -> Reach.TOO_LATE
        left < walkSec + HURRY_MARGIN_SEC -> Reach.HURRY
        else -> Reach.OK
    }
}

class ScheduledStop(val stopId: String, val sequence: Int, val secondsOfDay: Int)

/** Course de tram : [line] et jours de service ("20261120") où elle circule. */
class PlannedTrip(val line: String, val dates: Set<String>)

/**
 * Ce qu'affiche le panneau transports : dashboard/transport.json (édité à la main sur GitHub)
 * et les horaires TEC que la GitHub Action en dérive.
 */
class TransportConfig(
    val tecWindowMinutes: Int,
    val tecStops: List<TecStop>,
    val trainRoutes: List<TrainRoute>,
    val trainsPerRoute: Int,
    /** trip_id → passages à nos quais (horaire théorique). */
    val schedule: Map<String, List<ScheduledStop>>,
    /** "20260925|tripId|stopId" des dernières courses de chaque jour. */
    val lastTrips: Set<String>,
    /** trip_id → courses de tram, affichées à l'horaire théorique (absentes du temps réel). */
    val plannedTrips: Map<String, PlannedTrip> = emptyMap(),
)

/**
 * Charge la config transports : cache local (dernière version GitHub valide), sinon les
 * fichiers embarqués dans l'APK. [refresh] récupère la version GitHub et ne l'adopte que si
 * elle se lit entièrement.
 */
class TransportConfigRepository(context: Context) {
    private val assets = context.applicationContext.assets
    private val cacheDir = File(context.filesDir, "transport")

    private val _config = MutableStateFlow<TransportConfig?>(null)
    val config: StateFlow<TransportConfig?> = _config

    /** Contenu des petits fichiers de la version chargée, pour détecter un changement. */
    private var loadedTransport = ""
    private var loadedGenerated = ""

    /** Attend la première config chargée (cache ou fichiers embarqués). */
    suspend fun awaitConfig(): TransportConfig = _config.filterNotNull().first()

    suspend fun load() = withContext(Dispatchers.IO) {
        if (_config.value != null) return@withContext
        val started = SystemClock.elapsedRealtime()
        val fromCache = runCatching { read { File(cacheDir, it).inputStream() } }
            .onFailure { if (cacheDir.exists()) Log.w(TAG, "Cache illisible, fichiers embarqués", it) }
            .getOrNull()
        val (config, transport, generated) = fromCache ?: read { assets.open(it) }
        loadedTransport = transport
        loadedGenerated = generated
        _config.value = config
        Log.i(TAG, "Config transports chargée (${if (fromCache != null) "cache" else "embarquée"}) " +
            "en ${SystemClock.elapsedRealtime() - started} ms")
    }

    /** Récupère la version GitHub ; les CSV (~1 Mo) seulement si generated.json a changé. */
    suspend fun refresh() = withContext(Dispatchers.IO) {
        val transport = Http.getText(url(TRANSPORT))
        val generated = Http.getText(url(GENERATED))
        if (transport == loadedTransport && generated == loadedGenerated) return@withContext

        val staging = File(cacheDir.parentFile, "transport.new").apply { deleteRecursively(); mkdirs() }
        File(staging, TRANSPORT).writeText(transport)
        File(staging, GENERATED).writeText(generated)
        for (csv in listOf(SCHEDULE, LAST_TRIPS, PLANNED_TRIPS, SERVICES)) {
            val target = File(staging, csv)
            if (generated == loadedGenerated && File(cacheDir, csv).exists()) {
                File(cacheDir, csv).copyTo(target)
            } else if (generated == loadedGenerated) {
                assets.open(csv).use { input -> target.outputStream().use { input.copyTo(it) } }
            } else {
                Http.get(url(csv)) { input -> target.outputStream().use { input.copyTo(it) } }
            }
        }
        // Ne remplace le cache que par une version qui se lit entièrement
        val (config) = read { File(staging, it).inputStream() }
        cacheDir.deleteRecursively()
        if (!staging.renameTo(cacheDir)) throw IOException("Impossible d'installer la config")
        loadedTransport = transport
        loadedGenerated = generated
        _config.value = config
        Log.i(TAG, "Config transports mise à jour : ${config.tecStops.size} quais, ${config.trainRoutes.size} trajets")
    }

    private fun url(file: String) = Config.REMOTE_CONFIG_URL + file

    /** `"minutesFromSpace": 4` (optionnel). */
    private fun minutesFromSpace(json: JSONObject): Int? =
        if (json.has("minutesFromSpace")) json.getInt("minutesFromSpace") else null

    /** `"travelMode": "bike"` ; à pied par défaut. */
    private fun travelMode(json: JSONObject): TravelMode =
        if (json.optString("travelMode") == "bike") TravelMode.BIKE else TravelMode.FOOT

    private fun read(open: (String) -> InputStream): Triple<TransportConfig, String, String> {
        val transportText = open(TRANSPORT).use { it.readBytes().decodeToString() }
        val generatedText = open(GENERATED).use { it.readBytes().decodeToString() }
        val json = JSONObject(transportText)
        val tec = json.getJSONObject("tec")
        val sncb = json.getJSONObject("sncb")

        val stopsJson = tec.getJSONArray("stops")
        val stops = (0 until stopsJson.length()).map { i ->
            val s = stopsJson.getJSONObject(i)
            val lines = s.getJSONObject("lines")
            TecStop(
                id = s.getString("id"),
                name = s.getString("name"),
                lines = lines.keys().asSequence().associateWith { lines.getString(it) },
                minutesFromSpace = minutesFromSpace(s),
                travelMode = travelMode(s),
            )
        }
        val routesJson = sncb.getJSONArray("routes")
        val routes = (0 until routesJson.length()).map { i ->
            val r = routesJson.getJSONObject(i)
            TrainRoute(
                from = r.getString("from"),
                to = r.getString("to"),
                minutesFromSpace = minutesFromSpace(r),
                travelMode = travelMode(r),
            )
        }

        val schedule = HashMap<String, MutableList<ScheduledStop>>()
        open(SCHEDULE).bufferedReader().useLines { lines ->
            lines.filterNot { it.startsWith("#") || it.isBlank() }.forEach { line ->
                val (trip, stop, seq, secs) = line.split(',')
                schedule.getOrPut(trip) { mutableListOf() } += ScheduledStop(stop, seq.toInt(), secs.toInt())
            }
        }
        if (schedule.isEmpty()) throw IOException("Horaires TEC vides")
        val lastTrips = open(LAST_TRIPS).bufferedReader().useLines { lines ->
            lines.filterNot { it.startsWith("#") || it.isBlank() }.map { it.replace(',', '|') }.toHashSet()
        }

        // Fichiers ajoutés après coup : absents d'un ancien cache, on s'en passe
        fun optionalLines(name: String): List<String> =
            runCatching { open(name).bufferedReader().use { it.readLines() } }.getOrDefault(emptyList())
                .filterNot { it.startsWith("#") || it.isBlank() }
        val services = optionalLines(SERVICES).associate { line ->
            line.substringBefore(',') to line.substringAfter(',').split(' ').toHashSet()
        }
        val plannedTrips = optionalLines(PLANNED_TRIPS).associate { line ->
            val (trip, lineName, service) = line.split(',')
            trip to PlannedTrip(lineName, services[service].orEmpty())
        }

        val config = TransportConfig(
            tecWindowMinutes = tec.optInt("windowMinutes", 15),
            tecStops = stops,
            trainRoutes = routes,
            trainsPerRoute = sncb.optInt("departuresPerRoute", 3),
            schedule = schedule,
            lastTrips = lastTrips,
            plannedTrips = plannedTrips,
        )
        return Triple(config, transportText, generatedText)
    }

    private companion object {
        const val TAG = "TransportConfig"
        const val TRANSPORT = "transport.json"
        const val GENERATED = "generated.json"
        const val SCHEDULE = "tec_schedule.csv"
        const val LAST_TRIPS = "tec_last_trips.csv"
        const val PLANNED_TRIPS = "tec_planned_trips.csv"
        const val SERVICES = "tec_services.csv"
    }
}
