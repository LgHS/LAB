package be.lghs.lab.data

import be.lghs.lab.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.Calendar
import java.util.TimeZone

data class BusDeparture(
    val line: String,
    val destination: String,
    val stop: String,
    val epochSec: Long,
    val delaySec: Int,
    /** Dernière course de la journée pour cette ligne à ce quai. */
    val last: Boolean,
)

/**
 * Bus TEC en temps réel via l'API GTFS-RT officielle (clé `tec.apiKey` dans local.properties).
 *
 * Le flux ne contient que des *retards* par arrêt, pas d'heures : l'heure réelle =
 * horaire théorique de la course ([TransportConfig.schedule]) + retard.
 * Seules les courses présentes dans le flux
 * temps réel sont affichées. Limite TEC : 1 appel / 5 s.
 */
class TecRepository {

    suspend fun fetch(config: TransportConfig, nowSec: Long = System.currentTimeMillis() / 1000): List<BusDeparture> {
        val key = BuildConfig.TEC_API_KEY
        if (key.isBlank()) throw IOException("Clé TEC absente (tec.apiKey dans local.properties)")
        val trips = Http.get(URL + key) { GtfsRt.parse(it.readBytes()) }
        val until = nowSec + config.tecWindowMinutes * 60

        return withContext(Dispatchers.Default) { departures(config, trips, nowSec, until) }
    }

    private fun departures(config: TransportConfig, trips: List<RtTrip>, nowSec: Long, until: Long): List<BusDeparture> {
        val schedule = config.schedule
        val stopsById = config.tecStops.withIndex().associateBy { it.value.id }
        return trips.asSequence()
            .filter { !it.canceled }
            .flatMap { trip ->
                val stops = schedule[trip.tripId] ?: return@flatMap emptySequence()
                val midnight = serviceDayStart(trip.startDate) ?: return@flatMap emptySequence()
                stops.asSequence().mapNotNull { stop ->
                    val delay = trip.delayAt(stop.sequence) ?: return@mapNotNull null
                    Candidate(trip, stop, midnight + stop.secondsOfDay + delay, delay)
                }
            }
            .filter { it.time in (nowSec - 30)..until }
            // Seules les lignes choisies pour ce quai (un quai retiré de transport.json mais
            // encore dans les horaires générés est ignoré aussi)
            .filter { c -> stopsById[c.stop.stopId]?.value?.lines?.containsKey(lineNumber(c.trip.routeId)) == true }
            // Un bus passe par plusieurs de nos quais : on garde le plus proche (ordre de Config)
            .groupBy { it.trip.tripId }.values
            .map { sameTrip -> sameTrip.minBy { stopsById.getValue(it.stop.stopId).index } }
            .map { c ->
                val stop = stopsById.getValue(c.stop.stopId).value
                val line = lineNumber(c.trip.routeId)
                val last = "${c.trip.startDate}|${c.trip.tripId}|${c.stop.stopId}" in config.lastTrips
                BusDeparture(line, stop.lines.getValue(line), stop.name, c.time, c.delay, last)
            }
            .sortedBy { it.epochSec }
    }

    private class Candidate(val trip: RtTrip, val stop: ScheduledStop, val time: Long, val delay: Int)

    /** "L0004-19058" → "4" */
    private fun lineNumber(routeId: String): String =
        routeId.substringAfterLast(':').substringBefore('-').drop(1).trimStart('0')

    /** Minuit (heure belge) du jour de service "20260925". Les horaires GTFS peuvent dépasser 24:00. */
    private fun serviceDayStart(date: String): Long? {
        if (date.length != 8) return null
        return Calendar.getInstance(BRUSSELS).run {
            clear()
            set(date.substring(0, 4).toInt(), date.substring(4, 6).toInt() - 1, date.substring(6, 8).toInt())
            timeInMillis / 1000
        }
    }

    private companion object {
        const val URL = "https://gtfsrt.tectime.be/proto/RealTime/trips?key="
        val BRUSSELS: TimeZone = TimeZone.getTimeZone("Europe/Brussels")
    }
}

/** Une course du flux temps réel. */
internal class RtTrip(
    val tripId: String,
    val routeId: String,
    val startDate: String,
    val canceled: Boolean,
    val tripDelay: Int?,
    /** (stop_sequence, schedule_relationship, retard ou null), triés par séquence. */
    val updates: List<Triple<Int, Int, Int?>>,
) {
    /**
     * Retard applicable à l'arrêt [sequence] : celui de la dernière mise à jour à cette
     * séquence ou avant (règle de propagation GTFS-RT). null = inconnu, ou arrêt non desservi.
     */
    fun delayAt(sequence: Int): Int? {
        val update = updates.lastOrNull { it.first <= sequence } ?: return tripDelay
        val (seq, relationship, delay) = update
        return when {
            relationship == NO_DATA -> null
            relationship == SKIPPED -> if (seq == sequence) null else delay
            else -> delay ?: tripDelay
        }
    }

    companion object {
        const val SKIPPED = 1
        const val NO_DATA = 2
    }
}

/**
 * Décodeur protobuf minimal des champs GTFS-RT utilisés, sans dépendance.
 * FeedMessage.entity=2 · FeedEntity.trip_update=3 · TripUpdate.trip=1, stop_time_update=2, delay=5
 * TripDescriptor.trip_id=1, start_date=3, schedule_relationship=4, route_id=5
 * StopTimeUpdate.stop_sequence=1, arrival=2, departure=3, schedule_relationship=5 · StopTimeEvent.delay=1
 */
internal object GtfsRt {
    private const val TRIP_CANCELED = 3L

    fun parse(bytes: ByteArray): List<RtTrip> {
        val trips = mutableListOf<RtTrip>()
        val feed = ProtoReader(bytes)
        while (feed.next()) {
            if (feed.field != 2) { feed.skip(); continue }
            val entity = feed.message()
            while (entity.next()) {
                if (entity.field == 3) trips += readTripUpdate(entity.message()) else entity.skip()
            }
        }
        return trips
    }

    private fun readTripUpdate(tu: ProtoReader): RtTrip {
        var tripId = ""
        var routeId = ""
        var startDate = ""
        var canceled = false
        var tripDelay: Int? = null
        val updates = mutableListOf<Triple<Int, Int, Int?>>()
        while (tu.next()) when (tu.field) {
            1 -> {
                val trip = tu.message()
                while (trip.next()) when (trip.field) {
                    1 -> tripId = trip.string()
                    3 -> startDate = trip.string()
                    4 -> canceled = trip.varint() == TRIP_CANCELED
                    5 -> routeId = trip.string()
                    else -> trip.skip()
                }
            }
            2 -> {
                val stu = tu.message()
                var sequence = 0
                var relationship = 0
                var departure: Int? = null
                var arrival: Int? = null
                while (stu.next()) when (stu.field) {
                    1 -> sequence = stu.varint().toInt()
                    2 -> arrival = eventDelay(stu.message())
                    3 -> departure = eventDelay(stu.message())
                    5 -> relationship = stu.varint().toInt()
                    else -> stu.skip()
                }
                updates += Triple(sequence, relationship, departure ?: arrival)
            }
            5 -> tripDelay = tu.varint().toInt()
            else -> tu.skip()
        }
        updates.sortBy { it.first }
        return RtTrip(tripId, routeId, startDate, canceled, tripDelay, updates)
    }

    private fun eventDelay(ev: ProtoReader): Int? {
        var delay: Int? = null
        // int32 négatif = varint 64 bits en complément à deux : toInt() le restitue
        while (ev.next()) if (ev.field == 1) delay = ev.varint().toInt() else ev.skip()
        return delay
    }
}

/** Lecteur protobuf (wire format) sur un tableau d'octets. */
internal class ProtoReader(private val buf: ByteArray, private var pos: Int = 0, private val end: Int = buf.size) {
    var field = 0
        private set
    private var wireType = 0

    fun next(): Boolean {
        if (pos >= end) return false
        val tag = varint()
        field = (tag ushr 3).toInt()
        wireType = (tag and 7).toInt()
        return true
    }

    fun varint(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            val b = buf[pos++].toInt()
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
        }
    }

    private fun length(): Int = varint().toInt()

    fun string(): String {
        val len = length()
        return String(buf, pos, len, Charsets.UTF_8).also { pos += len }
    }

    fun message(): ProtoReader {
        val len = length()
        return ProtoReader(buf, pos, pos + len).also { pos += len }
    }

    fun skip() {
        when (wireType) {
            0 -> varint()
            1 -> pos += 8
            2 -> { val len = length(); pos += len } // pas `pos += length()` : pos serait lu avant d'avancer
            5 -> pos += 4
            else -> throw IOException("Wire type $wireType non supporté")
        }
    }
}
