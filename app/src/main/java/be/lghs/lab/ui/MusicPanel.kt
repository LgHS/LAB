package be.lghs.lab.ui

import android.graphics.BitmapFactory
import android.os.SystemClock
import android.view.View
import androidx.lifecycle.LifecycleCoroutineScope
import be.lghs.lab.data.Http
import be.lghs.lab.data.NowPlaying
import be.lghs.lab.data.PlayState
import be.lghs.lab.databinding.PanelMusicBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.min

/** Panneau bas-gauche : lecture en cours sur moOde. */
class MusicPanel(private val b: PanelMusicBinding, private val scope: LifecycleCoroutineScope) {
    private var current: NowPlaying? = null
    private var coverUrl: String? = null
    private var coverJob: Job? = null

    init {
        // Pochette carrée, calée sur la hauteur du panneau
        b.root.addOnLayoutChangeListener { root, left, top, right, bottom, _, _, _, _ ->
            val size = minOf(bottom - top - root.paddingTop - root.paddingBottom, ((right - left) * 0.32f).toInt())
            val lp = b.cover.layoutParams
            if (lp.width != size || lp.height != size) {
                b.cover.post { b.cover.layoutParams = lp.apply { width = size; height = size } }
            }
        }
    }

    fun show(np: NowPlaying?) {
        current = np
        // À l'arrêt (ou moOde injoignable) : juste un message, pas de morceau fantôme
        val idle = np == null || np.state == PlayState.STOP
        b.musicIdle.text = if (np == null) "♪  moOde injoignable" else "♪  Pas de musique"
        b.musicIdle.visibility = if (idle) View.VISIBLE else View.GONE
        b.musicContent.visibility = if (idle) View.GONE else View.VISIBLE
        if (idle) {
            loadCover(null)
            return
        }
        np!!
        val source = listOfNotNull(np.source, np.format.takeIf { it.isNotEmpty() }).joinToString(" · ")
        b.musicState.text = if (np.state == PlayState.PAUSE) "❚❚  En pause · $source" else "▶  $source"
        b.musicTitle.text = np.title.ifEmpty { "—" }
        b.musicArtist.text = np.artist
        b.musicAlbum.text = np.album
        b.musicContent.alpha = if (np.state == PlayState.PLAY) 1f else 0.6f
        loadCover(np.coverUrl)
        tick()
    }

    /** Avance la barre de progression entre deux événements MPD. */
    fun tick() {
        val np = current
        if (np != null && np.source != "moOde" && np.durationSec > 0) {
            b.musicProgress.visibility = View.INVISIBLE
            b.musicTime.text = mmss(np.durationSec)
            return
        }
        if (np == null || np.durationSec <= 0) {
            b.musicProgress.visibility = View.INVISIBLE
            b.musicTime.text = ""
            return
        }
        val elapsed = if (np.state == PlayState.PLAY) {
            np.elapsedSec + (SystemClock.elapsedRealtime() - np.sampledAtMs) / 1000.0
        } else np.elapsedSec
        val clamped = min(elapsed, np.durationSec)
        b.musicProgress.visibility = View.VISIBLE
        b.musicProgress.progress = (clamped / np.durationSec * 1000).toInt()
        b.musicTime.text = "${mmss(clamped)} / ${mmss(np.durationSec)}"
    }

    private fun loadCover(url: String?) {
        if (url == coverUrl) return
        coverUrl = url
        coverJob?.cancel()
        b.cover.setImageDrawable(null)
        if (url == null) return
        coverJob = scope.launch {
            val bitmap = runCatching {
                val bytes = Http.get(url) { it.readBytes() }
                withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
            }.getOrNull()
            if (coverUrl == url) b.cover.setImageBitmap(bitmap)
        }
    }

    private fun mmss(sec: Double): String {
        val s = sec.toInt()
        return "%d:%02d".format(s / 60, s % 60)
    }
}
