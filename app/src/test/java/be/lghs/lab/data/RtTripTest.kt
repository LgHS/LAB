package be.lghs.lab.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RtTripTest {
    private fun trip(vararg updates: Triple<Int, Int, Int?>, tripDelay: Int? = null) =
        RtTrip("t", "L1000-23082", "20261118", canceled = false, tripDelay = tripDelay, updates = updates.toList())

    /** Tram TEC du 18/11 : position aux arrêts 9 à 11, arrivée/départ vides, Curtius = arrêt 15. */
    @Test
    fun tramWithoutDelaysUsesSchedule() {
        val tram = trip(Triple(9, 0, null), Triple(10, 0, null), Triple(11, 0, null))
        assertEquals(0, tram.delayAt(15))
        assertNull(tram.delayAt(5)) // déjà passé
    }

    @Test
    fun noDataStopStaysUnknown() {
        assertNull(trip(Triple(1, RtTrip.NO_DATA, null)).delayAt(15))
    }

    /** Bus : un arrêt sans retard après un arrêt avec retard reste inconnu, comme avant. */
    @Test
    fun busWithDelaysUnchanged() {
        val bus = trip(Triple(5, 0, -60), Triple(7, 0, null))
        assertEquals(-60, bus.delayAt(6))
        assertNull(bus.delayAt(8))
    }
}
