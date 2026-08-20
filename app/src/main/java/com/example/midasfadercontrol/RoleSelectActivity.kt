package com.example.midasfadercontrol

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity

/**
 * Чёрные системные рамки (статус-бар сверху, навигация снизу) - общая для
 * всех экранов приложения (раньше это было только в MainActivity.onCreate,
 * из-за чего самый первый экран - выбор роли - оставался со стандартными,
 * не всегда тёмными системными рамками). Работает одинаково в любой
 * ориентации - вызывается один раз при создании активности.
 */
internal fun AppCompatActivity.applyBlackSystemBars() {
    window.statusBarColor = Color.BLACK
    window.navigationBarColor = Color.BLACK
    @Suppress("DEPRECATION")
    window.decorView.systemUiVisibility = window.decorView.systemUiVisibility and
        android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv() and
        android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
}

/**
 * Единственная launcher-активность приложения. Раньше было ДВА отдельных
 * значка (Midas Fader Control / Midas Monitor) с разными taskAffinity -
 * из-за этого оба режима иногда запускались ОДНОВРЕМЕННО с одного и того
 * же телефона (один IP), а пульт умеет отвечать только ОДНОМУ клиенту на
 * IP-адрес - при двух активных подключениях с одного IP пульт путался,
 * куда слать ответы, и мониторный режим переставал получать live-данные
 * (подтверждено реальным захватом трафика).
 *
 * Теперь один вход, дальше выбор роли - MainActivity просто получает режим
 * через Intent extra и либо строит полный интерфейс инженера, либо
 * упрощённый мониторный, используя ОДНО и то же подключение (порт 10001).
 */
class RoleSelectActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_role_select)
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
}
