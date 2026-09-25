package be.lghs.lab

import android.content.Context
import android.graphics.Outline
import android.os.Handler
import android.os.Looper
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
    private val handler = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private var camera: Camera? = null
    private var retryDelayMs = MIN_RETRY_MS

    init {
        LayoutInflater.from(context).inflate(R.layout.camera_tile, this, true)
        playerView = findViewById(R.id.player_view)
        status = findViewById(R.id.status)
        statusText = findViewById(R.id.status_text)
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
                Player.STATE_READY -> {
                    retryDelayMs = MIN_RETRY_MS
                    status.visibility = View.GONE
                }
                Player.STATE_BUFFERING -> showStatus(R.string.status_connecting)
                Player.STATE_ENDED -> scheduleReconnect()
                Player.STATE_IDLE -> Unit
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.w(TAG, "${camera?.name}: ${error.errorCodeName}", error)
            scheduleReconnect()
        }
    }

    fun start(camera: Camera) {
        this.camera = camera
        release()

        // Buffers courts : on privilégie la latence sur la fluidité (usage interphone)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(500, 2_000, 250, 500)
            .build()

        player = ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .build()
            .also {
                it.addListener(listener)
                // UniFi envoie de l'Opus sans en-tête in-band : RtpOpusReader de Media3 plante
                // ("ID Header missing"). Pas de son sur un moniteur, on ignore la piste.
                it.trackSelectionParameters = it.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                    .build()
                playerView.player = it
            }
        connect()
    }

    fun release() {
        handler.removeCallbacksAndMessages(null)
        playerView.player = null
        player?.release()
        player = null
    }

    private fun connect() {
        val cam = camera ?: return
        val p = player ?: return
        showStatus(R.string.status_connecting)
        val source = RtspMediaSource.Factory()
            .setForceUseRtpTcp(true) // UDP passe mal les NAT/Wi-Fi, TCP est plus fiable
            .setTimeoutMs(8_000)
            .createMediaSource(MediaItem.fromUri(cam.playableUri()))
        p.setMediaSource(source)
        p.prepare()
        p.playWhenReady = true
    }

    private fun scheduleReconnect() {
        showStatus(R.string.status_reconnecting)
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ connect() }, retryDelayMs)
        retryDelayMs = (retryDelayMs * 2).coerceAtMost(MAX_RETRY_MS)
    }

    private fun showStatus(textRes: Int) {
        statusText.setText(textRes)
        status.visibility = View.VISIBLE
    }

    private companion object {
        const val TAG = "CameraTile"
        const val MIN_RETRY_MS = 1_000L
        const val MAX_RETRY_MS = 15_000L
    }
}
