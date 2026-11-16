package be.lghs.lab.data

import be.lghs.lab.Config
import be.lghs.lab.Secrets
import org.json.JSONObject
import java.io.IOException

/** État de la porte d'entrée : OK seulement si verrouillée ET porte fermée. */
data class DoorStatus(val ok: Boolean, val label: String)

/**
 * Bridge Nuki, `/lockState` : état lu en direct sur la serrure (Bluetooth). Ça la réveille à
 * chaque appel, sans conséquence ici : les serrures du LgHS sont sur secteur.
 */
object NukiRepository {
    private const val LOCKED = 1
    private const val DOOR_CLOSED = 2

    private const val SMART_LOCK = 4 // deviceType : Smart Lock 3.0 / 4

    suspend fun fetch(nukiId: Long = Config.NUKI_PORTE_ID): DoorStatus {
        val state = JSONObject(
            Http.getText("${Config.NUKI_BRIDGE}/lockState?nukiId=$nukiId&deviceType=$SMART_LOCK&token=${Secrets.nukiToken}"),
        )
        if (!state.optBoolean("success", false)) throw IOException("Bridge Nuki : échec de lecture")
        return parse(state)
    }

    /** Réponse `/lockState` → vert seulement si verrouillée (state 1) et fermée (doorsensorState 2). */
    internal fun parse(state: JSONObject): DoorStatus {
        val lock = state.optInt("state", -1)
        val door = state.optInt("doorsensorState", -1)
        if (lock == LOCKED && door == DOOR_CLOSED) return DoorStatus(ok = true, label = "🔒  Fermée")

        val problems = buildList {
            when (door) {
                DOOR_CLOSED -> Unit
                3 -> add("Porte ouverte")
                240 -> add("Capteur forcé")
                else -> add("Capteur porte ?")
            }
            when (lock) {
                LOCKED -> Unit
                2, 3, 6 -> add("Déverrouillée")
                5, 7 -> add("Gâche ouverte")
                4 -> add("Verrouillage…")
                254 -> add("Moteur bloqué")
                else -> add("Serrure ?")
            }
        }
        return DoorStatus(ok = false, label = problems.joinToString(" · "))
    }
}

/**
 * Porte hors de l'état sûr (verrouillée et fermée) depuis [afterSec] : un seul signal, réarmé
 * quand elle y revient. Une lecture ratée (bridge injoignable) ne remet pas le décompte à zéro.
 */
class DoorAlarm(private val afterSec: Long) {
    private var unsafeSince: Long? = null
    private var rung = false

    /** true une seule fois par période hors état sûr, quand elle dépasse [afterSec]. */
    fun update(status: DoorStatus, nowSec: Long): Boolean {
        if (status.ok) {
            unsafeSince = null
            rung = false
            return false
        }
        val since = unsafeSince ?: nowSec.also { unsafeSince = it }
        if (rung || nowSec - since < afterSec) return false
        rung = true
        return true
    }
}
