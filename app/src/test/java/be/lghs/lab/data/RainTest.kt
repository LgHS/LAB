package be.lghs.lab.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 2 h à partir de 18:30 (heure belge, le 20/11/2026). */
class RainTest {
    private val start = 1795195800L // 2026-11-20 18:30

    private fun slots(vararg mm: Double) = mm.mapIndexed { i, v -> RainSlot(start + i * 15 * 60L, v) }

    @Test
    fun dry() {
        val s = slots(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        assertEquals("Sec jusqu'à 20:30", rainSummary(s))
        assertNull(nextRain(s))
    }

    @Test
    fun rainLater() {
        val s = slots(0.0, 0.0, 0.3, 0.5, 0.0, 0.0, 0.0, 0.0)
        assertEquals("Pluie vers 19:00", rainSummary(s))
        assertEquals("19:00", nextRain(s))
    }

    @Test
    fun rainingNow() {
        val s = slots(0.2, 0.1, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        assertEquals("Pluie jusqu'à 19:00", rainSummary(s))
        assertNull(nextRain(s))
    }

    @Test
    fun rainingAllTheTime() {
        assertEquals("Pluie pendant 2 h", rainSummary(slots(0.2, 0.1, 0.4, 0.3, 0.2, 0.1, 0.2, 0.1)))
    }

    @Test
    fun noForecast() {
        assertNull(rainSummary(emptyList()))
    }
}
