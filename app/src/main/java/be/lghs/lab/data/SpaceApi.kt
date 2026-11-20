package be.lghs.lab.data

import be.lghs.lab.Config
import org.json.JSONObject

/** Statut annoncé du hackerspace, comme le feu tricolore du site. */
enum class SpaceState { OPEN, MEMBERS, CLOSED }

/** SpaceAPI du LgHS : `state.open` et `state.open_to_visitors`. */
object SpaceApiRepository {

    suspend fun fetch(): SpaceState = parse(Http.getText(Config.SPACEAPI_URL))

    internal fun parse(json: String): SpaceState {
        val state = JSONObject(json).getJSONObject("state")
        return when {
            !state.optBoolean("open") -> SpaceState.CLOSED
            state.optBoolean("open_to_visitors") -> SpaceState.OPEN
            else -> SpaceState.MEMBERS
        }
    }
}
