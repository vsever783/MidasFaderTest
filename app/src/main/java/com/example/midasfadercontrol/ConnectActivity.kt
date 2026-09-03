package com.example.midasfadercontrol

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Новая, самая первая точка входа - ТОЛЬКО подключение (IP/Port/Connect),
 * без выбора режима. Раньше подключение было встроено внутрь MainActivity
 * и целиком пересоздавалось (новый сокет + полная подписка с нуля) при
 * КАЖДОМ переключении между инженерным и мониторным режимом - это было
 * тяжело для пульта и, судя по всему, сбивало официальный Mixtender при
 * частых переключениях.
 *
 * Теперь: подключаемся здесь ОДИН раз, сокет/адрес/сессия сохраняются в
 * ConnectionHolder и остаются живыми всё время, пока приложение открыто -
 * дальше RoleSelectActivity просто выбирает режим, а MainActivity
 * ПЕРЕИСПОЛЬЗУЕТ уже существующее соединение (см. MainActivity.
 * beginModeSession()), вместо того чтобы пересоздавать его заново.
 */
class ConnectActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_connect)
        // Отступы под системные панели - см. SystemInsets.kt
        findViewById<android.view.View>(android.R.id.content).applySystemBarInsets()
        supportActionBar?.hide()
        applyBlackSystemBars()

        val editHost = findViewById<EditText>(R.id.editHost)
        val editPort = findViewById<EditText>(R.id.editPort)
        val btnConnect = findViewById<Button>(R.id.btnConnect)
        val textStatus = findViewById<TextView>(R.id.textStatus)

        // Если соединение уже живо (например, пользователь вернулся сюда
        // случайно через "назад" из RoleSelectActivity, хотя обычно такого
        // пути нет) - не пересоздаём сокет заново, просто идём дальше.
        if (ConnectionHolder.socket != null && ConnectionHolder.consoleAddress != null) {
            goToRoleSelect()
            return
        }

        btnConnect.setOnClickListener {
            val host = editHost.text.toString().trim()
            val port = editPort.text.toString().trim().toIntOrNull()
            if (host.isEmpty() || port == null) {
                textStatus.text = "Check IP and port"
                return@setOnClickListener
            }
            btnConnect.isEnabled = false
            textStatus.text = "Connecting..."

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val address = InetAddress.getByName(host)
                    // Локальный порт мог на миг остаться занятым (см. ту же
                    // заметку в MainActivityNetwork.connectAndSync) -
                    // пробуем несколько раз с паузой вместо немедленного отказа.
                    var newSocket: DatagramSocket? = null
                    var lastError: Exception? = null
                    for (attempt in 0 until 5) {
                        try {
                            newSocket = DatagramSocket(10001)
                            break
                        } catch (e: Exception) {
                            lastError = e
                            delay(150)
                        }
                    }
                    if (newSocket == null) throw lastError ?: Exception("Не удалось занять локальный порт 10001")

                    ConnectionHolder.socket = newSocket
                    ConnectionHolder.consoleAddress = address
                    ConnectionHolder.consolePort = port
                    ConnectionHolder.sessionId = System.currentTimeMillis().toString(36)
                    ConnectionHolder.sessionToken = null
                    // Не `subscribedAlready = false`: признак хранится в
                    // атомарном замке, и сбрасывать надо именно его -
                    // иначе новая сессия не подпишется (см. заметку в
                    // MainActivity.onDestroy).
                    ConnectionHolder.releaseSubscribeGate()

                    withContext(Dispatchers.Main) {
                        goToRoleSelect()
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        btnConnect.isEnabled = true
                        textStatus.text = "Connection error: ${e.message}"
                    }
                }
            }
        }
    }

    private fun goToRoleSelect() {
        startActivity(Intent(this, RoleSelectActivity::class.java))
        finish()
    }
}
