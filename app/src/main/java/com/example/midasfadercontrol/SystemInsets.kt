package com.example.midasfadercontrol

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

/**
 * Отступы под системные панели (статус-бар сверху, панель навигации снизу,
 * вырез камеры).
 *
 * ЗАЧЕМ ЭТО НУЖНО. В разметке у всех экранов уже стоял
 * android:fitsSystemWindows="true", но начиная с Android 15 система
 * ПРИНУДИТЕЛЬНО включает режим edge-to-edge для приложений с targetSdk 35+
 * (у нас 37). В этом режиме окно рисуется под системными панелями, а
 * старый флаг fitsSystemWindows больше не даёт padding автоматически.
 *
 * Результат было видно на скриншотах: часы и значки статус-бара налезали
 * на кнопку BACK и заголовок, а ручки фейдеров внизу уходили под панель
 * навигации и становились неудобны для попадания пальцем.
 *
 * Программная обработка insets работает одинаково на всех версиях
 * Android, поэтому она надёжнее флага в разметке.
 */
fun View.applySystemBarInsets(
    top: Boolean = true,
    bottom: Boolean = true,
    horizontal: Boolean = true
) {
    // Исходные отступы запоминаем, чтобы системные прибавлялись к ним, а
    // не затирали padding, заданный в разметке.
    val baseLeft = paddingLeft
    val baseTop = paddingTop
    val baseRight = paddingRight
    val baseBottom = paddingBottom

    ViewCompat.setOnApplyWindowInsetsListener(this) { view, windowInsets ->
        val bars = windowInsets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        )
        view.updatePadding(
            left = baseLeft + if (horizontal) bars.left else 0,
            top = baseTop + if (top) bars.top else 0,
            right = baseRight + if (horizontal) bars.right else 0,
            bottom = baseBottom + if (bottom) bars.bottom else 0
        )
        // Не поглощаем insets: вложенные элементы (например, выезжающий
        // детальный экран канала) могут обрабатывать их самостоятельно.
        windowInsets
    }
    // На случай, если вью уже прикреплена к окну и insets успели прийти.
    ViewCompat.requestApplyInsets(this)
}
