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
import be.lghs.lab.data.TrainBoard
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
    private val sncbColor = ContextCompat.getColor(context, R.color.sncb)

    /** Une ligne de la liste, bus ou train. */
    private class Row(
        val epochSec: Long,
        val line: String,
        val isTrain: Boolean,
        val destination: String,
        val detail: String,
        val delaySec: Int,
        val canceled: Boolean,
        val last: Boolean,
    )

    private var buses: List<Row> = emptyList()
    private var trains: List<Row> = emptyList()
    private var busError: String? = null
    private var trainError: String? = null

    /** Nombre de vues de la liste « originale » (avant le repère et la copie). */
    private var listLength = 0
    private var looping = false

    init {
        scope.launch { autoScroll() }
    }

    fun showBuses(list: List<BusDeparture>, nowSec: Long) {
        buses = list.map {
            Row(it.epochSec, it.line, false, it.destination.ifEmpty { "Ligne ${it.line}" }, it.stop,
                it.delaySec, canceled = false, last = it.last)
        }
        busError = null
        render(nowSec)
    }

    fun showBusError(message: String, nowSec: Long) {
        busError = message
        render(nowSec)
    }

    fun showTrains(boards: List<TrainBoard>?, nowSec: Long) {
        if (boards == null) {
            trainError = "SNCB indisponible"
        } else {
            trainError = null
            trains = boards.flatMap { board ->
                board.departures.map {
                    Row(it.epochSec, it.line, true, board.route.to,
                        "${shortStation(board.route.from)} · v${it.platform}", it.delaySec, it.canceled, it.last)
                }
            }
        }
        render(nowSec)
    }

    /**
     * Recalcule la liste (minutes restantes comprises) sans refaire de requête.
     * Pour le défilement infini, la liste est suivie d'un repère « début de la liste »
     * puis d'une seconde copie, visibles seulement si la liste dépasse.
     */
    fun render(nowSec: Long) {
        val container = b.departureRows
        container.removeAllViews()
        val rows = (buses + trains).filter { it.epochSec >= nowSec - 30 }.sortedBy { it.epochSec }
        val messages = listOfNotNull(busError, trainError)
            .ifEmpty { if (rows.isEmpty()) listOf("Aucun départ") else emptyList() }
        fun addList() {
            rows.forEach { container.addView(bind(it, nowSec)) }
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

    private fun applyLoopVisibility() {
        val container = b.departureRows
        for (i in listLength until container.childCount) {
            container.getChildAt(i).visibility = if (looping) View.VISIBLE else View.GONE
        }
    }

    private fun bind(row: Row, nowSec: Long): View {
        val v = RowDepartureBinding.inflate(inflater, b.departureRows, false)
        v.line.text = row.line
        v.line.backgroundTintList = android.content.res.ColorStateList.valueOf(if (row.isTrain) sncbColor else tecColor)
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
            minutes < 30 -> "$minutes min"
            else -> hhmm.format(Date(row.epochSec * 1000))
        }
        v.time.setTextColor(
            when {
                row.canceled -> late
                minutes <= 2 -> accent
                minutes < 30 -> onTime
                else -> text
            }
        )
        if (row.canceled) {
            v.time.paintFlags = v.time.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
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

    private fun placeholder(message: String) = TextView(context).apply {
        text = message
        setTextColor(dim)
        textSize = 14f
        setPadding(0, 6, 0, 6)
    }

    private companion object {
        const val SPEED_DP_PER_SEC = 24f
        const val PAUSE_NS = 3_000_000_000L
        const val IDLE_CHECK_MS = 1_000L
    }
}
