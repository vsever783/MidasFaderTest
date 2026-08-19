package com.example.midasfadercontrol

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import android.widget.ToggleButton
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer

class MainActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_APP_MODE = "app_mode"
        const val MODE_ENGINEER = "engineer"
        const val MODE_MONITOR = "monitor"
    }
    internal var appMode: String = MODE_ENGINEER

    // Реальная конфигурация пульта Pro2 (подтверждено мануалом): 56 входных
    // каналов. Показываем по 8 за раз через переключение банков (см.
    // switchBank), но строим полосы для всех 56 сразу.
    internal val numChannels = 56
    private val channelsPerBank = 8

    internal lateinit var editHost: EditText
    internal lateinit var editPort: EditText
    private lateinit var btnConnect: Button
    internal lateinit var textStatus: TextView
    internal lateinit var textConnectionStatus: TextView
    private lateinit var containerChannels: android.widget.LinearLayout
    private lateinit var channelDetailContainer: android.widget.FrameLayout

    // Прокси к ConnectionHolder - весь остальной код обращается к этим полям
    // как раньше (socket, receiveJob и т.д.), но реальное хранение теперь
    // живёт вне Activity и переживает поворот экрана.
    internal var socket: DatagramSocket?
        get() = ConnectionHolder.socket
        set(value) { ConnectionHolder.socket = value }
    internal var receiveJob: Job?
        get() = ConnectionHolder.receiveJob
        set(value) { ConnectionHolder.receiveJob = value }
    internal var pollJob: Job?
        get() = ConnectionHolder.pollJob
        set(value) { ConnectionHolder.pollJob = value }
    internal var consoleAddress: InetAddress?
        get() = ConnectionHolder.consoleAddress
        set(value) { ConnectionHolder.consoleAddress = value }
    internal var consolePort: Int
        get() = ConnectionHolder.consolePort
        set(value) { ConnectionHolder.consolePort = value }
    internal var sessionToken: Int?
        get() = ConnectionHolder.sessionToken
        set(value) { ConnectionHolder.sessionToken = value }
    internal var subscribedAlready: Boolean
        get() = ConnectionHolder.subscribedAlready
        set(value) { ConnectionHolder.subscribedAlready = value }
    internal var sessionId: String
        get() = ConnectionHolder.sessionId
        set(value) { ConnectionHolder.sessionId = value }
    internal val subscriptions get() = ConnectionHolder.subscriptions
    internal val masterSubscriptions get() = ConnectionHolder.masterSubscriptions
    internal val auxSubscriptions get() = ConnectionHolder.auxSubscriptions
    internal val auxBusSubscriptions get() = ConnectionHolder.auxBusSubscriptions
    internal val vcaSubscriptions get() = ConnectionHolder.vcaSubscriptions
    internal val vcaMemberSubscriptions get() = ConnectionHolder.vcaMemberSubscriptions
    internal val mainOutSubscriptions get() = ConnectionHolder.mainOutSubscriptions
    internal val auxSendsSubscribed get() = ConnectionHolder.auxSendsSubscribed
    internal val eqSubscribed get() = ConnectionHolder.eqSubscribed
    internal val gateSubscribed get() = ConnectionHolder.gateSubscribed
    internal val inputExtrasSubscribed get() = ConnectionHolder.inputExtrasSubscribed
    internal val compGateExtrasSubscribed get() = ConnectionHolder.compGateExtrasSubscribed

    internal data class ChannelUi(
        val rootView: android.view.View,
        val labelView: TextView,
        val levelValueText: TextView,
        val fader: SeekBar,
        val muteButton: Button,
        val soloButton: ToggleButton,
        val headerView: android.view.View,
        val meterBar: android.view.View,
        var lastFaderSendTime: Long = 0L,
        // Локальное "оптимистичное" состояние mute - обновляется МГНОВЕННО по
        // нажатию, не дожидаясь ответа от пульта. См. примечание у muteButton
        // listener ниже про то, почему это нужно.
        var mutedLocal: Boolean = false,
        // То же самое для компрессора (вкл/выкл) - та же логика, что и mute.
        var compInLocal: Boolean = false,
        // Последние известные значения компрессора (из push) - чтобы диалог
        // настроек открывался сразу с актуальными позициями ползунков, а не
        // с нуля.
        var compRatio: Float = 0f,
        var compAttack: Float = 0f,
        var compRelease: Float = 0f,
        var compThreshold: Float = 0f,
        var compMakeup: Float = 0f,
        // Пока идёт обновление из сети (GET-ответ), нужно временно
        // "заглушить" собственные слушатели виджетов - иначе обновление
        // экрана вызовет обратную отправку команды на пульт по кругу.
        var suppressEvents: Boolean = false,
        // Пользователь прямо сейчас держит палец на фейдере - пока это так,
        // push-обновления (в т.ч. "эхо" наших же недавних команд, которое
        // может прийти с задержкой до 30 сек) не должны трогать визуальное
        // положение фейдера, иначе получается подёргивание/скачки при
        // быстром перемещении.
        var isDragging: Boolean = false
    )

    internal val channels = mutableListOf<ChannelUi>()

    private val minSendIntervalMs = 40L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appMode = intent.getStringExtra(EXTRA_APP_MODE) ?: ConnectionHolder.uiAppMode
        ConnectionHolder.uiAppMode = appMode

        // Убираем системную белую панель заголовка - она отнимает заметную
        // полосу по высоте, особенно чувствительно в альбомной ориентации.
        supportActionBar?.hide()

        if (appMode == MODE_MONITOR) {
            onCreateMonitor()
        } else {
            onCreateEngineer()
        }
    }

    private fun onCreateEngineer() {
        setContentView(R.layout.activity_main)

        editHost = findViewById(R.id.editHost)
        editPort = findViewById(R.id.editPort)
        btnConnect = findViewById(R.id.btnConnect)
        textStatus = findViewById(R.id.textStatus)
        textConnectionStatus = findViewById(R.id.textConnectionStatus)
        containerChannels = findViewById(R.id.containerChannels)
        channelDetailContainer = findViewById(R.id.channelDetailContainer)

        buildChannelStrips()
        buildBankButtons()
        buildMasterStrips()
        buildAuxStrips()
        buildAuxBusStrips()
        buildVcaStrips()
        buildMainOutStrips()
        buildModeButtons()

        btnConnect.setOnClickListener { connectAndSync() }

        // Если сокет уже есть - значит, это не холодный старт, а пересоздание
        // Activity (например, из-за поворота экрана). Соединение и подписки
        // на пульт уже живы в ConnectionHolder - просто восстанавливаем
        // экран из уже накопленных данных и запускаем приём заново (старый
        // receiveJob/pollJob были привязаны к уже уничтоженному экземпляру
        // Activity и их нужно перезапустить на новом, БЕЗ пересоздания
        // самого сокета - пульт даже не заметит, что происходит).
        val existingSocket = socket
        val existingAddress = consoleAddress
        if (existingSocket != null && existingAddress != null) {
            receiveJob?.cancel()
            pollJob?.cancel()
            textConnectionStatus.text = "● Connected"
            textConnectionStatus.setTextColor(Color.parseColor("#34c759"))
            textStatus.text = "Connection restored after screen rotation"
            restoreUiFromChannelData()
            startReceiveLoop(existingSocket)
            startPollLoop(existingSocket, existingAddress, consolePort)
        }
    }

    // ============================================================
    // === МОНИТОРНЫЙ РЕЖИМ - использует ТОТ ЖЕ ConnectionHolder и
    // тот же порт 10001, что и инженерский. Раньше это было отдельное
    // приложение со своим сокетом на порту 10002 - но пульт различает
    // клиентов только по IP-адресу (не по порту), и при одновременной
    // работе обоих режимов с одного телефона пульт слал все ответы
    // только одному из них (подтверждено реальным захватом трафика).
    // Отсюда и объединение в один режим/один порт.
    // ============================================================

    private var monitorSelectedBus: Int = -1
    private var monitorBusFader: SeekBar? = null
    private var monitorBusMuteButton: Button? = null
    private var monitorBusMutedLocal = false
    private data class MonitorChannelUi(val seek: SeekBar, val valueText: TextView, val meterBar: android.view.View)
    private var monitorChannelStrips: Array<MonitorChannelUi?> = arrayOfNulls(56)
    // Какая VCA-группа сейчас открыта на экране "VCA N MEMBERS" (для live-
    // обновления кнопок push-ответами) и сами кнопки по ключу "тип:индекс".
    private var openVcaMembersIndex: Int? = null
    private val vcaMemberButtons = mutableMapOf<String, Button>()
    private var monitorChannelLabels: Array<TextView?> = arrayOfNulls(56)
    private val monitorBankButtons = mutableListOf<Button>()
    private var monitorBankStart = 0

    private fun onCreateMonitor() {
        setContentView(R.layout.activity_monitor_connect)

        editHost = findViewById(R.id.editHost)
        editPort = findViewById(R.id.editPort)
        btnConnect = findViewById(R.id.btnConnect)
        textStatus = findViewById(R.id.textStatus)
        textConnectionStatus = findViewById(R.id.textConnectionStatus)

        btnConnect.setOnClickListener {
            connectAndSync()
            // Список шин будет заполняться по мере прихода имён (см.
            // updateAuxBusNameForMonitorList, вызывается из уже
            // существующего разбора ParamKind.NAME для aux-шин).
            findViewById<android.widget.LinearLayout>(R.id.containerBuses).postDelayed(
                { buildMonitorBusList() }, 800
            )
        }

        // Восстановление после поворота экрана - если уже подключены,
        // сразу показываем список шин (или экран выбранной шины).
        if (socket != null) {
            textConnectionStatus.text = "● Connected"
            textConnectionStatus.setTextColor(Color.parseColor("#34c759"))
            buildMonitorBusList()
            if (ConnectionHolder.uiMonitorSelectedBus >= 0) {
                openMonitorBus(ConnectionHolder.uiMonitorSelectedBus)
            }
        }
    }

    /** Список шин на экране подключения монитора - имена подтягиваются по мере прихода push. */
    private fun buildMonitorBusList() {
        val container = findViewById<android.widget.LinearLayout>(R.id.containerBuses)
        container.removeAllViews()
        for (b in 0 until 16) {
            val data = ConnectionHolder.auxBusData[b]
            val btn = Button(this)
            btn.text = if (data.name.isNotBlank()) "BUS ${b + 1} — ${data.name}" else "BUS ${b + 1}"
            btn.setTextColor(Color.parseColor("#ffffff"))
            btn.backgroundTintList = null
            btn.setBackgroundColor(Color.parseColor("#3a3a3c"))
            val params = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.bottomMargin = 8
            btn.layoutParams = params
            btn.setOnClickListener { openMonitorBus(b) }
            container.addView(btn)
        }
    }

    /** Открывает экран выбранной шины: посылы всех 56 каналов + громкость/mute самой шины. */
    private fun openMonitorBus(bus: Int) {
        monitorSelectedBus = bus
        ConnectionHolder.uiMonitorSelectedBus = bus
        setContentView(R.layout.activity_monitor_bus)

        val busData = ConnectionHolder.auxBusData[bus]
        findViewById<TextView>(R.id.textMonitorBusName).text =
            if (busData.name.isNotBlank()) "BUS ${bus + 1} — ${busData.name}" else "BUS ${bus + 1}"

        findViewById<Button>(R.id.btnMonitorBack).setOnClickListener {
            monitorSelectedBus = -1
            ConnectionHolder.uiMonitorSelectedBus = -1
            onCreateMonitor()
        }

        val busFader = findViewById<SeekBar>(R.id.seekMonitorBusLevel)
        val busMute = findViewById<Button>(R.id.btnMonitorBusMute)
        monitorBusFader = busFader
        monitorBusMuteButton = busMute
        busFader.progress = (busData.fader * 1000).toInt()
        busMute.backgroundTintList = null
        monitorBusMutedLocal = busData.mutedLocal
        busMute.setBackgroundColor(Color.parseColor(if (busData.mutedLocal) "#ff3b30" else "#3a3a3c"))

        busFader.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            var lastSend = 0L
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val level = progress / 1000f
                ConnectionHolder.auxBusData[bus].fader = level
                val now = System.currentTimeMillis()
                if (now - lastSend >= minSendIntervalMs) {
                    lastSend = now
                    sendAuxBusFader(bus, level)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                val level = (sb?.progress ?: 0) / 1000f
                ConnectionHolder.auxBusData[bus].fader = level
                sendAuxBusFader(bus, level)
            }
        })
        busMute.setOnClickListener {
            monitorBusMutedLocal = !monitorBusMutedLocal
            busMute.setBackgroundColor(Color.parseColor(if (monitorBusMutedLocal) "#ff3b30" else "#3a3a3c"))
            ConnectionHolder.auxBusData[bus].mutedLocal = monitorBusMutedLocal
            sendAuxBusMute(bus, true)
        }

        buildMonitorBankButtons()
        subscribeChannelSendsForBus(bus)
    }

    private fun buildMonitorBankButtons() {
        val row = findViewById<android.widget.LinearLayout>(R.id.monitorBankButtonsRow)
        row.removeAllViews()
        monitorBankButtons.clear()
        val bankCount = (numChannels + channelsPerBank - 1) / channelsPerBank
        for (bk in 0 until bankCount) {
            val start = bk * channelsPerBank
            val end = minOf(start + channelsPerBank, numChannels)
            val btn = Button(this)
            btn.text = "${start + 1}-$end"
            btn.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.bank_button_text_size))
            btn.backgroundTintList = null
            btn.minHeight = 0
            val vPad = resources.getDimensionPixelSize(R.dimen.bank_button_v_padding)
            btn.setPadding(24, vPad, 24, vPad)
            btn.setTextColor(Color.parseColor("#ffffff"))
            btn.setBackgroundColor(Color.parseColor("#3a3a3c"))
            val params = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.marginEnd = 8
            btn.layoutParams = params
            btn.setOnClickListener { switchMonitorBank(start) }
            row.addView(btn)
            monitorBankButtons.add(btn)
        }
        switchMonitorBank(0)
    }

    private fun switchMonitorBank(start: Int) {
        monitorBankStart = start
        for ((idx, btn) in monitorBankButtons.withIndex()) {
            val active = idx * channelsPerBank == start
            btn.setBackgroundColor(Color.parseColor(if (active) "#ff9f0a" else "#3a3a3c"))
            btn.setTextColor(Color.parseColor(if (active) "#000000" else "#ffffff"))
        }
        buildMonitorChannelStrips(monitorSelectedBus, start)
    }

    /**
     * Строит полосы каналов для монитора - показывает посыл каждого из 8
     * каналов текущего банка в выбранную шину. Фейдер занимает всю
     * выделенную высоту (запрошено), с той же адаптивной подгонкой ширины
     * повёрнутого SeekBar, что и в инженерском режиме.
     */
    private fun buildMonitorChannelStrips(bus: Int, bankStart: Int) {
        val container = findViewById<android.widget.LinearLayout>(R.id.containerMonitorChannels)
        container.removeAllViews()
        val inflater = LayoutInflater.from(this)
        for (i in bankStart until minOf(bankStart + channelsPerBank, numChannels)) {
            val strip = inflater.inflate(R.layout.channel_strip_monitor, container, false)
            val label = strip.findViewById<TextView>(R.id.textMonitorChannelLabel)
            val valueText = strip.findViewById<TextView>(R.id.textMonitorSendValue)
            val seek = strip.findViewById<SeekBar>(R.id.seekMonitorSend)
            val sendContainer = strip.findViewById<android.widget.FrameLayout>(R.id.monitorSendContainer)
            val meterBar = strip.findViewById<android.view.View>(R.id.monitorMeterBar)
            setupMeterBarPivot(meterBar)

            val chData = ConnectionHolder.channelData[i]
            label.text = if (chData.name.isNotBlank()) chData.name else "CH ${i + 1}"
            chData.colourArgb?.let {
                strip.findViewById<android.view.View>(R.id.monitorChannelHeader).setBackgroundColor(it)
            }
            val level = chData.auxSends.getOrElse(bus) { 0f }
            seek.progress = (level * 1000).toInt()
            valueText.text = "%.2f".format(level)

            // Та же подгонка ширины повёрнутого фейдера под реальную высоту,
            // что и везде в приложении - фейдер тянется на всю доступную
            // область канала целиком.
            sendContainer.viewTreeObserver.addOnGlobalLayoutListener(
                object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
                    override fun onGlobalLayout() {
                        val h = sendContainer.height
                        if (h > 0) {
                            val p = seek.layoutParams
                            if (p.width != h) {
                                p.width = h
                                seek.layoutParams = p
                            }
                            sendContainer.viewTreeObserver.removeOnGlobalLayoutListener(this)
                        }
                    }
                }
            )

            var lastSend = 0L
            val channelIndex = i
            seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    val lvl = progress / 1000f
                    valueText.text = "%.2f".format(lvl)
                    if (!fromUser) return
                    ConnectionHolder.channelData[channelIndex].auxSends[bus] = lvl
                    val now = System.currentTimeMillis()
                    if (now - lastSend >= minSendIntervalMs) {
                        lastSend = now
                        sendSubSend(channelIndex, bus + 1, lvl)
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    val lvl = (sb?.progress ?: 0) / 1000f
                    ConnectionHolder.channelData[channelIndex].auxSends[bus] = lvl
                    sendSubSend(channelIndex, bus + 1, lvl)
                }
            })

            monitorChannelStrips[i] = MonitorChannelUi(seek, valueText, meterBar)
            monitorChannelLabels[i] = label
            container.addView(strip)
        }
    }

    /**
     * Подписывается на посылы ВСЕХ 56 каналов в ОДНУ конкретную шину (не то
     * же самое, что подписка на все 16 шин ОДНОГО канала во вкладке SENDS
     * инженерского режима - там другой порядок перебора).
     */

    /** Заполняет только что построенный экран уже накопленными данными каналов. */
    private fun restoreUiFromChannelData() {
        for (i in 0 until numChannels) {
            val data = ConnectionHolder.channelData[i]
            updateFaderUi(i, data.fader)
            updateMuteUi(i, data.mutedLocal)
            updateSoloUi(i, data.soloed)
            updateGainUi(i, data.gain)
            if (data.name.isNotBlank()) updateNameUi(i, data.name)
            data.colourArgb?.let { updateColourUi(i, it) }
            updateCompParamUi(i, ParamKind.COMP_RATIO, data.compRatio)
            updateCompParamUi(i, ParamKind.COMP_ATTACK, data.compAttack)
            updateCompParamUi(i, ParamKind.COMP_RELEASE, data.compRelease)
            updateCompParamUi(i, ParamKind.COMP_THRESHOLD, data.compThreshold)
            updateCompParamUi(i, ParamKind.COMP_MAKEUP, data.compMakeup)
            updateCompInUi(i, data.compInLocal)
        }
    }

    private fun buildChannelStrips() {
        val inflater = LayoutInflater.from(this)
        for (i in 0 until numChannels) {
            val displayedNumber = i + 1
            val strip = inflater.inflate(R.layout.channel_strip, containerChannels, false)

            val labelView = strip.findViewById<TextView>(R.id.textChannelLabel)
            val levelValueText = strip.findViewById<TextView>(R.id.textLevelValue)
            val fader = strip.findViewById<SeekBar>(R.id.seekFader)
            val faderContainer = strip.findViewById<android.widget.FrameLayout>(R.id.faderContainer)
            val muteButton = strip.findViewById<Button>(R.id.btnMute)
            val soloButton = strip.findViewById<ToggleButton>(R.id.btnSolo)
            val headerView = strip.findViewById<android.view.View>(R.id.channelHeader)
            val meterBar = strip.findViewById<android.view.View>(R.id.meterBar)
            setupMeterBarPivot(meterBar)

            // Раньше исходный серый фон задавался через android:backgroundTint в XML,
            // но AppCompat-тема иногда переприменяет тинт поверх ручного
            // setBackgroundColor(), из-за чего кнопка визуально не перекрашивалась
            // при mute/solo. Теперь фон целиком управляется из кода - и на всякий
            // случай явно обнуляем backgroundTintList (на некоторых устройствах/
            // темах он может быть задан неявно самим стилем кнопки по умолчанию,
            // даже без явного атрибута в XML, и мешать ручной перекраске).
            muteButton.backgroundTintList = null
            soloButton.backgroundTintList = null
            muteButton.stateListAnimator = null
            soloButton.stateListAnimator = null
            muteButton.setBackgroundColor(Color.parseColor("#3a3a3c"))
            soloButton.setBackgroundColor(Color.parseColor("#3a3a3c"))

            // ВАЖНО ДЛЯ АДАПТИВНОСТИ: fader повёрнут на 270°, поэтому его
            // ВИЗУАЛЬНАЯ высота на экране на самом деле берётся из его
            // layout_width (ширина ДО поворота). faderContainer теперь
            // резиновый (layout_weight=1) и подстраивается под реальную
            // высоту экрана конкретного телефона/планшета - а раз так,
            // нужно один раз измерить, сколько места он реально получил
            // на этом экране, и подставить это значение как ширину SeekBar.
            // Без этого шага сам SeekBar остался бы жёстко на 220dp и либо
            // вылезал за пределы контейнера (обрезка), либо не дотягивался
            // до реального размера контейнера (пустое место).
            faderContainer.viewTreeObserver.addOnGlobalLayoutListener(
                object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
                    override fun onGlobalLayout() {
                        val measuredHeight = faderContainer.height
                        if (measuredHeight > 0) {
                            val params = fader.layoutParams
                            if (params.width != measuredHeight) {
                                params.width = measuredHeight
                                fader.layoutParams = params
                            }
                            faderContainer.viewTreeObserver.removeOnGlobalLayoutListener(this)
                        }
                    }
                }
            )

            labelView.text = "CH $displayedNumber"

            val ui = ChannelUi(strip, labelView, levelValueText, fader, muteButton, soloButton, headerView, meterBar)
            channels.add(ui)

            fader.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    val level = progress / 1000f
                    levelValueText.text = "%.2f".format(level)
                    if (!fromUser || ui.suppressEvents) return
                    ConnectionHolder.channelData[i].fader = level
                    val now = System.currentTimeMillis()
                    if (now - ui.lastFaderSendTime >= minSendIntervalMs) {
                        ui.lastFaderSendTime = now
                        sendFader(i, level)
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {
                    ui.isDragging = true
                }
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    ui.isDragging = false
                    if (ui.suppressEvents) return
                    val level = (sb?.progress ?: 0) / 1000f
                    ConnectionHolder.channelData[i].fader = level
                    sendFader(i, level)
                }
            })

            // ВАЖНО - ПОДТВЕРЖДЕНО ЗАХВАТОМ ТРАФИКА: настоящее подтверждение
            // mute-переключения от пульта через подписку приходит с задержкой
            // от <1 сек до ~30 сек (пульт обновляет параметры управления не
            // мгновенно, а пачками) - именно это раньше выглядело как
            // "зависание"/"нужно нажать дважды", а не баг в коде. Раз ждать
            // подтверждения от пульта для КАЖДОГО собственного нажатия долго и
            // неудобно, красим кнопку МГНОВЕННО по нажатию (оптимистично), а
            // не дожидаясь ответа - solo так уже случайно работал (через
            // встроенное поведение ToggleButton) и поэтому казался "рабочим".
            // Если через какое-то время (до 30 сек) придёт push с другим
            // состоянием - он всё равно тихо поправит кнопку через updateMuteUi.
            muteButton.setOnClickListener {
                updateMuteUi(i, !ui.mutedLocal)
                sendMute(i, true)
            }

            // Открытие детального экрана канала (с компрессором:
            // ratio/attack/release/threshold/makeup gain/вкл-выкл, и
            // постоянно видимыми mute/solo/фейдером/метром) - по тапу на
            // саму шапку канала, как в Mixing Station.
            headerView.setOnClickListener {
                openChannelDetail(i)
            }

            soloButton.setOnCheckedChangeListener { _: CompoundButton, isChecked: Boolean ->
                if (ui.suppressEvents) return@setOnCheckedChangeListener
                ConnectionHolder.channelData[i].soloed = isChecked
                soloButton.setBackgroundColor(
                    Color.parseColor(if (isChecked) "#ff9f0a" else "#3a3a3c")
                )
                sendSolo(i, isChecked)
            }

            strip.visibility = if (i < channelsPerBank) android.view.View.VISIBLE else android.view.View.GONE
            containerChannels.addView(strip)
        }
    }

    private var currentBankStart = 0
    private val bankButtons = mutableListOf<Button>()

    /**
     * Переключает видимый банк каналов (по 8 за раз, как в Mixing Station:
     * Ch 1-8 / 9-16 / и т.д.). Полосы для ВСЕХ 56 каналов уже построены
     * заранее в buildChannelStrips() - здесь просто прячем/показываем.
     * Подписка на живые обновления не зависит от того, какой банк виден -
     * данные для скрытых каналов продолжают приходить и накапливаться в
     * их виджетах (просто не видно, пока не переключишься обратно).
     */
    private fun switchBank(bankStart: Int) {
        currentBankStart = bankStart
        ConnectionHolder.uiBankStart = bankStart
        for (i in 0 until numChannels) {
            channels[i].rootView.visibility =
                if (i >= bankStart && i < bankStart + channelsPerBank) android.view.View.VISIBLE
                else android.view.View.GONE
        }
        for ((index, btn) in bankButtons.withIndex()) {
            val isActive = index * channelsPerBank == bankStart
            btn.setBackgroundColor(Color.parseColor(if (isActive) "#ff9f0a" else "#3a3a3c"))
            btn.setTextColor(Color.parseColor(if (isActive) "#000000" else "#ffffff"))
        }
    }

    private fun buildBankButtons() {
        val row = findViewById<android.widget.LinearLayout>(R.id.bankButtonsRow)
        row.removeAllViews()
        bankButtons.clear()
        val bankCount = (numChannels + channelsPerBank - 1) / channelsPerBank
        for (b in 0 until bankCount) {
            val start = b * channelsPerBank
            val end = minOf(start + channelsPerBank, numChannels)
            val btn = Button(this)
            btn.text = "${start + 1}-$end"
            btn.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.bank_button_text_size))
            btn.backgroundTintList = null
            btn.stateListAnimator = null
            // Material-стиль кнопки по умолчанию навязывает довольно большую
            // минимальную высоту (для комфортного тапа пальцем) - в альбомной
            // ориентации, где вертикального места и так впритык, это может
            // само по себе съедать заметную часть высоты экрана. Обнуляем.
            btn.minHeight = 0
            btn.minimumHeight = 0
            val vPad = resources.getDimensionPixelSize(R.dimen.bank_button_v_padding)
            btn.setPadding(24, vPad, 24, vPad)
            btn.setTextColor(Color.parseColor("#ffffff"))
            btn.setBackgroundColor(Color.parseColor("#3a3a3c"))
            val params = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.marginEnd = 8
            btn.layoutParams = params
            btn.setOnClickListener { switchBank(start) }
            row.addView(btn)
            bankButtons.add(btn)
        }
        // Восстанавливаем банк, выбранный до пересоздания Activity (поворот
        // экрана), вместо того чтобы всегда сбрасывать на 1-8.
        switchBank(ConnectionHolder.uiBankStart)
    }

    /**
     * Строит постоянно видимую панель мастера (закреплена справа, вне
     * прокрутки каналов). НЕ подтверждено реальным захватом трафика - см.
     * заметку в Pro2Commands.kt. Пока используется только индекс 0 (обычно
     * это главный LR-мастер) - по мануалу у Pro2 их 3 (0..2), если
     * понадобятся остальные, добавить ещё такие же панели по аналогии.
     */
    /** Лёгкая версия ChannelUi для мастера/aux - без gain/comp/sends/detail-экрана. */
    private data class SimpleStripUi(
        val rootView: android.view.View,
        val labelView: TextView,
        val headerView: android.view.View,
        val fader: SeekBar,
        val levelText: TextView,
        val muteButton: Button,
        val soloButton: ToggleButton,
        val soloBButton: ToggleButton,
        val meterBar: android.view.View,
        var mutedLocal: Boolean = false,
        var suppressEvents: Boolean = false,
        var lastFaderSendTime: Long = 0L
    )
    private val masterStrips = mutableListOf<SimpleStripUi>()
    private val auxStrips = mutableListOf<SimpleStripUi>()
    private val auxBusStrips = mutableListOf<SimpleStripUi>()
    private val vcaStrips = mutableListOf<SimpleStripUi>()
    private val mainOutStrips = mutableListOf<SimpleStripUi>()

    /**
     * Строит одну упрощённую полосу (шапка+SOLO+метр/фейдер+MUTE) в
     * заданный контейнер, переиспользуя channel_strip.xml. Используется и
     * для мастера, и для aux returns - разница только в том, какие
     * send-функции и начальные данные передаются.
     */
    private fun buildSimpleStrip(
        container: android.widget.LinearLayout,
        label: String,
        initialFader: Float,
        initialMuted: Boolean,
        initialSoloB: Boolean,
        onFader: (Float) -> Unit,
        onMute: (Boolean) -> Unit,
        onSolo: (Boolean) -> Unit,
        onSoloB: (Boolean) -> Unit,
        showSoloB: Boolean = false // Убрано по просьбе пользователя
    ): SimpleStripUi {
        val inflater = LayoutInflater.from(this)
        val strip = inflater.inflate(R.layout.channel_strip, container, false)

        val labelView = strip.findViewById<TextView>(R.id.textChannelLabel)
        val fader = strip.findViewById<SeekBar>(R.id.seekFader)
        val faderContainer = strip.findViewById<android.widget.FrameLayout>(R.id.faderContainer)
        val levelText = strip.findViewById<TextView>(R.id.textLevelValue)
        val muteButton = strip.findViewById<Button>(R.id.btnMute)
        val soloButton = strip.findViewById<ToggleButton>(R.id.btnSolo)
        val soloBButton = strip.findViewById<ToggleButton>(R.id.btnSoloB)
        val meterBar = strip.findViewById<android.view.View>(R.id.meterBar)
        setupMeterBarPivot(meterBar)
        val headerView = strip.findViewById<android.view.View>(R.id.channelHeader)

        labelView.text = label
        headerView.setOnClickListener(null) // нет детального экрана для мастера/aux

        muteButton.backgroundTintList = null
        muteButton.stateListAnimator = null
        soloButton.backgroundTintList = null
        soloButton.stateListAnimator = null
        muteButton.setBackgroundColor(Color.parseColor("#3a3a3c"))
        soloButton.setBackgroundColor(Color.parseColor("#3a3a3c"))

        // Вторая шина solo - ПОДТВЕРЖДЕНО реальным захватом. Только для
        // мастера/aux returns/aux-шин (у обычных 56 каналов остаётся
        // скрытой - там Solo B в детальном экране).
        soloBButton.visibility = if (showSoloB) android.view.View.VISIBLE else android.view.View.GONE
        // Когда кнопок solo две (мастер/aux returns/aux-шины) - "SOLO" не
        // помещается рядом с "B", переименовываем в короткие A/B, чтобы
        // сэкономить место. Там, где кнопка одна (обычные каналы, VCA) -
        // остаётся полное "SOLO" (задаётся в самом XML по умолчанию).
        if (showSoloB) {
            soloButton.textOn = "A"
            soloButton.textOff = "A"
            soloButton.text = "A"
        }
        soloBButton.backgroundTintList = null
        soloBButton.stateListAnimator = null
        soloBButton.setBackgroundColor(Color.parseColor(if (initialSoloB) "#ff9f0a" else "#3a3a3c"))
        soloBButton.isChecked = initialSoloB

        val ui = SimpleStripUi(strip, labelView, headerView, fader, levelText, muteButton, soloButton, soloBButton, meterBar)

        faderContainer.viewTreeObserver.addOnGlobalLayoutListener(
            object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    val h = faderContainer.height
                    if (h > 0) {
                        val p = fader.layoutParams
                        if (p.width != h) {
                            p.width = h
                            fader.layoutParams = p
                        }
                        faderContainer.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    }
                }
            }
        )

        fader.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val level = progress / 1000f
                levelText.text = "%.2f".format(level)
                if (!fromUser || ui.suppressEvents) return
                val now = System.currentTimeMillis()
                if (now - ui.lastFaderSendTime >= minSendIntervalMs) {
                    ui.lastFaderSendTime = now
                    onFader(level)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                if (ui.suppressEvents) return
                onFader((sb?.progress ?: 0) / 1000f)
            }
        })

        muteButton.setOnClickListener {
            val newState = !ui.mutedLocal
            ui.mutedLocal = newState
            muteButton.setBackgroundColor(Color.parseColor(if (newState) "#ff3b30" else "#3a3a3c"))
            onMute(newState)
        }
        soloButton.setOnCheckedChangeListener { _, isChecked ->
            if (ui.suppressEvents) return@setOnCheckedChangeListener
            soloButton.setBackgroundColor(Color.parseColor(if (isChecked) "#ff9f0a" else "#3a3a3c"))
            onSolo(isChecked)
        }
        soloBButton.setOnCheckedChangeListener { _, isChecked ->
            if (ui.suppressEvents) return@setOnCheckedChangeListener
            soloBButton.setBackgroundColor(Color.parseColor(if (isChecked) "#ff9f0a" else "#3a3a3c"))
            onSoloB(isChecked)
        }

        // Восстановление после поворота экрана - сразу подтягиваем последние
        // известные значения, не дожидаясь нового push.
        ui.suppressEvents = true
        fader.progress = (initialFader * 1000).toInt()
        levelText.text = "%.2f".format(initialFader)
        ui.suppressEvents = false
        ui.mutedLocal = initialMuted
        muteButton.setBackgroundColor(Color.parseColor(if (initialMuted) "#ff3b30" else "#3a3a3c"))

        container.addView(strip)
        return ui
    }

    /** Мастер (3 канала по мануалу) - НЕ подтверждено реальным захватом. */
    private fun buildMasterStrips() {
        val container = findViewById<android.widget.LinearLayout>(R.id.containerMaster)
        container.removeAllViews()
        masterStrips.clear()
        for (m in 0 until 3) {
            val data = ConnectionHolder.masterData[m]
            val ui = buildSimpleStrip(
                container, "M ${m + 1}", data.fader, data.mutedLocal, data.soloBLocal,
                onFader = { level -> ConnectionHolder.masterData[m].fader = level; sendMasterFader(m, level) },
                onMute = { muted -> ConnectionHolder.masterData[m].mutedLocal = muted; sendMasterMute(m, true) },
                onSolo = { soloed -> sendMasterSolo(m, soloed) },
                onSoloB = { soloed -> ConnectionHolder.masterData[m].soloBLocal = soloed; sendRawAsync(Pro2Commands.setMasterSoloB(m, soloed)) }
            )
            masterStrips.add(ui)
        }
    }

    /** Aux Returns (8 шт. по мануалу) - НЕ подтверждено реальным захватом. */
    private fun buildAuxStrips() {
        val container = findViewById<android.widget.LinearLayout>(R.id.containerAux)
        container.removeAllViews()
        auxStrips.clear()
        for (a in 0 until 8) {
            val data = ConnectionHolder.auxReturnData[a]
            val ui = buildSimpleStrip(
                container, "AUX ${a + 1}", data.fader, data.mutedLocal, data.soloBLocal,
                onFader = { level -> ConnectionHolder.auxReturnData[a].fader = level; sendAuxReturnFader(a, level) },
                onMute = { muted -> ConnectionHolder.auxReturnData[a].mutedLocal = muted; sendAuxReturnMute(a, true) },
                onSolo = { soloed -> sendAuxReturnSolo(a, soloed) },
                onSoloB = { soloed -> ConnectionHolder.auxReturnData[a].soloBLocal = soloed; sendRawAsync(Pro2Commands.setAuxReturnSoloB(a, soloed)) }
            )
            auxStrips.add(ui)
        }
    }

    /** 16 aux-шин (собственный уровень шины) - НЕ подтверждено реальным захватом. */
    private fun buildAuxBusStrips() {
        val container = findViewById<android.widget.LinearLayout>(R.id.containerAuxBus)
        container.removeAllViews()
        auxBusStrips.clear()
        for (b in 0 until 16) {
            val data = ConnectionHolder.auxBusData[b]
            val ui = buildSimpleStrip(
                container, "BUS ${b + 1}", data.fader, data.mutedLocal, data.soloBLocal,
                onFader = { level -> ConnectionHolder.auxBusData[b].fader = level; sendAuxBusFader(b, level) },
                onMute = { muted -> ConnectionHolder.auxBusData[b].mutedLocal = muted; sendAuxBusMute(b, true) },
                onSolo = { soloed -> sendAuxBusSolo(b, soloed) },
                onSoloB = { soloed -> ConnectionHolder.auxBusData[b].soloBLocal = soloed; sendRawAsync(Pro2Commands.setAuxBusSoloB(b, soloed)) }
            )
            auxBusStrips.add(ui)
        }
    }

    /** VCA-группы (8 шт.) - ПОЛНОСТЬЮ ПОДТВЕРЖДЕНО реальным трафиком iPad. */
    private fun buildVcaStrips() {
        val container = findViewById<android.widget.LinearLayout>(R.id.containerVca)
        container.removeAllViews()
        vcaStrips.clear()
        for (v in 0 until 8) {
            val data = ConnectionHolder.vcaData[v]
            val ui = buildSimpleStrip(
                container, "VCA ${v + 1}", data.fader, data.mutedLocal, initialSoloB = false,
                onFader = { level -> ConnectionHolder.vcaData[v].fader = level; sendVcaFader(v, level) },
                onMute = { muted -> ConnectionHolder.vcaData[v].mutedLocal = muted; sendVcaMute(v, true) },
                onSolo = { soloed -> sendVcaSolo(v, soloed) },
                onSoloB = { /* Solo B у VCA НЕ подтверждено реальным захватом - кнопка скрыта. */ },
                showSoloB = false
            )
            vcaStrips.add(ui)
            ui.headerView.setOnClickListener { openVcaMembers(v) }
        }
    }

    /**
     * Main Outs (8 позиций, "matrix out" на самом пульте). Базовая полоса
     * (фейдер/mute/solo/имя/цвет) - см. подробную заметку у
     * Pro2Commands.mainOut*Address(). EQ/компрессор Main Outs пока не
     * реализованы.
     */
    private fun buildMainOutStrips() {
        val container = findViewById<android.widget.LinearLayout>(R.id.containerMainOuts)
        container.removeAllViews()
        mainOutStrips.clear()
        for (m in 0 until 8) {
            val data = ConnectionHolder.mainOutData[m]
            val ui = buildSimpleStrip(
                container, "MAIN ${m + 1}", data.fader, data.mutedLocal, initialSoloB = false,
                onFader = { level -> ConnectionHolder.mainOutData[m].fader = level; sendRawAsync(Pro2Commands.setMainOutFader(m, level)) },
                onMute = { muted -> ConnectionHolder.mainOutData[m].mutedLocal = muted; sendRawAsync(Pro2Commands.setMainOutMute(m, true)) },
                onSolo = { soloed -> sendRawAsync(Pro2Commands.setMainOutSolo(m, soloed)) },
                onSoloB = { /* Solo B у Main Outs не подтверждено, кнопка скрыта. */ },
                showSoloB = false
            )
            mainOutStrips.add(ui)
        }
    }

    // Порядок вкладок: КАНАЛЫ, AUX RETURNS, AUX BUSES, MASTER - мастер
    // намеренно в конце, по просьбе (обычно с ним работают реже всего).
    private var currentStripMode = StripMode.CHANNELS

    private fun buildModeButtons() {
        val row = findViewById<android.widget.LinearLayout>(R.id.modeButtonsRow)
        row.removeAllViews()

        fun makeTab(text: String, mode: StripMode): Button {
            val btn = Button(this)
            btn.text = text
            btn.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.bank_button_text_size))
            btn.backgroundTintList = null
            btn.stateListAnimator = null
            btn.minHeight = 0
            btn.minimumHeight = 0
            val vPad = resources.getDimensionPixelSize(R.dimen.bank_button_v_padding)
            btn.setPadding(24, vPad, 24, vPad)
            btn.setTextColor(Color.parseColor("#ffffff"))
            btn.setBackgroundColor(Color.parseColor("#3a3a3c"))
            val params = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.marginEnd = 8
            btn.layoutParams = params
            btn.setOnClickListener { switchStripMode(mode) }
            row.addView(btn)
            return btn
        }

        // Порядок как попросили: каналы, aux returns, aux-шины, VCA, мастер - в конце.
        val btnChannels = makeTab("CHANNELS", StripMode.CHANNELS)
        val btnAux = makeTab("AUX RETURNS", StripMode.AUX_RETURNS)
        val btnAuxBus = makeTab("AUX BUSES", StripMode.AUX_BUS)
        val btnVca = makeTab("VCA", StripMode.VCA)
        val btnMaster = makeTab("MASTER", StripMode.MASTER)
        val btnMainOuts = makeTab("MAIN OUTS", StripMode.MAIN_OUTS)
        modeButtons = mapOf(
            StripMode.CHANNELS to btnChannels,
            StripMode.AUX_RETURNS to btnAux,
            StripMode.AUX_BUS to btnAuxBus,
            StripMode.VCA to btnVca,
            StripMode.MASTER to btnMaster,
            StripMode.MAIN_OUTS to btnMainOuts
        )

        switchStripMode(ConnectionHolder.uiStripMode)

        // ВАЖНО (исправление сброса при повороте экрана): если детальный
        // экран канала был открыт ДО поворота (пересоздания активности) -
        // переоткрываем его автоматически, на той же вкладке, что и была
        // (данные пережили пересоздание в ConnectionHolder, но сам ЭКРАН
        // без этого вызова остался бы закрытым - активность просто
        // построила бы заново обычный список каналов).
        ConnectionHolder.openDetailChannel?.let { ch ->
            if (ch in 0 until numChannels) {
                openChannelDetail(ch)
            }
        }
    }

    private var modeButtons: Map<StripMode, Button> = emptyMap()

    private fun switchStripMode(mode: StripMode) {
        currentStripMode = mode
        ConnectionHolder.uiStripMode = mode
        findViewById<android.view.View>(R.id.containerChannels).visibility =
            if (mode == StripMode.CHANNELS) android.view.View.VISIBLE else android.view.View.GONE
        findViewById<android.view.View>(R.id.containerAux).visibility =
            if (mode == StripMode.AUX_RETURNS) android.view.View.VISIBLE else android.view.View.GONE
        findViewById<android.view.View>(R.id.containerAuxBus).visibility =
            if (mode == StripMode.AUX_BUS) android.view.View.VISIBLE else android.view.View.GONE
        findViewById<android.view.View>(R.id.containerVca).visibility =
            if (mode == StripMode.VCA) android.view.View.VISIBLE else android.view.View.GONE
        findViewById<android.view.View>(R.id.containerMaster).visibility =
            if (mode == StripMode.MASTER) android.view.View.VISIBLE else android.view.View.GONE
        findViewById<android.view.View>(R.id.containerMainOuts).visibility =
            if (mode == StripMode.MAIN_OUTS) android.view.View.VISIBLE else android.view.View.GONE
        // Банки (1-8, 9-16 и т.д.) относятся только к обычным входным каналам.
        findViewById<android.view.View>(R.id.bankButtonsScroll).visibility =
            if (mode == StripMode.CHANNELS) android.view.View.VISIBLE else android.view.View.GONE

        for ((m, btn) in modeButtons) {
            val active = m == mode
            btn.setBackgroundColor(Color.parseColor(if (active) "#ff9f0a" else "#3a3a3c"))
            btn.setTextColor(Color.parseColor(if (active) "#000000" else "#ffffff"))
        }
    }



    /**
     * Облегчённая подписка специально для мониторного режима - только то,
     * что реально нужно для списка шин (фейдер/mute/имя/цвет по 16
     * aux-шинам, ~64 подписки вместо ~800 у инженерского режима). Посылы
     * конкретных каналов в выбранную шину подписываются отдельно и позже,
     * когда пользователь реально выберет шину (subscribeChannelSendsForBus).
     */

    /**
     * Отправляет "/renew" каждые 3 секунды, пока идёт сессия.
     *
     * ПОДТВЕРЖДЕНО: без этого пульт, судя по всему, отключает нашу подписку
     * по таймауту через какое-то время после подключения - живые обновления
     * приходят недолго, а потом полностью прекращаются насовсем (не просто с
     * задержкой). Реальный Mixtender 2 отправляет "/renew" именно с таким
     * интервалом (~3 сек) постоянно, всё время, пока идёт сессия - это,
     * по всей видимости, продление "аренды" подписки на стороне пульта.
     */


    /**
     * Обрабатываем одно "плоское" сообщение (уже развёрнутое из бандла, если было).
     *
     * Два независимых пути:
     * 1) Сообщение адресовано одному из НАШИХ хендлов подписки (см. subscribeAll) -
     *    это и есть живое обновление параметра, которое мы явно запросили.
     * 2) Старый путь на человекочитаемый адрес (Pro2Commands.faderAddress() и т.п.) -
     *    оставлен на случай, если пульт когда-нибудь всё же ответит так напрямую,
     *    но по факту (подтверждено захватом трафика) пульт так не отвечает никогда -
     *    этот путь, скорее всего, никогда не сработает на реальном пульте.
     */

    /**
     * Отправляет подписку на mute/solo/fader/gain/имя для каждого канала.
     * Вызывается один раз за сессию, как только станет известен токен пульта.
     *
     * ВНИМАНИЕ - НЕ ДО КОНЦА ПОДТВЕРЖДЕНО: формат самой подписки (/batchsubscribe)
     * и разбор blob для СТРОК (имя) подтверждены захватом реального трафика.
     * А вот то, как именно упакован blob для mute/solo (переключатель) и
     * fader/gain (float 0..1), напрямую захватом НЕ проверялось - в захваченном
     * трафике встретились только подписки на метры (однобайтовый blob) и на
     * имя пульта (строка). Ниже - осторожное предположение с запасными
     * вариантами; перед тем как полагаться на это в реальной работе, стоит
     * сделать ещё один прицельный захват (подписаться и подвигать mute/fader
     * в самом Mixtender) и свериться.
     */

    /**
     * Подписывается на 16 aux-посылов ОДНОГО канала. Не делается сразу для
     * всех 56 каналов при подключении - это ещё ~900 подписок сверху, а
     * посылы реально нужны только когда открыта вкладка SENDS конкретного
     * канала. Вызывается один раз при первом открытии этой вкладки.
     */

    /**
     * Подписывается на EQ ОДНОГО канала (17 параметров: общий IN + 4 полосы
     * × (активность + частота + гейн + ширина)). Лениво, только при первом
     * открытии вкладки EQ - как и с посылами.
     */

    /** Лениво подписывается на 9 параметров Gate одного канала. */
    /**
     * Лениво подписывается на pan/48V/фазу/GAIN TRIM/HP-LP-фильтры/задержку/
     * Solo B этого канала - раньше все эти 10 параметров были в общей
     * мгновенной подписке при подключении (56 каналов × 10 = 560 лишних
     * подписок), что, судя по всему, перегружало пульт настолько, что
     * сбоил даже официальный Mixtender на другом устройстве. Теперь - как
     * и с EQ/Gate/Sends - только при реальном открытии вкладки INPUT.
     */

    /**
     * Лениво подписывается на фильтр компрессора и GR/detector-метры
     * (компрессор и gate) этого канала - тоже раньше были в мгновенной
     * подписке, тоже переведены на ленивую (см. заметку у subscribeInputExtras).
     */


    /** Разбор blob-значения из push-обновления по подписке. См. примечание к subscribeAll(). */
    internal fun handleSubscribedValue(sub: Subscription, blob: ByteArray) {
        // ПОДТВЕРЖДЕНО реальным захватом трафика нашего же приложения (подписка +
        // движение фейдеров): числа в blob идут в LITTLE-ENDIAN, а не big-endian,
        // как я предполагал раньше без проверки. Это отличается от команд
        // --set/--get, которые кодируются в big-endian - протокол в этом плане
        // непоследователен, но факт есть факт: без этой правки float-значения
        // (fader/gain) читались как околонулевой мусор, из-за чего казалось,
        // что изменения на пульте вообще не доходят до приложения.
        when (sub.kind) {
            ParamKind.NAME -> {
                val name = String(blob, Charsets.US_ASCII).trimEnd('\u0000')
                updateNameUi(sub.channel, name)
            }
            ParamKind.MUTE -> {
                // Подтверждено: 4 байта, little-endian int32 (0/1).
                val muted = blob.size >= 4 &&
                    ByteBuffer.wrap(blob).order(java.nio.ByteOrder.LITTLE_ENDIAN).int != 0
                updateMuteUi(sub.channel, muted)
            }
            ParamKind.SOLO -> {
                // Подтверждено: та же кодировка, что и mute.
                val soloed = blob.size >= 4 &&
                    ByteBuffer.wrap(blob).order(java.nio.ByteOrder.LITTLE_ENDIAN).int != 0
                updateSoloUi(sub.channel, soloed)
            }
            ParamKind.SOLO_B -> {
                val soloed = blob.size >= 4 &&
                    ByteBuffer.wrap(blob).order(java.nio.ByteOrder.LITTLE_ENDIAN).int != 0
                ConnectionHolder.channelData[sub.channel].soloBLocal = soloed
                if (openDetailChannel == sub.channel) {
                    detailSoloBRef?.isChecked = soloed
                    detailSoloBRef?.setBackgroundColor(Color.parseColor(if (soloed) "#ff9f0a" else "#3a3a3c"))
                }
            }
            ParamKind.FADER, ParamKind.GAIN -> {
                // Подтверждено: 4 байта, little-endian float32, диапазон 0..1.
                if (blob.size < 4) return
                val level = ByteBuffer.wrap(blob).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    .float.coerceIn(0f, 1f)
                if (sub.kind == ParamKind.FADER) updateFaderUi(sub.channel, level)
                else updateGainUi(sub.channel, level)
            }
            ParamKind.GAIN_TRIM -> {
                if (blob.size >= 4) {
                    val level = ByteBuffer.wrap(blob).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                        .float.coerceIn(0f, 1f)
                    updateGainTrimUi(sub.channel, level)
                }
            }
            ParamKind.COLOUR -> {
                // ОКОНЧАТЕЛЬНО ПОДТВЕРЖДЕНО реальным захватом (сменили канал 1
                // на красный прямо на пульте и поймали push): blob - это НЕ
                // big/little-endian вариант обычного ARGB-int, а байты идут в
                // порядке R, G, B, A по отдельности. Например:
                //   00 C8 FF FF -> R=00 G=C8 B=FF A=FF (голубой - как и было
                //                  на большинстве каналов до смены)
                //   FF C8 00 FF -> R=FF G=C8 B=00 A=FF (жёлто-оранжевый -
                //                  как раз канал 5, который был жёлтым)
                //   FF 00 00 FF -> R=FF G=00 B=00 A=FF (чистый красный -
                //                  ровно то, что вы поставили на канале 1)
                if (blob.size >= 4) {
                    val r = blob[0].toInt() and 0xFF
                    val g = blob[1].toInt() and 0xFF
                    val b = blob[2].toInt() and 0xFF
                    val a = blob[3].toInt() and 0xFF
                    val argb = (a shl 24) or (r shl 16) or (g shl 8) or b
                    updateColourUi(sub.channel, argb)
                }
            }
            ParamKind.METER -> {
                // Подтверждено реальным трафиком (первый же захват в самом
                // начале проекта): значение приходит как 1 байт. Наблюдаемый
                // диапазон в наших захватах был 0..~130 (не полные 0..255) -
                // ИСПРАВЛЕНО: раньше делили на 127 - эта цифра была взята из
                // очень ограниченной выборки (тихие тестовые записи, где
                // сигнал просто никогда не доходил до настоящего максимума).
                // Судя по вашему замеру (-10 дБ на пульте показывало почти
                // полный красный у нас), потолок был занижен - берём полный
                // диапазон байта 0..255 (стандартное предположение для
                // однобайтового метра). Точную формулу перевода в дБ без
                // калибровочного захвата (известный уровень на пульте +
                // соответствующий сырой байт) подобрать нельзя - если и это
                // окажется не совсем точным, нужен ещё один прицельный замер.
                if (blob.isNotEmpty()) {
                    val raw = blob[0].toInt() and 0xFF
                    val level = (raw / 255f).coerceIn(0f, 1f)
                    updateMeterUi(sub.channel, level)
                }
            }
            ParamKind.COMP_RATIO, ParamKind.COMP_ATTACK, ParamKind.COMP_RELEASE,
            ParamKind.COMP_THRESHOLD, ParamKind.COMP_MAKEUP -> {
                // Компрессор - это тоже enPPCRotaryMessage, как gain/fader, поэтому
                // используем тот же подтверждённый little-endian float32 разбор.
                if (blob.size >= 4) {
                    val level = ByteBuffer.wrap(blob).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                        .float.coerceIn(0f, 1f)
                    updateCompParamUi(sub.channel, sub.kind, level)
                }
            }
            ParamKind.COMP_IN -> {
                // enPPCSwitchMessage, как mute/solo - та же little-endian int32 логика.
                val on = blob.size >= 4 &&
                    ByteBuffer.wrap(blob).order(java.nio.ByteOrder.LITTLE_ENDIAN).int != 0
                updateCompInUi(sub.channel, on)
            }
            ParamKind.COMP_FILTERS_IN -> {
                val on = blob.size >= 4 &&
                    ByteBuffer.wrap(blob).order(java.nio.ByteOrder.LITTLE_ENDIAN).int != 0
                ConnectionHolder.channelData[sub.channel].compFiltersInLocal = on
                if (openDetailChannel == sub.channel) {
                    val btn = detailCompDynViews?.filtersInButton
                    btn?.text = if (on) "FILTER ON" else "FILTER OFF"
                    btn?.setBackgroundColor(Color.parseColor(if (on) "#ff9f0a" else "#3a3a3c"))
                }
            }
            ParamKind.COMP_FILTER_FREQ -> {
                if (blob.size >= 4) {
                    val level = ByteBuffer.wrap(blob).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                        .float.coerceIn(0f, 1f)
                    ConnectionHolder.channelData[sub.channel].compFilterFreq = level
                    if (openDetailChannel == sub.channel) {
                        updateDynamicsKnobUi(detailCompDynViews, ParamKind.COMP_FILTER_FREQ, level)
                    }
                }
            }
            ParamKind.AUX_SEND -> {
                // НЕ подтверждено реальным захватом - по аналогии с gain/fader
                // (тот же тип enPPCRotaryMessage), пробуем little-endian float32.
                if (blob.size >= 4) {
                    val level = ByteBuffer.wrap(blob).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                        .float.coerceIn(0f, 1f)
                    updateAuxSendUi(sub.channel, sub.auxBus, level)
                }
            }
            ParamKind.EQ_IN -> {
                val on = blob.size >= 4 &&
                    ByteBuffer.wrap(blob).order(java.nio.ByteOrder.LITTLE_ENDIAN).int != 0
                updateEqInUi(sub.channel, on)
            }
            ParamKind.EQ_BAND_ACTIVE -> {
                val on = blob.size >= 4 &&
                    ByteBuffer.wrap(blob).order(java.nio.ByteOrder.LITTLE_ENDIAN).int != 0
                updateEqBandActiveUi(sub.channel, sub.eqBand, on)
            }
            ParamKind.EQ_FREQ, ParamKind.EQ_GAIN, ParamKind.EQ_WIDTH -> {
                if (blob.size >= 4) {
                    val level = ByteBuffer.wrap(blob).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                        .float.coerceIn(0f, 1f)
                    updateEqParamUi(sub.channel, sub.eqBand, sub.kind, level)
                }
            }
            ParamKind.EQ_SHAPE_BASS -> {
                val isShelf = blob.size >= 4 &&
                    ByteBuffer.wrap(blob).order(java.nio.ByteOrder.LITTLE_ENDIAN).int != 0
                ConnectionHolder.channelData[sub.channel].eqBassShelf = isShelf
                if (openDetailChannel == sub.channel) {
                    detailEqViews?.bassShapeButton?.text = if (isShelf) "SHELF" else "BELL"
                }
            }
            ParamKind.EQ_SHAPE_TREBLE -> {
                val isShelf = blob.size >= 4 &&
                    ByteBuffer.wrap(blob).order(java.nio.ByteOrder.LITTLE_ENDIAN).int != 0
                ConnectionHolder.channelData[sub.channel].eqTrebleShelf = isShelf
                if (openDetailChannel == sub.channel) {
                    detailEqViews?.trebleShapeButton?.text = if (isShelf) "SHELF" else "BELL"
                }
            }
            ParamKind.PAN -> {
                if (blob.size >= 4) {
                    val level = ByteBuffer.wrap(blob).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                        .float.coerceIn(0f, 1f)
                    updatePanUi(sub.channel, level)
                }
            }
            ParamKind.PHANTOM -> {
                val on = blob.size >= 4 &&
                    ByteBuffer.wrap(blob).order(java.nio.ByteOrder.LITTLE_ENDIAN).int != 0
                updatePhantomUi(sub.channel, on)
            }
            ParamKind.PHASE -> {
                val on = blob.size >= 4 &&
                    ByteBuffer.wrap(blob).order(java.nio.ByteOrder.LITTLE_ENDIAN).int != 0
                updatePhaseUi(sub.channel, on)
            }
            ParamKind.LINK -> {
                val on = blob.size >= 4 &&
                    ByteBuffer.wrap(blob).order(java.nio.ByteOrder.LITTLE_ENDIAN).int != 0
                ConnectionHolder.channelData[sub.channel].linkedLocal = on
                if (openDetailChannel == sub.channel) {
                    detailLinkButton?.text = if (on) "LINK ON" else "LINK OFF"
                    detailLinkButton?.setBackgroundColor(Color.parseColor(if (on) "#ff9f0a" else "#3a3a3c"))
                }
            }
            ParamKind.HP_FILTER_IN -> {
                val on = blob.size >= 4 &&
                    ByteBuffer.wrap(blob).order(java.nio.ByteOrder.LITTLE_ENDIAN).int != 0
                ConnectionHolder.channelData[sub.channel].hpFilterInLocal = on
                if (openDetailChannel == sub.channel) {
                    detailHpFilterInButton?.text = if (on) "HP FILTER ON" else "HP FILTER OFF"
                    detailHpFilterInButton?.setBackgroundColor(Color.parseColor(if (on) "#ff9f0a" else "#3a3a3c"))
                    detailEqViews?.let { it.graphView.hpOn = on; it.graphView.invalidate() }
                }
            }
            ParamKind.LP_FILTER_IN -> {
                val on = blob.size >= 4 &&
                    ByteBuffer.wrap(blob).order(java.nio.ByteOrder.LITTLE_ENDIAN).int != 0
                ConnectionHolder.channelData[sub.channel].lpFilterInLocal = on
                if (openDetailChannel == sub.channel) {
                    detailLpFilterInButton?.text = if (on) "LP FILTER ON" else "LP FILTER OFF"
                    detailLpFilterInButton?.setBackgroundColor(Color.parseColor(if (on) "#ff9f0a" else "#3a3a3c"))
                    detailEqViews?.let { it.graphView.lpOn = on; it.graphView.invalidate() }
                }
            }
            ParamKind.HP_FILTER_FREQ -> {
                if (blob.size >= 4) {
                    val level = ByteBuffer.wrap(blob).order(java.nio.ByteOrder.LITTLE_ENDIAN).float.coerceIn(0f, 1f)
                    ConnectionHolder.channelData[sub.channel].hpFilterFreq = level
                    if (openDetailChannel == sub.channel) {
                        detailHpFreqSeek?.progress = (level * 1000).toInt()
                        detailEqViews?.let { it.graphView.hpFreq = level; it.graphView.invalidate() }
                    }
                }
            }
            ParamKind.LP_FILTER_FREQ -> {
                if (blob.size >= 4) {
                    val level = ByteBuffer.wrap(blob).order(java.nio.ByteOrder.LITTLE_ENDIAN).float.coerceIn(0f, 1f)
                    ConnectionHolder.channelData[sub.channel].lpFilterFreq = level
                    if (openDetailChannel == sub.channel) {
                        detailLpFreqSeek?.progress = (level * 1000).toInt()
                        detailEqViews?.let { it.graphView.lpFreq = level; it.graphView.invalidate() }
                    }
                }
            }
            ParamKind.INPUT_DELAY -> {
                if (blob.size >= 4) {
                    val level = ByteBuffer.wrap(blob).order(java.nio.ByteOrder.LITTLE_ENDIAN).float.coerceIn(0f, 1f)
                    ConnectionHolder.channelData[sub.channel].inputDelay = level
                    if (openDetailChannel == sub.channel) detailDelaySeek?.progress = (level * 1000).toInt()
                }
            }
            ParamKind.COMP_GR_METER -> {
                if (blob.isNotEmpty()) {
                    val level = ((blob[0].toInt() and 0xFF) / 255f).coerceIn(0f, 1f)
                    ConnectionHolder.channelData[sub.channel].compGrMeter = level
                    if (openDetailChannel == sub.channel) {
                        detailCompDynViews?.grText?.text = "GR -%.1f".format(level * 20f)
                    }
                }
            }
            ParamKind.GATE_GR_METER -> {
                if (blob.isNotEmpty()) {
                    val level = ((blob[0].toInt() and 0xFF) / 255f).coerceIn(0f, 1f)
                    ConnectionHolder.channelData[sub.channel].gateGrMeter = level
                    if (openDetailChannel == sub.channel) {
                        detailGateDynViews?.grText?.text = "GR -%.1f".format(level * 60f)
                    }
                }
            }
            ParamKind.COMP_DET_METER -> {
                if (blob.isNotEmpty()) {
                    val level = ((blob[0].toInt() and 0xFF) / 255f).coerceIn(0f, 1f)
                    ConnectionHolder.channelData[sub.channel].compDetMeter = level
                    if (openDetailChannel == sub.channel) {
                        detailCompDynViews?.detText?.text = "IN %.1f".format(level * 60f - 60f)
                    }
                }
            }
            ParamKind.GATE_DET_METER -> {
                if (blob.isNotEmpty()) {
                    val level = ((blob[0].toInt() and 0xFF) / 255f).coerceIn(0f, 1f)
                    ConnectionHolder.channelData[sub.channel].gateDetMeter = level
                    if (openDetailChannel == sub.channel) {
                        detailGateDynViews?.detText?.text = "IN %.1f".format(level * 60f - 60f)
                    }
                }
            }
            ParamKind.COMP_MODE -> {
                if (blob.size >= 4) {
                    val mode = ByteBuffer.wrap(blob).order(java.nio.ByteOrder.LITTLE_ENDIAN).int
                    ConnectionHolder.channelData[sub.channel].compMode = mode
                    if (openDetailChannel == sub.channel) {
                        detailCompDynViews?.modeText?.text = "MODE: $mode"
                    }
                }
            }
            ParamKind.GATE_MODE -> {
                if (blob.size >= 4) {
                    val mode = ByteBuffer.wrap(blob).order(java.nio.ByteOrder.LITTLE_ENDIAN).int
                    ConnectionHolder.channelData[sub.channel].gateMode = mode
                    if (openDetailChannel == sub.channel) {
                        detailGateDynViews?.modeText?.text = "MODE: $mode"
                    }
                }
            }
            ParamKind.AUX_SEND_ENABLE -> {
                val on = blob.size >= 4 &&
                    ByteBuffer.wrap(blob).order(java.nio.ByteOrder.LITTLE_ENDIAN).int != 0
                if (sub.auxBus in 1..16) ConnectionHolder.channelData[sub.channel].auxSendEnable[sub.auxBus - 1] = on
            }
            ParamKind.AUX_SEND_PREFADE -> {
                val on = blob.size >= 4 &&
                    ByteBuffer.wrap(blob).order(java.nio.ByteOrder.LITTLE_ENDIAN).int != 0
                if (sub.auxBus in 1..16) ConnectionHolder.channelData[sub.channel].auxSendPreFade[sub.auxBus - 1] = on
            }
            ParamKind.GATE_IN -> {
                val on = blob.size >= 4 &&
                    ByteBuffer.wrap(blob).order(java.nio.ByteOrder.LITTLE_ENDIAN).int != 0
                updateGateInUi(sub.channel, on)
            }
            ParamKind.GATE_FILTERS_IN -> {
                val on = blob.size >= 4 &&
                    ByteBuffer.wrap(blob).order(java.nio.ByteOrder.LITTLE_ENDIAN).int != 0
                updateGateFiltersInUi(sub.channel, on)
            }
            ParamKind.GATE_THRESHOLD, ParamKind.GATE_RANGE, ParamKind.GATE_ATTACK,
            ParamKind.GATE_HOLD, ParamKind.GATE_RELEASE, ParamKind.GATE_TRANSIENT,
            ParamKind.GATE_FILTER_FREQ -> {
                if (blob.size >= 4) {
                    val level = ByteBuffer.wrap(blob).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                        .float.coerceIn(0f, 1f)
                    updateGateParamUi(sub.channel, sub.kind, level)
                }
            }
        }
    }

    /** Разбор push-обновления для мастер-каналов - НЕ подтверждено реальным захватом. */
    internal fun handleMasterSubscribedValue(sub: Subscription, blob: ByteArray) {
        when (sub.kind) {
            ParamKind.FADER -> {
                if (blob.size < 4) return
                val level = ByteBuffer.wrap(blob).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    .float.coerceIn(0f, 1f)
                updateMasterFaderUi(sub.channel, level)
            }
            ParamKind.MUTE -> {
                val muted = blob.size >= 4 &&
                    ByteBuffer.wrap(blob).order(java.nio.ByteOrder.LITTLE_ENDIAN).int != 0
                updateMasterMuteUi(sub.channel, muted)
            }
            ParamKind.SOLO -> {
                val soloed = blob.size >= 4 &&
                    ByteBuffer.wrap(blob).order(java.nio.ByteOrder.LITTLE_ENDIAN).int != 0
                updateMasterSoloUi(sub.channel, soloed)
            }
            ParamKind.SOLO_B -> {
                val soloed = blob.size >= 4 &&
                    ByteBuffer.wrap(blob).order(java.nio.ByteOrder.LITTLE_ENDIAN).int != 0
                ConnectionHolder.masterData.getOrNull(sub.channel)?.soloBLocal = soloed
                updateSimpleStripSoloB(masterStrips, sub.channel, soloed)
            }
            ParamKind.METER -> {
                if (blob.isEmpty()) return
                val level = ((blob[0].toInt() and 0xFF) / 255f).coerceIn(0f, 1f)
                updateSimpleStripMeter(masterStrips, sub.channel, level)
            }
            ParamKind.NAME -> {
                val name = String(blob, Charsets.US_ASCII).trimEnd('\u0000')
                if (name.isBlank()) return
                ConnectionHolder.masterData.getOrNull(sub.channel)?.name = name
                masterStrips.getOrNull(sub.channel)?.labelView?.text = name
            }
            else -> {}
        }
    }

    /** Разбор push-обновления для aux returns - НЕ подтверждено реальным захватом. */
    internal fun handleAuxReturnSubscribedValue(sub: Subscription, blob: ByteArray) {
        when (sub.kind) {
            ParamKind.FADER -> {
                if (blob.size < 4) return
                val level = ByteBuffer.wrap(blob).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    .float.coerceIn(0f, 1f)
                updateAuxReturnFaderUi(sub.channel, level)
            }
            ParamKind.MUTE -> {
                val muted = blob.size >= 4 &&
                    ByteBuffer.wrap(blob).order(java.nio.ByteOrder.LITTLE_ENDIAN).int != 0
                updateAuxReturnMuteUi(sub.channel, muted)
            }
            ParamKind.SOLO -> {
                val soloed = blob.size >= 4 &&
                    ByteBuffer.wrap(blob).order(java.nio.ByteOrder.LITTLE_ENDIAN).int != 0
                updateAuxReturnSoloUi(sub.channel, soloed)
            }
            ParamKind.SOLO_B -> {
                val soloed = blob.size >= 4 &&
                    ByteBuffer.wrap(blob).order(java.nio.ByteOrder.LITTLE_ENDIAN).int != 0
                ConnectionHolder.auxReturnData.getOrNull(sub.channel)?.soloBLocal = soloed
                updateSimpleStripSoloB(auxStrips, sub.channel, soloed)
            }
            ParamKind.METER -> {
                if (blob.isEmpty()) return
                val level = ((blob[0].toInt() and 0xFF) / 255f).coerceIn(0f, 1f)
                updateSimpleStripMeter(auxStrips, sub.channel, level)
            }
            ParamKind.NAME -> {
                val name = String(blob, Charsets.US_ASCII).trimEnd('\u0000')
                if (name.isBlank()) return
                ConnectionHolder.auxReturnData.getOrNull(sub.channel)?.name = name
                auxStrips.getOrNull(sub.channel)?.labelView?.text = name
            }
            ParamKind.COLOUR -> {
                if (blob.size >= 4) {
                    val r = blob[0].toInt() and 0xFF
                    val g = blob[1].toInt() and 0xFF
                    val b = blob[2].toInt() and 0xFF
                    val a = blob[3].toInt() and 0xFF
                    val argb = (a shl 24) or (r shl 16) or (g shl 8) or b
                    ConnectionHolder.auxReturnData.getOrNull(sub.channel)?.colourArgb = argb
                    auxStrips.getOrNull(sub.channel)?.headerView?.setBackgroundColor(argb)
                }
            }
            else -> {}
        }
    }

    /** Разбор push-обновления для 16 aux-шин - НЕ подтверждено реальным захватом. */
    internal fun handleAuxBusSubscribedValue(sub: Subscription, blob: ByteArray) {
        when (sub.kind) {
            ParamKind.FADER -> {
                if (blob.size < 4) return
                val level = ByteBuffer.wrap(blob).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    .float.coerceIn(0f, 1f)
                ConnectionHolder.auxBusData[sub.channel].fader = level
                if (appMode == MODE_MONITOR && monitorSelectedBus == sub.channel) {
                    monitorBusFader?.progress = (level * 1000).toInt()
                }
                val ui = auxBusStrips.getOrNull(sub.channel) ?: return
                ui.suppressEvents = true
                ui.fader.progress = (level * 1000).toInt()
                ui.levelText.text = "%.2f".format(level)
                ui.suppressEvents = false
            }
            ParamKind.MUTE -> {
                val muted = blob.size >= 4 &&
                    ByteBuffer.wrap(blob).order(java.nio.ByteOrder.LITTLE_ENDIAN).int != 0
                ConnectionHolder.auxBusData[sub.channel].mutedLocal = muted
                if (appMode == MODE_MONITOR && monitorSelectedBus == sub.channel) {
                    monitorBusMutedLocal = muted
                    monitorBusMuteButton?.setBackgroundColor(Color.parseColor(if (muted) "#ff3b30" else "#3a3a3c"))
                }
                val ui = auxBusStrips.getOrNull(sub.channel) ?: return
                ui.mutedLocal = muted
                ui.muteButton.setBackgroundColor(Color.parseColor(if (muted) "#ff3b30" else "#3a3a3c"))
            }
            ParamKind.SOLO -> {
                val soloed = blob.size >= 4 &&
                    ByteBuffer.wrap(blob).order(java.nio.ByteOrder.LITTLE_ENDIAN).int != 0
                val ui = auxBusStrips.getOrNull(sub.channel) ?: return
                ui.suppressEvents = true
                ui.soloButton.isChecked = soloed
                ui.soloButton.setBackgroundColor(Color.parseColor(if (soloed) "#ff9f0a" else "#3a3a3c"))
                ui.suppressEvents = false
            }
            ParamKind.SOLO_B -> {
                val soloed = blob.size >= 4 &&
                    ByteBuffer.wrap(blob).order(java.nio.ByteOrder.LITTLE_ENDIAN).int != 0
                ConnectionHolder.auxBusData.getOrNull(sub.channel)?.soloBLocal = soloed
                updateSimpleStripSoloB(auxBusStrips, sub.channel, soloed)
            }
            ParamKind.METER -> {
                if (blob.isEmpty()) return
                val level = ((blob[0].toInt() and 0xFF) / 255f).coerceIn(0f, 1f)
                updateSimpleStripMeter(auxBusStrips, sub.channel, level)
            }
            ParamKind.NAME -> {
                val name = String(blob, Charsets.US_ASCII).trimEnd('\u0000')
                if (name.isBlank()) return
                ConnectionHolder.auxBusData.getOrNull(sub.channel)?.name = name
                auxBusStrips.getOrNull(sub.channel)?.labelView?.text = name
            }
            ParamKind.COLOUR -> {
                // Тот же подтверждённый формат, что и у каналов: 4 байта в
                // порядке R, G, B, A (не обычный ARGB).
                if (blob.size >= 4) {
                    val r = blob[0].toInt() and 0xFF
                    val g = blob[1].toInt() and 0xFF
                    val b = blob[2].toInt() and 0xFF
                    val a = blob[3].toInt() and 0xFF
                    val argb = (a shl 24) or (r shl 16) or (g shl 8) or b
                    ConnectionHolder.auxBusData.getOrNull(sub.channel)?.colourArgb = argb
                    auxBusStrips.getOrNull(sub.channel)?.headerView?.setBackgroundColor(argb)
                }
            }
            else -> {}
        }
    }

    /** Разбор push-обновления для VCA-групп - ПОЛНОСТЬЮ ПОДТВЕРЖДЕНО реальным захватом трафика iPad. */
    internal fun handleVcaSubscribedValue(sub: Subscription, blob: ByteArray) {
        when (sub.kind) {
            ParamKind.FADER -> {
                if (blob.size < 4) return
                val level = ByteBuffer.wrap(blob).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    .float.coerceIn(0f, 1f)
                ConnectionHolder.vcaData[sub.channel].fader = level
                val ui = vcaStrips.getOrNull(sub.channel) ?: return
                ui.suppressEvents = true
                ui.fader.progress = (level * 1000).toInt()
                ui.levelText.text = "%.2f".format(level)
                ui.suppressEvents = false
            }
            ParamKind.MUTE -> {
                val muted = blob.size >= 4 &&
                    ByteBuffer.wrap(blob).order(java.nio.ByteOrder.LITTLE_ENDIAN).int != 0
                ConnectionHolder.vcaData[sub.channel].mutedLocal = muted
                val ui = vcaStrips.getOrNull(sub.channel) ?: return
                ui.mutedLocal = muted
                ui.muteButton.setBackgroundColor(Color.parseColor(if (muted) "#ff3b30" else "#3a3a3c"))
            }
            ParamKind.SOLO -> {
                val soloed = blob.size >= 4 &&
                    ByteBuffer.wrap(blob).order(java.nio.ByteOrder.LITTLE_ENDIAN).int != 0
                val ui = vcaStrips.getOrNull(sub.channel) ?: return
                ui.suppressEvents = true
                ui.soloButton.isChecked = soloed
                ui.soloButton.setBackgroundColor(Color.parseColor(if (soloed) "#ff9f0a" else "#3a3a3c"))
                ui.suppressEvents = false
            }
            ParamKind.NAME -> {
                val name = String(blob, Charsets.US_ASCII).trimEnd('\u0000')
                if (name.isBlank()) return
                ConnectionHolder.vcaData.getOrNull(sub.channel)?.name = name
                vcaStrips.getOrNull(sub.channel)?.labelView?.text = name
            }
            ParamKind.COLOUR -> {
                if (blob.size >= 4) {
                    val r = blob[0].toInt() and 0xFF
                    val g = blob[1].toInt() and 0xFF
                    val b = blob[2].toInt() and 0xFF
                    val a = blob[3].toInt() and 0xFF
                    val argb = (a shl 24) or (r shl 16) or (g shl 8) or b
                    ConnectionHolder.vcaData.getOrNull(sub.channel)?.colourArgb = argb
                    vcaStrips.getOrNull(sub.channel)?.headerView?.setBackgroundColor(argb)
                }
            }
            else -> {}
        }
    }

    /**
     * Push-обновление членства одного "ребёнка" в VCA-группе (см. заметку
     * в VcaData/subscribeVcaMembers про степень подтверждённости).
     */
    internal fun handleVcaMemberSubscribedValue(sub: VcaMemberSub, blob: ByteArray) {
        val member = blob.size >= 4 &&
            ByteBuffer.wrap(blob).order(java.nio.ByteOrder.LITTLE_ENDIAN).int != 0
        val vca = ConnectionHolder.vcaData.getOrNull(sub.vcaIndex) ?: return
        when (sub.childType) {
            "input" -> vca.memberInput.getOrNull(sub.childIndex)?.let { vca.memberInput[sub.childIndex] = member }
            "submix" -> vca.memberSubMix.getOrNull(sub.childIndex)?.let { vca.memberSubMix[sub.childIndex] = member }
            "auxreturn" -> vca.memberAuxReturn.getOrNull(sub.childIndex)?.let { vca.memberAuxReturn[sub.childIndex] = member }
            "main" -> vca.memberMain.getOrNull(sub.childIndex)?.let { vca.memberMain[sub.childIndex] = member }
            "master" -> vca.memberMaster.getOrNull(sub.childIndex)?.let { vca.memberMaster[sub.childIndex] = member }
        }
        // Если экран VCA members сейчас открыт именно для этой группы -
        // подкрашиваем кнопку вживую, а не только на будущее открытие.
        if (openVcaMembersIndex == sub.vcaIndex) {
            val btn = vcaMemberButtons["${sub.childType}:${sub.childIndex}"] ?: return
            btn.setBackgroundColor(Color.parseColor(if (member) "#ff9f0a" else "#3a3a3c"))
            btn.setTextColor(Color.parseColor(if (member) "#000000" else "#ffffff"))
        }
    }

    /**
     * Main Outs (8 позиций) - базовая полоса. См. заметку в
     * Pro2Commands.kt у mainOut*Address() про степень подтверждённости.
     */
    internal fun handleMainOutSubscribedValue(sub: Subscription, blob: ByteArray) {
        when (sub.kind) {
            ParamKind.FADER -> {
                if (blob.size < 4) return
                val level = ByteBuffer.wrap(blob).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    .float.coerceIn(0f, 1f)
                ConnectionHolder.mainOutData[sub.channel].fader = level
                val ui = mainOutStrips.getOrNull(sub.channel) ?: return
                ui.suppressEvents = true
                ui.fader.progress = (level * 1000).toInt()
                ui.levelText.text = "%.2f".format(level)
                ui.suppressEvents = false
            }
            ParamKind.MUTE -> {
                val muted = blob.size >= 4 &&
                    ByteBuffer.wrap(blob).order(java.nio.ByteOrder.LITTLE_ENDIAN).int != 0
                ConnectionHolder.mainOutData[sub.channel].mutedLocal = muted
                val ui = mainOutStrips.getOrNull(sub.channel) ?: return
                ui.mutedLocal = muted
                ui.muteButton.setBackgroundColor(Color.parseColor(if (muted) "#ff3b30" else "#3a3a3c"))
            }
            ParamKind.SOLO -> {
                val soloed = blob.size >= 4 &&
                    ByteBuffer.wrap(blob).order(java.nio.ByteOrder.LITTLE_ENDIAN).int != 0
                val ui = mainOutStrips.getOrNull(sub.channel) ?: return
                ui.suppressEvents = true
                ui.soloButton.isChecked = soloed
                ui.soloButton.setBackgroundColor(Color.parseColor(if (soloed) "#ff9f0a" else "#3a3a3c"))
                ui.suppressEvents = false
            }
            ParamKind.NAME -> {
                val name = String(blob, Charsets.US_ASCII).trimEnd('\u0000')
                if (name.isBlank()) return
                ConnectionHolder.mainOutData.getOrNull(sub.channel)?.name = name
                mainOutStrips.getOrNull(sub.channel)?.labelView?.text = name
            }
            ParamKind.COLOUR -> {
                if (blob.size >= 4) {
                    val r = blob[0].toInt() and 0xFF
                    val g = blob[1].toInt() and 0xFF
                    val b = blob[2].toInt() and 0xFF
                    val a = blob[3].toInt() and 0xFF
                    val argb = (a shl 24) or (r shl 16) or (g shl 8) or b
                    ConnectionHolder.mainOutData.getOrNull(sub.channel)?.colourArgb = argb
                    mainOutStrips.getOrNull(sub.channel)?.headerView?.setBackgroundColor(argb)
                }
            }
            ParamKind.METER -> {
                if (blob.isNotEmpty()) {
                    val level = ((blob[0].toInt() and 0xFF) / 255f).coerceIn(0f, 1f)
                    updateSimpleStripMeter(mainOutStrips, sub.channel, level)
                }
            }
            else -> {}
        }
    }

    /** Общая функция подсветки метра для мастера/aux returns/aux-шин (все используют SimpleStripUi). */
    private fun updateSimpleStripMeter(list: List<SimpleStripUi>, index: Int, level: Float) {
        val ui = list.getOrNull(index) ?: return
        // Тот же переход на layoutParams.height, что и у обычных каналов -
        // см. заметку в updateMeterUi.
        applyMeterHeight(ui.meterBar, level)
        val color = when {
            level > 0.85f -> "#ff3b30"
            level > 0.6f -> "#ff9f0a"
            else -> "#34c759"
        }
        ui.meterBar.setBackgroundColor(Color.parseColor(color))
    }

    /** Общая функция для обновления Solo B у master/aux returns/aux-шин (все используют SimpleStripUi). */
    private fun updateSimpleStripSoloB(list: List<SimpleStripUi>, index: Int, soloed: Boolean) {
        val ui = list.getOrNull(index) ?: return
        ui.suppressEvents = true
        ui.soloBButton.isChecked = soloed
        ui.soloBButton.setBackgroundColor(Color.parseColor(if (soloed) "#ff9f0a" else "#3a3a3c"))
        ui.suppressEvents = false
    }

    private fun updateColourUi(channel: Int, argbColor: Int) {
        ConnectionHolder.channelData[channel].colourArgb = argbColor
        val ui = channels.getOrNull(channel) ?: return
        ui.headerView.setBackgroundColor(argbColor)

        // Чёрный или белый текст имени канала - в зависимости от яркости фона,
        // чтобы имя оставалось читаемым на любом цвете (светлый фон -> чёрный
        // текст, тёмный фон -> белый текст). Стандартная взвешенная формула
        // воспринимаемой яркости (без альфы - она у цветов каналов всегда FF).
        val r = (argbColor shr 16) and 0xFF
        val g = (argbColor shr 8) and 0xFF
        val b = argbColor and 0xFF
        val luminance = 0.299 * r + 0.587 * g + 0.114 * b
        val textColor = if (luminance > 150) "#000000" else "#ffffff"
        ui.labelView.setTextColor(Color.parseColor(textColor))
    }

    /**
     * Двигает полоску метра снизу вверх на долю level (0..1) от доступной
     * высоты, и красит её зелёным/жёлтым/красным по типичным порогам VU-метра.
     */
    private fun setupMeterBarPivot(meterBar: android.view.View) {
        meterBar.viewTreeObserver.addOnGlobalLayoutListener(
            object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    if (meterBar.height > 0) {
                        meterBar.pivotY = meterBar.height.toFloat()
                        meterBar.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    }
                }
            }
        )
    }

    private fun updateMeterUi(channel: Int, level: Float) {
        val color = when {
            level > 0.85f -> "#ff3b30" // красный - близко к перегрузке
            level > 0.6f -> "#ff9f0a"  // жёлтый/оранжевый
            else -> "#34c759"          // зелёный - нормальный уровень
        }

        // В мониторном режиме список инженерских строк (channels) вообще не
        // строится (см. onCreateMonitor) - поэтому это обновление стоит
        // ДО раннего return по нему, иначе индикация сигнала в мониторном
        // режиме никогда бы не срабатывала.
        monitorChannelStrips.getOrNull(channel)?.let { mui ->
            applyMeterHeight(mui.meterBar, level)
            mui.meterBar.setBackgroundColor(Color.parseColor(color))
        }

        val ui = channels.getOrNull(channel) ?: return
        // ВАЖНО: раньше здесь был scaleY-подход (чисто визуальное
        // преобразование, без re-layout) - задумывался как решение
        // "зависания метров", но, судя по всему, в общем виде списка
        // каналов это почему-то не отрисовывалось вообще (в отличие от
        // детального экрана - там работало). Причину найти по коду не
        // удалось, а сама изначальная проблема "зависания" уже отдельно
        // решена на уровне приёма UDP-пакетов (fire-and-forget вместо
        // блокирующего ожидания на главном потоке) - так что возвращаемся
        // к простому и надёжному layoutParams.height.
        applyMeterHeight(ui.meterBar, level)
        ui.meterBar.setBackgroundColor(Color.parseColor(color))

        // Если детальный экран для этого канала открыт - синхронизируем и его метр.
        if (openDetailChannel == channel) {
            val dv = detailViews ?: return
            applyMeterHeight(dv.meterBar, level)
            dv.meterBar.setBackgroundColor(Color.parseColor(color))
        }
    }

    private fun applyMeterHeight(meterBar: android.view.View, level: Float) {
        val parent = meterBar.parent as? android.view.View ?: return
        val totalHeight = parent.height
        if (totalHeight <= 0) return
        val params = meterBar.layoutParams
        params.height = (totalHeight * level.coerceIn(0f, 1f)).toInt().coerceAtLeast(0)
        meterBar.layoutParams = params
    }


    // Если сейчас открыт детальный экран для какого-то канала - храним ссылку
    // на его элементы, чтобы push-обновления могли их вживую подкручивать,
    // пока экран открыт (то же самое, что раньше делал модальный диалог, но
    // теперь fader/mute/solo/метр остаются видны одновременно с компрессором,
    // как в Mixing Station).
    // Проксируем в ConnectionHolder, чтобы значение переживало пересоздание
    // активности при повороте экрана (см. заметку в ConnectionHolder).
    private var openDetailChannel: Int?
        get() = ConnectionHolder.openDetailChannel
        set(value) { ConnectionHolder.openDetailChannel = value }
    private var detailViews: ChannelDetailViews? = null
    // Ссылки на 16 пар (ползунок, текст) вкладки SENDS детального экрана,
    // пока он открыт - для живого обновления от push.
    private var detailSendViews: Array<Pair<SeekBar, TextView>>? = null

    /** Виджеты одной полосы EQ, пока детальный экран открыт - для живого обновления. */
    private data class EqBandViews(
        val activeButton: Button,
        val freqKnob: RotaryKnobView, val freqText: TextView,
        val gainKnob: RotaryKnobView, val gainText: TextView,
        val widthKnob: RotaryKnobView, val widthText: TextView
    )
    private data class EqBlockViews(
        val inButton: Button,
        val bands: Array<EqBandViews>,
        val graphView: EqCurveView,
        val hpButton: Button,
        val lpButton: Button,
        val bassShapeButton: Button?,
        val trebleShapeButton: Button?
    )
    private var detailEqViews: EqBlockViews? = null
    // Живые ссылки на виджеты вкладки INPUT, пока детальный экран открыт -
    // для подкрутки от push (тот же паттерн, что и detailEqViews).
    private var detailPanSeek: SeekBar? = null
    private var detailPanText: TextView? = null
    private var detailPhantomButton: Button? = null
    private var detailPhaseButton: Button? = null
    private var detailLinkButton: Button? = null
    private var detailHpFilterInButton: Button? = null
    private var detailLpFilterInButton: Button? = null
    private var detailHpFreqSeek: SeekBar? = null
    private var detailLpFreqSeek: SeekBar? = null
    private var detailDelaySeek: SeekBar? = null
    private var detailGainTrimKnobRef: RotaryKnobView? = null
    private var detailGainTrimValueRef: TextView? = null
    private var detailSoloBRef: ToggleButton? = null

    /** Общая структура для вкладок COMP и GATE - сетка ручек + график + IN/фильтр. */
    private data class DynamicsBlockViews(
        val inButton: Button,
        val graphView: TransferCurveView,
        val knobViews: Map<ParamKind, Pair<RotaryKnobView, TextView>>,
        val filtersInButton: Button?,
        val thresholdKind: ParamKind,
        val ratioOrRangeKind: ParamKind,
        val grText: TextView,
        val detText: TextView,
        val modeText: TextView
    )
    private var detailCompDynViews: DynamicsBlockViews? = null
    private var detailGateDynViews: DynamicsBlockViews? = null

    private data class ChannelDetailViews(
        val muteButton: Button,
        val soloButton: ToggleButton,
        val fader: SeekBar,
        val meterBar: android.view.View,
        val levelText: TextView,
        val gainKnob: RotaryKnobView,
        val gainValueText: TextView
    )

    private fun updateCompParamUi(channel: Int, kind: ParamKind, level: Float) {
        val ui = channels.getOrNull(channel)
        val data = ConnectionHolder.channelData[channel]
        when (kind) {
            ParamKind.COMP_RATIO -> { ui?.compRatio = level; data.compRatio = level }
            ParamKind.COMP_ATTACK -> { ui?.compAttack = level; data.compAttack = level }
            ParamKind.COMP_RELEASE -> { ui?.compRelease = level; data.compRelease = level }
            ParamKind.COMP_THRESHOLD -> { ui?.compThreshold = level; data.compThreshold = level }
            ParamKind.COMP_MAKEUP -> { ui?.compMakeup = level; data.compMakeup = level }
            else -> return
        }
        // Если детальный экран для этого канала сейчас открыт - подкручиваем
        // ручку вживую, чтобы она отражала изменения, сделанные прямо на пульте.
        if (openDetailChannel == channel) {
            updateDynamicsKnobUi(detailCompDynViews, kind, level)
        }
    }

    private fun updateCompInUi(channel: Int, on: Boolean) {
        ConnectionHolder.channelData[channel].compInLocal = on
        val ui = channels.getOrNull(channel)
        ui?.compInLocal = on
        if (openDetailChannel == channel) {
            val btn = detailCompDynViews?.inButton
            btn?.text = if (on) "IN ●" else "IN ○"
            btn?.setTextColor(if (on) Color.parseColor("#ff9f0a") else Color.parseColor("#8e8e93"))
        }
    }

    /** Обновляет один aux-посыл канала (индекс busNumber 1..16). */
    private fun updateAuxSendUi(channel: Int, busNumber: Int, level: Float) {
        if (busNumber !in 1..16) return
        ConnectionHolder.channelData[channel].auxSends[busNumber - 1] = level
        // Если открыт детальный экран этого канала и видна вкладка SENDS -
        // подкручиваем нужный ползунок вживую.
        if (openDetailChannel == channel) {
            val pair = detailSendViews?.getOrNull(busNumber - 1) ?: return
            pair.first.progress = (level * 1000).toInt()
            pair.second.text = "%.2f".format(level)
        }
        // Если в мониторном режиме сейчас открыта именно эта шина - тоже
        // подкручиваем соответствующий канал вживую.
        if (appMode == MODE_MONITOR && monitorSelectedBus == busNumber - 1) {
            val ui = monitorChannelStrips.getOrNull(channel) ?: return
            ui.seek.progress = (level * 1000).toInt()
            ui.valueText.text = "%.2f".format(level)
        }
    }

    private fun updateEqInUi(channel: Int, on: Boolean) {
        ConnectionHolder.channelData[channel].eqInLocal = on
        if (openDetailChannel == channel) {
            val btn = detailEqViews?.inButton ?: return
            btn.text = if (on) "EQ ON" else "EQ OFF"
            btn.setBackgroundColor(Color.parseColor(if (on) "#ff9f0a" else "#3a3a3c"))
        }
    }

    private fun updateEqBandActiveUi(channel: Int, bandIndex: Int, on: Boolean) {
        if (bandIndex !in 0..3) return
        ConnectionHolder.channelData[channel].eqBandActiveLocal[bandIndex] = on
        if (openDetailChannel == channel) {
            val views = detailEqViews ?: return
            val btn = views.bands.getOrNull(bandIndex)?.activeButton ?: return
            btn.text = if (on) "ON" else "OFF"
            btn.setBackgroundColor(Color.parseColor(if (on) "#ff9f0a" else "#3a3a3c"))
            views.graphView.bands.getOrNull(bandIndex)?.active = on
            views.graphView.invalidate()
        }
    }

    private fun updateEqParamUi(channel: Int, bandIndex: Int, kind: ParamKind, level: Float) {
        if (bandIndex !in 0..3) return
        persistEq(channel, bandIndex, kind, level)
        if (openDetailChannel != channel) return
        val views = detailEqViews ?: return
        val band = views.bands.getOrNull(bandIndex) ?: return
        val (knob, text) = when (kind) {
            ParamKind.EQ_FREQ -> band.freqKnob to band.freqText
            ParamKind.EQ_GAIN -> band.gainKnob to band.gainText
            ParamKind.EQ_WIDTH -> band.widthKnob to band.widthText
            else -> return
        }
        knob.value = level
        text.text = when (kind) {
            ParamKind.EQ_FREQ -> {
                val bandId = when (bandIndex) {
                    0 -> EqCurveView.BandId.BASS
                    1 -> EqCurveView.BandId.LOW_MID
                    2 -> EqCurveView.BandId.MID_HIGH
                    else -> EqCurveView.BandId.TREBLE
                }
                EqCurveView.formatHz(EqCurveView.rawToHzPublic(level, bandId))
            }
            ParamKind.EQ_WIDTH -> EqCurveView.formatWidth(EqCurveView.rawToWidthPublic(level))
            else -> "%.2f".format(level)
        }
        if (kind == ParamKind.EQ_FREQ) views.graphView.bands.getOrNull(bandIndex)?.freq = level
        if (kind == ParamKind.EQ_GAIN) views.graphView.bands.getOrNull(bandIndex)?.gain = level
        if (kind == ParamKind.EQ_FREQ || kind == ParamKind.EQ_GAIN) views.graphView.invalidate()
    }

    private fun updateMasterFaderUi(masterIndex: Int, level: Float) {
        val data = ConnectionHolder.masterData.getOrNull(masterIndex) ?: return
        data.fader = level
        val ui = masterStrips.getOrNull(masterIndex) ?: return
        ui.suppressEvents = true
        ui.fader.progress = (level.coerceIn(0f, 1f) * 1000).toInt()
        ui.levelText.text = "%.2f".format(level)
        ui.suppressEvents = false
    }

    private fun updateMasterMuteUi(masterIndex: Int, muted: Boolean) {
        val data = ConnectionHolder.masterData.getOrNull(masterIndex) ?: return
        data.mutedLocal = muted
        val ui = masterStrips.getOrNull(masterIndex) ?: return
        ui.mutedLocal = muted
        ui.muteButton.setBackgroundColor(Color.parseColor(if (muted) "#ff3b30" else "#3a3a3c"))
    }

    private fun updateMasterSoloUi(masterIndex: Int, soloed: Boolean) {
        val ui = masterStrips.getOrNull(masterIndex) ?: return
        ui.suppressEvents = true
        ui.soloButton.isChecked = soloed
        ui.soloButton.setBackgroundColor(Color.parseColor(if (soloed) "#ff9f0a" else "#3a3a3c"))
        ui.suppressEvents = false
    }

    private fun updateAuxReturnFaderUi(auxIndex: Int, level: Float) {
        val data = ConnectionHolder.auxReturnData.getOrNull(auxIndex) ?: return
        data.fader = level
        val ui = auxStrips.getOrNull(auxIndex) ?: return
        ui.suppressEvents = true
        ui.fader.progress = (level.coerceIn(0f, 1f) * 1000).toInt()
        ui.levelText.text = "%.2f".format(level)
        ui.suppressEvents = false
    }

    private fun updateAuxReturnMuteUi(auxIndex: Int, muted: Boolean) {
        val data = ConnectionHolder.auxReturnData.getOrNull(auxIndex) ?: return
        data.mutedLocal = muted
        val ui = auxStrips.getOrNull(auxIndex) ?: return
        ui.mutedLocal = muted
        ui.muteButton.setBackgroundColor(Color.parseColor(if (muted) "#ff3b30" else "#3a3a3c"))
    }

    private fun updateAuxReturnSoloUi(auxIndex: Int, soloed: Boolean) {
        val ui = auxStrips.getOrNull(auxIndex) ?: return
        ui.suppressEvents = true
        ui.soloButton.isChecked = soloed
        ui.soloButton.setBackgroundColor(Color.parseColor(if (soloed) "#ff9f0a" else "#3a3a3c"))
        ui.suppressEvents = false
    }

    /**
     * Открывает полноэкранный детальный вид одного канала (по образцу
     * Mixing Station): слева - блоки обработки (сейчас только COMP, задел
     * под EQ/Sends позже), справа - ПОСТОЯННО видимые mute/solo/фейдер/метр,
     * чтобы уровень сигнала не терялся из виду, пока крутишь эффекты.
     */
    /**
     * Экран назначения участников VCA-группы. ВАЖНО: структура (какое имя
     * параметра соответствует какому потенциальному участнику, и что
     * числовой аргумент - это номер VCA-группы) взята из большого
     * стороннего датасета (не из нашего собственного захвата трафика) - см.
     * подробную заметку в Pro2Commands.kt у vcaChild*Address(). Отправка
     * команд реализована (по той же toggle-логике, что и остальные
     * enPPCSwitchMessage переключатели), а вот живой подписки на ТЕКУЩЕЕ
     * состояние членства здесь пока нет - слишком много неопределённости
     * сразу, чтобы делать полноценный live-экран. Проверяйте результат
     * прямо на экране пульта.
     */
    private fun openVcaMembers(vcaIndex: Int) {
        val view = LayoutInflater.from(this).inflate(R.layout.activity_vca_members, channelDetailContainer, false)
        channelDetailContainer.removeAllViews()
        channelDetailContainer.addView(view)
        channelDetailContainer.visibility = android.view.View.VISIBLE

        openVcaMembersIndex = vcaIndex
        vcaMemberButtons.clear()

        view.findViewById<TextView>(R.id.textVcaMembersTitle).text = "VCA ${vcaIndex + 1} MEMBERS"
        view.findViewById<Button>(R.id.btnVcaMembersBack).setOnClickListener {
            channelDetailContainer.visibility = android.view.View.GONE
            channelDetailContainer.removeAllViews()
            openVcaMembersIndex = null
            vcaMemberButtons.clear()
        }

        val container = view.findViewById<android.widget.LinearLayout>(R.id.containerVcaMembers)
        container.removeAllViews()

        fun sectionHeader(title: String) {
            val header = TextView(this).apply {
                text = title
                setTextColor(Color.parseColor("#ff9f0a"))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                textSize = 13f
                setPadding(0, 20, 0, 8)
            }
            container.addView(header)
        }

        // childType - ключ для хранения в ConnectionHolder.vcaData и для
        // live-обновления кнопки при входящем push (см. handleVcaMemberSubscribedValue).
        fun memberGrid(labels: List<String>, childType: String, initial: (Int) -> Boolean, onToggle: (index: Int, member: Boolean) -> Unit) {
            var row: android.widget.LinearLayout? = null
            for ((i, label) in labels.withIndex()) {
                if (i % 4 == 0) {
                    row = android.widget.LinearLayout(this).apply {
                        orientation = android.widget.LinearLayout.HORIZONTAL
                    }
                    container.addView(row, android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = 6 })
                }
                var memberState = initial(i)
                val btn = Button(this).apply {
                    text = label
                    textSize = 10f
                    minHeight = 0
                    backgroundTintList = null
                    setPadding(4, 12, 4, 12)
                    setTextColor(Color.parseColor(if (memberState) "#000000" else "#ffffff"))
                    setBackgroundColor(Color.parseColor(if (memberState) "#ff9f0a" else "#3a3a3c"))
                    layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        .apply { marginEnd = 4 }
                    setOnClickListener {
                        memberState = !memberState
                        setBackgroundColor(Color.parseColor(if (memberState) "#ff9f0a" else "#3a3a3c"))
                        setTextColor(Color.parseColor(if (memberState) "#000000" else "#ffffff"))
                        onToggle(i, memberState)
                    }
                }
                vcaMemberButtons["$childType:$i"] = btn
                row?.addView(btn)
            }
        }

        val vca = ConnectionHolder.vcaData[vcaIndex]

        sectionHeader("ВХОДНЫЕ КАНАЛЫ (1-56)")
        memberGrid((1..56).map { n ->
            val realName = ConnectionHolder.channelData.getOrNull(n - 1)?.name?.takeIf { it.isNotBlank() }
            if (realName != null) "$n: $realName" else "CH $n"
        }, "input", { i -> vca.memberInput.getOrElse(i) { false } }) { i, member ->
            sendRawAsync(Pro2Commands.setVcaChildInput(i, vcaIndex, member))
        }

        sectionHeader("AUX-ШИНЫ (1-16)")
        memberGrid((1..16).map { n ->
            val realName = ConnectionHolder.auxBusData.getOrNull(n - 1)?.name?.takeIf { it.isNotBlank() }
            if (realName != null) "$n: $realName" else "BUS $n"
        }, "submix", { i -> vca.memberSubMix.getOrElse(i) { false } }) { i, member ->
            sendRawAsync(Pro2Commands.setVcaChildSubMix(i, vcaIndex, member))
        }

        sectionHeader("AUX RETURNS (1-8)")
        memberGrid((1..8).map { n ->
            val realName = ConnectionHolder.auxReturnData.getOrNull(n - 1)?.name?.takeIf { it.isNotBlank() }
            if (realName != null) "$n: $realName" else "AUX $n"
        }, "auxreturn", { i -> vca.memberAuxReturn.getOrElse(i) { false } }) { i, member ->
            sendRawAsync(Pro2Commands.setVcaChildAuxReturn(i, vcaIndex, member))
        }

        sectionHeader("MAIN OUTS (1-8)")
        memberGrid((1..8).map { "MAIN $it" }, "main", { i -> vca.memberMain.getOrElse(i) { false } }) { i, member ->
            sendRawAsync(Pro2Commands.setVcaChildMain(i, vcaIndex, member))
        }

        sectionHeader("МАСТЕР")
        memberGrid(listOf("MASTER L", "MASTER R", "MASTER C"), "master", { i -> vca.memberMaster.getOrElse(i) { false } }) { i, member ->
            val letter = listOf("L", "R", "C")[i]
            sendRawAsync(Pro2Commands.setVcaChildMaster(letter, vcaIndex, member))
        }

        // Запрашиваем свежее состояние с пульта - кнопки выше построены по
        // тому, что уже было известно локально (может быть пусто при первом
        // открытии), а push-ответы дальше подкрасят их правильно вживую.
        subscribeVcaMembers(vcaIndex)
    }

    private fun openChannelDetail(channel: Int) {
        val ui = channels.getOrNull(channel) ?: return
        val view = LayoutInflater.from(this).inflate(R.layout.channel_detail, channelDetailContainer, false)
        channelDetailContainer.removeAllViews()
        channelDetailContainer.addView(view)
        channelDetailContainer.visibility = android.view.View.VISIBLE

        view.findViewById<TextView>(R.id.textDetailChannelName).text = ui.labelView.text
        view.findViewById<Button>(R.id.btnDetailBack).setOnClickListener {
            channelDetailContainer.visibility = android.view.View.GONE
            channelDetailContainer.removeAllViews()
            openDetailChannel = null
            detailViews = null
            detailSendViews = null
            detailEqViews = null
            detailPanSeek = null
            detailPanText = null
            detailPhantomButton = null
            detailPhaseButton = null
            detailCompDynViews = null
            detailHpFilterInButton = null
            detailLpFilterInButton = null
            detailHpFreqSeek = null
            detailLpFreqSeek = null
            detailDelaySeek = null
            detailGainTrimKnobRef = null
            detailGainTrimValueRef = null
            detailSoloBRef = null
            detailLinkButton = null
            detailGateDynViews = null
        }

        // --- Постоянная панель mute/solo/фейдер/метр ---
        val detailMute = view.findViewById<Button>(R.id.detailMute)
        val detailSolo = view.findViewById<ToggleButton>(R.id.detailSolo)
        val detailFader = view.findViewById<SeekBar>(R.id.detailFader)
        val detailFaderContainer = view.findViewById<android.widget.FrameLayout>(R.id.detailFaderContainer)
        val detailMeterBar = view.findViewById<android.view.View>(R.id.detailMeterBar)
        setupMeterBarPivot(detailMeterBar)
        val detailLevelText = view.findViewById<TextView>(R.id.textDetailLevelValue)

        detailMute.backgroundTintList = null
        detailMute.stateListAnimator = null
        detailSolo.backgroundTintList = null
        detailSolo.stateListAnimator = null
        detailMute.setBackgroundColor(Color.parseColor(if (ui.mutedLocal) "#ff3b30" else "#3a3a3c"))
        detailSolo.isChecked = ui.soloButton.isChecked
        detailSolo.setBackgroundColor(
            Color.parseColor(if (ui.soloButton.isChecked) "#ff9f0a" else "#3a3a3c")
        )
        detailFader.progress = ui.fader.progress
        detailLevelText.text = ui.levelValueText.text

        detailMute.setOnClickListener {
            updateMuteUi(channel, !ui.mutedLocal)
            sendMute(channel, true)
        }
        detailSolo.setOnCheckedChangeListener { _, isChecked ->
            updateSoloUi(channel, isChecked)
            sendSolo(channel, isChecked)
        }

        val detailSoloB = view.findViewById<ToggleButton>(R.id.detailSoloB)
        // Убрано по просьбе - не нужна пользователю сейчас.
        detailSoloB.visibility = android.view.View.GONE
        detailSoloB.backgroundTintList = null
        detailSoloB.stateListAnimator = null
        val soloBData = ConnectionHolder.channelData[channel]
        detailSoloB.isChecked = soloBData.soloBLocal
        detailSoloB.setBackgroundColor(Color.parseColor(if (soloBData.soloBLocal) "#ff9f0a" else "#3a3a3c"))
        detailSoloB.setOnCheckedChangeListener { _, isChecked ->
            ConnectionHolder.channelData[channel].soloBLocal = isChecked
            detailSoloB.setBackgroundColor(Color.parseColor(if (isChecked) "#ff9f0a" else "#3a3a3c"))
            sendRawAsync(Pro2Commands.setSoloB(channel, isChecked))
        }
        detailSoloBRef = detailSoloB
        detailFader.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val level = progress / 1000f
                detailLevelText.text = "%.2f".format(level)
                ui.fader.progress = progress
                if (!fromUser) return
                ConnectionHolder.channelData[channel].fader = level
                val now = System.currentTimeMillis()
                if (now - ui.lastFaderSendTime >= minSendIntervalMs) {
                    ui.lastFaderSendTime = now
                    sendFader(channel, level)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {
                ui.isDragging = true
            }
            override fun onStopTrackingTouch(sb: SeekBar?) {
                ui.isDragging = false
                val level = (sb?.progress ?: 0) / 1000f
                ConnectionHolder.channelData[channel].fader = level
                sendFader(channel, level)
            }
        })

        // Ширина повёрнутого фейдера подгоняется под реальную высоту, как и
        // на основном экране.
        detailFaderContainer.viewTreeObserver.addOnGlobalLayoutListener(
            object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    val h = detailFaderContainer.height
                    if (h > 0) {
                        val p = detailFader.layoutParams
                        if (p.width != h) {
                            p.width = h
                            detailFader.layoutParams = p
                        }
                        detailFaderContainer.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    }
                }
            }
        )

        // ВАЖНО: запоминаем ДО перезаписи ниже - иначе проверка всегда
        // будет "истина" (мы же сами через строчку это поле и перезапишем).
        val wasThisChannelAlreadyOpen = ConnectionHolder.openDetailChannel == channel
        openDetailChannel = channel
        val detailGainKnob = view.findViewById<RotaryKnobView>(R.id.knobDetailGain)
        val detailGainValue = view.findViewById<TextView>(R.id.textDetailGainValue)

        detailViews = ChannelDetailViews(
            detailMute, detailSolo, detailFader, detailMeterBar, detailLevelText,
            detailGainKnob, detailGainValue
        )

        // --- Вкладки INPUT / COMP / SENDS ---
        val tabInput = view.findViewById<Button>(R.id.btnTabInput)
        val tabComp = view.findViewById<Button>(R.id.btnTabComp)
        val tabSends = view.findViewById<Button>(R.id.btnTabSends)
        val tabEq = view.findViewById<Button>(R.id.btnTabEq)
        val tabGate = view.findViewById<Button>(R.id.btnTabGate)
        val inputBlock = view.findViewById<android.widget.LinearLayout>(R.id.inputBlockContent)
        val compBlock = view.findViewById<android.widget.LinearLayout>(R.id.compBlockContent)
        val sendsBlock = view.findViewById<android.widget.HorizontalScrollView>(R.id.sendsBlockContent)
        val tabContentScroll = view.findViewById<android.widget.ScrollView>(R.id.tabContentScroll)
        val eqBlock = view.findViewById<android.widget.LinearLayout>(R.id.eqBlockContent)
        val gateBlock = view.findViewById<android.widget.LinearLayout>(R.id.gateBlockContent)

        fun selectTab(active: Button) {
            for (tab in listOf(tabInput, tabComp, tabSends, tabEq, tabGate)) {
                val isActive = tab === active
                tab.backgroundTintList = android.content.res.ColorStateList.valueOf(
                    Color.parseColor(if (isActive) "#ff9f0a" else "#1c1c1e")
                )
                tab.setTextColor(Color.parseColor(if (isActive) "#000000" else "#ffffff"))
            }
        }
        fun hideAllBlocks() {
            inputBlock.visibility = android.view.View.GONE
            compBlock.visibility = android.view.View.GONE
            sendsBlock.visibility = android.view.View.GONE
            eqBlock.visibility = android.view.View.GONE
            gateBlock.visibility = android.view.View.GONE
            // ВАЖНО: сама ScrollView-обёртка вокруг INPUT/COMP/EQ/GATE
            // никогда не пряталась раньше - только её содержимое. Из-за
            // этого пустая ScrollView всё равно занимала половину ширины
            // (weight=1 наравне с SENDS), сжимая таблицу SENDS в правую
            // половину экрана. Теперь скрываем и её саму на вкладке SENDS.
            tabContentScroll.visibility = android.view.View.VISIBLE
        }
        fun showInput() {
            hideAllBlocks()
            inputBlock.visibility = android.view.View.VISIBLE
            selectTab(tabInput)
        }
        fun showComp() {
            hideAllBlocks()
            compBlock.visibility = android.view.View.VISIBLE
            selectTab(tabComp)
        }
        fun showSends() {
            hideAllBlocks()
            tabContentScroll.visibility = android.view.View.GONE
            sendsBlock.visibility = android.view.View.VISIBLE
            selectTab(tabSends)
        }
        fun showEq() {
            hideAllBlocks()
            eqBlock.visibility = android.view.View.VISIBLE
            selectTab(tabEq)
        }
        fun showGate() {
            hideAllBlocks()
            gateBlock.visibility = android.view.View.VISIBLE
            selectTab(tabGate)
        }
        tabInput.setOnClickListener {
            showInput()
            ConnectionHolder.openDetailTabName = "INPUT"
            // Подписываемся на pan/48V/фазу/HP-LP/delay/SoloB этого канала
            // только сейчас, при первом реальном открытии вкладки - не
            // хотим подписывать все 56 каналов сразу при подключении
            // (перегружало пульт настолько, что сбоил даже официальный
            // Mixtender на другом устройстве).
            subscribeInputExtras(channel)
        }
        tabComp.setOnClickListener {
            showComp()
            ConnectionHolder.openDetailTabName = "COMP"
            // Фильтр компрессора и GR/detector-метры (comp+gate) - тоже
            // лениво, только при реальном открытии вкладки.
            subscribeCompGateExtras(channel)
        }
        tabSends.setOnClickListener {
            showSends()
            ConnectionHolder.openDetailTabName = "SENDS"
            // Подписываемся на 16 aux-посылов этого канала только сейчас,
            // при первом реальном открытии вкладки (см. subscribeAuxSends).
            subscribeAuxSends(channel)
        }
        tabEq.setOnClickListener {
            showEq()
            ConnectionHolder.openDetailTabName = "EQ"
            // Подписываемся на EQ этого канала только сейчас (17 параметров
            // на канал - как и с посылами, не хотим подписывать все 56
            // каналов сразу при подключении).
            subscribeEq(channel)
        }
        tabGate.setOnClickListener {
            showGate()
            ConnectionHolder.openDetailTabName = "GATE"
            // Подписываемся на Gate этого канала только сейчас (9 параметров).
            subscribeGate(channel)
            subscribeCompGateExtras(channel)
        }
        // ВАЖНО (восстановление после поворота экрана): если ConnectionHolder
        // помнит, что для ЭТОГО канала была открыта не INPUT, а другая
        // вкладка (например EQ) - открываем сразу её и лениво подписываемся
        // на её параметры, а не всегда откатываемся на INPUT по умолчанию.
        when (if (wasThisChannelAlreadyOpen) ConnectionHolder.openDetailTabName else "INPUT") {
            "COMP" -> { showComp(); subscribeCompGateExtras(channel) }
            "GATE" -> { showGate(); subscribeGate(channel); subscribeCompGateExtras(channel) }
            "EQ" -> { showEq(); subscribeEq(channel) }
            "SENDS" -> { showSends(); subscribeAuxSends(channel) }
            else -> showInput()
        }

        subscribeInputExtras(channel)

        // --- Вкладка SENDS: 16 посылов, таблица горизонтальных полос ---
        // НЕ подтверждено реальным захватом трафика - см. заметку в
        // Pro2Commands.kt про enSubSendLevel1..16.
        val sendsRow = view.findViewById<android.widget.LinearLayout>(R.id.sendsBlockRow)
        sendsRow.removeAllViews()
        val sendViews = arrayOfNulls<Pair<SeekBar, TextView>>(16)
        val sendsData = ConnectionHolder.channelData[channel]
        for (bus in 1..16) {
            val column = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    (86 * resources.displayMetrics.density).toInt(),
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT
                ).apply { marginEnd = (4 * resources.displayMetrics.density).toInt() }
            }
            val label = TextView(this).apply {
                text = "AUX $bus"
                setTextColor(Color.parseColor("#ff9f0a"))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                textSize = 10f
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                maxLines = 1
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            val valueText = TextView(this).apply {
                text = "%.2f".format(sendsData.auxSends[bus - 1])
                setTextColor(Color.parseColor("#aaaaaa"))
                textSize = 11f
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                maxLines = 1
                setPadding(0, 4, 0, 4)
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            // Вертикальный фейдер на всю доступную высоту - та же техника
            // подгонки под реальную высоту экрана, что и везде в приложении.
            val faderContainer = android.widget.FrameLayout(this).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
                )
            }
            val seek = SeekBar(this).apply {
                max = 1000
                progress = (sendsData.auxSends[bus - 1] * 1000).toInt()
                progressTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#ff9f0a"))
                thumbTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#ffffff"))
                rotation = 270f
                layoutParams = android.widget.FrameLayout.LayoutParams(220, 60, android.view.Gravity.CENTER)
            }
            faderContainer.addView(seek)
            faderContainer.viewTreeObserver.addOnGlobalLayoutListener(
                object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
                    override fun onGlobalLayout() {
                        val h = faderContainer.height
                        if (h > 0) {
                            val p = seek.layoutParams
                            if (p.width != h) {
                                p.width = h
                                seek.layoutParams = p
                            }
                            faderContainer.viewTreeObserver.removeOnGlobalLayoutListener(this)
                        }
                    }
                }
            )
            var lastSend = 0L
            seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    val level = progress / 1000f
                    valueText.text = "%.2f".format(level)
                    if (!fromUser) return
                    sendsData.auxSends[bus - 1] = level
                    val now = System.currentTimeMillis()
                    if (now - lastSend >= minSendIntervalMs) {
                        lastSend = now
                        sendSubSend(channel, bus, level)
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    val level = (sb?.progress ?: 0) / 1000f
                    sendsData.auxSends[bus - 1] = level
                    sendSubSend(channel, bus, level)
                }
            })

            // Отдельно от уровня посыла - ПОДТВЕРЖДЕНО реальным захватом.
            val btnPreFade = Button(this).apply {
                backgroundTintList = null
                minHeight = 0
                textSize = 10f
                setPadding(4, 4, 4, 4)
                text = if (sendsData.auxSendPreFade[bus - 1]) "PRE" else "POST"
                setTextColor(Color.parseColor("#ffffff"))
                setBackgroundColor(Color.parseColor("#3a3a3c"))
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = 4 }
                setOnClickListener {
                    val newState = !ConnectionHolder.channelData[channel].auxSendPreFade[bus - 1]
                    ConnectionHolder.channelData[channel].auxSendPreFade[bus - 1] = newState
                    text = if (newState) "PRE" else "POST"
                    sendRawAsync(Pro2Commands.setSubSendPreFade(channel, bus, true))
                }
            }
            val btnEnable = Button(this).apply {
                backgroundTintList = null
                minHeight = 0
                textSize = 10f
                setPadding(4, 4, 4, 4)
                text = if (sendsData.auxSendEnable[bus - 1]) "ON" else "OFF"
                setTextColor(Color.parseColor("#ffffff"))
                setBackgroundColor(Color.parseColor(if (sendsData.auxSendEnable[bus - 1]) "#34c759" else "#3a3a3c"))
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = 4 }
                setOnClickListener {
                    val newState = !ConnectionHolder.channelData[channel].auxSendEnable[bus - 1]
                    ConnectionHolder.channelData[channel].auxSendEnable[bus - 1] = newState
                    text = if (newState) "ON" else "OFF"
                    setBackgroundColor(Color.parseColor(if (newState) "#34c759" else "#3a3a3c"))
                    sendRawAsync(Pro2Commands.setSubSendEnable(channel, bus, true))
                }
            }

            column.addView(label)
            column.addView(valueText)
            column.addView(faderContainer)
            column.addView(btnPreFade)
            column.addView(btnEnable)
            sendsRow.addView(column)
            sendViews[bus - 1] = seek to valueText
        }
        @Suppress("UNCHECKED_CAST")
        detailSendViews = sendViews as Array<Pair<SeekBar, TextView>>

        // --- Вкладка COMP: строится программно (сетка ручек + график) ---
        compBlock.removeAllViews()
        buildDynamicsBlock(compBlock, channel, TransferCurveView.Mode.COMPRESSOR)

        // --- Вкладка GATE: строится программно (сетка ручек + график) ---
        // ПОЛНОСТЬЮ ПОДТВЕРЖДЕНО реальным захватом трафика iPad.
        gateBlock.removeAllViews()
        buildDynamicsBlock(gateBlock, channel, TransferCurveView.Mode.GATE)

        // --- Вкладка EQ: 4 полосы, строится программно ---
        // Подтверждено ОПИСАНИЯМИ в списке команд, но НЕ реальным захватом.
        eqBlock.removeAllViews()
        buildEqBlock(eqBlock, channel)

        // --- Вкладка INPUT: ручка GAIN (48V/фаза/trim - задел на будущее) ---
        val gainData = ConnectionHolder.channelData[channel]
        detailGainKnob.value = gainData.gain
        detailGainValue.text = "%.2f".format(gainData.gain)
        detailGainKnob.onValueChanged = { v ->
            detailGainValue.text = "%.2f".format(v)
            ConnectionHolder.channelData[channel].gain = v
            sendGain(channel, v)
        }

        val detailGainTrimKnob = view.findViewById<RotaryKnobView>(R.id.knobDetailGainTrim)
        val detailGainTrimValue = view.findViewById<TextView>(R.id.textDetailGainTrimValue)
        detailGainTrimKnob.value = ConnectionHolder.channelData[channel].gainTrim
        detailGainTrimValue.text = "%.2f".format(ConnectionHolder.channelData[channel].gainTrim)
        detailGainTrimKnob.onValueChanged = { v ->
            detailGainTrimValue.text = "%.2f".format(v)
            ConnectionHolder.channelData[channel].gainTrim = v
            sendRawAsync(Pro2Commands.setGainTrim(channel, v))
        }
        detailGainTrimKnobRef = detailGainTrimKnob
        detailGainTrimValueRef = detailGainTrimValue

        // --- Вкладка INPUT: 48V, фаза, панорама, имя, цвет ---
        val inputData = ConnectionHolder.channelData[channel]

        val btnPhantom = view.findViewById<Button>(R.id.btnDetailPhantom)
        btnPhantom.backgroundTintList = null
        btnPhantom.text = if (inputData.phantomLocal) "48V ON" else "48V OFF"
        btnPhantom.setBackgroundColor(Color.parseColor(if (inputData.phantomLocal) "#ff9f0a" else "#3a3a3c"))
        btnPhantom.setOnClickListener {
            val newState = !ConnectionHolder.channelData[channel].phantomLocal
            ConnectionHolder.channelData[channel].phantomLocal = newState
            btnPhantom.text = if (newState) "48V ON" else "48V OFF"
            btnPhantom.setBackgroundColor(Color.parseColor(if (newState) "#ff9f0a" else "#3a3a3c"))
            sendPhantomPower(channel, newState)
        }

        val btnPhase = view.findViewById<Button>(R.id.btnDetailPhase)
        btnPhase.backgroundTintList = null
        btnPhase.text = if (inputData.phaseLocal) "PHASE INV" else "PHASE NORM"
        btnPhase.setBackgroundColor(Color.parseColor(if (inputData.phaseLocal) "#ff9f0a" else "#3a3a3c"))
        btnPhase.setOnClickListener {
            val newState = !ConnectionHolder.channelData[channel].phaseLocal
            ConnectionHolder.channelData[channel].phaseLocal = newState
            btnPhase.text = if (newState) "PHASE INV" else "PHASE NORM"
            btnPhase.setBackgroundColor(Color.parseColor(if (newState) "#ff9f0a" else "#3a3a3c"))
            sendPhase(channel, newState)
        }

        // Стерео-пара (link) - НЕ подтверждено реальным захватом (см. заметку в Pro2Commands.kt).
        val btnLink = view.findViewById<Button>(R.id.btnDetailLink)
        btnLink.backgroundTintList = null
        btnLink.text = if (inputData.linkedLocal) "LINK ON" else "LINK OFF"
        btnLink.setBackgroundColor(Color.parseColor(if (inputData.linkedLocal) "#ff9f0a" else "#3a3a3c"))
        btnLink.setOnClickListener {
            val newState = !ConnectionHolder.channelData[channel].linkedLocal
            ConnectionHolder.channelData[channel].linkedLocal = newState
            btnLink.text = if (newState) "LINK ON" else "LINK OFF"
            btnLink.setBackgroundColor(Color.parseColor(if (newState) "#ff9f0a" else "#3a3a3c"))
            sendRawAsync(Pro2Commands.setLink(channel, true))
        }
        detailLinkButton = btnLink

        val seekPan = view.findViewById<SeekBar>(R.id.seekDetailPan)
        val textPan = view.findViewById<TextView>(R.id.textDetailPanValue)
        detailPanSeek = seekPan
        detailPanText = textPan
        detailPhantomButton = btnPhantom
        detailPhaseButton = btnPhase
        fun panLabel(v: Float): String = when {
            v < 0.48f -> "L %.0f".format((0.5f - v) * 200)
            v > 0.52f -> "R %.0f".format((v - 0.5f) * 200)
            else -> "CENTER"
        }
        seekPan.progress = (inputData.pan * 1000).toInt()
        textPan.text = panLabel(inputData.pan)
        seekPan.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            var lastSend = 0L
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val v = progress / 1000f
                textPan.text = panLabel(v)
                if (!fromUser) return
                ConnectionHolder.channelData[channel].pan = v
                val now = System.currentTimeMillis()
                if (now - lastSend >= minSendIntervalMs) {
                    lastSend = now
                    sendPan(channel, v)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                val v = (sb?.progress ?: 0) / 1000f
                ConnectionHolder.channelData[channel].pan = v
                sendPan(channel, v)
            }
        })

        val editName = view.findViewById<EditText>(R.id.editDetailChannelName)
        editName.setText(inputData.name)
        view.findViewById<Button>(R.id.btnDetailApplyName).setOnClickListener {
            val newName = editName.text.toString().trim()
            if (newName.isNotBlank()) {
                ConnectionHolder.channelData[channel].name = newName
                sendChannelName(channel, newName)
            }
        }

        // Небольшая палитра готовых цветов - переиспользуем стандартную
        // палитру пульта, уже подтверждённую реальным захватом (setColour).
        val swatchRow = view.findViewById<android.widget.LinearLayout>(R.id.rowColourSwatches)
        swatchRow.removeAllViews()
        val presetColours = listOf(
            Color.parseColor("#FF0000FF"), Color.parseColor("#FFFFC800"),
            Color.parseColor("#FFFF0000"), Color.parseColor("#FF00C8FF"),
            Color.parseColor("#FF00FF00"), Color.parseColor("#FFFF00FF"),
            Color.parseColor("#FFFFFFFF"), Color.parseColor("#FF808080")
        )
        for (argb in presetColours) {
            val swatch = android.view.View(this)
            val params = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            params.marginEnd = 6
            swatch.layoutParams = params
            swatch.setBackgroundColor(argb)
            swatch.setOnClickListener {
                ConnectionHolder.channelData[channel].colourArgb = argb
                updateColourUi(channel, argb)
                sendChannelColour(channel, argb)
            }
            swatchRow.addView(swatch)
        }

        // --- HP/LP фильтры и задержка входа - ПОДТВЕРЖДЕНО реальным захватом ---
        val btnHp = view.findViewById<Button>(R.id.btnDetailHpFilterIn)
        btnHp.backgroundTintList = null
        btnHp.text = if (inputData.hpFilterInLocal) "HP FILTER ON" else "HP FILTER OFF"
        btnHp.setBackgroundColor(Color.parseColor(if (inputData.hpFilterInLocal) "#ff9f0a" else "#3a3a3c"))
        btnHp.setOnClickListener {
            val newState = !ConnectionHolder.channelData[channel].hpFilterInLocal
            ConnectionHolder.channelData[channel].hpFilterInLocal = newState
            btnHp.text = if (newState) "HP FILTER ON" else "HP FILTER OFF"
            btnHp.setBackgroundColor(Color.parseColor(if (newState) "#ff9f0a" else "#3a3a3c"))
            sendRawAsync(Pro2Commands.setHpFilterIn(channel, true))
        }
        detailHpFilterInButton = btnHp

        val btnLp = view.findViewById<Button>(R.id.btnDetailLpFilterIn)
        btnLp.backgroundTintList = null
        btnLp.text = if (inputData.lpFilterInLocal) "LP FILTER ON" else "LP FILTER OFF"
        btnLp.setBackgroundColor(Color.parseColor(if (inputData.lpFilterInLocal) "#ff9f0a" else "#3a3a3c"))
        btnLp.setOnClickListener {
            val newState = !ConnectionHolder.channelData[channel].lpFilterInLocal
            ConnectionHolder.channelData[channel].lpFilterInLocal = newState
            btnLp.text = if (newState) "LP FILTER ON" else "LP FILTER OFF"
            btnLp.setBackgroundColor(Color.parseColor(if (newState) "#ff9f0a" else "#3a3a3c"))
            sendRawAsync(Pro2Commands.setLpFilterIn(channel, true))
        }
        detailLpFilterInButton = btnLp

        val seekHpFreq = view.findViewById<SeekBar>(R.id.seekDetailHpFreq)
        seekHpFreq.progress = (inputData.hpFilterFreq * 1000).toInt()
        detailHpFreqSeek = seekHpFreq
        seekHpFreq.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            var lastSend = 0L
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val level = progress / 1000f
                ConnectionHolder.channelData[channel].hpFilterFreq = level
                val now = System.currentTimeMillis()
                if (now - lastSend >= minSendIntervalMs) {
                    lastSend = now
                    sendRawAsync(Pro2Commands.setHpFilterFreq(channel, level))
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                val level = (sb?.progress ?: 0) / 1000f
                ConnectionHolder.channelData[channel].hpFilterFreq = level
                sendRawAsync(Pro2Commands.setHpFilterFreq(channel, level))
            }
        })

        val seekLpFreq = view.findViewById<SeekBar>(R.id.seekDetailLpFreq)
        seekLpFreq.progress = (inputData.lpFilterFreq * 1000).toInt()
        detailLpFreqSeek = seekLpFreq
        seekLpFreq.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            var lastSend = 0L
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val level = progress / 1000f
                ConnectionHolder.channelData[channel].lpFilterFreq = level
                val now = System.currentTimeMillis()
                if (now - lastSend >= minSendIntervalMs) {
                    lastSend = now
                    sendRawAsync(Pro2Commands.setLpFilterFreq(channel, level))
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                val level = (sb?.progress ?: 0) / 1000f
                ConnectionHolder.channelData[channel].lpFilterFreq = level
                sendRawAsync(Pro2Commands.setLpFilterFreq(channel, level))
            }
        })

        val seekDelay = view.findViewById<SeekBar>(R.id.seekDetailDelay)
        seekDelay.progress = (inputData.inputDelay * 1000).toInt()
        detailDelaySeek = seekDelay
        seekDelay.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            var lastSend = 0L
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val level = progress / 1000f
                ConnectionHolder.channelData[channel].inputDelay = level
                val now = System.currentTimeMillis()
                if (now - lastSend >= minSendIntervalMs) {
                    lastSend = now
                    sendRawAsync(Pro2Commands.setInputDelay(channel, level))
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                val level = (sb?.progress ?: 0) / 1000f
                ConnectionHolder.channelData[channel].inputDelay = level
                sendRawAsync(Pro2Commands.setInputDelay(channel, level))
            }
        })

        // --- Вкладка SENDS: 16 посылов, строится программно ---
    }

    /**
     * Строит содержимое вкладки EQ для одного канала: общий переключатель
     * IN/OUT сверху, затем 4 полосы (бас/сред-низ/сред-выс/треб), у каждой -
     * своя кнопка активности + 3 ползунка (частота/гейн/ширина).
     * Подтверждено ОПИСАНИЯМИ в списке команд, НЕ подтверждено реальным
     * захватом трафика.
     */
    /**
     * Строит вкладку EQ: интерактивный график (точки полос + HPF/LPF можно
     * таскать пальцем прямо по нему) + компактные карточки с мини-ручками
     * под ним. Подтверждено ОПИСАНИЯМИ в списке команд, HPF/LPF -
     * ПОДТВЕРЖДЕНО реальным захватом (см. Pro2Commands.kt).
     */
    private fun buildEqBlock(container: android.widget.LinearLayout, channel: Int) {
        val data = ConnectionHolder.channelData[channel]
        val bandNames = arrayOf("BASS", "LOW-MID", "MID-HIGH", "TREBLE")
        val bandKinds = arrayOf(Pro2Commands.EqBand.BASS, Pro2Commands.EqBand.LOW_MID, Pro2Commands.EqBand.MID_HIGH, Pro2Commands.EqBand.TREBLE)
        val bandColours = arrayOf(
            Color.parseColor("#34c759"), Color.parseColor("#ffcc00"),
            Color.parseColor("#ff9500"), Color.parseColor("#af52de")
        )

        // --- Заголовок: EQ IN ---
        val eqInButton = Button(this).apply {
            backgroundTintList = null
            text = if (data.eqInLocal) "EQ ON" else "EQ OFF"
            setTextColor(Color.parseColor("#ffffff"))
            setBackgroundColor(Color.parseColor(if (data.eqInLocal) "#ff9f0a" else "#3a3a3c"))
            setOnClickListener {
                val newState = !ConnectionHolder.channelData[channel].eqInLocal
                ConnectionHolder.channelData[channel].eqInLocal = newState
                text = if (newState) "EQ ON" else "EQ OFF"
                setBackgroundColor(Color.parseColor(if (newState) "#ff9f0a" else "#3a3a3c"))
                sendEqIn(channel)
            }
        }
        container.addView(eqInButton, android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = 8 })

        // --- Интерактивный график - точки можно таскать пальцем ---
        val graph = EqCurveView(this)
        for (i in 0 until 4) {
            graph.bands[i] = EqCurveView.Band(data.eqFreq[i], data.eqGain[i], data.eqBandActiveLocal[i], bandColours[i])
        }
        graph.hpFreq = data.hpFilterFreq
        graph.hpOn = data.hpFilterInLocal
        graph.lpFreq = data.lpFilterFreq
        graph.lpOn = data.lpFilterInLocal
        container.addView(graph, android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            (150 * resources.displayMetrics.density).toInt()
        ).apply { bottomMargin = 12 })

        // --- HPF/LPF - те же данные и команды, что и во вкладке INPUT,
        // просто продублированы здесь для наглядности на графике. ---
        val hpLpRow = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
        }
        val hpButton = Button(this).apply {
            backgroundTintList = null
            minHeight = 0
            textSize = 11f
            text = if (data.hpFilterInLocal) "HPF ON" else "HPF OFF"
            setTextColor(Color.parseColor("#ffffff"))
            setBackgroundColor(Color.parseColor(if (data.hpFilterInLocal) "#34c759" else "#3a3a3c"))
            layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginEnd = 6 }
            setOnClickListener {
                val newState = !ConnectionHolder.channelData[channel].hpFilterInLocal
                ConnectionHolder.channelData[channel].hpFilterInLocal = newState
                text = if (newState) "HPF ON" else "HPF OFF"
                setBackgroundColor(Color.parseColor(if (newState) "#34c759" else "#3a3a3c"))
                graph.hpOn = newState
                graph.invalidate()
                sendRawAsync(Pro2Commands.setHpFilterIn(channel, true))
            }
        }
        val lpButton = Button(this).apply {
            backgroundTintList = null
            minHeight = 0
            textSize = 11f
            text = if (data.lpFilterInLocal) "LPF ON" else "LPF OFF"
            setTextColor(Color.parseColor("#ffffff"))
            setBackgroundColor(Color.parseColor(if (data.lpFilterInLocal) "#af52de" else "#3a3a3c"))
            layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                val newState = !ConnectionHolder.channelData[channel].lpFilterInLocal
                ConnectionHolder.channelData[channel].lpFilterInLocal = newState
                text = if (newState) "LPF ON" else "LPF OFF"
                setBackgroundColor(Color.parseColor(if (newState) "#af52de" else "#3a3a3c"))
                graph.lpOn = newState
                graph.invalidate()
                sendRawAsync(Pro2Commands.setLpFilterIn(channel, true))
            }
        }
        hpLpRow.addView(hpButton)
        hpLpRow.addView(lpButton)
        container.addView(hpLpRow, android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = 12 })

        graph.onNodeDragged = { bandIndex, freq, gain ->
            when (bandIndex) {
                -1 -> {
                    ConnectionHolder.channelData[channel].hpFilterFreq = freq
                    sendRawAsync(Pro2Commands.setHpFilterFreq(channel, freq))
                }
                -2 -> {
                    ConnectionHolder.channelData[channel].lpFilterFreq = freq
                    sendRawAsync(Pro2Commands.setLpFilterFreq(channel, freq))
                }
                else -> {
                    val band = bandKinds[bandIndex]
                    ConnectionHolder.channelData[channel].eqFreq[bandIndex] = freq
                    ConnectionHolder.channelData[channel].eqGain[bandIndex] = gain
                    sendEqParam(channel, band, ParamKind.EQ_FREQ, freq)
                    sendEqParam(channel, band, ParamKind.EQ_GAIN, gain)
                    detailEqViews?.bands?.getOrNull(bandIndex)?.let {
                        it.freqKnob.value = freq
                        it.freqText.text = "%.2f".format(freq)
                        it.gainKnob.value = gain
                        it.gainText.text = "%.2f".format(gain)
                    }
                }
            }
        }

        // --- Компактные карточки полос: имя + вкл/выкл + 3 мини-ручки ---
        var bassShapeBtn: Button? = null
        var trebleShapeBtn: Button? = null
        val bandViews = Array(4) { bandIndex ->
            val band = bandKinds[bandIndex]

            val header = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                setPadding(0, if (bandIndex == 0) 0 else 10, 0, 6)
            }
            val bandLabel = TextView(this).apply {
                text = bandNames[bandIndex]
                setTextColor(bandColours[bandIndex])
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                textSize = 12f
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
            }
            val activeButton = Button(this).apply {
                text = if (data.eqBandActiveLocal[bandIndex]) "ON" else "OFF"
                textSize = 10f
                minHeight = 0
                minimumHeight = 0
                backgroundTintList = null
                setPadding(16, 4, 16, 4)
                setTextColor(Color.parseColor("#ffffff"))
                setBackgroundColor(Color.parseColor(if (data.eqBandActiveLocal[bandIndex]) "#ff9f0a" else "#3a3a3c"))
                setOnClickListener {
                    val newState = !ConnectionHolder.channelData[channel].eqBandActiveLocal[bandIndex]
                    ConnectionHolder.channelData[channel].eqBandActiveLocal[bandIndex] = newState
                    text = if (newState) "ON" else "OFF"
                    setBackgroundColor(Color.parseColor(if (newState) "#ff9f0a" else "#3a3a3c"))
                    graph.bands[bandIndex].active = newState
                    graph.invalidate()
                    sendEqBandActive(channel, band)
                }
            }
            header.addView(bandLabel)
            // Форма (bell/shelf) - ТОЛЬКО у BASS и TREBLE. ПОДТВЕРЖДЕНО
            // реальным захватом трафика iPad.
            if (bandIndex == 0 || bandIndex == 3) {
                val isShelfInitial = if (bandIndex == 0) data.eqBassShelf else data.eqTrebleShelf
                val shapeButton = Button(this).apply {
                    text = if (isShelfInitial) "SHELF" else "BELL"
                    textSize = 10f
                    minHeight = 0
                    minimumHeight = 0
                    backgroundTintList = null
                    setPadding(12, 4, 12, 4)
                    setTextColor(Color.parseColor("#ffffff"))
                    setBackgroundColor(Color.parseColor("#3a3a3c"))
                    setOnClickListener {
                        val d = ConnectionHolder.channelData[channel]
                        val newState = if (bandIndex == 0) !d.eqBassShelf else !d.eqTrebleShelf
                        if (bandIndex == 0) d.eqBassShelf = newState else d.eqTrebleShelf = newState
                        text = if (newState) "SHELF" else "BELL"
                        sendRawAsync(Pro2Commands.setEqShape(channel, band, newState))
                    }
                }
                val shapeParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = 6 }
                header.addView(shapeButton, shapeParams)
                if (bandIndex == 0) bassShapeBtn = shapeButton else trebleShapeBtn = shapeButton
            }
            header.addView(activeButton)
            container.addView(header)

            val knobRow = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
            }
            fun makeKnob(label: String, initial: Float, kind: ParamKind): Pair<RotaryKnobView, TextView> {
                val cell = android.widget.LinearLayout(this).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    gravity = android.view.Gravity.CENTER_HORIZONTAL
                    layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                val cellLabel = TextView(this).apply {
                    text = label
                    setTextColor(Color.parseColor("#8e8e93"))
                    textSize = 10f
                }
                val knob = RotaryKnobView(this).apply {
                    value = initial
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        (52 * resources.displayMetrics.density).toInt(),
                        (52 * resources.displayMetrics.density).toInt()
                    ).apply { topMargin = 4; bottomMargin = 4 }
                }
                // Для FREQ теперь показываем реальные Гц (подтверждено
                // приблизительно по фото экрана пульта - см. заметку в
                // EqCurveView.kt про bandHzRange). GAIN/WIDTH остаются
                // сырым числом - для них калибровки нет.
                val eqBandId = when (bandIndex) {
                    0 -> EqCurveView.BandId.BASS
                    1 -> EqCurveView.BandId.LOW_MID
                    2 -> EqCurveView.BandId.MID_HIGH
                    else -> EqCurveView.BandId.TREBLE
                }
                fun formatValue(v: Float): String = when (kind) {
                    ParamKind.EQ_FREQ -> EqCurveView.formatHz(EqCurveView.rawToHzPublic(v, eqBandId))
                    ParamKind.EQ_WIDTH -> EqCurveView.formatWidth(EqCurveView.rawToWidthPublic(v))
                    else -> "%.2f".format(v)
                }
                val valueText = TextView(this).apply {
                    text = formatValue(initial)
                    setTextColor(Color.parseColor("#aaaaaa"))
                    textSize = 10f
                }
                var lastSend = 0L
                knob.onValueChanged = { v ->
                    valueText.text = formatValue(v)
                    persistEq(channel, bandIndex, kind, v)
                    if (kind == ParamKind.EQ_FREQ) { graph.bands[bandIndex].freq = v; graph.invalidate() }
                    if (kind == ParamKind.EQ_GAIN) { graph.bands[bandIndex].gain = v; graph.invalidate() }
                    val now = System.currentTimeMillis()
                    if (now - lastSend >= minSendIntervalMs) {
                        lastSend = now
                        sendEqParam(channel, band, kind, v)
                    }
                }
                cell.addView(cellLabel)
                cell.addView(knob)
                cell.addView(valueText)
                knobRow.addView(cell)
                return knob to valueText
            }

            val (freqKnob, freqText) = makeKnob("FREQ", data.eqFreq[bandIndex], ParamKind.EQ_FREQ)
            val (gainKnob, gainText) = makeKnob("GAIN", data.eqGain[bandIndex], ParamKind.EQ_GAIN)
            val (widthKnob, widthText) = makeKnob("WIDTH", data.eqWidth[bandIndex], ParamKind.EQ_WIDTH)
            container.addView(knobRow)

            EqBandViews(activeButton, freqKnob, freqText, gainKnob, gainText, widthKnob, widthText)
        }

        detailEqViews = EqBlockViews(eqInButton, bandViews, graph, hpButton, lpButton, bassShapeBtn, trebleShapeBtn)
    }

    private fun persistEq(channel: Int, bandIndex: Int, kind: ParamKind, level: Float) {
        val data = ConnectionHolder.channelData[channel]
        when (kind) {
            ParamKind.EQ_FREQ -> data.eqFreq[bandIndex] = level
            ParamKind.EQ_GAIN -> data.eqGain[bandIndex] = level
            ParamKind.EQ_WIDTH -> data.eqWidth[bandIndex] = level
            else -> {}
        }
    }

    /**
     * Строит содержимое вкладки GATE: переключатель IN/OUT, 7 непрерывных
     * параметров и переключатель фильтров. ПОЛНОСТЬЮ ПОДТВЕРЖДЕНО реальным
     * захватом трафика iPad.
     */
    /**
     * Строит содержимое вкладки COMP или GATE: заголовок с переключателем
     * IN, график передаточной функции, сетка ручек (2 в ряд), и (если есть)
     * переключатель фильтра снизу. Общая функция для обоих блоков - они
     * структурно идентичны, отличаются только набором параметров и цветом.
     */
    private fun buildDynamicsBlock(
        container: android.widget.LinearLayout,
        channel: Int,
        mode: TransferCurveView.Mode
    ) {
        val data = ConnectionHolder.channelData[channel]
        val isGate = mode == TransferCurveView.Mode.GATE
        val accent = if (isGate) Color.parseColor("#34c759") else Color.parseColor("#ff9f0a")
        val accentHex = if (isGate) "#34c759" else "#ff9f0a"

        // --- Заголовок: название + переключатель IN (с цветной точкой) ---
        val header = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = if (isGate) "GATE" else "COMPRESSOR"
            setTextColor(accent)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            textSize = 16f
            layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val inLocal = if (isGate) data.gateInLocal else data.compInLocal
        val inButton = Button(this).apply {
            text = if (inLocal) "IN ●" else "IN ○"
            backgroundTintList = null
            setTextColor(if (inLocal) accent else Color.parseColor("#8e8e93"))
            setBackgroundColor(Color.parseColor("#2c2c2e"))
            setOnClickListener {
                val d = ConnectionHolder.channelData[channel]
                if (isGate) {
                    val newState = !d.gateInLocal
                    d.gateInLocal = newState
                    text = if (newState) "IN ●" else "IN ○"
                    setTextColor(if (newState) accent else Color.parseColor("#8e8e93"))
                    sendGateIn(channel, newState)
                } else {
                    val newState = !d.compInLocal
                    d.compInLocal = newState
                    text = if (newState) "IN ●" else "IN ○"
                    setTextColor(if (newState) accent else Color.parseColor("#8e8e93"))
                    sendRawAsync(Pro2Commands.setCompIn(channel))
                }
            }
        }
        val detText = TextView(this).apply {
            text = "IN -0.0"
            setTextColor(Color.parseColor("#666666"))
            textSize = 10f
            setPadding(8, 0, 8, 0)
        }
        val grText = TextView(this).apply {
            text = "GR -0.0"
            setTextColor(Color.parseColor("#8e8e93"))
            textSize = 11f
            setPadding(8, 0, 16, 0)
        }
        header.addView(title)
        header.addView(detText)
        header.addView(grText)
        header.addView(inButton)
        container.addView(header, android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = 4 })

        // Режим (компрессор/gate) - ТОЛЬКО ЧТЕНИЕ, не знаем формат SET
        // (менялось прямо на экране пульта в захвате, см. заметку в
        // Pro2Commands.kt). Показываем как текст под заголовком.
        val modeText = TextView(this).apply {
            text = "MODE: —"
            setTextColor(Color.parseColor("#8e8e93"))
            textSize = 11f
        }
        container.addView(modeText, android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = 8 })

        // --- График передаточной функции ---
        val graph = TransferCurveView(this).apply {
            this.mode = mode
            accentColor = accent
        }
        val graphParams = android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            (130 * resources.displayMetrics.density).toInt()
        ).apply { bottomMargin = 12 }
        container.addView(graph, graphParams)

        // --- Сетка ручек (2 в ряд) ---
        val paramList: List<Triple<String, ParamKind, Float>> = if (isGate) listOf(
            Triple("THRESHOLD", ParamKind.GATE_THRESHOLD, data.gateThreshold),
            Triple("RANGE", ParamKind.GATE_RANGE, data.gateRange),
            Triple("ATTACK", ParamKind.GATE_ATTACK, data.gateAttack),
            Triple("HOLD", ParamKind.GATE_HOLD, data.gateHold),
            Triple("RELEASE", ParamKind.GATE_RELEASE, data.gateRelease),
            Triple("TRANSIENT", ParamKind.GATE_TRANSIENT, data.gateTransient),
            Triple("FILTER FREQ", ParamKind.GATE_FILTER_FREQ, data.gateFilterFreq)
        ) else listOf(
            Triple("THRESHOLD", ParamKind.COMP_THRESHOLD, data.compThreshold),
            Triple("RATIO", ParamKind.COMP_RATIO, data.compRatio),
            Triple("ATTACK", ParamKind.COMP_ATTACK, data.compAttack),
            Triple("RELEASE", ParamKind.COMP_RELEASE, data.compRelease),
            Triple("GAIN", ParamKind.COMP_MAKEUP, data.compMakeup),
            Triple("FILTER FREQ", ParamKind.COMP_FILTER_FREQ, data.compFilterFreq)
        )
        val thresholdKind = if (isGate) ParamKind.GATE_THRESHOLD else ParamKind.COMP_THRESHOLD
        val ratioOrRangeKind = if (isGate) ParamKind.GATE_RANGE else ParamKind.COMP_RATIO
        graph.threshold = paramList.first { it.second == thresholdKind }.third
        graph.ratioOrRange = paramList.first { it.second == ratioOrRangeKind }.third

        val knobViews = mutableMapOf<ParamKind, Pair<RotaryKnobView, TextView>>()
        var row: android.widget.LinearLayout? = null
        for ((index, item) in paramList.withIndex()) {
            val (label, kind, initial) = item
            if (index % 2 == 0) {
                row = android.widget.LinearLayout(this).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                }
                container.addView(row, android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 8 })
            }
            val cell = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val cellLabel = TextView(this).apply {
                text = label
                setTextColor(Color.parseColor("#8e8e93"))
                textSize = 11f
            }
            val knob = RotaryKnobView(this).apply {
                value = initial
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    (58 * resources.displayMetrics.density).toInt(),
                    (58 * resources.displayMetrics.density).toInt()
                ).apply { topMargin = 4; bottomMargin = 4 }
            }
            val valueText = TextView(this).apply {
                text = formatKnobValue(kind, initial)
                setTextColor(accent)
                textSize = 12f
            }
            knob.onValueChanged = { v ->
                valueText.text = formatKnobValue(kind, v)
                persistDynamicsParam(channel, kind, v)
                if (kind == thresholdKind) graph.threshold = v
                if (kind == ratioOrRangeKind) graph.ratioOrRange = v
                sendDynamicsParam(channel, kind, v)
            }
            cell.addView(cellLabel)
            cell.addView(knob)
            cell.addView(valueText)
            row?.addView(cell)
            knobViews[kind] = knob to valueText
        }

        // --- Переключатель фильтра (есть и у gate, и у comp) ---
        val filtersInLocal = if (isGate) data.gateFiltersInLocal else data.compFiltersInLocal
        val filtersInButton = Button(this).apply {
            backgroundTintList = null
            text = if (filtersInLocal) "FILTER ON" else "FILTER OFF"
            setTextColor(Color.parseColor("#ffffff"))
            setBackgroundColor(Color.parseColor(if (filtersInLocal) accentHex else "#3a3a3c"))
            setOnClickListener {
                val d = ConnectionHolder.channelData[channel]
                if (isGate) {
                    val newState = !d.gateFiltersInLocal
                    d.gateFiltersInLocal = newState
                    text = if (newState) "FILTER ON" else "FILTER OFF"
                    setBackgroundColor(Color.parseColor(if (newState) accentHex else "#3a3a3c"))
                    sendGateFiltersIn(channel, newState)
                } else {
                    val newState = !d.compFiltersInLocal
                    d.compFiltersInLocal = newState
                    text = if (newState) "FILTER ON" else "FILTER OFF"
                    setBackgroundColor(Color.parseColor(if (newState) accentHex else "#3a3a3c"))
                    sendRawAsync(Pro2Commands.setCompFiltersIn(channel, true))
                }
            }
        }
        container.addView(filtersInButton)

        val views = DynamicsBlockViews(inButton, graph, knobViews, filtersInButton, thresholdKind, ratioOrRangeKind, grText, detText, modeText)
        if (isGate) detailGateDynViews = views else detailCompDynViews = views
    }

    private fun persistDynamicsParam(channel: Int, kind: ParamKind, level: Float) {
        val data = ConnectionHolder.channelData[channel]
        when (kind) {
            ParamKind.COMP_RATIO -> data.compRatio = level
            ParamKind.COMP_ATTACK -> data.compAttack = level
            ParamKind.COMP_RELEASE -> data.compRelease = level
            ParamKind.COMP_THRESHOLD -> data.compThreshold = level
            ParamKind.COMP_MAKEUP -> data.compMakeup = level
            ParamKind.COMP_FILTER_FREQ -> data.compFilterFreq = level
            ParamKind.GATE_THRESHOLD -> data.gateThreshold = level
            ParamKind.GATE_RANGE -> data.gateRange = level
            ParamKind.GATE_ATTACK -> data.gateAttack = level
            ParamKind.GATE_HOLD -> data.gateHold = level
            ParamKind.GATE_RELEASE -> data.gateRelease = level
            ParamKind.GATE_TRANSIENT -> data.gateTransient = level
            ParamKind.GATE_FILTER_FREQ -> data.gateFilterFreq = level
            else -> {}
        }
    }

    private fun sendDynamicsParam(channel: Int, kind: ParamKind, level: Float) {
        val packet = when (kind) {
            ParamKind.COMP_RATIO -> Pro2Commands.setCompRatio(channel, level)
            ParamKind.COMP_ATTACK -> Pro2Commands.setCompAttack(channel, level)
            ParamKind.COMP_RELEASE -> Pro2Commands.setCompRelease(channel, level)
            ParamKind.COMP_THRESHOLD -> Pro2Commands.setCompThreshold(channel, level)
            ParamKind.COMP_MAKEUP -> Pro2Commands.setCompMakeupGain(channel, level)
            ParamKind.COMP_FILTER_FREQ -> Pro2Commands.setCompFilterFreq(channel, level)
            ParamKind.GATE_THRESHOLD -> Pro2Commands.setGateThreshold(channel, level)
            ParamKind.GATE_RANGE -> Pro2Commands.setGateRange(channel, level)
            ParamKind.GATE_ATTACK -> Pro2Commands.setGateAttack(channel, level)
            ParamKind.GATE_HOLD -> Pro2Commands.setGateHold(channel, level)
            ParamKind.GATE_RELEASE -> Pro2Commands.setGateRelease(channel, level)
            ParamKind.GATE_TRANSIENT -> Pro2Commands.setGateTransient(channel, level)
            ParamKind.GATE_FILTER_FREQ -> Pro2Commands.setGateFilterFreq(channel, level)
            else -> return
        }
        sendRawAsync(packet)
    }

    /**
     * Линейная интерполяция по точкам-подсказкам из списка команд Pro2.
     * Подтверждено только для 3 параметров компрессора (threshold/attack/
     * release) - у остальных таких точек нет, там остаётся сырое 0.00-1.00.
     */
    private fun interpolate(v: Float, points: List<Pair<Float, Float>>): Float {
        val x = v.coerceIn(0f, 1f)
        for (i in 0 until points.size - 1) {
            val (x0, y0) = points[i]
            val (x1, y1) = points[i + 1]
            if (x <= x1) {
                val t = if (x1 == x0) 0f else (x - x0) / (x1 - x0)
                return y0 + (y1 - y0) * t
            }
        }
        return points.last().second
    }

    // Подтверждено описанием в списке команд Pro2 (не реальным захватом
    // формулы, но это прямая подсказка производителя, не догадка).
    private val compThresholdPoints = listOf(0f to -50f, 0.35f to -25f, 0.65f to 0f, 1f to 25f)
    private val compAttackPoints = listOf(0f to 0.2f, 0.35f to 1f, 0.65f to 6f, 1f to 20f)
    private val compReleasePoints = listOf(0f to 0.05f, 0.35f to 0.2f, 0.65f to 0.8f, 1f to 3f)

    private fun formatCompThreshold(v: Float): String = "%.1f dB".format(interpolate(v, compThresholdPoints))
    private fun formatCompAttack(v: Float): String = "%.1f ms".format(interpolate(v, compAttackPoints))
    private fun formatCompRelease(v: Float): String {
        val sec = interpolate(v, compReleasePoints)
        return if (sec < 1f) "%.0f ms".format(sec * 1000) else "%.2f s".format(sec)
    }

    /** Показывает реальные единицы там, где формула подтверждена, иначе сырое 0.00-1.00. */
    private fun formatKnobValue(kind: ParamKind, v: Float): String = when (kind) {
        ParamKind.COMP_THRESHOLD -> formatCompThreshold(v)
        ParamKind.COMP_ATTACK -> formatCompAttack(v)
        ParamKind.COMP_RELEASE -> formatCompRelease(v)
        else -> "%.2f".format(v)
    }

    private fun panLabelFor(v: Float): String = when {
        v < 0.48f -> "L %.0f".format((0.5f - v) * 200)
        v > 0.52f -> "R %.0f".format((v - 0.5f) * 200)
        else -> "CENTER"
    }

    private fun updatePanUi(channel: Int, level: Float) {
        ConnectionHolder.channelData[channel].pan = level
        if (openDetailChannel != channel) return
        detailPanSeek?.progress = (level * 1000).toInt()
        detailPanText?.text = panLabelFor(level)
    }

    private fun updatePhantomUi(channel: Int, on: Boolean) {
        ConnectionHolder.channelData[channel].phantomLocal = on
        if (openDetailChannel != channel) return
        detailPhantomButton?.text = if (on) "48V ON" else "48V OFF"
        detailPhantomButton?.setBackgroundColor(Color.parseColor(if (on) "#ff9f0a" else "#3a3a3c"))
    }

    private fun updatePhaseUi(channel: Int, on: Boolean) {
        ConnectionHolder.channelData[channel].phaseLocal = on
        if (openDetailChannel != channel) return
        detailPhaseButton?.text = if (on) "PHASE INV" else "PHASE NORM"
        detailPhaseButton?.setBackgroundColor(Color.parseColor(if (on) "#ff9f0a" else "#3a3a3c"))
    }

    /** Общее обновление живого виджета сетки (используется и для COMP, и для GATE). */
    private fun updateDynamicsKnobUi(views: DynamicsBlockViews?, kind: ParamKind, level: Float) {
        val pair = views?.knobViews?.get(kind) ?: return
        pair.first.value = level
        pair.second.text = formatKnobValue(kind, level)
        if (kind == views.thresholdKind) views.graphView.threshold = level
        if (kind == views.ratioOrRangeKind) views.graphView.ratioOrRange = level
    }

    private fun updateGateInUi(channel: Int, on: Boolean) {
        ConnectionHolder.channelData[channel].gateInLocal = on
        if (openDetailChannel != channel) return
        val views = detailGateDynViews ?: return
        views.inButton.text = if (on) "IN ●" else "IN ○"
        views.inButton.setTextColor(if (on) Color.parseColor("#34c759") else Color.parseColor("#8e8e93"))
    }

    private fun updateGateFiltersInUi(channel: Int, on: Boolean) {
        ConnectionHolder.channelData[channel].gateFiltersInLocal = on
        if (openDetailChannel != channel) return
        val views = detailGateDynViews ?: return
        views.filtersInButton?.text = if (on) "FILTER ON" else "FILTER OFF"
        views.filtersInButton?.setBackgroundColor(Color.parseColor(if (on) "#34c759" else "#3a3a3c"))
    }

    private fun updateGateParamUi(channel: Int, kind: ParamKind, level: Float) {
        persistDynamicsParam(channel, kind, level)
        if (openDetailChannel != channel) return
        updateDynamicsKnobUi(detailGateDynViews, kind, level)
    }

    internal fun updateFaderUi(channel: Int, level: Float) {
        val ui = channels.getOrNull(channel)
        // Пока пользователь реально держит палец на фейдере - не даём
        // push-обновлению (в т.ч. запоздавшему "эхо" наших же команд)
        // перебивать его текущее положение. Собственные onProgressChanged
        // уже держат ConnectionHolder в актуальном состоянии в реальном
        // времени, так что пропустить устаревшее значение здесь безопасно.
        if (ui?.isDragging == true) return

        ConnectionHolder.channelData[channel].fader = level
        if (ui == null) return
        ui.suppressEvents = true
        ui.fader.progress = (level.coerceIn(0f, 1f) * 1000).toInt()
        ui.levelValueText.text = "%.2f".format(level)
        ui.suppressEvents = false

        if (openDetailChannel == channel) {
            val dv = detailViews ?: return
            dv.fader.progress = (level.coerceIn(0f, 1f) * 1000).toInt()
            dv.levelText.text = "%.2f".format(level)
        }
    }

    internal fun updateMuteUi(channel: Int, muted: Boolean) {
        ConnectionHolder.channelData[channel].mutedLocal = muted
        val ui = channels.getOrNull(channel) ?: return
        ui.mutedLocal = muted
        ui.muteButton.setBackgroundColor(
            Color.parseColor(if (muted) "#ff3b30" else "#3a3a3c")
        )
        if (openDetailChannel == channel) {
            detailViews?.muteButton?.setBackgroundColor(
                Color.parseColor(if (muted) "#ff3b30" else "#3a3a3c")
            )
        }
    }

    internal fun updateSoloUi(channel: Int, soloed: Boolean) {
        ConnectionHolder.channelData[channel].soloed = soloed
        val ui = channels.getOrNull(channel) ?: return
        ui.suppressEvents = true
        ui.soloButton.isChecked = soloed
        ui.soloButton.setBackgroundColor(
            Color.parseColor(if (soloed) "#ff9f0a" else "#3a3a3c")
        )
        ui.suppressEvents = false

        if (openDetailChannel == channel) {
            val dv = detailViews ?: return
            dv.soloButton.isChecked = soloed
            dv.soloButton.setBackgroundColor(
                Color.parseColor(if (soloed) "#ff9f0a" else "#3a3a3c")
            )
        }
    }

    internal fun updateGainUi(channel: Int, level: Float) {
        ConnectionHolder.channelData[channel].gain = level
        // Ручку GAIN убрали из основной полосы канала - теперь она живёт
        // только в детальном экране (вкладка INPUT), поэтому обновляем её
        // там же, только если этот канал сейчас реально открыт.
        if (openDetailChannel == channel) {
            val dv = detailViews ?: return
            dv.gainKnob.value = level
            dv.gainValueText.text = "%.2f".format(level)
        }
    }

    private fun updateGainTrimUi(channel: Int, level: Float) {
        ConnectionHolder.channelData[channel].gainTrim = level
        if (openDetailChannel == channel) {
            detailGainTrimKnobRef?.value = level
            detailGainTrimValueRef?.text = "%.2f".format(level)
        }
    }

    internal fun updateNameUi(channel: Int, name: String) {
        if (name.isBlank()) return
        ConnectionHolder.channelData[channel].name = name
        channels.getOrNull(channel)?.labelView?.text = name
        // Мониторный режим не строит инженерский список channels - у него
        // свой собственный набор полос, обновляем отдельно.
        if (appMode == MODE_MONITOR) {
            monitorChannelLabels.getOrNull(channel)?.text = name
        }
    }

    private fun sendFader(channelIndex: Int, level: Float) {
        sendRawAsync(Pro2Commands.setFader(channelIndex, level))
    }

    private fun sendGain(channelIndex: Int, level: Float) {
        sendRawAsync(Pro2Commands.setGain(channelIndex, level))
    }

    private fun sendPan(channelIndex: Int, level: Float) {
        sendRawAsync(Pro2Commands.setPan(channelIndex, level))
    }

    private fun sendPhantomPower(channelIndex: Int, on: Boolean) {
        // ВАЖНО: как и mute/solo/compIn - это TOGGLE-параметр на пульте
        // (любой полученный пакет переключает состояние, независимо от
        // значения). Раньше здесь отправлялось явное on/off, и пульт,
        // судя по всему, игнорировал пакеты со значением 0 - отсюда баг
        // "включается, но не выключается". Теперь всегда шлём "включить",
        // а направление контролирует локальное состояние + push с пульта.
        sendRawAsync(Pro2Commands.setPhantomPower(channelIndex, true))
    }

    private fun sendPhase(channelIndex: Int, inverted: Boolean) {
        sendRawAsync(Pro2Commands.setPhase(channelIndex, true))
    }

    private fun sendChannelName(channelIndex: Int, name: String) {
        sendRawAsync(Pro2Commands.setName(channelIndex, name))
    }

    private fun sendChannelColour(channelIndex: Int, argb: Int) {
        sendRawAsync(Pro2Commands.setColour(channelIndex, argb))
    }

    private fun sendGateIn(channelIndex: Int, on: Boolean) {
        sendRawAsync(Pro2Commands.setGateIn(channelIndex, true))
    }

    private fun sendGateFiltersIn(channelIndex: Int, on: Boolean) {
        sendRawAsync(Pro2Commands.setGateFiltersIn(channelIndex, true))
    }

    private fun sendGateParam(channelIndex: Int, kind: ParamKind, level: Float) {
        val packet = when (kind) {
            ParamKind.GATE_THRESHOLD -> Pro2Commands.setGateThreshold(channelIndex, level)
            ParamKind.GATE_RANGE -> Pro2Commands.setGateRange(channelIndex, level)
            ParamKind.GATE_ATTACK -> Pro2Commands.setGateAttack(channelIndex, level)
            ParamKind.GATE_HOLD -> Pro2Commands.setGateHold(channelIndex, level)
            ParamKind.GATE_RELEASE -> Pro2Commands.setGateRelease(channelIndex, level)
            ParamKind.GATE_TRANSIENT -> Pro2Commands.setGateTransient(channelIndex, level)
            ParamKind.GATE_FILTER_FREQ -> Pro2Commands.setGateFilterFreq(channelIndex, level)
            else -> return
        }
        sendRawAsync(packet)
    }

    private fun sendMute(channelIndex: Int, muted: Boolean) {
        val packet = Pro2Commands.setMute(channelIndex, muted)
        val address = consoleAddress ?: return
        val sock = socket ?: return
        val port = consolePort
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // ВАЖНО: реальное поведение пульта для enMuteStatus — это TOGGLE
                // (переключение на любой полученный пакет), а не "установка" в explicit
                // значение. Раньше здесь была отправка одного и того же пакета 3 раза
                // "для надёжности" - но при toggle-семантике это САМО ломает результат:
                // 3 отправки = 3 переключения, и итоговое состояние зависит от того,
                // сколько из трёх пакетов реально дошло по Wi-Fi (UDP не гарантирует
                // доставку). Именно это вызывало необходимость иногда жать кнопку дважды.
                // Раз это toggle - отправляем РОВНО один пакет на одно нажатие.
                sendRaw(sock, address, port, packet)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { textStatus.text = "Mute error: ${e.message}" }
            }
        }
    }

    private fun sendMasterFader(masterIndex: Int, level: Float) {
        val packet = Pro2Commands.setMasterFader(masterIndex, level)
        val address = consoleAddress ?: return
        val sock = socket ?: return
        val port = consolePort
        CoroutineScope(Dispatchers.IO).launch {
            try {
                sendRaw(sock, address, port, packet)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { textStatus.text = "Master fader error: ${e.message}" }
            }
        }
    }

    private fun sendMasterMute(masterIndex: Int, muted: Boolean) {
        val packet = Pro2Commands.setMasterMute(masterIndex, muted)
        val address = consoleAddress ?: return
        val sock = socket ?: return
        val port = consolePort
        CoroutineScope(Dispatchers.IO).launch {
            try {
                sendRaw(sock, address, port, packet)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { textStatus.text = "Master mute error: ${e.message}" }
            }
        }
    }

    private fun sendMasterSolo(masterIndex: Int, soloed: Boolean) {
        val packet = Pro2Commands.setMasterSolo(masterIndex, soloed)
        val address = consoleAddress ?: return
        val sock = socket ?: return
        val port = consolePort
        CoroutineScope(Dispatchers.IO).launch {
            try {
                sendRaw(sock, address, port, packet)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { textStatus.text = "Master solo error: ${e.message}" }
            }
        }
    }

    private fun sendAuxReturnFader(auxIndex: Int, level: Float) {
        val packet = Pro2Commands.setAuxReturnFader(auxIndex, level)
        val address = consoleAddress ?: return
        val sock = socket ?: return
        val port = consolePort
        CoroutineScope(Dispatchers.IO).launch {
            try {
                sendRaw(sock, address, port, packet)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { textStatus.text = "Aux fader error: ${e.message}" }
            }
        }
    }

    private fun sendAuxReturnMute(auxIndex: Int, muted: Boolean) {
        val packet = Pro2Commands.setAuxReturnMute(auxIndex, muted)
        val address = consoleAddress ?: return
        val sock = socket ?: return
        val port = consolePort
        CoroutineScope(Dispatchers.IO).launch {
            try {
                sendRaw(sock, address, port, packet)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { textStatus.text = "Aux mute error: ${e.message}" }
            }
        }
    }

    private fun sendAuxReturnSolo(auxIndex: Int, soloed: Boolean) {
        val packet = Pro2Commands.setAuxReturnSolo(auxIndex, soloed)
        val address = consoleAddress ?: return
        val sock = socket ?: return
        val port = consolePort
        CoroutineScope(Dispatchers.IO).launch {
            try {
                sendRaw(sock, address, port, packet)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { textStatus.text = "Aux solo error: ${e.message}" }
            }
        }
    }

    private fun sendAuxBusFader(busIndex: Int, level: Float) {
        val packet = Pro2Commands.setAuxBusFader(busIndex, level)
        val address = consoleAddress ?: return
        val sock = socket ?: return
        val port = consolePort
        CoroutineScope(Dispatchers.IO).launch {
            try {
                sendRaw(sock, address, port, packet)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { textStatus.text = "Aux bus fader error: ${e.message}" }
            }
        }
    }

    private fun sendAuxBusMute(busIndex: Int, muted: Boolean) {
        val packet = Pro2Commands.setAuxBusMute(busIndex, muted)
        val address = consoleAddress ?: return
        val sock = socket ?: return
        val port = consolePort
        CoroutineScope(Dispatchers.IO).launch {
            try {
                sendRaw(sock, address, port, packet)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { textStatus.text = "Aux bus mute error: ${e.message}" }
            }
        }
    }

    private fun sendAuxBusSolo(busIndex: Int, soloed: Boolean) {
        val packet = Pro2Commands.setAuxBusSolo(busIndex, soloed)
        val address = consoleAddress ?: return
        val sock = socket ?: return
        val port = consolePort
        CoroutineScope(Dispatchers.IO).launch {
            try {
                sendRaw(sock, address, port, packet)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { textStatus.text = "Aux bus solo error: ${e.message}" }
            }
        }
    }

    private fun sendVcaFader(vcaIndex: Int, level: Float) {
        val packet = Pro2Commands.setVcaFader(vcaIndex, level)
        val address = consoleAddress ?: return
        val sock = socket ?: return
        val port = consolePort
        CoroutineScope(Dispatchers.IO).launch {
            try {
                sendRaw(sock, address, port, packet)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { textStatus.text = "VCA fader error: ${e.message}" }
            }
        }
    }

    private fun sendVcaMute(vcaIndex: Int, muted: Boolean) {
        val packet = Pro2Commands.setVcaMute(vcaIndex, muted)
        val address = consoleAddress ?: return
        val sock = socket ?: return
        val port = consolePort
        CoroutineScope(Dispatchers.IO).launch {
            try {
                sendRaw(sock, address, port, packet)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { textStatus.text = "VCA mute error: ${e.message}" }
            }
        }
    }

    private fun sendVcaSolo(vcaIndex: Int, soloed: Boolean) {
        val packet = Pro2Commands.setVcaSolo(vcaIndex, soloed)
        val address = consoleAddress ?: return
        val sock = socket ?: return
        val port = consolePort
        CoroutineScope(Dispatchers.IO).launch {
            try {
                sendRaw(sock, address, port, packet)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { textStatus.text = "VCA solo error: ${e.message}" }
            }
        }
    }

    private fun sendSubSend(channelIndex: Int, auxBus: Int, level: Float) {
        val packet = Pro2Commands.setSubSendLevel(channelIndex, auxBus, level)
        val address = consoleAddress ?: return
        val sock = socket ?: return
        val port = consolePort
        CoroutineScope(Dispatchers.IO).launch {
            try {
                sendRaw(sock, address, port, packet)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { textStatus.text = "Aux send error: ${e.message}" }
            }
        }
    }

    private fun sendEqIn(channelIndex: Int) {
        val packet = Pro2Commands.setEqIn(channelIndex)
        val address = consoleAddress ?: return
        val sock = socket ?: return
        val port = consolePort
        CoroutineScope(Dispatchers.IO).launch {
            try {
                sendRaw(sock, address, port, packet)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { textStatus.text = "EQ in error: ${e.message}" }
            }
        }
    }

    private fun sendEqBandActive(channelIndex: Int, band: Pro2Commands.EqBand) {
        val packet = Pro2Commands.setEqBandActive(channelIndex, band)
        val address = consoleAddress ?: return
        val sock = socket ?: return
        val port = consolePort
        CoroutineScope(Dispatchers.IO).launch {
            try {
                sendRaw(sock, address, port, packet)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { textStatus.text = "EQ band error: ${e.message}" }
            }
        }
    }

    private fun sendEqParam(channelIndex: Int, band: Pro2Commands.EqBand, kind: ParamKind, level: Float) {
        val packet = when (kind) {
            ParamKind.EQ_FREQ -> Pro2Commands.setEqFreq(channelIndex, band, level)
            ParamKind.EQ_GAIN -> Pro2Commands.setEqGain(channelIndex, band, level)
            ParamKind.EQ_WIDTH -> Pro2Commands.setEqWidth(channelIndex, band, level)
            else -> return
        }
        val address = consoleAddress ?: return
        val sock = socket ?: return
        val port = consolePort
        CoroutineScope(Dispatchers.IO).launch {
            try {
                sendRaw(sock, address, port, packet)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { textStatus.text = "EQ error: ${e.message}" }
            }
        }
    }

    private fun sendCompIn(channelIndex: Int) {
        val packet = Pro2Commands.setCompIn(channelIndex)
        val address = consoleAddress ?: return
        val sock = socket ?: return
        val port = consolePort
        CoroutineScope(Dispatchers.IO).launch {
            try {
                sendRaw(sock, address, port, packet)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { textStatus.text = "Comp in error: ${e.message}" }
            }
        }
    }

    private fun sendCompParam(channelIndex: Int, level: Float, which: ParamKind) {
        val address = consoleAddress ?: return
        val sock = socket ?: return
        val port = consolePort
        val packet = when (which) {
            ParamKind.COMP_RATIO -> Pro2Commands.setCompRatio(channelIndex, level)
            ParamKind.COMP_ATTACK -> Pro2Commands.setCompAttack(channelIndex, level)
            ParamKind.COMP_RELEASE -> Pro2Commands.setCompRelease(channelIndex, level)
            ParamKind.COMP_THRESHOLD -> Pro2Commands.setCompThreshold(channelIndex, level)
            ParamKind.COMP_MAKEUP -> Pro2Commands.setCompMakeupGain(channelIndex, level)
            ParamKind.COMP_FILTER_FREQ -> Pro2Commands.setCompFilterFreq(channelIndex, level)
            else -> return
        }
        CoroutineScope(Dispatchers.IO).launch {
            try {
                sendRaw(sock, address, port, packet)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { textStatus.text = "Compressor error: ${e.message}" }
            }
        }
    }

    private fun sendSolo(channelIndex: Int, soloed: Boolean) {
        val packet = Pro2Commands.setSolo(channelIndex, soloed)
        val address = consoleAddress ?: return
        val sock = socket ?: return
        val port = consolePort
        CoroutineScope(Dispatchers.IO).launch {
            try {
                repeat(3) { attempt ->
                    sendRaw(sock, address, port, packet)
                    if (attempt < 2) delay(20L)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { textStatus.text = "Solo error: ${e.message}" }
            }
        }
    }



    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (appMode == MODE_ENGINEER && channelDetailContainer.visibility == android.view.View.VISIBLE) {
            channelDetailContainer.visibility = android.view.View.GONE
            channelDetailContainer.removeAllViews()
            openDetailChannel = null
            detailViews = null
            detailSendViews = null
            detailEqViews = null
            detailPanSeek = null
            detailPanText = null
            detailPhantomButton = null
            detailPhaseButton = null
            detailCompDynViews = null
            detailHpFilterInButton = null
            detailLpFilterInButton = null
            detailHpFreqSeek = null
            detailLpFreqSeek = null
            detailDelaySeek = null
            detailGainTrimKnobRef = null
            detailGainTrimValueRef = null
            detailSoloBRef = null
            detailLinkButton = null
            detailGateDynViews = null
            return
        }
        if (appMode == MODE_MONITOR && monitorSelectedBus >= 0) {
            monitorSelectedBus = -1
            ConnectionHolder.uiMonitorSelectedBus = -1
            onCreateMonitor()
            return
        }
        super.onBackPressed()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            // Activity реально закрывается (не просто пересоздаётся из-за
            // поворота экрана) - вот тут действительно отключаемся.
            receiveJob?.cancel()
            pollJob?.cancel()
            socket?.close()
            ConnectionHolder.reset()
        }
        // Если это пересоздание из-за смены конфигурации (поворот экрана) -
        // НИЧЕГО не закрываем: сокет, job'ы и подписки остаются жить в
        // ConnectionHolder, новый экземпляр Activity подхватит их в onCreate.
    }
}
