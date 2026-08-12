package com.example.midasfadercontrol

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Упрощённый экран для музыканта: каналы показываются постранично (по 8,
 * как на пульте), справа - собственная громкость и mute выбранной шины.
 * Работает и в портретной, и в альбомной ориентации - строгой блокировки
 * ориентации нет, разметка одна и та же, каналы горизонтально
 * прокручиваются, если не помещаются по ширине.
 */
class MonitorActivity : AppCompatActivity() {

    companion object {
        private const val CHANNELS_PER_PAGE = 8
    }

    private lateinit var textConnectionStatus: TextView
    private lateinit var textBusTitle: TextView
    private lateinit var btnChangeBus: Button
    private lateinit var containerPages: LinearLayout
    private lateinit var containerChannels: LinearLayout
    private lateinit var seekBusFader: SeekBar
    private lateinit var textBusFaderValue: TextView
    private lateinit var btnBusMute: Button

    private class RowUi(val nameView: TextView, val seek: SeekBar, val valueView: TextView) {
        var isDragging: Boolean = false
        var lastSendTime: Long = 0L
    }

    // Индекс канала (0..55) -> UI строки, только для каналов ТЕКУЩЕЙ страницы
    private val rows = mutableMapOf<Int, RowUi>()
    private var currentPage = 0
    private val pageButtons = mutableListOf<Button>()

    private var busSeekDragging = false
    private var busLastSendTime = 0L
    private val minSendIntervalMs = 40L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_monitor)
        supportActionBar?.hide()

        if (!MonitorLink.isConnected || MonitorLink.selectedBus < 0) {
            finish()
            return
        }

        textConnectionStatus = findViewById(R.id.textConnectionStatus)
        textBusTitle = findViewById(R.id.textBusTitle)
        btnChangeBus = findViewById(R.id.btnChangeBus)
        containerPages = findViewById(R.id.containerPages)
        containerChannels = findViewById(R.id.containerChannels)
        seekBusFader = findViewById(R.id.seekBusFader)
        textBusFaderValue = findViewById(R.id.textBusFaderValue)
        btnBusMute = findViewById(R.id.btnBusMute)

        btnChangeBus.setOnClickListener { finish() }

        buildPageTabs()
        setupBusFader()
        setupBusMuteButton()
        showPage(0)
        refreshAllFromState()

        MonitorLink.subscribeForSelectedBus(MonitorLink.selectedBus)
    }

    override fun onResume() {
        super.onResume()
        MonitorLink.onUpdate = { refreshAllFromState() }
        refreshAllFromState()
    }

    override fun onPause() {
        super.onPause()
        MonitorLink.onUpdate = null
    }

    // ---------- страницы по 8 каналов ----------

    private fun buildPageTabs() {
        containerPages.removeAllViews()
        pageButtons.clear()
        val pageCount = (MonitorLink.numChannels + CHANNELS_PER_PAGE - 1) / CHANNELS_PER_PAGE
        for (p in 0 until pageCount) {
            val from = p * CHANNELS_PER_PAGE + 1
            val to = minOf((p + 1) * CHANNELS_PER_PAGE, MonitorLink.numChannels)
            val btn = Button(this).apply {
                text = "$from–$to"
                textSize = 13f
                setPadding(24, 8, 24, 8)
                val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                lp.setMargins(0, 0, 8, 0)
                layoutParams = lp
                setOnClickListener { showPage(p) }
            }
            containerPages.addView(btn)
            pageButtons.add(btn)
        }
    }

    private fun showPage(page: Int) {
        currentPage = page
        for (i in pageButtons.indices) {
            pageButtons[i].backgroundTintList = android.content.res.ColorStateList.valueOf(
                if (i == page) Color.parseColor("#ff9f0a") else Color.parseColor("#3a3a3c")
            )
            pageButtons[i].setTextColor(if (i == page) Color.BLACK else Color.WHITE)
        }
        buildChannelRowsForPage(page)
    }

    private fun buildChannelRowsForPage(page: Int) {
        containerChannels.removeAllViews()
        rows.clear()
        val inflater = LayoutInflater.from(this)
        val bus = MonitorLink.selectedBus
        val from = page * CHANNELS_PER_PAGE
        val to = minOf(from + CHANNELS_PER_PAGE, MonitorLink.numChannels)

        for (i in from until to) {
            val row = inflater.inflate(R.layout.channel_strip_monitor, containerChannels, false)
            val numberView = row.findViewById<TextView>(R.id.textChannelNumber)
            val nameView = row.findViewById<TextView>(R.id.textChannelName)
            val seek = row.findViewById<SeekBar>(R.id.seekSend)
            val valueView = row.findViewById<TextView>(R.id.textSendValue)
            seek.max = 1000
            numberView.text = "${i + 1}"

            val ui = RowUi(nameView, seek, valueView)
            rows[i] = ui

            seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val level = progress / 1000f
                    valueView.text = formatLevel(level)
                    val now = System.currentTimeMillis()
                    if (now - ui.lastSendTime >= minSendIntervalMs) {
                        ui.lastSendTime = now
                        MonitorLink.sendChannelSend(i, bus, level)
                    } else {
                        MonitorLink.channelSendLevels[i] = level
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) { ui.isDragging = true }
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    ui.isDragging = false
                    MonitorLink.sendChannelSend(i, bus, seek.progress / 1000f)
                }
            })

            containerChannels.addView(row)
        }
        refreshChannelRows()
    }

    // ---------- мастер (громкость и mute самой шины) ----------

    private fun setupBusFader() {
        val bus = MonitorLink.selectedBus
        seekBusFader.max = 1000
        seekBusFader.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val level = progress / 1000f
                textBusFaderValue.text = formatLevel(level)
                val now = System.currentTimeMillis()
                if (now - busLastSendTime >= minSendIntervalMs) {
                    busLastSendTime = now
                    MonitorLink.sendBusFader(bus, level)
                } else {
                    MonitorLink.busFaders[bus] = level
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) { busSeekDragging = true }
            override fun onStopTrackingTouch(sb: SeekBar?) {
                busSeekDragging = false
                MonitorLink.sendBusFader(bus, seekBusFader.progress / 1000f)
            }
        })
    }

    private fun setupBusMuteButton() {
        val bus = MonitorLink.selectedBus
        btnBusMute.setOnClickListener {
            MonitorLink.sendBusMute(bus, !MonitorLink.busMutes[bus])
            refreshMasterPanel()
        }
    }

    // ---------- перерисовка из состояния MonitorLink ----------

    private fun refreshAllFromState() {
        textConnectionStatus.text = if (MonitorLink.isConnected) {
            "● Подключено · ${MonitorLink.connectedHost() ?: ""}"
        } else {
            "● Нет соединения"
        }
        textConnectionStatus.setTextColor(
            if (MonitorLink.isConnected) Color.parseColor("#34c759") else Color.parseColor("#ff3b30")
        )

        val bus = MonitorLink.selectedBus
        val busName = MonitorLink.busNames.getOrNull(bus)?.takeIf { it.isNotBlank() }
        textBusTitle.text = busName ?: "AUX ${bus + 1}"

        refreshMasterPanel()
        refreshChannelRows()
    }

    private fun refreshMasterPanel() {
        val bus = MonitorLink.selectedBus
        if (!busSeekDragging) {
            val level = MonitorLink.busFaders.getOrNull(bus) ?: 0f
            seekBusFader.progress = (level * 1000).toInt()
            textBusFaderValue.text = formatLevel(level)
        }
        val muted = MonitorLink.busMutes.getOrNull(bus) ?: false
        btnBusMute.backgroundTintList = android.content.res.ColorStateList.valueOf(
            if (muted) Color.parseColor("#ff3b30") else Color.parseColor("#3a3a3c")
        )
    }

    private fun refreshChannelRows() {
        for ((i, ui) in rows) {
            val name = MonitorLink.channelNames.getOrNull(i)?.takeIf { it.isNotBlank() }
            ui.nameView.text = name ?: "CH ${i + 1}"
            if (!ui.isDragging) {
                val level = MonitorLink.channelSendLevels.getOrNull(i) ?: 0f
                ui.seek.progress = (level * 1000).toInt()
                ui.valueView.text = formatLevel(level)
            }
        }
    }

    private fun formatLevel(level: Float): String = String.format("%.2f", level)
}
