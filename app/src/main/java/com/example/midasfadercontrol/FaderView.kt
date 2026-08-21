package com.example.midasfadercontrol

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * Вертикальный фейдер, рисуется сам (Canvas), без старого трюка
 * "SeekBar с rotation=270 + подгонка ширины через viewTreeObserver под
 * реальную высоту контейнера". Тот трюк был источником части багов и
 * лишнего кода, разбросанного по всему проекту (везде, где есть фейдер,
 * приходилось повторять один и тот же listener на layout). Здесь фейдер
 * просто занимает весь выделенный ему прямоугольник и рисует себя внутри
 * него - высота/ширина обычные, без поворота.
 *
 * Показывает реальную шкалу в дБ (та же кривая, что и во всём остальном
 * приложении - см. MainActivity.rawToFaderDb) - деления идут неравномерно,
 * как на настоящем физическом фейдере: гуще внизу, реже наверху, потому
 * что кривая логарифмическая, а не линейная.
 */
class FaderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /** Текущее сырое значение 0.00-1.00 - как у обычного SeekBar.progress/max, только сразу в долях. */
    var value: Float = 0f
        set(v) {
            field = v.coerceIn(0f, 1f)
            invalidate()
        }

    /** Вызывается при каждом изменении пальцем (аналог onProgressChanged с fromUser=true). */
    var onValueChanged: ((Float) -> Unit)? = null
    /** Вызывается в момент начала касания (аналог onStartTrackingTouch). */
    var onDragStarted: (() -> Unit)? = null
    /** Вызывается при отпускании пальца (аналог onStopTrackingTouch) - именно тут обычно шлют финальное значение на пульт. */
    var onDragFinished: ((Float) -> Unit)? = null

    /** Показывать ли шкалу дБ слева от трека. По умолчанию выключено - на
     * телефонной ширине полосы (см. dimens.xml) шкала рискует не влезть,
     * не проверено на реальном устройстве. Планшет (больше места) можно
     * включить отдельно после проверки, что подписи не обрезаются. */
    var showScale: Boolean = false
        set(v) { field = v; invalidate() }

    var fillColor: Int = Color.parseColor("#ff9f0a")
        set(v) { field = v; invalidate() }

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#161618") }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val unityPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#ff9f0a")
        alpha = 130
    }
    private val capPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#e3e3e6") }
    private val capRidgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#9a9a9d") }
    private val scaleTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#5f5f62")
        textSize = 7f * context.resources.displayMetrics.scaledDensity
        textAlign = Paint.Align.RIGHT
    }
    private val unityTextPaint = Paint(scaleTextPaint).apply { color = Color.parseColor("#ff9f0a") }

    // Позиции делений шкалы (доля от низа трека, 0..1) и подписи - вычислены
    // как обратная функция от rawToDb для круглых значений дБ, поэтому сами
    // деления идут неравномерно, как на реальном фейдере.
    private val scaleTicks = listOf(
        1.0f to "+10",
        0.75f to "0",
        0.5f to "-10",
        0.375f to "-20",
        0.25f to "-30",
        0.0f to "-∞"
    )

    // Пропорции ВСЕХ частей считаются от реальной измеренной ширины вида
    // (не от фиксированных dp) - тогда один и тот же класс одинаково хорошо
    // выглядит и на узкой телефонной полосе, и на широкой планшетной, без
    // отдельных наборов констант под каждое устройство.
    private val scaleWidthFraction = 0.34f   // доля ширины под подписи дБ (если showScale)
    private val capOverhangFraction = 0.30f  // насколько колпачок шире трека с каждой стороны
    private val trackWidthFraction = 0.34f   // ширина самого трека

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val d = resources.displayMetrics.density
        val w = width.toFloat()
        val scaleWidth = if (showScale) w * scaleWidthFraction else 0f
        val remaining = w - scaleWidth
        val capOverhang = remaining * capOverhangFraction
        val trackWidth = remaining * trackWidthFraction
        val trackLeft = scaleWidth + capOverhang
        val trackRight = trackLeft + trackWidth
        val capHeight = (24f * d).coerceAtMost(height / 10f)
        val top = capHeight / 2
        val bottom = height - capHeight / 2
        val travel = bottom - top
        if (travel <= 0) return

        if (showScale) {
            for ((frac, label) in scaleTicks) {
                val y = bottom - travel * frac
                val paint = if (label == "0") unityTextPaint else scaleTextPaint
                canvas.drawText(label, scaleWidth - 4f * d, y + paint.textSize / 3, paint)
            }
        }

        canvas.drawRect(trackLeft, top, trackRight, bottom, trackPaint)

        val unityY = bottom - travel * 0.75f
        canvas.drawRect(trackLeft - 3f * d, unityY - 1f * d, trackRight + 3f * d, unityY + 1f * d, unityPaint)

        val fillTop = bottom - travel * value
        fillPaint.color = fillColor
        canvas.drawRect(trackLeft, fillTop, trackRight, bottom, fillPaint)

        val capY = fillTop
        val capLeft = trackLeft - capOverhang
        val capRight = trackRight + capOverhang
        val capTop = capY - capHeight / 2
        val capBottom = capY + capHeight / 2
        val capRect = RectF(capLeft, capTop, capRight, capBottom)
        canvas.drawRoundRect(capRect, 2f * d, 2f * d, capPaint)
        val ridgeInset = (capRight - capLeft) * 0.12f
        for (i in 0 until 3) {
            val ridgeY = capTop + (capBottom - capTop) * (0.28f + i * 0.22f)
            canvas.drawRect(capLeft + ridgeInset, ridgeY, capRight - ridgeInset, ridgeY + 1.5f * d, capRidgePaint)
        }
    }

    private var dragging = false

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val capHeight = (24f * resources.displayMetrics.density).coerceAtMost(height / 10f)
        val top = capHeight / 2
        val bottom = height - capHeight / 2
        val travel = bottom - top
        if (travel <= 0) return false

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                dragging = true
                parent?.requestDisallowInterceptTouchEvent(true)
                onDragStarted?.invoke()
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging) return false
                val frac = ((bottom - event.y) / travel).coerceIn(0f, 1f)
                value = frac
                onValueChanged?.invoke(frac)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    dragging = false
                    parent?.requestDisallowInterceptTouchEvent(false)
                    onDragFinished?.invoke(value)
                }
            }
        }
        return true
    }
}
