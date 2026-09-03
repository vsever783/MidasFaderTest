package com.example.midasfadercontrol

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Чёрные системные рамки (статус-бар сверху, навигация снизу) - общая для
 * всех экранов приложения. Используется WindowInsetsControllerCompat из
 * androidx.core - устаревшие systemUiVisibility-флаги на некоторых
 * устройствах/оболочках (например Samsung One UI) срабатывали не всегда
 * надёжно.
 */
internal fun AppCompatActivity.applyBlackSystemBars() {
    window.statusBarColor = Color.BLACK
    window.navigationBarColor = Color.BLACK
    val controller = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
    controller.isAppearanceLightStatusBars = false
    controller.isAppearanceLightNavigationBars = false
}

/**
 * Выбор режима (инженер/музыкант) - ВТОРОЙ экран в цепочке (после
 * ConnectActivity, где уже установлено соединение с пультом). Само
 * соединение сюда не относится - просто передаём выбранный режим в
 * MainActivity через Intent extra, MainActivity переиспользует уже живой
 * сокет (см. MainActivity.beginModeSession()).
 *
 * ВАЖНО про полное отключение: пока пользователь просто переключается
 * между инженерным и мониторным режимом (MainActivity -> назад -> сюда ->
 * другой режим), соединение остаётся живым - MainActivity.onDestroy()
 * теперь делает только ЛЁГКую отписку (см. заметку там). Полное
 * отключение (закрытие сокета, сброс ConnectionHolder) происходит здесь,
 * в onDestroy() ЭТОЙ активности - то есть когда пользователь уходит даже
 * отсюда (например, кнопкой "назад" возвращается к экрану подключения,
 * или полностью закрывает приложение).
 */
class RoleSelectActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_role_select)
        findViewById<android.view.View>(android.R.id.content).applySystemBarInsets()
        supportActionBar?.hide()
        applyBlackSystemBars()

        findViewById<Button>(R.id.btnRoleEngineer).setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java).apply {
                putExtra(MainActivity.EXTRA_APP_MODE, MainActivity.MODE_ENGINEER)
            })
        }
        findViewById<Button>(R.id.btnRoleMonitor).setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java).apply {
                putExtra(MainActivity.EXTRA_APP_MODE, MainActivity.MODE_MONITOR)
            })
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!isFinishing) return // пересоздание из-за поворота экрана и т.п. - ничего не трогаем

        // Пользователь ушёл даже отсюда (не просто выбирает режим) -
        // самое время по-настоящему отключиться: отписаться от всего, что
        // ещё могло остаться подписанным (например, если предыдущий режим
        // не успел корректно завершить свою лёгкую отписку), закрыть
        // сокет, сбросить состояние. Наилучшее усилие - см. ту же заметку
        // в MainActivity про best-effort отписку.
        val s = ConnectionHolder.socket
        val addr = ConnectionHolder.consoleAddress
        val prt = ConnectionHolder.consolePort
        if (s != null && addr != null) {
            val handles = mutableListOf<String>()
            handles.addAll(ConnectionHolder.subscriptions.keys)
            handles.addAll(ConnectionHolder.masterSubscriptions.keys)
            handles.addAll(ConnectionHolder.auxSubscriptions.keys)
            handles.addAll(ConnectionHolder.auxBusSubscriptions.keys)
            handles.addAll(ConnectionHolder.vcaSubscriptions.keys)
            handles.addAll(ConnectionHolder.mainOutSubscriptions.keys)
            handles.addAll(ConnectionHolder.vcaMemberSubscriptions.keys)
            CoroutineScope(Dispatchers.IO).launch {
                // Общая аккуратная отписка - см. unsubscribeHandles().
                // Сокет закрываем только ПОСЛЕ неё: иначе пульт остался бы
                // с живыми подписками на несуществующего клиента.
                unsubscribeHandles(s, addr, prt, handles)
                s.close()
            }
        } else {
            s?.close()
        }
        ConnectionHolder.reset()
    }
}
