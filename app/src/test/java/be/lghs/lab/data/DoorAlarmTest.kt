package be.lghs.lab.data

import org.junit.Assert.assertEquals
import org.junit.Test

class DoorAlarmTest {
    private val safe = DoorStatus(ok = true, label = "🔒  Fermée")
    private val unlocked = DoorStatus(ok = false, label = "Déverrouillée")

    /** Lectures toutes les 5 s, à partir de t = 0 ; renvoie les instants où le son part. */
    private fun rings(alarm: DoorAlarm, states: List<DoorStatus>) =
        states.mapIndexedNotNull { i, s -> (i * 5L).takeIf { alarm.update(s, it) } }

    @Test
    fun ringsOnceAfterDelay() {
        val alarm = DoorAlarm(afterSec = 60)
        assertEquals(listOf(60L), rings(alarm, List(100) { unlocked }))
    }

    @Test
    fun safeStateResetsCountdownAndRearms() {
        val alarm = DoorAlarm(afterSec = 60)
        // 55 s non sûre, retour à l'état sûr : rien ; puis de nouveau non sûre : son 60 s plus tard
        val states = List(12) { unlocked } + safe + List(20) { unlocked } + safe + List(20) { unlocked }
        assertEquals(listOf(125L, 230L), rings(alarm, states))
    }

    @Test
    fun safeDoorNeverRings() {
        assertEquals(emptyList<Long>(), rings(DoorAlarm(afterSec = 0), List(10) { safe }))
    }
}
