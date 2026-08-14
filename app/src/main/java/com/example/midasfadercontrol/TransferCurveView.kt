package com.example.midasfadercontrol

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

/**
 * Рисует классический график "вход/выход" (дБ по обеим осям) для Gate или
 * Compressor - как в референсных плагинах (FabFilter, Logic и т.п.).
 *
 * ВАЖНО: это ИЛЛЮСТРАТИВНАЯ визуализация, не лабораторно точная. У нас нет
 * подтверждённой формулы перевода "сырое значение ручки 0..1" -> "реальные
 * дБ" для каждого параметра Pro2 (для части параметров в списке команд есть
 * подсказки вида "0=-50, 0.35=-25, 0.65=0, 1=25", но не для всех, и они
 * нелинейные). Поэтому здесь используется простое линейное отображение
 * 0..1 -> диапазон дБ, которое корректно передаёт НАПРАВЛЕНИЕ и ФОРМУ
 * изменений при вращении ручек, но не является точной калибровкой пульта.
 */
class TransferCurveView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class Mode { GATE, COMPRESSOR }

    var mode: Mode = Mode.COMPRESSOR
        set(v) { field = v; invalidate() }

    // Все значения 0..1 (сырые значения ручек), как их присылает пульт.
    var threshold: Float = 0.5f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }
    var ratioOrRange: Float = 0.3f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }
    var accentColor: Int = Color.parseColor("#ff9f0a")
        set(v) { field = v; invalidate() }

    private val axisMinDb = -60f
    private val axisMaxDb = 0f

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        color = Color.parseColor("#2c2c2e")
    }
    private val axisTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#666666")
        textSize = 22f
    }
    private val curvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
        strokeCap = Paint.Cap.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#111113")
    }

    private fun dbToX(db: Float, left: Float, right: Float): Float =
        left + (db - axisMinDb) / (axisMaxDb - axisMinDb) * (right - left)

    private fun dbToY(db: Float, top: Float, bottom: Float): Float =
        bottom - (db - axisMinDb) / (axisMaxDb - axisMinDb) * (bottom - top)

    /** Считает выходной уровень (дБ) для заданного входного - определяет форму кривой. */
    private fun outputDb(inputDb: Float, thresholdDb: Float): Float {
        return when (mode) {
            Mode.COMPRESSOR -> {
                // Стандартная кривая компрессора: ниже threshold - 1:1,
                // выше - сжатие с наклоном 1/ratio.
                val ratio = 1f + ratioOrRange * 19f // 1:1 .. 20:1
                if (inputDb <= thresholdDb) inputDb
                else thresholdDb + (inputDb - thresholdDb) / ratio
            }
            Mode.GATE -> {
                // Ниже threshold - подавление на величину range (дБ), с
                // мягким переходом (пара дБ) для наглядности, вместо
                // резкого излома.
                val rangeDb = ratioOrRange * 60f // 0 .. -60 дБ подавления
                val knee = 4f
                when {
                    inputDb >= thresholdDb + knee -> inputDb
                    inputDb <= thresholdDb - knee -> inputDb - rangeDb
                    else -> {
                        // плавный переход через колено
                        val t = (inputDb - (thresholdDb - knee)) / (2 * knee)
                        val below = inputDb - rangeDb
                        val above = inputDb
                        below + (above - below) * t
                    }
                }
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val padLeft = 60f
        val padBottom = 40f
        val padTop = 12f
        val padRight = 12f
        val left = padLeft
        val right = w - padRight
        val top = padTop
        val bottom = h - padBottom

        canvas.drawRect(left, top, right, bottom, bgPaint)

        // Сетка + подписи осей (каждые 20 дБ)
        var db = axisMinDb
        while (db <= axisMaxDb) {
            val x = dbToX(db, left, right)
            val y = dbToY(db, top, bottom)
            canvas.drawLine(x, top, x, bottom, gridPaint)
            canvas.drawLine(left, y, right, y, gridPaint)
            if (db.toInt() % 20 == 0) {
                canvas.drawText(db.toInt().toString(), 4f, y + 8f, axisTextPaint)
            }
            db += 10f
        }

        // Кривая передачи
        curvePaint.color = accentColor
        val thresholdDb = axisMinDb + threshold * (axisMaxDb - axisMinDb)
        val path = Path()
        var first = true
        var inDb = axisMinDb
        while (inDb <= axisMaxDb) {
            val outDb = outputDb(inDb, thresholdDb).coerceIn(axisMinDb, axisMaxDb)
            val x = dbToX(inDb, left, right)
            val y = dbToY(outDb, top, bottom)
            if (first) { path.moveTo(x, y); first = false } else path.lineTo(x, y)
            inDb += 1f
        }
        canvas.drawPath(path, curvePaint)

        // Точка текущей рабочей точки - на пороге (как в референсе).
        dotPaint.color = accentColor
        val dotX = dbToX(thresholdDb, left, right)
        val dotY = dbToY(outputDb(thresholdDb, thresholdDb), top, bottom)
        canvas.drawCircle(dotX, dotY, 10f, dotPaint)
    }
}
