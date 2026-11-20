package be.lghs.lab.ui

import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import android.widget.TextView
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
        // Fond flouté qui suit les coins arrondis de la carte
        b.root.clipToOutline = true
        // Pochette carrée, calée sur la hauteur du panneau
        b.root.addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
            val padding = b.musicContent.paddingTop + b.musicContent.paddingBottom
            val size = minOf(bottom - top - padding, ((right - left) * 0.40f).toInt())
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
        b.coverBg.visibility = if (idle) View.GONE else View.VISIBLE
        if (idle) {
            loadCover(null)
            return
        }
        np!!
        b.musicState.text = if (np.state == PlayState.PAUSE) "❚❚  En pause · ${np.source}" else "▶  ${np.source}"
        val title = np.title.ifEmpty { "—" }
        setIfChanged(b.musicTitle, title)
        setIfChanged(b.musicArtist, np.artist)
        b.musicContent.alpha = if (np.state == PlayState.PLAY) 1f else 0.6f
        loadCover(np.coverUrl)
        tick()
    }

    /** Avance la barre de progression entre deux événements MPD. */
    fun tick() {
        val np = current
        // Spotify, AirPlay, Deezer : moOde ne donne pas la position de lecture, pas de barre
        if (np == null || np.source != "moOde" || np.durationSec <= 0) {
            b.musicProgress.visibility = View.GONE
            return
        }
        val elapsed = if (np.state == PlayState.PLAY) {
            np.elapsedSec + (SystemClock.elapsedRealtime() - np.sampledAtMs) / 1000.0
        } else np.elapsedSec
        val clamped = min(elapsed, np.durationSec)
        b.musicProgress.visibility = View.VISIBLE
        b.musicProgress.progress = (clamped / np.durationSec * 1000).toInt()
    }

    private fun loadCover(url: String?) {
        if (url == coverUrl) return
        coverUrl = url
        coverJob?.cancel()
        b.cover.setImageDrawable(null)
        b.coverBg.setImageDrawable(null)
        if (url == null) return
        coverJob = scope.launch {
            val (bitmap, blurred) = runCatching {
                val bytes = Http.get(url) { it.readBytes() }
                withContext(Dispatchers.Default) {
                    val full = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    full to full?.let(::blur)
                }
            }.getOrNull() ?: (null to null)
            if (coverUrl == url) {
                b.cover.setImageBitmap(bitmap)
                b.coverBg.setImageBitmap(blurred)
            }
        }
    }

    /**
     * Flou calculé une fois par pochette : réduite à quelques pixels puis agrandie avec lissage.
     * Un flou RenderEffect est recalculé par le GPU à chaque image (défilements, chrono) : la box
     * tombait à ~6 images/s.
     */
    private fun blur(source: Bitmap): Bitmap {
        val tiny = Bitmap.createScaledBitmap(source, BLUR_TINY_PX, BLUR_TINY_PX, true)
        return Bitmap.createScaledBitmap(tiny, BLUR_SMOOTH_PX, BLUR_SMOOTH_PX, true).also { tiny.recycle() }
    }

    private companion object {
        const val BLUR_TINY_PX = 10
        const val BLUR_SMOOTH_PX = 80
    }

    /** Réaffecter le même texte relancerait le défilement depuis le début ([TickerTextView]). */
    private fun setIfChanged(view: TextView, text: String) {
        if (view.text.toString() != text) view.text = text
    }
}
