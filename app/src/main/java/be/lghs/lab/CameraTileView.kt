package be.lghs.lab

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Outline
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.ui.PlayerView
import be.lghs.lab.data.DoorStatus

/** Une tuile = un flux RTSP, avec reconnexion automatique (backoff exponentiel). */
@OptIn(UnstableApi::class)
class CameraTileView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    // TextureView (et non SurfaceView) : seule à respecter les coins arrondis et la superposition
    private val playerView: PlayerView
    private val status: LinearLayout
    private val statusText: TextView
    private val doorFrame: View
    private val doorBadge: TextView
    private var doorPulse: ObjectAnimator? = null
    private val handler = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private var camera: Camera? = null
    private var retryDelayMs = MIN_RETRY_MS

    init {
        LayoutInflater.from(context).inflate(R.layout.camera_tile, this, true)
        playerView = findViewById(R.id.player_view)
        status = findViewById(R.id.status)
        statusText = findViewById(R.id.status_text)
        doorFrame = findViewById(R.id.door_frame)
        doorBadge = findViewById(R.id.door_badge)
        val radius = 14 * resources.displayMetrics.density
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) =
                outline.setRoundRect(0, 0, view.width, view.height, radius)
        }
        clipToOutline = true
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            when (state) {
                Player.STATE_READY -> status.visibility = View.GONE
                Player.STATE_BUFFERING -> showStatus(R.string.status_connecting)
                Player.STATE_ENDED -> scheduleReconnect("fin de flux")
                Player.STATE_IDLE -> Unit
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.w(TAG, "${camera?.name}: ${error.errorCodeName}", error)
            scheduleReconnect(error.errorCodeName)
        }
    }

    /**
     * Chien de garde : le lecteur peut rester bloqué sans erreur (connexion RTSP ouverte mais
     * plus d'images : NVR redémarré, caméra reconnectée, horodatages RTP qui rebouclent après
     * ~13 h…). On surveille le nombre d'images réellement affichées ; s'il n'avance plus,
     * on repart d'un lecteur neuf.
     */
    private val watchdog = object : Runnable {
        override fun run() {
            val p = player
            if (p != null) {
                val counters = p.videoDecoderCounters
                counters?.ensureUpdated()
                val rendered = counters?.renderedOutputBufferCount ?: 0
                val now = SystemClock.elapsedRealtime()
                if (rendered != lastRendered) {
                    lastRendered = rendered
                    lastProgressAt = now
                    retryDelayMs = MIN_RETRY_MS // ça tourne vraiment : on oublie les échecs
                } else if (now - lastProgressAt > STALL_MS && !reconnectPending) {
                    scheduleReconnect("aucune image depuis ${(now - lastProgressAt) / 1000} s")
                }
            }
            handler.postDelayed(this, WATCHDOG_MS)
        }
    }
    private var lastRendered = 0
    private var lastProgressAt = 0L
    private var reconnectPending = false

    fun start(camera: Camera) {
        this.camera = camera
        retryDelayMs = MIN_RETRY_MS
        restart()
        handler.removeCallbacks(watchdog)
        handler.postDelayed(watchdog, WATCHDOG_MS)
    }

    /** Lecteur neuf à chaque (re)connexion : un ExoPlayer réutilisé après des heures d'erreurs peut rester bancal. */
    private fun restart() {
        val cam = camera ?: return
        reconnectPending = false
        releasePlayer()
        showStatus(R.string.status_connecting)

        // Buffers courts : on privilégie la latence sur la fluidité (usage interphone)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(500, 2_000, 250, 500)
            .build()
        val p = ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .build()
        p.addListener(listener)
        // UniFi envoie de l'Opus sans en-tête in-band : RtpOpusReader de Media3 plante
        // ("ID Header missing"). Pas de son sur un moniteur, on ignore la piste.
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
            .build()
        playerView.player = p
        player = p

        val source = RtspMediaSource.Factory()
            .setForceUseRtpTcp(true) // UDP passe mal les NAT/Wi-Fi, TCP est plus fiable
            .setTimeoutMs(8_000)
            .createMediaSource(MediaItem.fromUri(cam.playableUri()))
        p.setMediaSource(source)
        p.prepare()
        p.playWhenReady = true

        lastRendered = 0
        lastProgressAt = SystemClock.elapsedRealtime()
    }

    /**
     * État de la porte : `null` = bridge injoignable (pastille grise), OK = pastille verte
     * discrète, sinon cadre rouge qui pulse et pastille rouge avec la raison.
     */
    fun showDoor(status: DoorStatus?) {
        doorBadge.visibility = View.VISIBLE
        doorBadge.text = status?.label ?: "Nuki ?"
        val (bg, fg) = when {
            status == null -> 0x99000000.toInt() to 0xFFB0B0B0.toInt()
            status.ok -> 0xB3102A1C.toInt() to context.getColor(R.color.ontime)
            else -> context.getColor(R.color.alert) to 0xFFFFFFFF.toInt()
        }
        doorBadge.backgroundTintList = ColorStateList.valueOf(bg)
        doorBadge.setTextColor(fg)

        val alert = status != null && !status.ok
        doorFrame.visibility = if (alert) View.VISIBLE else View.GONE
        if (alert && doorPulse == null) {
            doorPulse = ObjectAnimator.ofFloat(doorFrame, View.ALPHA, 1f, 0.35f).apply {
                duration = 800
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                start()
            }
        } else if (!alert) {
            doorPulse?.cancel()
            doorPulse = null
        }
    }

    fun release() {
        handler.removeCallbacksAndMessages(null)
        reconnectPending = false
        releasePlayer()
    }

    private fun releasePlayer() {
        playerView.player = null
        player?.release()
        player = null
    }

    private fun scheduleReconnect(reason: String) {
        if (reconnectPending) return
        reconnectPending = true
        Log.w(TAG, "${camera?.name}: relance dans ${retryDelayMs / 1000} s ($reason)")
        showStatus(R.string.status_reconnecting)
        handler.removeCallbacks(reconnect)
        handler.postDelayed(reconnect, retryDelayMs)
        retryDelayMs = (retryDelayMs * 2).coerceAtMost(MAX_RETRY_MS)
    }

    private val reconnect = Runnable {
        restart()
        // Laisse au nouveau lecteur le temps de se connecter avant de le juger
        lastProgressAt = SystemClock.elapsedRealtime()
    }

    private fun showStatus(textRes: Int) {
        statusText.setText(textRes)
        status.visibility = View.VISIBLE
    }

    private companion object {
        const val TAG = "CameraTile"
        const val MIN_RETRY_MS = 1_000L
        const val MAX_RETRY_MS = 15_000L
        const val WATCHDOG_MS = 5_000L
        const val STALL_MS = 20_000L
    }
}
