package com.example.midasfadercontrol

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.exp
import kotlin.math.pow

/**
 * Интерактивный график параметрического EQ - частота по X (логарифмически,
 * 20Гц-20кГц), гейн по Y, точки полос (+ HPF/LPF) можно таскать пальцем
 * прямо по графику.
 *
 * ВАЖНО: как и TransferCurveView - это ИЛЛЮСТРАТИВНАЯ визуализация, не
 * лабораторно точная. У нас нет подтверждённой формулы "сырое значение
 * ручки 0..1" -> точные Гц/дБ. НО по сравнению с фото реального экрана
 * пульта ("Overview" для канала) удалось подтвердить кое-что важное:
 * у КАЖДОЙ полосы EQ - СВОЙ СОБСТВЕННЫЙ поддиапазон частот, а не общий
 * 20Гц-20кГц для всех четырёх (см. bandHzRange ниже). Это на порядок
 * точнее передаёт форму и позицию кривой, чем раньше, но САМИ границы
 * диапазонов сняты приблизительно с фотографии шкалы регулятора на
 * экране пульта - не пиксель-в-пиксель точная калибровка.
 */
class EqCurveView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class BandId { BASS, LOW_MID, MID_HIGH, TREBLE }

    companion object {
        private val bandHzRangeStatic = mapOf(
            BandId.BASS to (16f to 400f),
            BandId.LOW_MID to (80f to 2000f),
            BandId.MID_HIGH to (320f to 8000f),
            BandId.TREBLE to (3000f to 25000f)
        )

        /** Сырое значение ручки 0..1 -> реальные Гц для конкретной полосы (для подписей вне графика). */
        fun rawToHzPublic(raw: Float, band: BandId): Float {
            val (minHz, maxHz) = bandHzRangeStatic.getValue(band)
            return exp(ln(minHz) + raw.coerceIn(0f, 1f) * (ln(maxHz) - ln(minHz)))
        }

        /** Аккуратное форматирование Гц для подписи ("84 Hz" / "1.2 kHz"). */
        fun formatHz(hz: Float): String =
            if (hz >= 1000f) "%.1f kHz".format(hz / 1000f) else "%.0f Hz".format(hz)
    }

    data class Band(
        var freq: Float,      // 0..1, сырое значение ручки (в пределах СВОЕГО поддиапазона - см. bandHzRange)
        var gain: Float,      // 0..1, сырое значение ручки (0.5 = условный центр/0дБ)
        var active: Boolean,
        val color: Int
    )

    private val bandHzRange = mapOf(
        BandId.BASS to (16f to 400f),
        BandId.LOW_MID to (80f to 2000f),
        BandId.MID_HIGH to (320f to 8000f),
        BandId.TREBLE to (3000f to 25000f)
    )
    private val bandOrder = listOf(BandId.BASS, BandId.LOW_MID, BandId.MID_HIGH, BandId.TREBLE)

    val bands = Array(4) { Band(0.5f, 0.5f, true, Color.WHITE) }
    var hpFreq: Float = 0f
    var hpOn: Boolean = false
    var lpFreq: Float = 1f
    var lpOn: Boolean = false

    var onNodeDragged: ((Int, Float, Float) -> Unit)? = null

    private val gainRangeDb = 15f
    private val axisMinHz = 20f
    private val axisMaxHz = 20000f

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        color = Color.parseColor("#2c2c2e")
    }
    private val zeroLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.parseColor("#4a4a4c")
    }
    private val axisTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#666666")
        textSize = 22f
    }
    private val curvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#ff9f0a")
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#33ff9f0a")
    }
    private val nodePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val nodeStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.parseColor("#000000")
    }
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#111113")
    }

    private val freqLabels = listOf(20f to "20", 100f to "100", 1000f to "1k", 10000f to "10k", 20000f to "20k")

    private var plotLeft = 0f
    private var plotRight = 0f
    private var plotTop = 0f
    private var plotBottom = 0f

    private fun hzToXNorm(hz: Float): Float {
        val clamped = hz.coerceIn(axisMinHz, axisMaxHz)
        return (ln(clamped) - ln(axisMinHz)) / (ln(axisMaxHz) - ln(axisMinHz))
    }

    private fun xNormToHz(t: Float): Float =
        exp(ln(axisMinHz) + t.coerceIn(0f, 1f) * (ln(axisMaxHz) - ln(axisMinHz)))

    private fun rawToHz(raw: Float, band: BandId): Float {
        val (minHz, maxHz) = bandHzRange.getValue(band)
        return exp(ln(minHz) + raw.coerceIn(0f, 1f) * (ln(maxHz) - ln(minHz)))
    }

    private fun hzToRaw(hz: Float, band: BandId): Float {
        val (minHz, maxHz) = bandHzRange.getValue(band)
        val clamped = hz.coerceIn(minHz, maxHz)
        return ((ln(clamped) - ln(minHz)) / (ln(maxHz) - ln(minHz))).coerceIn(0f, 1f)
    }

    private fun bandFreqToX(raw: Float, band: BandId): Float {
        val hz = rawToHz(raw, band)
        return plotLeft + hzToXNorm(hz) * (plotRight - plotLeft)
    }

    private fun xToBandFreq(x: Float, band: BandId): Float {
        val t = ((x - plotLeft) / (plotRight - plotLeft)).coerceIn(0f, 1f)
        val hz = xNormToHz(t)
        return hzToRaw(hz, band)
    }

    private fun freqToX(f: Float) = plotLeft + f.coerceIn(0f, 1f) * (plotRight - plotLeft)
    private fun xToFreq(x: Float) = ((x - plotLeft) / (plotRight - plotLeft)).coerceIn(0f, 1f)

    private fun gainToY(g: Float): Float {
        val db = (g - 0.5f) * 2f * gainRangeDb
        return plotBottom - (db + gainRangeDb) / (2 * gainRangeDb) * (plotBottom - plotTop)
    }
    private fun yToGain(y: Float): Float {
        val t = ((plotBottom - y) / (plotBottom - plotTop)).coerceIn(0f, 1f)
        val db = t * (2 * gainRangeDb) - gainRangeDb
        return (db / (2 * gainRangeDb) + 0.5f).coerceIn(0f, 1f)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        plotLeft = 44f
        plotRight = w - 12f
        plotTop = 12f
        plotBottom = h - 28f

        canvas.drawRect(plotLeft, plotTop, plotRight, plotBottom, bgPaint)

        for ((hz, label) in freqLabels) {
            val x = plotLeft + hzToXNorm(hz) * (plotRight - plotLeft)
            canvas.drawLine(x, plotTop, x, plotBottom, gridPaint)
            canvas.drawText(label, (x - 12).coerceAtLeast(plotLeft), h - 6f, axisTextPaint)
        }
        for (db in listOf(-15f, 0f, 15f)) {
            val y = plotBottom - (db + gainRangeDb) / (2 * gainRangeDb) * (plotBottom - plotTop)
            canvas.drawLine(plotLeft, y, plotRight, y, if (db == 0f) zeroLinePaint else gridPaint)
            canvas.drawText("%+d".format(db.toInt()), 2f, y + 7f, axisTextPaint)
        }

        val path = Path()
        var first = true
        var tx = 0f
        while (tx <= 1f) {
            var totalDb = 0f
            for ((i, band) in bands.withIndex()) {
                if (!band.active) continue
                val bandId = bandOrder[i]
                val bandDb = (band.gain - 0.5f) * 2f * gainRangeDb
                val bandXNorm = hzToXNorm(rawToHz(band.freq, bandId))
                val dist = abs(tx - bandXNorm)
                val widthFactor = 0.10f
                val influence = Math.E.toFloat().pow(-(dist * dist) / (2 * widthFactor * widthFactor))
                totalDb += bandDb * influence
            }
            val x = plotLeft + tx * (plotRight - plotLeft)
            val y = gainToY((totalDb / (2 * gainRangeDb) + 0.5f).coerceIn(0f, 1f))
            if (first) { path.moveTo(x, y); first = false } else path.lineTo(x, y)
            tx += 0.01f
        }
        val fillPath = Path(path)
        fillPath.lineTo(plotRight, gainToY(0.5f))
        fillPath.lineTo(plotLeft, gainToY(0.5f))
        fillPath.close()
        canvas.drawPath(fillPath, fillPaint)
        canvas.drawPath(path, curvePaint)

        val hpX = freqToX(hpFreq)
        val hpY = plotBottom - 10f
        nodePaint.color = if (hpOn) Color.parseColor("#34c759") else Color.parseColor("#666666")
        canvas.drawCircle(hpX, hpY, 14f, nodePaint)
        canvas.drawCircle(hpX, hpY, 14f, nodeStrokePaint)

        val lpX = freqToX(lpFreq)
        nodePaint.color = if (lpOn) Color.parseColor("#af52de") else Color.parseColor("#666666")
        canvas.drawCircle(lpX, hpY, 14f, nodePaint)
        canvas.drawCircle(lpX, hpY, 14f, nodeStrokePaint)

        for ((i, band) in bands.withIndex()) {
            val x = bandFreqToX(band.freq, bandOrder[i])
            val y = gainToY(band.gain)
            nodePaint.color = if (band.active) band.color else Color.parseColor("#555555")
            canvas.drawCircle(x, y, 16f, nodePaint)
            canvas.drawCircle(x, y, 16f, nodeStrokePaint)
        }
    }

    private var draggingNode = -3

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                draggingNode = findNearestNode(event.x, event.y)
                if (draggingNode != -3) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                if (draggingNode == -3) return false
                when {
                    draggingNode == -1 -> {
                        val freq = xToFreq(event.x)
                        hpFreq = freq
                        onNodeDragged?.invoke(-1, freq, 0f)
                    }
                    draggingNode == -2 -> {
                        val freq = xToFreq(event.x)
                        lpFreq = freq
                        onNodeDragged?.invoke(-2, freq, 0f)
                    }
                    else -> {
                        val bandId = bandOrder[draggingNode]
                        val freq = xToBandFreq(event.x, bandId)
                        val gain = yToGain(event.y)
                        bands[draggingNode].freq = freq
                        bands[draggingNode].gain = gain
                        onNodeDragged?.invoke(draggingNode, freq, gain)
                    }
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                draggingNode = -3
                return true
            }
        }
        return false
    }

    private fun findNearestNode(x: Float, y: Float): Int {
        val touchRadius = 60f
        var best = -3
        var bestDist = Float.MAX_VALUE
        for ((i, band) in bands.withIndex()) {
            val bx = bandFreqToX(band.freq, bandOrder[i])
            val by = gainToY(band.gain)
            val d = distance(x, y, bx, by)
            if (d < touchRadius && d < bestDist) { best = i; bestDist = d }
        }
        val hpX = freqToX(hpFreq)
        val hpY = plotBottom - 10f
        val dHp = distance(x, y, hpX, hpY)
        if (dHp < touchRadius && dHp < bestDist) { best = -1; bestDist = dHp }
        val lpX = freqToX(lpFreq)
        val dLp = distance(x, y, lpX, hpY)
        if (dLp < touchRadius && dLp < bestDist) { best = -2; bestDist = dLp }
        return best
    }

    private fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x1 - x2
        val dy = y1 - y2
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }
}
