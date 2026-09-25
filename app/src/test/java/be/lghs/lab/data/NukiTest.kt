package be.lghs.lab.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Réponses réelles du bridge Nuki du LgHS. */
class NukiTest {
    private fun state(lock: Int, door: Int) =
        JSONObject("""{"mode": 2, "state": $lock, "doorsensorState": $door, "success": true}""")

    @Test
    fun lockedAndClosedIsOk() {
        val status = NukiRepository.parse(state(lock = 1, door = 2))
        assertTrue(status.ok)
    }

    @Test
    fun doorOpenedIsAlert() {
        val status = NukiRepository.parse(state(lock = 1, door = 3))
        assertFalse(status.ok)
        assertEquals("Porte ouverte", status.label)
    }

    @Test
    fun unlockedAndOpenedListsBoth() {
        val status = NukiRepository.parse(state(lock = 3, door = 3))
        assertFalse(status.ok)
        assertEquals("Porte ouverte · Déverrouillée", status.label)
    }

    @Test
    fun anyOtherStateIsAlert() {
        for (lock in listOf(0, 2, 3, 4, 5, 6, 7, 254, 255)) assertFalse(NukiRepository.parse(state(lock, 2)).ok)
        for (door in listOf(0, 1, 3, 4, 5, 16, 240, 255)) assertFalse(NukiRepository.parse(state(1, door)).ok)
    }
}
