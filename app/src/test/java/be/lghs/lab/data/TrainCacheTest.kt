package be.lghs.lab.data

import org.junit.Assert.assertEquals
import org.junit.Test

class TrainCacheTest {
    @Test
    fun roundTrip() {
        val board = TrainBoard(
            TrainRoute("Liège-Saint-Lambert", "Nessonvaux"),
            listOf(
                TrainDeparture(1795190400, 120, "S41", "2", canceled = false, last = false),
                TrainDeparture(1795194000, 0, "S41", "?", canceled = true, last = true),
            ),
        )
        val (restored, received) = TrainCache.decode(TrainCache.encode(listOf(board to 1795190000L))).single()
        assertEquals(board, restored)
        assertEquals(1795190000L, received)
    }
}
