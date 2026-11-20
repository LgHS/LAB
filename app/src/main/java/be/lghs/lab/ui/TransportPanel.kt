package be.lghs.lab.ui

import android.graphics.Paint
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.text.inSpans
import androidx.lifecycle.LifecycleCoroutineScope
import be.lghs.lab.R
import be.lghs.lab.data.BusDeparture
import be.lghs.lab.data.Reach
import be.lghs.lab.data.TrainBoard
import be.lghs.lab.data.TrainRoute
import be.lghs.lab.data.TravelMode
import be.lghs.lab.data.reach
import be.lghs.lab.databinding.PanelTransportBinding
import be.lghs.lab.databinding.RowDepartureBinding
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** Panneau bas-gauche : bus TEC et trains SNCB dans une seule liste triée par heure. */
class TransportPanel(private val b: PanelTransportBinding, scope: LifecycleCoroutineScope) {
    private val context = b.root.context
    private val inflater = LayoutInflater.from(context)
    private val hhmm = SimpleDateFormat("HH:mm", Locale.FRANCE)
    private val late = ContextCompat.getColor(context, R.color.late)
    private val onTime = ContextCompat.getColor(context, R.color.ontime)
    private val accent = ContextCompat.getColor(context, R.color.accent)
    private val text = ContextCompat.getColor(context, R.color.text)
    private val dim = ContextCompat.getColor(context, R.color.text_dim)
    private val tecColor = ContextCompat.getColor(context, R.color.tec)
    private val tramColor = ContextCompat.getColor(context, R.color.tram)
    private val sncbColor = ContextCompat.getColor(context, R.color.sncb)

    /** Une ligne de la liste, bus ou train. */
    private class Row(
        /** Heure de départ réelle (retard compris), pour le chrono et l'heure affichée. */
        val epochSec: Long,
        val line: String,
        val isTrain: Boolean,
        val destination: String,
        val detail: String,
        val delaySec: Int,
        val canceled: Boolean,
        val last: Boolean,
        /** Minutes à pied jusqu'au quai ou à la gare, null = inconnu. */
        val minutesFromSpace: Int?,
        val travelMode: TravelMode,
        /** Trajet SNCB (« from|to »), pour retrouver le train suivant d'un train supprimé. */
        val route: String? = null,
        /** Train supprimé : heure du suivant sur le même trajet, affiché juste en dessous. */
        val nextSec: Long? = null,
    )

    private var buses: List<Row> = emptyList()
    private var trains: List<Row> = emptyList()
    private var busError: String? = null
    private var trainError: String? = null

    /** Derniers trains reçus par trajet (gare de départ, d'arrivée), avec l'heure de réception. */
    private val trainBoards = mutableMapOf<Pair<String, String>, Pair<TrainBoard, Long>>()

    /** Trajets de la config actuelle (temps d'accès compris), connus après la première lecture. */
    private var trainRoutes: Map<Pair<String, String>, TrainRoute> = emptyMap()

    /** À sauver sur la box ([be.lghs.lab.data.TrainCache]). */
    val savedTrains: List<Pair<TrainBoard, Long>> get() = trainBoards.values.toList()

    /** Nombre de vues de la liste « originale » (avant le repère et la copie). */
    private var listLength = 0
    private var looping = false

    init {
        scope.launch { autoScroll() }
    }

    fun showBuses(list: List<BusDeparture>, nowSec: Long) {
        buses = list.map {
            Row(it.epochSec, it.line, false, it.destination.ifEmpty { "Ligne ${it.line}" }, it.stop,
                it.delaySec, canceled = false, last = it.last, minutesFromSpace = it.minutesFromSpace,
                travelMode = it.travelMode)
        }
        busError = null
        render(nowSec)
    }

    fun showBusError(message: String, nowSec: Long) {
        busError = message
        render(nowSec)
    }

    /**
     * Une lecture par trajet, `null` = échec. iRail renvoie souvent des 504 passagers : un trajet
     * en échec garde ses derniers trains, et on ne le signale qu'après [TRAIN_STALE_SEC].
     */
    fun showTrains(results: Map<TrainRoute, TrainBoard?>, nowSec: Long) {
        trainRoutes = results.keys.associateBy(::key)
        trainBoards.keys.retainAll(trainRoutes.keys) // trajet retiré de transport.json
        results.forEach { (route, board) -> if (board != null) trainBoards[key(route)] = board to nowSec }
        val missing = trainRoutes.keys.any { it !in trainBoards }
        val oldest = trainBoards.values.minOfOrNull { it.second }
        trainError = when {
            missing -> "SNCB indisponible"
            oldest != null && nowSec - oldest >= TRAIN_STALE_SEC -> "SNCB : pas à jour depuis ${hhmm.format(Date(oldest * 1000))}"
            else -> null
        }
        buildTrainRows()
        render(nowSec)
    }

    /** Trains gardés sur la box, affichés en attendant la première réponse d'iRail. */
    fun restoreTrains(saved: List<Pair<TrainBoard, Long>>, nowSec: Long) {
        saved.forEach { trainBoards[key(it.first.route)] = it }
        buildTrainRows()
        render(nowSec)
    }

    private fun buildTrainRows() {
        trains = trainBoards.flatMap { (key, entry) ->
            val board = entry.first
            val route = trainRoutes[key] ?: board.route
            board.departures.mapIndexed { i, d ->
                // iRail donne l'heure prévue : on affiche l'heure réelle, comme pour les bus
                val next = if (d.canceled) board.departures.drop(i + 1).firstOrNull { !it.canceled } else null
                Row(d.epochSec + d.delaySec, d.line, true, route.to,
                    stationAndPlatform(route.from, d.platform), d.delaySec, d.canceled, d.last,
                    route.minutesFromSpace, route.travelMode,
                    route = "${route.from}|${route.to}", nextSec = next?.let { it.epochSec + it.delaySec })
            }
        }
    }

    private fun key(route: TrainRoute) = route.from to route.to

    /**
     * Recalcule la liste (minutes restantes comprises) sans refaire de requête.
     * Pour le défilement infini, la liste est suivie d'un repère « début de la liste »
     * puis d'une seconde copie, visibles seulement si la liste dépasse.
     */
    fun render(nowSec: Long) {
        val container = b.departureRows
        container.removeAllViews()
        val rows = ordered((buses + trains)
            .filter { it.epochSec >= nowSec - 30 }
            // Départs qu'on n'a plus le temps d'attraper depuis le space : masqués
            .filter { reach(it.epochSec, nowSec, it.minutesFromSpace) != Reach.TOO_LATE }
            .sortedBy { it.epochSec })
        val messages = listOfNotNull(busError, trainError)
            .ifEmpty { if (rows.isEmpty()) listOf("Aucun départ") else emptyList() }
        fun addList() {
            rows.forEach { (row, replacement) -> container.addView(bind(row, nowSec, replacement)) }
            messages.forEach { container.addView(placeholder(it)) }
        }
        addList()
        listLength = container.childCount
        if (rows.isNotEmpty()) {
            container.addView(loopMarker())
            addList()
        }
        applyLoopVisibility()
    }

    /**
     * Ordre chronologique, sauf le train qui suit un train supprimé (même trajet) : remonté
     * juste sous lui, marqué comme remplaçant.
     */
    private fun ordered(rows: List<Row>): List<Pair<Row, Boolean>> {
        val replacements = rows.filter { it.canceled && it.nextSec != null }.mapNotNull { canceled ->
            rows.firstOrNull { it.route == canceled.route && it.epochSec == canceled.nextSec && !it.canceled }
                ?.let { canceled to it }
        }.toMap()
        val moved = replacements.values.toSet()
        return rows.filter { it !in moved }.flatMap { row ->
            listOfNotNull(row to false, replacements[row]?.let { it to true })
        }
    }

    private fun applyLoopVisibility() {
        val container = b.departureRows
        for (i in listLength until container.childCount) {
            container.getChildAt(i).visibility = if (looping) View.VISIBLE else View.GONE
        }
    }

    private fun bind(row: Row, nowSec: Long, replacement: Boolean = false): View {
        val v = RowDepartureBinding.inflate(inflater, b.departureRows, false)
        // Train suivant d'un train supprimé : « ↳ » et léger retrait, sous la ligne barrée
        if (replacement) {
            v.replaces.visibility = View.VISIBLE
            v.root.setPaddingRelative((REPLACEMENT_INDENT_DP * context.resources.displayMetrics.density).toInt(), 0, 0, 0)
        }
        v.line.text = row.line
        // Tram TEC (T1…) en orange, pour le distinguer des bus jaunes
        val badge = when {
            row.isTrain -> sncbColor
            row.line.startsWith("T") -> tramColor
            else -> tecColor
        }
        v.line.backgroundTintList = android.content.res.ColorStateList.valueOf(badge)
        v.line.setTextColor(if (row.isTrain) 0xFFFFFFFF.toInt() else 0xFF111111.toInt())
        v.destination.text = row.destination
        v.last.visibility = if (row.last) View.VISIBLE else View.GONE

        val delayMin = (row.delaySec / 60.0).roundToInt()
        v.detail.text = SpannableStringBuilder(row.detail).apply {
            when {
                row.canceled -> inSpans(ForegroundColorSpan(late)) { append(" supprimé") }
                delayMin >= 1 -> inSpans(ForegroundColorSpan(late)) { append(" +$delayMin'") }
            }
        }

        val minutes = ((row.epochSec - nowSec) / 60.0).roundToInt()
        v.time.text = when {
            minutes <= 0 -> "à quai"
            minutes < 60 -> "$minutes min"
            else -> "%d h %02d".format(minutes / 60, minutes % 60)
        }
        // Heure de départ réelle (retard compris), toujours affichée
        v.clock.text = hhmm.format(Date(row.epochSec * 1000))
        // Avec le temps pour y aller : jaune s'il faut partir maintenant, vert sinon (ceux qu'on
        // ne peut plus attraper sont masqués, le rouge ne sert qu'entre deux rafraîchissements)
        val reach = reach(row.epochSec, nowSec, row.minutesFromSpace)
        v.time.setTextColor(
            when {
                row.canceled -> late
                reach == Reach.TOO_LATE -> late
                reach == Reach.HURRY -> accent
                reach == null && minutes <= 2 -> accent
                minutes < 30 -> onTime
                else -> text
            }
        )
        // À pied ou à vélo, de la couleur du chrono
        if (reach != null && !row.canceled) {
            v.travelMode.setImageResource(if (row.travelMode == TravelMode.BIKE) R.drawable.ic_bike else R.drawable.ic_walk)
            v.travelMode.imageTintList = android.content.res.ColorStateList.valueOf(v.time.currentTextColor)
            v.travelMode.visibility = View.VISIBLE
        }
        if (row.canceled) {
            v.time.paintFlags = v.time.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            v.clock.paintFlags = v.clock.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            v.destination.alpha = 0.5f
        }
        return v.root
    }

    /**
     * Défilement continu et infini : quand la copie arrive en haut, on revient d'autant en
     * arrière (image identique, donc invisible), avec une pause chaque fois que le début
     * de la liste est en haut. Ne fait rien si tout tient.
     */
    private suspend fun autoScroll() {
        val scroll = b.departuresScroll
        val container = b.departureRows
        val speedPxPerSec = SPEED_DP_PER_SEC * context.resources.displayMetrics.density
        var position = 0f
        var pausedUntil = 0L
        var lastFrame = awaitFrame()
        while (true) {
            val frame = awaitFrame()
            val dt = (frame - lastFrame) / 1e9f
            lastFrame = frame
            if (listLength == 0 || container.childCount <= listLength) {
                if (looping) { looping = false; applyLoopVisibility() }
                position = 0f
                scroll.scrollTo(0, 0)
                delay(IDLE_CHECK_MS)
                lastFrame = awaitFrame()
                continue
            }
            // Liste reconstruite (toutes les 10 s) mais pas encore mesurée : on attend
            if (container.isLayoutRequested || scroll.isLayoutRequested) continue
            val shouldLoop = container.getChildAt(listLength - 1).bottom > scroll.height
            if (shouldLoop != looping) {
                looping = shouldLoop
                applyLoopVisibility()
                position = 0f
                pausedUntil = frame + PAUSE_NS
            }
            if (!looping) {
                scroll.scrollTo(0, 0)
                delay(IDLE_CHECK_MS)
                lastFrame = awaitFrame()
                continue
            }
            // Début de la copie = longueur d'un tour (liste + repère)
            val loopLength = container.getChildAt(listLength + 1).top
            if (loopLength <= 0 || frame < pausedUntil) continue
            position += speedPxPerSec * dt
            if (position >= loopLength) {
                position %= loopLength
                pausedUntil = frame + PAUSE_NS
            }
            scroll.scrollTo(0, position.toInt())
        }
    }

    private fun loopMarker() = TextView(context).apply {
        text = "↻  Début de la liste"
        setTextColor(dim)
        textSize = 11f
        letterSpacing = 0.08f
        isAllCaps = true
        gravity = android.view.Gravity.CENTER
        val pad = (10 * context.resources.displayMetrics.density).toInt()
        setPadding(0, pad, 0, pad)
    }

    private fun shortStation(name: String) = name.removePrefix("Liège-").replace("Saint-", "St-")

    /** « St-Lambert · Voie 2 » ; iRail renvoie « ? » tant que la voie n'est pas connue. */
    private fun stationAndPlatform(station: String, platform: String) =
        if (platform.isBlank() || platform == "?") shortStation(station)
        else "${shortStation(station)} · Voie $platform"

    private fun placeholder(message: String) = TextView(context).apply {
        text = message
        setTextColor(dim)
        textSize = 17f
        setPadding(0, 6, 0, 6)
    }

    private companion object {
        const val SPEED_DP_PER_SEC = 24f
        const val PAUSE_NS = 3_000_000_000L
        const val IDLE_CHECK_MS = 1_000L
        const val TRAIN_STALE_SEC = 5 * 60L
        const val REPLACEMENT_INDENT_DP = 20
    }
}
