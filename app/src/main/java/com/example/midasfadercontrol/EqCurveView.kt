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
import kotlin.math.pow

/**
 * Интерактивный график параметрического EQ - частота по X, гейн по Y,
 * точки полос (+ HPF/LPF) можно таскать пальцем прямо по графику.
 *
 * ВАЖНО: как и TransferCurveView - это ИЛЛЮСТРАТИВНАЯ визуализация. У нас
 * нет подтверждённой формулы "сырое значение ручки 0..1" -> реальные Гц/дБ
 * ни для одного параметра EQ (в списке команд для них нет подсказок с
 * точками привязки, в отличие от threshold/attack/release компрессора).
 * Ось X размечена под привычные отметки 20Гц-20кГц ЛОГАРИФМИЧЕСКИ (обычная
 * практика для звуковых графиков), но перевод самого сырого значения ручки
 * в позицию на этой оси - ПРЯМОЙ (линейный), не откалиброванный под
 * реальный отклик пульта. Форма и направление движения корректны,
 * абсолютные подписи Гц/дБ - ориентировочные.
 */
class EqCurveView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    data class Band(
        var freq: Float,      // 0..1, сырое значение ручки
        var gain: Float,      // 0..1, сырое значение ручки (0.5 = условный центр/0дБ)
        var active: Boolean,
        val color: Int
    )

    val bands = Array(4) { Band(0.5f, 0.5f, true, Color.WHITE) }
    var hpFreq: Float = 0f
    var hpOn: Boolean = false
    var lpFreq: Float = 1f
    var lpOn: Boolean = false

    /** (bandIndex, freq, gain) - bandIndex == -1 для HPF, -2 для LPF. */
    var onNodeDragged: ((Int, Float, Float) -> Unit)? = null

    private val gainRangeDb = 15f // Y: -15..+15 дБ (условная шкала, см. заметку выше)

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

    // Метки оси частоты - чисто декоративные (см. заметку в шапке файла).
    private val freqLabels = listOf(0f to "20", 0.25f to "100", 0.5f to "1k", 0.75f to "10k", 1f to "20k")

    private var plotLeft = 0f
    private var plotRight = 0f
    private var plotTop = 0f
    private var plotBottom = 0f

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

        // Вертикальная сетка по частоте + подписи снизу.
        for ((f, label) in freqLabels) {
            val x = freqToX(f)
            canvas.drawLine(x, plotTop, x, plotBottom, gridPaint)
            canvas.drawText(label, (x - 12).coerceAtLeast(plotLeft), h - 6f, axisTextPaint)
        }
        // Горизонтальная сетка по гейну (-15/0/+15) + подписи слева.
        for (db in listOf(-15f, 0f, 15f)) {
            val y = plotBottom - (db + gainRangeDb) / (2 * gainRangeDb) * (plotBottom - plotTop)
            canvas.drawLine(plotLeft, y, plotRight, y, if (db == 0f) zeroLinePaint else gridPaint)
            canvas.drawText("%+d".format(db.toInt()), 2f, y + 7f, axisTextPaint)
        }

        // Суммарная кривая - упрощённое визуальное приближение (сумма
        // гауссоподобных "колоколов" каждой активной полосы), НЕ точная
        // модель фильтров пульта.
        val path = Path()
        var first = true
        var fx = 0f
        while (fx <= 1f) {
            var totalDb = 0f
            for (band in bands) {
                if (!band.active) continue
                val bandDb = (band.gain - 0.5f) * 2f * gainRangeDb
                val dist = abs(fx - band.freq)
                val widthFactor = 0.12f
                val influence = Math.E.toFloat().pow(-(dist * dist) / (2 * widthFactor * widthFactor))
                totalDb += bandDb * influence
            }
            val x = freqToX(fx)
            val y = gainToY((totalDb / (2 * gainRangeDb) + 0.5f).coerceIn(0f, 1f))
            if (first) { path.moveTo(x, y); first = false } else path.lineTo(x, y)
            fx += 0.01f
        }
        val fillPath = Path(path)
        fillPath.lineTo(plotRight, gainToY(0.5f))
        fillPath.lineTo(plotLeft, gainToY(0.5f))
        fillPath.close()
        canvas.drawPath(fillPath, fillPaint)
        canvas.drawPath(path, curvePaint)

        // HPF/LPF - маркеры только по X (у них нет своего гейна), у нижнего края.
        val hpX = freqToX(hpFreq)
        val hpY = plotBottom - 10f
        nodePaint.color = if (hpOn) Color.parseColor("#34c759") else Color.parseColor("#666666")
        canvas.drawCircle(hpX, hpY, 14f, nodePaint)
        canvas.drawCircle(hpX, hpY, 14f, nodeStrokePaint)

        val lpX = freqToX(lpFreq)
        nodePaint.color = if (lpOn) Color.parseColor("#af52de") else Color.parseColor("#666666")
        canvas.drawCircle(lpX, hpY, 14f, nodePaint)
        canvas.drawCircle(lpX, hpY, 14f, nodeStrokePaint)

        // Точки полос - перетаскиваемые.
        for (band in bands) {
            val x = freqToX(band.freq)
            val y = gainToY(band.gain)
            nodePaint.color = if (band.active) band.color else Color.parseColor("#555555")
            canvas.drawCircle(x, y, 16f, nodePaint)
            canvas.drawCircle(x, y, 16f, nodeStrokePaint)
        }
    }

    private var draggingNode = -3 // -3 = ничего, -1 = HPF, -2 = LPF, 0..3 = полоса

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
                val freq = xToFreq(event.x)
                when {
                    draggingNode == -1 -> { hpFreq = freq; onNodeDragged?.invoke(-1, freq, 0f) }
                    draggingNode == -2 -> { lpFreq = freq; onNodeDragged?.invoke(-2, freq, 0f) }
                    else -> {
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
            val bx = freqToX(band.freq)
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
