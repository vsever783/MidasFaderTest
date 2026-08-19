package com.example.midasfadercontrol

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

/**
 * Simple rotary knob, styled after a mixer-console gain pot.
 * Value range is always 0.0..1.0 (matches the float range the Pro2
 * fader/gain commands expect).
 *
 * Interaction: drag vertically (standard mixer/DAW knob UX — dragging
 * left/right is imprecise for fine control, up/down is the convention
 * everyone already knows from Pro Tools, Logic, etc.).
 */
class RotaryKnobView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var value: Float = 0.5f
        set(v) {
            field = v.coerceIn(0f, 1f)
            invalidate()
        }

    var onValueChanged: ((Float) -> Unit)? = null

    private val startAngleDeg = 135.0
    private val sweepAngleDeg = 270.0

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        color = Color.parseColor("#3a3a3c")
        strokeCap = Paint.Cap.ROUND
    }

    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        color = Color.parseColor("#ff9f0a")
        strokeCap = Paint.Cap.ROUND
    }

    private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#2c2c2e")
    }

    private val indicatorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
        color = Color.parseColor("#ff9f0a")
        strokeCap = Paint.Cap.ROUND
    }

    private var dragStartY = 0f
    private var dragStartValue = 0f

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                dragStartY = event.y
                dragStartValue = value
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                // ВАЖНО (безопасность на живом мероприятии): было 150px на
                // весь диапазон 0..1 - слишком чувствительно, случайное
                // короткое движение пальцем могло дёрнуть параметр (gain,
                // частоту EQ и т.п.) сразу в другой конец шкалы. Теперь
                // нужно ~750px - примерно весь экран телефона по вертикали,
                // то есть даже сознательный длинный жест даёт плавное,
                // предсказуемое изменение, а не рывок.
                val deltaY = dragStartY - event.y
                val deltaValue = deltaY / 750f
                value = dragStartValue + deltaValue
                onValueChanged?.invoke(value)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = minOf(width, height).toFloat()
        val strokeInset = 12f
        val rect = RectF(strokeInset, strokeInset, size - strokeInset, size - strokeInset)

        // фоновое кольцо (весь диапазон)
        canvas.drawArc(rect, startAngleDeg.toFloat(), sweepAngleDeg.toFloat(), false, ringPaint)

        // заполненная дуга (текущее значение)
        canvas.drawArc(rect, startAngleDeg.toFloat(), (sweepAngleDeg * value).toFloat(), false, valuePaint)

        // сам "пятак" крутилки
        val centerX = size / 2f
        val centerY = size / 2f
        val knobRadius = size / 2f - 24f
        canvas.drawCircle(centerX, centerY, knobRadius, knobPaint)

        // индикатор поворота
        val angleRad = Math.toRadians(startAngleDeg + sweepAngleDeg * value)
        val indicatorLen = knobRadius - 8f
        val startR = knobRadius * 0.35f
        val x1 = centerX + (startR * cos(angleRad)).toFloat()
        val y1 = centerY + (startR * sin(angleRad)).toFloat()
        val x2 = centerX + (indicatorLen * cos(angleRad)).toFloat()
        val y2 = centerY + (indicatorLen * sin(angleRad)).toFloat()
        canvas.drawLine(x1, y1, x2, y2, indicatorPaint)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(size, size)
    }
}
