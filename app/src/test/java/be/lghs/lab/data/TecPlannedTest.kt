package be.lghs.lab.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** Tram absent du flux temps réel TEC (vendredi 20/11 à 13:11) : horaire théorique. */
class TecPlannedTest {
    private val now = 1795176660L // 2026-11-20 13:11:00 heure belge
    private val trip = "55986700-L_PA_2026-26_LG_VE-Vendredi-11"

    private fun config(dates: Set<String>) = TransportConfig(
        tecWindowMinutes = 15,
        tecStops = listOf(TecStop("L2Tcurt2", "Curtius", mapOf("T1" to "Coronmeuse"))),
        trainRoutes = emptyList(),
        trainsPerRoute = 3,
        schedule = mapOf(trip to listOf(ScheduledStop("L2Tcurt2", 15, 13 * 3600 + 15 * 60))),
        lastTrips = emptySet(),
        plannedTrips = mapOf(trip to PlannedTrip("T1", dates)),
    )

    private fun departures(config: TransportConfig, feed: List<RtTrip> = emptyList()) =
        TecRepository().departures(config, feed, now, now + 15 * 60)

    @Test
    fun tramShownAtScheduledTime() {
        val d = departures(config(setOf("20261120"))).single()
        assertEquals("T1", d.line)
        assertEquals("Coronmeuse", d.destination)
        assertEquals("Curtius", d.stop)
        assertEquals(now + 4 * 60, d.epochSec) // 13:15
        assertEquals(0, d.delaySec)
    }

    @Test
    fun notRunningToday() {
        assertEquals(emptyList<BusDeparture>(), departures(config(setOf("20261121"))))
    }

    @Test
    fun canceledInFeedStaysHidden() {
        val canceled = RtTrip(trip, "L1000-23082", "20261120", canceled = true, tripDelay = null, updates = emptyList())
        assertEquals(emptyList<BusDeparture>(), departures(config(setOf("20261120")), listOf(canceled)))
    }

    @Test
    fun delayFromFeedWins() {
        val late = RtTrip(trip, "L1000-23082", "20261120", canceled = false, tripDelay = 120, updates = emptyList())
        val d = departures(config(setOf("20261120")), listOf(late)).single()
        assertEquals(120, d.delaySec)
        assertEquals(now + 6 * 60, d.epochSec)
    }
}
