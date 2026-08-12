package com.example.midasfadercontrol

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Стартовый экран: ввод IP/порта пульта, подключение, затем список всех
 * 16 aux-шин (с живыми именами, как только придут с пульта) - тап по шине
 * открывает MonitorActivity для неё.
 */
class ConnectionActivity : AppCompatActivity() {

    private lateinit var editHost: EditText
    private lateinit var editPort: EditText
    private lateinit var btnConnect: Button
    private lateinit var textStatus: TextView
    private lateinit var containerBuses: LinearLayout
    private val busLabels = mutableListOf<TextView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_connection)
        supportActionBar?.hide()

        editHost = findViewById(R.id.editHost)
        editPort = findViewById(R.id.editPort)
        btnConnect = findViewById(R.id.btnConnect)
        textStatus = findViewById(R.id.textStatus)
        containerBuses = findViewById(R.id.containerBuses)

        buildBusRows()

        // Если уже подключены (вернулись назад из MonitorActivity) - не
        // подключаемся заново, просто показываем актуальный список шин.
        if (MonitorLink.isConnected) {
            textStatus.text = "Подключено. Выберите вашу мониторную шину."
        }

        btnConnect.setOnClickListener {
            val host = editHost.text.toString().trim()
            val port = editPort.text.toString().trim().toIntOrNull() ?: 10000
            if (host.isEmpty()) {
                textStatus.text = "Введите IP-адрес пульта"
                return@setOnClickListener
            }
            textStatus.text = "Подключаюсь..."
            MonitorLink.connect(
                host, port,
                onStatus = { textStatus.text = it },
                onDone = {}
            )
        }
    }

    override fun onResume() {
        super.onResume()
        MonitorLink.onUpdate = { refreshBusRowLabels() }
        refreshBusRowLabels()
    }

    override fun onPause() {
        super.onPause()
        MonitorLink.onUpdate = null
    }

    private fun buildBusRows() {
        containerBuses.removeAllViews()
        busLabels.clear()
        for (i in 0 until MonitorLink.numBuses) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(28, 28, 28, 28)
                setBackgroundColor(Color.parseColor("#2c2c2e"))
                isClickable = true
                isFocusable = true
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.setMargins(0, 0, 0, 10)
                layoutParams = lp
            }
            val label = TextView(this).apply {
                text = "AUX ${i + 1}"
                setTextColor(Color.WHITE)
                textSize = 17f
            }
            row.addView(label)
            row.setOnClickListener {
                MonitorLink.selectedBus = i
                startActivity(Intent(this, MonitorActivity::class.java))
            }
            containerBuses.addView(row)
            busLabels.add(label)
        }
    }

    private fun refreshBusRowLabels() {
        for (i in busLabels.indices) {
            val name = MonitorLink.busNames.getOrNull(i)?.takeIf { it.isNotBlank() }
            busLabels[i].text = if (name != null) "AUX ${i + 1} — $name" else "AUX ${i + 1}"
        }
    }
}
