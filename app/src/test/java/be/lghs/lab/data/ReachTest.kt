package be.lghs.lab.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Quai à 5 min à pied du space. */
class ReachTest {
    private val now = 1_000_000L

    private fun reachIn(seconds: Long) = reach(now + seconds, now, minutesFromSpace = 5)

    @Test
    fun tooLateBeforeWalkingTime() {
        assertEquals(Reach.TOO_LATE, reachIn(0))
        assertEquals(Reach.TOO_LATE, reachIn(4 * 60 + 59))
    }

    @Test
    fun hurryJustAfterWalkingTime() {
        assertEquals(Reach.HURRY, reachIn(5 * 60))
        assertEquals(Reach.HURRY, reachIn(6 * 60 + 59))
    }

    @Test
    fun okWithMargin() {
        assertEquals(Reach.OK, reachIn(7 * 60))
    }

    @Test
    fun unknownWithoutWalkingTime() {
        assertNull(reach(now + 60, now, minutesFromSpace = null))
    }

    @Test
    fun walkingTimeReadFromStop() {
        val stop = TecStop("Llgsime1", "Simenon", mapOf("11" to "Liège Expo"), minutesFromSpace = 3)
        val trip = "t"
        val config = TransportConfig(
            tecWindowMinutes = 15,
            tecStops = listOf(stop),
            trainRoutes = emptyList(),
            trainsPerRoute = 3,
            schedule = mapOf(trip to listOf(ScheduledStop("Llgsime1", 1, 13 * 3600 + 15 * 60))),
            lastTrips = emptySet(),
        )
        val feed = listOf(RtTrip(trip, "L0011-1", "20261120", false, 60, emptyList()))
        val nowSec = 1795176660L // 2026-11-20 13:11:00 heure belge
        assertEquals(3, TecRepository().departures(config, feed, nowSec, nowSec + 15 * 60).single().minutesFromSpace)
    }
}
