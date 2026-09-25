package be.lghs.lab.data

import android.util.Log
import be.lghs.lab.Config
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLEncoder

enum class PlayState { PLAY, PAUSE, STOP }

data class NowPlaying(
    /** "moOde" (MPD) ou le renderer actif : "Spotify", "AirPlay", "Deezer". */
    val source: String,
    /** Format audio annoncé par le renderer ("Vorbis 320K"…), vide pour MPD. */
    val format: String,
    val state: PlayState,
    val title: String,
    val artist: String,
    val album: String,
    val elapsedSec: Double,
    val durationSec: Double,
    val coverUrl: String?,
    /** Horodatage (elapsedRealtime) de la lecture de [elapsedSec], pour extrapoler la progression. */
    val sampledAtMs: Long,
)

/**
 * Client MPD minimal (moOde). `idle player` bloque jusqu'au prochain changement :
 * mise à jour instantanée, sans sondage.
 */
object MpdClient {
    private const val TAG = "Mpd"
    private const val IDLE_TIMEOUT_MS = 60_000

    /** Émet l'état courant à chaque changement ; `null` quand moOde est injoignable. */
    fun nowPlaying(): Flow<NowPlaying?> = flow {
        while (currentCoroutineContext().isActive) {
            val socket = Socket()
            // Débloque une lecture en cours si le collecteur est annulé
            val cancelHandle = currentCoroutineContext().job.invokeOnCompletion { socket.close() }
            try {
                socket.connect(InetSocketAddress(Config.MOODE_HOST, Config.MPD_PORT), 5_000)
                socket.soTimeout = IDLE_TIMEOUT_MS
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                val writer = OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8)
                val greeting = reader.readLine() ?: throw IOException("MPD fermé")
                if (!greeting.startsWith("OK MPD")) throw IOException("Réponse inattendue : $greeting")

                fun command(cmd: String): Map<String, String> {
                    writer.write(cmd + "\n"); writer.flush()
                    return readResponse(reader)
                }

                while (true) {
                    val status = command("status")
                    val song = command("currentsong")
                    emit(toNowPlaying(status, song))
                    writer.write("idle player\n"); writer.flush()
                    try {
                        readResponse(reader)
                    } catch (_: SocketTimeoutException) {
                        // Rien n'a changé : on sort de l'idle pour vérifier que la connexion vit
                        command("noidle")
                    }
                }
            } catch (e: IOException) {
                Log.w(TAG, "MPD: ${e.message}")
                emit(null)
            } finally {
                cancelHandle.dispose()
                socket.close()
            }
            delay(5_000)
        }
    }.flowOn(Dispatchers.IO)

    private fun readResponse(reader: BufferedReader): Map<String, String> {
        val map = mutableMapOf<String, String>()
        while (true) {
            val line = reader.readLine() ?: throw IOException("MPD fermé")
            if (line == "OK") return map
            if (line.startsWith("ACK")) throw IOException(line)
            val sep = line.indexOf(": ")
            if (sep > 0) map.putIfAbsent(line.substring(0, sep), line.substring(sep + 2))
        }
    }

    private fun toNowPlaying(status: Map<String, String>, song: Map<String, String>): NowPlaying {
        val file = song["file"].orEmpty()
        val isStream = file.contains("://")
        // Radio : "Name" = station, "Title" = "Artiste - Titre" envoyé par le flux
        val streamTitle = song["Title"].orEmpty()
        val (artist, title) = when {
            !isStream -> song["Artist"].orEmpty() to (song["Title"] ?: file.substringAfterLast('/'))
            " - " in streamTitle -> streamTitle.substringBefore(" - ") to streamTitle.substringAfter(" - ")
            else -> song["Name"].orEmpty() to streamTitle
        }
        return NowPlaying(
            source = "moOde",
            format = "",
            state = when (status["state"]) {
                "play" -> PlayState.PLAY
                "pause" -> PlayState.PAUSE
                else -> PlayState.STOP
            },
            title = title,
            artist = artist,
            album = if (isStream) song["Name"].orEmpty() else song["Album"].orEmpty(),
            elapsedSec = status["elapsed"]?.toDoubleOrNull() ?: 0.0,
            durationSec = status["duration"]?.toDoubleOrNull() ?: 0.0,
            coverUrl = if (file.isEmpty() || isStream) null
            else "http://${Config.MOODE_HOST}/coverart.php/" +
                URLEncoder.encode(file, "UTF-8").replace("+", "%20"),
            sampledAtMs = android.os.SystemClock.elapsedRealtime(),
        )
    }
}
