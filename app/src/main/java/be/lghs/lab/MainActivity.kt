package be.lghs.lab

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import be.lghs.lab.data.MpdClient
import be.lghs.lab.data.NukiRepository
import be.lghs.lab.data.RendererClient
import be.lghs.lab.data.TecRepository
import be.lghs.lab.data.TransportConfigRepository
import be.lghs.lab.data.TrainRepository
import be.lghs.lab.data.WeatherRepository
import be.lghs.lab.databinding.ActivityMainBinding
import be.lghs.lab.ui.InfoPanel
import be.lghs.lab.ui.MusicPanel
import be.lghs.lab.ui.TransportPanel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var info: InfoPanel
    private lateinit var music: MusicPanel
    private lateinit var transport: TransportPanel
    private val tec = TecRepository()
    private lateinit var transportConfig: TransportConfigRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        hideSystemBars()
        info = InfoPanel(binding.panelInfo)
        music = MusicPanel(binding.panelInfo.music, lifecycleScope)
        transport = TransportPanel(binding.panelTransport, lifecycleScope)
        transportConfig = TransportConfigRepository(this)

        // Tout ne tourne que quand l'app est visible
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Cache local ou fichiers embarqués, puis version GitHub toutes les heures.
                // Seuls bus et trains attendent cette config : le reste démarre tout de suite.
                launch {
                    transportConfig.load()
                    poll("config transports", everyMs = Config.REMOTE_CONFIG_REFRESH_MS, retryMs = 5 * 60_000) {
                        transportConfig.refresh()
                    }
                }
                launch {
                    // Un renderer actif (Spotify, AirPlay…) prime sur MPD
                    combine(MpdClient.nowPlaying(), RendererClient.nowPlaying().onStart { emit(null) }) { mpd, renderer ->
                        renderer ?: mpd
                    }.collect { music.show(it) }
                }
                launch {
                    var n = 0L
                    while (true) {
                        val now = System.currentTimeMillis()
                        info.showClock(now)
                        music.tick()
                        if (n++ % 10 == 0L) transport.render(now / 1000)
                        delay(1_000 - now % 1_000)
                    }
                }
                // Le bridge rate parfois une lecture Bluetooth : on garde le dernier état connu
                // et on n'affiche « Nuki ? » qu'après 3 échecs d'affilée (15 s)
                var nukiFailures = 0
                poll("Nuki", everyMs = 5_000, onError = { if (++nukiFailures >= 3) binding.tilePorte.showDoor(null) }) {
                    binding.tilePorte.showDoor(NukiRepository.fetch())
                    nukiFailures = 0
                }
                poll("météo", everyMs = 10 * 60_000, retryMs = 60_000) {
                    info.showWeather(WeatherRepository.fetch())
                }
                poll("TEC", everyMs = 30_000, onError = { transport.showBusError("TEC indisponible", nowSec()) }) {
                    transport.showBuses(tec.fetch(transportConfig.awaitConfig()), nowSec())
                }
                poll("SNCB", everyMs = 60_000, onError = { transport.showTrains(null, nowSec()) }) {
                    val config = transportConfig.awaitConfig()
                    transport.showTrains(config.trainRoutes.map { TrainRepository.fetch(it, config.trainsPerRoute) }, nowSec())
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        binding.tilePorte.start(Config.cameraPorte)
        binding.tileSas.start(Config.cameraSas)
    }

    // Libère les décodeurs matériels quand l'app n'est plus visible
    override fun onStop() {
        binding.tilePorte.release()
        binding.tileSas.release()
        super.onStop()
    }

    // En launcher, « retour » ne doit pas quitter l'écran d'accueil
    @Deprecated("Remplacé par OnBackPressedDispatcher, mais suffit ici")
    @Suppress("MissingSuperCall")
    override fun onBackPressed() = Unit

    /** Sortie de secours vers les réglages Android : touche Menu/Réglages, ou appui long sur OK. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS -> openSettings()
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> event.startTracking()
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            openSettings()
            return true
        }
        return super.onKeyLongPress(keyCode, event)
    }

    private fun openSettings() {
        startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun kotlinx.coroutines.CoroutineScope.poll(
        name: String,
        everyMs: Long,
        retryMs: Long = everyMs,
        onError: () -> Unit = {},
        block: suspend () -> Unit,
    ) = launch {
        while (true) {
            val wait = try {
                block()
                everyMs
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("Dashboard", "$name: ${e.message}")
                onError()
                retryMs
            }
            delay(wait)
        }
    }

    private fun nowSec() = System.currentTimeMillis() / 1000

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}
