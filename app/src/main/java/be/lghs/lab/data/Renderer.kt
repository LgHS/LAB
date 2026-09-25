package be.lghs.lab.data

import android.os.SystemClock
import be.lghs.lab.Config
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Renderers moOde (Spotify Connect, AirPlay, Deezer) : ils court-circuitent MPD, qui reste
 * « à l'arrêt ». On lit les mêmes sources que l'interface web de moOde :
 *
 * - sondage des métadonnées toutes les 2 s tant qu'un renderer joue : moOde écrit le fichier
 *   dès que librespot/shairport change de morceau ;
 * - push (`engine-cmd.php`, envoyé à chaque client en attente, on ne vole rien à l'interface
 *   web) : sert juste de déclencheur. Sur le Pi il arrive ~10 s après le fichier (démarrage
 *   PHP de send-fecmd), on ne se fie donc pas à son contenu ;
 * - le renderer actif (`get_cfg_system`, 2 à 7 s sur le Pi) est revérifié toutes les 15 s.
 */
object RendererClient {
    private const val BASE = "http://${Config.MOODE_HOST}"
    private const val ACTIVE_CHECK_MS = 15_000L
    private const val META_POLL_MS = 2_000L
    private const val IDLE_POLL_MS = 3_000L
    private const val PUSH_TIMEOUT_MS = 5 * 60_000

    private class Renderer(val flag: String, val metaCmd: String, val name: String)

    private val renderers = listOf(
        Renderer("spotactive", "get_spotmeta", "Spotify"),
        Renderer("aplactive", "get_aplmeta", "AirPlay"),
        Renderer("deezactive", "get_deezmeta", "Deezer"),
    )

    /** Émet le morceau du renderer actif, ou `null` si aucun renderer ne joue. */
    fun nowPlaying(): Flow<NowPlaying?> = channelFlow {
        var active: Renderer? = null

        suspend fun publish() {
            val renderer = active
            if (renderer == null) send(null) else ignoringNetworkErrors { send(fetch(renderer)) }
        }

        // Push : réaction immédiate aux événements moOde
        launch {
            while (true) {
                val event = try {
                    JSONTokener(Http.getText("$BASE/engine-cmd.php", PUSH_TIMEOUT_MS)).nextValue() as? String
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    delay(2_000) // moOde injoignable ou délai dépassé : on se réabonne
                    null
                } ?: continue
                val name = event.substringBefore(',')
                when {
                    name.startsWith("update_") && name.endsWith("meta") -> {
                        active = renderers.firstOrNull { it.metaCmd == "get_" + name.removePrefix("update_") } ?: active
                        publish()
                    }
                    name.endsWith("active1") -> {
                        active = renderers.firstOrNull { it.flag == name.dropLast(1) } ?: active
                        publish()
                    }
                    name.endsWith("active0") && active?.flag == name.dropLast(1) -> {
                        active = null
                        publish()
                    }
                }
            }
        }

        var checkedAt = 0L
        while (true) {
            val now = SystemClock.elapsedRealtime()
            if (now - checkedAt >= ACTIVE_CHECK_MS) {
                // En cas d'erreur on garde l'état précédent plutôt que de basculer sur MPD
                ignoringNetworkErrors { active = activeRenderer() }
                checkedAt = now
            }
            publish()
            delay(if (active == null) IDLE_POLL_MS else META_POLL_MS)
        }
    }.distinctUntilChanged { a, b -> a?.copy(sampledAtMs = 0) == b?.copy(sampledAtMs = 0) }

    private suspend fun activeRenderer(): Renderer? {
        val cfg = JSONObject(Http.getText("$BASE/command/cfg-table.php?cmd=get_cfg_system"))
        return renderers.firstOrNull { cfg.optString(it.flag) == "1" }
    }

    /** Ignore les erreurs réseau (moOde lent/injoignable), sans avaler l'annulation. */
    private suspend fun ignoringNetworkErrors(block: suspend () -> Unit) = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
    }

    private suspend fun fetch(renderer: Renderer): NowPlaying {
        val raw = JSONTokener(Http.getText("$BASE/command/renderer.php?cmd=${renderer.metaCmd}")).nextValue()
        return parse(renderer, raw as? String)
    }

    /** "titre~~~artiste(s)~~~album~~~durée~~~pochette(s)~~~format", listes séparées par \n */
    private fun parse(renderer: Renderer, raw: String?): NowPlaying {
        val parts = raw?.split("~~~") ?: emptyList()
        fun part(i: Int) = parts.getOrNull(i).orEmpty().trim()
        val durationRaw = part(3).toDoubleOrNull() ?: 0.0
        val cover = part(4).lineSequence().firstOrNull { it.isNotBlank() }?.trim()
        return NowPlaying(
            source = renderer.name,
            format = part(5),
            state = PlayState.PLAY,
            title = part(0),
            artist = part(1).lines().filter { it.isNotBlank() }.joinToString(", "),
            album = part(2),
            elapsedSec = 0.0,
            // Spotify/AirPlay en ms, Deezer en s ; sans position de lecture, pas de barre
            durationSec = if (renderer.metaCmd == "get_deezmeta") durationRaw else durationRaw / 1000,
            coverUrl = when {
                cover.isNullOrEmpty() -> null
                cover.startsWith("http") -> cover
                else -> "$BASE/${cover.removePrefix("/")}"
            },
            sampledAtMs = SystemClock.elapsedRealtime(),
        )
    }
}
