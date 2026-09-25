package be.lghs.lab.data

import org.junit.Assert.assertTrue
import org.junit.Test

class GtfsRtTest {
    @Test
    fun parsesRealTecFeed() {
        val bytes = javaClass.getResource("/tec_trip_updates.pb")!!.readBytes()
        val trips = GtfsRt.parse(bytes)
        assertTrue("courses: ${trips.size}", trips.size > 1000)
        assertTrue(trips.all { it.tripId.isNotEmpty() && it.startDate.length == 8 })
        assertTrue(trips.any { t -> t.updates.any { it.third != null } })
    }
}
