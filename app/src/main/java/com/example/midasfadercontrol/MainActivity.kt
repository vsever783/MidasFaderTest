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

/**
 * Multi-channel mixer-style test screen for the Midas Pro2/PPC protocol.
 *
 * SAFETY: only connect to a console that is NOT live / not in a show.
 * UDP commands here can move real faders on a real console immediately.
 *
 * Protocol notes:
 *   - Transport: raw OSC over UDP. We send FROM local port 10001 TO the
 *     console's port 10000 - the console replies (and pushes state) to
 *     port 10001 specifically, not to whatever ephemeral port sent the
 *     request, so binding to 10001 locally is required to receive anything.
 *   - Channel indices are 0-based (index 0 = physical channel 1). The UI
 *     always shows/accepts the 1-based number people actually see on the
 *     console; conversion happens right before building any packet.
 *   - See Pro2Commands.kt for the specific addresses/types, with notes on
 *     which ones are confirmed vs. best-effort based on the address list.
 */
// === Живая подписка на пульт (см. Pro2Commands.batchSubscribe) ===
enum class ParamKind { FADER, MUTE, SOLO, GAIN, NAME, COLOUR, METER, COMP_RATIO, COMP_ATTACK, COMP_RELEASE, COMP_THRESHOLD, COMP_MAKEUP, COMP_IN, AUX_SEND, EQ_IN, EQ_BAND_ACTIVE, EQ_FREQ, EQ_GAIN, EQ_WIDTH }
data class Subscription(val channel: Int, val kind: ParamKind, val auxBus: Int = 0, val eqBand: Int = 0)

/** Последние известные значения одного канала - переживают поворот экрана (см. ConnectionHolder). */
data class ChannelData(
    var fader: Float = 0f,
    var mutedLocal: Boolean = false,
    var soloed: Boolean = false,
    var gain: Float = 0f,
    var name: String = "",
    var colourArgb: Int? = null,
    var compRatio: Float = 0f,
    var compAttack: Float = 0f,
    var compRelease: Float = 0f,
    var compThreshold: Float = 0f,
    var compMakeup: Float = 0f,
    var compInLocal: Boolean = false,
    // Посылы на 16 aux-шин (индекс 0 = aux 1, ... индекс 15 = aux 16).
    // НЕ подтверждено реальным захватом - см. заметку в Pro2Commands.
    val auxSends: FloatArray = FloatArray(16),
    // EQ (4 полосы: 0=bass, 1=low-mid, 2=mid-high, 3=treble) - подтверждено
    // описаниями в списке команд, но НЕ реальным захватом.
    var eqInLocal: Boolean = false,
    val eqBandActiveLocal: BooleanArray = BooleanArray(4),
    val eqFreq: FloatArray = FloatArray(4),
    val eqGain: FloatArray = FloatArray(4),
    val eqWidth: FloatArray = FloatArray(4)
)

/** Состояние одного мастер-канала - НЕ подтверждено реальным захватом. */
data class MasterData(
    var fader: Float = 0f,
    var mutedLocal: Boolean = false
)

/** Состояние одного aux return - НЕ подтверждено реальным захватом. */
data class AuxReturnData(
    var fader: Float = 0f,
    var mutedLocal: Boolean = false
)

/** Состояние одной aux-шины (собственный уровень шины, не посыл с канала). */
data class AuxBusData(
    var fader: Float = 0f,
    var mutedLocal: Boolean = false
)

/**
 * Хранит сокет, подписки и последние значения каналов ВНЕ жизненного цикла
 * Activity. ВАЖНО: при повороте экрана Android по умолчанию полностью
 * уничтожает и пересоздаёт Activity - без этого объекта UDP-сокет и все
 * подписки на пульт закрывались бы при каждом повороте, и приходилось бы
 * подключаться заново. Simple top-level object переживает столько, сколько
 * жив процесс приложения, независимо от Activity.
 */
object ConnectionHolder {
    var socket: DatagramSocket? = null
    var receiveJob: Job? = null
    var pollJob: Job? = null
    var consoleAddress: InetAddress? = null
    var consolePort: Int = 10000
    var sessionToken: Int? = null
    var subscribedAlready: Boolean = false
    // Уникальный для каждого подключения суффикс хендлов - чтобы старые
    // подписки от прошлых сессий (пульт, похоже, не всегда их сам заменяет,
    // а копит) не могли смешаться с текущими и вызывать хаотичные скачки
    // значений на одноимённых хендлах.
    var sessionId: String = ""
    val subscriptions = mutableMapOf<String, Subscription>()
    // Отдельная карта для мастер-каналов - индексы 0..2 у мастеров и
    // обычных каналов пересекаются, поэтому держим их раздельно, чтобы
    // не перепутать при разборе входящих push-обновлений.
    val masterSubscriptions = mutableMapOf<String, Subscription>()
    // Какие каналы уже подписаны на свои 16 aux-посылов (лениво, по
    // требованию - см. subscribeAuxSends).
    val auxSendsSubscribed = mutableSetOf<Int>()
    // Какие каналы уже подписаны на свой EQ (лениво, по требованию).
    val eqSubscribed = mutableSetOf<Int>()
    val channelData = Array(56) { ChannelData() }
    // По мануалу у Pro2 3 мастер-канала.
    val masterData = Array(3) { MasterData() }
    val auxReturnData = Array(8) { AuxReturnData() }
    val auxBusData = Array(16) { AuxBusData() }
    // Отдельная карта для 16 aux-шин.
    val auxBusSubscriptions = mutableMapOf<String, Subscription>()
    // Отдельная карта для aux returns - индексы 0..7 пересекаются и с
    // обычными каналами, и с VCA в будущем.
    val auxSubscriptions = mutableMapOf<String, Subscription>()

    fun reset() {
        socket = null
        receiveJob = null
        pollJob = null
        consoleAddress = null
        sessionToken = null
        subscribedAlready = false
        subscriptions.clear()
    }
}

class MainActivity : AppCompatActivity() {

    // Реальная конфигурация пульта Pro2 (подтверждено мануалом): 56 входных
    // каналов. Показываем по 8 за раз через переключение банков (см.
    // switchBank), но строим полосы для всех 56 сразу.
    private val numChannels = 56
    private val channelsPerBank = 8

    private lateinit var editHost: EditText
    private lateinit var editPort: EditText
    private lateinit var btnConnect: Button
    private lateinit var textStatus: TextView
    private lateinit var textConnectionStatus: TextView
    private lateinit var containerChannels: android.widget.LinearLayout
    private lateinit var channelDetailContainer: android.widget.FrameLayout

    // Прокси к ConnectionHolder - весь остальной код обращается к этим полям
    // как раньше (socket, receiveJob и т.д.), но реальное хранение теперь
    // живёт вне Activity и переживает поворот экрана.
    private var socket: DatagramSocket?
        get() = ConnectionHolder.socket
        set(value) { ConnectionHolder.socket = value }
    private var receiveJob: Job?
        get() = ConnectionHolder.receiveJob
        set(value) { ConnectionHolder.receiveJob = value }
    private var pollJob: Job?
        get() = ConnectionHolder.pollJob
        set(value) { ConnectionHolder.pollJob = value }
    private var consoleAddress: InetAddress?
        get() = ConnectionHolder.consoleAddress
        set(value) { ConnectionHolder.consoleAddress = value }
    private var consolePort: Int
        get() = ConnectionHolder.consolePort
        set(value) { ConnectionHolder.consolePort = value }
    private var sessionToken: Int?
        get() = ConnectionHolder.sessionToken
        set(value) { ConnectionHolder.sessionToken = value }
    private var subscribedAlready: Boolean
        get() = ConnectionHolder.subscribedAlready
        set(value) { ConnectionHolder.subscribedAlready = value }
    private var sessionId: String
        get() = ConnectionHolder.sessionId
        set(value) { ConnectionHolder.sessionId = value }
    private val subscriptions get() = ConnectionHolder.subscriptions
    private val masterSubscriptions get() = ConnectionHolder.masterSubscriptions
    private val auxSubscriptions get() = ConnectionHolder.auxSubscriptions
    private val auxBusSubscriptions get() = ConnectionHolder.auxBusSubscriptions
    private val auxSendsSubscribed get() = ConnectionHolder.auxSendsSubscribed
    private val eqSubscribed get() = ConnectionHolder.eqSubscribed

    private data class ChannelUi(
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

    private val channels = mutableListOf<ChannelUi>()

    private val minSendIntervalMs = 40L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Убираем системную белую панель заголовка ("Midas Fader Test") -
        // она отнимает заметную полосу по высоте, особенно чувствительно в
        // альбомной ориентации, где вертикального места и так впритык.
        supportActionBar?.hide()

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
            textConnectionStatus.text = "● Подключено"
            textConnectionStatus.setTextColor(Color.parseColor("#34c759"))
            textStatus.text = "Подключение восстановлено после поворота экрана"
            restoreUiFromChannelData()
            startReceiveLoop(existingSocket)
            startPollLoop(existingSocket, existingAddress, consolePort)
        }
    }

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
        switchBank(0)
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
        val fader: SeekBar,
        val levelText: TextView,
        val muteButton: Button,
        val soloButton: ToggleButton,
        val meterBar: android.view.View,
        var mutedLocal: Boolean = false,
        var suppressEvents: Boolean = false,
        var lastFaderSendTime: Long = 0L
    )
    private val masterStrips = mutableListOf<SimpleStripUi>()
    private val auxStrips = mutableListOf<SimpleStripUi>()
    private val auxBusStrips = mutableListOf<SimpleStripUi>()

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
        onFader: (Float) -> Unit,
        onMute: (Boolean) -> Unit,
        onSolo: (Boolean) -> Unit
    ): SimpleStripUi {
        val inflater = LayoutInflater.from(this)
        val strip = inflater.inflate(R.layout.channel_strip, container, false)

        val labelView = strip.findViewById<TextView>(R.id.textChannelLabel)
        val fader = strip.findViewById<SeekBar>(R.id.seekFader)
        val faderContainer = strip.findViewById<android.widget.FrameLayout>(R.id.faderContainer)
        val levelText = strip.findViewById<TextView>(R.id.textLevelValue)
        val muteButton = strip.findViewById<Button>(R.id.btnMute)
        val soloButton = strip.findViewById<ToggleButton>(R.id.btnSolo)
        val meterBar = strip.findViewById<android.view.View>(R.id.meterBar)
        val headerView = strip.findViewById<android.view.View>(R.id.channelHeader)

        labelView.text = label
        headerView.setOnClickListener(null) // нет детального экрана для мастера/aux

        muteButton.backgroundTintList = null
        muteButton.stateListAnimator = null
        soloButton.backgroundTintList = null
        soloButton.stateListAnimator = null
        muteButton.setBackgroundColor(Color.parseColor("#3a3a3c"))
        soloButton.setBackgroundColor(Color.parseColor("#3a3a3c"))

        val ui = SimpleStripUi(strip, fader, levelText, muteButton, soloButton, meterBar)

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
                container, "M ${m + 1}", data.fader, data.mutedLocal,
                onFader = { level -> ConnectionHolder.masterData[m].fader = level; sendMasterFader(m, level) },
                onMute = { muted -> ConnectionHolder.masterData[m].mutedLocal = muted; sendMasterMute(m, muted) },
                onSolo = { soloed -> sendMasterSolo(m, soloed) }
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
                container, "AUX ${a + 1}", data.fader, data.mutedLocal,
                onFader = { level -> ConnectionHolder.auxReturnData[a].fader = level; sendAuxReturnFader(a, level) },
                onMute = { muted -> ConnectionHolder.auxReturnData[a].mutedLocal = muted; sendAuxReturnMute(a, muted) },
                onSolo = { soloed -> sendAuxReturnSolo(a, soloed) }
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
                container, "BUS ${b + 1}", data.fader, data.mutedLocal,
                onFader = { level -> ConnectionHolder.auxBusData[b].fader = level; sendAuxBusFader(b, level) },
                onMute = { muted -> ConnectionHolder.auxBusData[b].mutedLocal = muted; sendAuxBusMute(b, muted) },
                onSolo = { soloed -> sendAuxBusSolo(b, soloed) }
            )
            auxBusStrips.add(ui)
        }
    }

    // Порядок вкладок: КАНАЛЫ, AUX RETURNS, AUX BUSES, MASTER - мастер
    // намеренно в конце, по просьбе (обычно с ним работают реже всего).
    private enum class StripMode { CHANNELS, AUX_RETURNS, AUX_BUS, MASTER }
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

        // Порядок как попросили: каналы, aux returns, aux-шины, мастер - в конце.
        val btnChannels = makeTab("КАНАЛЫ", StripMode.CHANNELS)
        val btnAux = makeTab("AUX RETURNS", StripMode.AUX_RETURNS)
        val btnAuxBus = makeTab("AUX ШИНЫ", StripMode.AUX_BUS)
        val btnMaster = makeTab("MASTER", StripMode.MASTER)
        modeButtons = mapOf(
            StripMode.CHANNELS to btnChannels,
            StripMode.AUX_RETURNS to btnAux,
            StripMode.AUX_BUS to btnAuxBus,
            StripMode.MASTER to btnMaster
        )

        switchStripMode(StripMode.CHANNELS)
    }

    private var modeButtons: Map<StripMode, Button> = emptyMap()

    private fun switchStripMode(mode: StripMode) {
        currentStripMode = mode
        findViewById<android.view.View>(R.id.containerChannels).visibility =
            if (mode == StripMode.CHANNELS) android.view.View.VISIBLE else android.view.View.GONE
        findViewById<android.view.View>(R.id.containerAux).visibility =
            if (mode == StripMode.AUX_RETURNS) android.view.View.VISIBLE else android.view.View.GONE
        findViewById<android.view.View>(R.id.containerAuxBus).visibility =
            if (mode == StripMode.AUX_BUS) android.view.View.VISIBLE else android.view.View.GONE
        findViewById<android.view.View>(R.id.containerMaster).visibility =
            if (mode == StripMode.MASTER) android.view.View.VISIBLE else android.view.View.GONE
        // Банки (1-8, 9-16 и т.д.) относятся только к обычным входным каналам.
        findViewById<android.view.View>(R.id.bankButtonsScroll).visibility =
            if (mode == StripMode.CHANNELS) android.view.View.VISIBLE else android.view.View.GONE

        for ((m, btn) in modeButtons) {
            val active = m == mode
            btn.setBackgroundColor(Color.parseColor(if (active) "#ff9f0a" else "#3a3a3c"))
            btn.setTextColor(Color.parseColor(if (active) "#000000" else "#ffffff"))
        }
    }

    private fun connectAndSync() {
        val host = editHost.text.toString().trim()
        val port = editPort.text.toString().trim().toIntOrNull()
        if (host.isEmpty() || port == null) {
            textStatus.text = "Проверьте IP и порт"
            return
        }

        receiveJob?.cancel()
        pollJob?.cancel()
        socket?.close()

        // Новое подключение - начинаем подписку с нуля.
        sessionToken = null
        subscribedAlready = false
        subscriptions.clear()
        masterSubscriptions.clear()
        auxSubscriptions.clear()
        auxBusSubscriptions.clear()
        auxSendsSubscribed.clear()
        eqSubscribed.clear()
        sessionId = System.currentTimeMillis().toString(36)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val address = InetAddress.getByName(host)
                val newSocket = DatagramSocket(10001) // локальный порт - пульт шлёт ответы именно сюда

                withContext(Dispatchers.Main) {
                    socket = newSocket
                    consoleAddress = address
                    consolePort = port
                    textConnectionStatus.text = "● Подключено"
                    textConnectionStatus.setTextColor(Color.parseColor("#34c759"))
                    textStatus.text = "Подключено к $host:$port, подписываюсь на живые обновления..."
                }

                startReceiveLoop(newSocket)
                requestInitialState(newSocket, address, port)

                // ВАЖНО - ИСПРАВЛЕНИЕ ЗАЦИКЛИВАНИЯ: раньше подписка ждала, пока
                // придёт входящий пакет с "токеном" пульта, чтобы его переиспользовать.
                // Но если это устройство/IP ещё ни на что не подписывалось, пульт
                // может вообще ничего не присылать сам по себе - тогда токен никогда
                // не появится, подписка никогда не отправится, и телефон бесконечно
                // ждёт то, что зависит от его же собственного действия.
                // Решение: подписываемся СРАЗУ с условным токеном-заглушкой (0),
                // не дожидаясь ответа. Если позже придёт настоящий токен от пульта -
                // sessionToken обновится, но переподписываться заново не обязательно.
                subscribedAlready = true
                sessionToken = 0
                subscribeAll()
                startPollLoop(newSocket, address, port)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    textConnectionStatus.text = "● Ошибка подключения"
                    textConnectionStatus.setTextColor(Color.parseColor("#ff3b30"))
                    textStatus.text = "Ошибка подключения: ${e.message}"
                }
            }
        }
    }

    private fun requestInitialState(socket: DatagramSocket, address: InetAddress, port: Int) {
        for (i in 0 until numChannels) {
            sendRaw(socket, address, port, Pro2Commands.getFader(i))
            sendRaw(socket, address, port, Pro2Commands.getMute(i))
            sendRaw(socket, address, port, Pro2Commands.getSolo(i))
            sendRaw(socket, address, port, Pro2Commands.getGain(i))
            sendRaw(socket, address, port, Pro2Commands.getName(i))
        }
    }

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
    private fun startPollLoop(socket: DatagramSocket, address: InetAddress, port: Int) {
        pollJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                delay(3000L)
                try {
                    sendRaw(socket, address, port, Pro2Commands.renew())
                } catch (e: Exception) {
                    if (!isActive) break
                }
            }
        }
    }

    private fun startReceiveLoop(socket: DatagramSocket) {
        receiveJob = CoroutineScope(Dispatchers.IO).launch {
            val buffer = ByteArray(4096)
            socket.soTimeout = 1000
            while (isActive) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    val data = packet.data.copyOfRange(packet.offset, packet.offset + packet.length)

                    // ВАЖНО: пульт почти всё шлёт завёрнутым в OSC-бандлы, а не
                    // простыми сообщениями - используем decodeElement (умеет и то,
                    // и другое), а не старый decode().
                    val element = OscUtil.decodeElement(data) ?: continue
                    val messages = OscUtil.flatten(element)

                    withContext(Dispatchers.Main) {
                        for (msg in messages) handleIncomingMessage(msg)
                    }
                } catch (e: SocketTimeoutException) {
                    // норма - просто нет данных за секунду, продолжаем ждать
                } catch (e: Exception) {
                    if (!isActive) break
                    withContext(Dispatchers.Main) { textStatus.text = "Ошибка приёма: ${e.message}" }
                }
            }
        }
    }

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
    private fun handleIncomingMessage(message: OscElement.Message) {
        // Учимся реальному токену пульта из ЛЮБОГО входящего ",bi"-сообщения.
        // Подписка (subscribeAll) уже отправлена сразу при подключении с токеном-
        // заглушкой (0), чтобы не зависеть от того, придёт ли что-то от пульта
        // само по себе - здесь просто держим sessionToken в актуальном состоянии
        // на будущее (например, если понадобится переподписаться).
        if (message.typeTag == ",bi" && message.args.size == 2) {
            val token = message.args[1] as? Int
            if (token != null) {
                sessionToken = token
                if (!subscribedAlready) {
                    subscribedAlready = true
                    subscribeAll()
                }
            }
        }

        // Путь 1: это ответ на нашу подписку?
        val subscription = subscriptions[message.address]
        if (subscription != null && message.typeTag == ",bi" && message.args.isNotEmpty()) {
            val blob = message.args[0] as? ByteArray ?: return
            handleSubscribedValue(subscription, blob)
            return
        }
        val masterSub = masterSubscriptions[message.address]
        if (masterSub != null && message.typeTag == ",bi" && message.args.isNotEmpty()) {
            val blob = message.args[0] as? ByteArray ?: return
            handleMasterSubscribedValue(masterSub, blob)
            return
        }
        val auxSub = auxSubscriptions[message.address]
        if (auxSub != null && message.typeTag == ",bi" && message.args.isNotEmpty()) {
            val blob = message.args[0] as? ByteArray ?: return
            handleAuxReturnSubscribedValue(auxSub, blob)
            return
        }
        val auxBusSub = auxBusSubscriptions[message.address]
        if (auxBusSub != null && message.typeTag == ",bi" && message.args.isNotEmpty()) {
            val blob = message.args[0] as? ByteArray ?: return
            handleAuxBusSubscribedValue(auxBusSub, blob)
            return
        }

        // Путь 2: человекочитаемый адрес (см. примечание выше - по факту не срабатывает,
        // оставлено на будущее / на случай другой прошивки пульта).
        val args = message.args
        when (message.address) {
            Pro2Commands.faderAddress() -> {
                val channel = (args.getOrNull(0) as? Int) ?: return
                val level = (args.getOrNull(1) as? Float) ?: return
                updateFaderUi(channel, level)
            }
            Pro2Commands.muteAddress() -> {
                val channel = (args.getOrNull(0) as? Int) ?: return
                val muted = (args.getOrNull(1) as? Int) ?: return
                updateMuteUi(channel, muted != 0)
            }
            Pro2Commands.soloAddress() -> {
                val channel = (args.getOrNull(0) as? Int) ?: return
                val soloed = (args.getOrNull(1) as? Int) ?: return
                updateSoloUi(channel, soloed != 0)
            }
            Pro2Commands.gainAddress() -> {
                val channel = (args.getOrNull(0) as? Int) ?: return
                val level = (args.getOrNull(1) as? Float) ?: return
                updateGainUi(channel, level)
            }
            Pro2Commands.nameAddress() -> {
                val channel = (args.getOrNull(0) as? Int) ?: return
                val name = (args.getOrNull(1) as? String) ?: return
                updateNameUi(channel, name)
            }
            else -> { /* прочие сообщения (например, метры) пока не обрабатываем */ }
        }
    }

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
    private fun subscribeAll() {
        val sock = socket ?: return
        val address = consoleAddress ?: return
        val port = consolePort
        val token = sessionToken ?: return

        CoroutineScope(Dispatchers.IO).launch {
            val sid = sessionId
            for (i in 0 until numChannels) {
                val subs = listOf(
                    "/h_${sid}_${i}_fader" to Triple(Pro2Commands.faderAddress(), ParamKind.FADER, i),
                    "/h_${sid}_${i}_mute" to Triple(Pro2Commands.muteAddress(), ParamKind.MUTE, i),
                    "/h_${sid}_${i}_solo" to Triple(Pro2Commands.soloAddress(), ParamKind.SOLO, i),
                    "/h_${sid}_${i}_gain" to Triple(Pro2Commands.gainAddress(), ParamKind.GAIN, i),
                    "/h_${sid}_${i}_name" to Triple(Pro2Commands.nameAddress(), ParamKind.NAME, i),
                    "/h_${sid}_${i}_colour" to Triple(Pro2Commands.colourAddress(), ParamKind.COLOUR, i),
                    "/h_${sid}_${i}_meter" to Triple(Pro2Commands.meterAddress(), ParamKind.METER, i),
                    "/h_${sid}_${i}_compratio" to Triple(Pro2Commands.compRatioAddress(), ParamKind.COMP_RATIO, i),
                    "/h_${sid}_${i}_compattack" to Triple(Pro2Commands.compAttackAddress(), ParamKind.COMP_ATTACK, i),
                    "/h_${sid}_${i}_comprelease" to Triple(Pro2Commands.compReleaseAddress(), ParamKind.COMP_RELEASE, i),
                    "/h_${sid}_${i}_compthreshold" to Triple(Pro2Commands.compThresholdAddress(), ParamKind.COMP_THRESHOLD, i),
                    "/h_${sid}_${i}_compmakeup" to Triple(Pro2Commands.compMakeupGainAddress(), ParamKind.COMP_MAKEUP, i),
                    "/h_${sid}_${i}_compin" to Triple(Pro2Commands.compInAddress(), ParamKind.COMP_IN, i),
                )
                for ((handle, info) in subs) {
                    val (path, kind, channel) = info
                    withContext(Dispatchers.Main) { subscriptions[handle] = Subscription(channel, kind) }
                    try {
                        sendRaw(sock, address, port, Pro2Commands.batchSubscribe(handle, path, channel, channel, token))
                    } catch (e: Exception) {
                        // подписка на один параметр не удалась - не прерываем остальные
                    }
                }
            }

            // Мастер-каналы (3 шт. по мануалу) - НЕ подтверждено реальным
            // захватом, но объём небольшой (12 подписок), поэтому
            // подписываемся сразу вместе со всем остальным.
            for (m in 0 until 3) {
                val masterSubs = listOf(
                    "/h_${sid}_m${m}_fader" to Triple(Pro2Commands.masterFaderAddress(), ParamKind.FADER, m),
                    "/h_${sid}_m${m}_mute" to Triple(Pro2Commands.masterMuteAddress(), ParamKind.MUTE, m),
                    "/h_${sid}_m${m}_solo" to Triple(Pro2Commands.masterSoloAddress(), ParamKind.SOLO, m),
                    "/h_${sid}_m${m}_meter" to Triple(Pro2Commands.masterMeterAddress(), ParamKind.METER, m),
                )
                for ((handle, info) in masterSubs) {
                    val (path, kind, mIdx) = info
                    withContext(Dispatchers.Main) { masterSubscriptions[handle] = Subscription(mIdx, kind) }
                    try {
                        sendRaw(sock, address, port, Pro2Commands.batchSubscribe(handle, path, mIdx, mIdx, token))
                    } catch (e: Exception) {
                        // не критично - не прерываем остальное
                    }
                }
            }

            // Aux Returns (8 шт. по мануалу) - НЕ подтверждено реальным
            // захватом. Объём небольшой (32 подписки), подписываемся сразу.
            for (a in 0 until 8) {
                val auxSubs = listOf(
                    "/h_${sid}_a${a}_fader" to Triple(Pro2Commands.auxReturnFaderAddress(), ParamKind.FADER, a),
                    "/h_${sid}_a${a}_mute" to Triple(Pro2Commands.auxReturnMuteAddress(), ParamKind.MUTE, a),
                    "/h_${sid}_a${a}_solo" to Triple(Pro2Commands.auxReturnSoloAddress(), ParamKind.SOLO, a),
                    "/h_${sid}_a${a}_meter" to Triple(Pro2Commands.auxReturnMeterAddress(), ParamKind.METER, a),
                )
                for ((handle, info) in auxSubs) {
                    val (path, kind, aIdx) = info
                    withContext(Dispatchers.Main) { auxSubscriptions[handle] = Subscription(aIdx, kind) }
                    try {
                        sendRaw(sock, address, port, Pro2Commands.batchSubscribe(handle, path, aIdx, aIdx, token))
                    } catch (e: Exception) {
                        // не критично - не прерываем остальное
                    }
                }
            }

            // 16 aux-шин - НЕ подтверждено реальным захватом. Объём умеренный
            // (64 подписки), но всё ещё небольшой на фоне 56 каналов, так что
            // подписываемся сразу, без ленивой подписки.
            for (b in 0 until 16) {
                val busSubs = listOf(
                    "/h_${sid}_b${b}_fader" to Triple(Pro2Commands.auxBusFaderAddress(), ParamKind.FADER, b),
                    "/h_${sid}_b${b}_mute" to Triple(Pro2Commands.auxBusMuteAddress(), ParamKind.MUTE, b),
                    "/h_${sid}_b${b}_solo" to Triple(Pro2Commands.auxBusSoloAddress(), ParamKind.SOLO, b),
                    "/h_${sid}_b${b}_meter" to Triple(Pro2Commands.auxBusMeterAddress(), ParamKind.METER, b),
                )
                for ((handle, info) in busSubs) {
                    val (path, kind, bIdx) = info
                    withContext(Dispatchers.Main) { auxBusSubscriptions[handle] = Subscription(bIdx, kind) }
                    try {
                        sendRaw(sock, address, port, Pro2Commands.batchSubscribe(handle, path, bIdx, bIdx, token))
                    } catch (e: Exception) {
                        // не критично - не прерываем остальное
                    }
                }
            }

            withContext(Dispatchers.Main) {
                textStatus.text = "Подписался на живые обновления всех каналов"
            }
        }
    }

    /**
     * Подписывается на 16 aux-посылов ОДНОГО канала. Не делается сразу для
     * всех 56 каналов при подключении - это ещё ~900 подписок сверху, а
     * посылы реально нужны только когда открыта вкладка SENDS конкретного
     * канала. Вызывается один раз при первом открытии этой вкладки.
     */
    private fun subscribeAuxSends(channel: Int) {
        val sock = socket ?: return
        val address = consoleAddress ?: return
        val port = consolePort
        val token = sessionToken ?: return
        if (auxSendsSubscribed.contains(channel)) return
        auxSendsSubscribed.add(channel)

        CoroutineScope(Dispatchers.IO).launch {
            val sid = sessionId
            for (bus in 1..16) {
                val handle = "/h_${sid}_${channel}_send$bus"
                withContext(Dispatchers.Main) { subscriptions[handle] = Subscription(channel, ParamKind.AUX_SEND, bus) }
                try {
                    sendRaw(sock, address, port, Pro2Commands.batchSubscribe(handle, Pro2Commands.subSendLevelAddress(bus), channel, channel, token))
                } catch (e: Exception) {
                    // не критично
                }
            }
        }
    }

    /**
     * Подписывается на EQ ОДНОГО канала (17 параметров: общий IN + 4 полосы
     * × (активность + частота + гейн + ширина)). Лениво, только при первом
     * открытии вкладки EQ - как и с посылами.
     */
    private fun subscribeEq(channel: Int) {
        val sock = socket ?: return
        val address = consoleAddress ?: return
        val port = consolePort
        val token = sessionToken ?: return
        if (eqSubscribed.contains(channel)) return
        eqSubscribed.add(channel)

        CoroutineScope(Dispatchers.IO).launch {
            val sid = sessionId
            val subs = mutableListOf<Triple<String, String, Subscription>>()
            subs.add(Triple("/h_${sid}_${channel}_eqin", Pro2Commands.eqInAddress(), Subscription(channel, ParamKind.EQ_IN)))
            val bands = arrayOf(Pro2Commands.EqBand.BASS, Pro2Commands.EqBand.LOW_MID, Pro2Commands.EqBand.MID_HIGH, Pro2Commands.EqBand.TREBLE)
            for ((bandIndex, band) in bands.withIndex()) {
                subs.add(Triple("/h_${sid}_${channel}_eqact$bandIndex", Pro2Commands.eqBandActiveAddress(band), Subscription(channel, ParamKind.EQ_BAND_ACTIVE, eqBand = bandIndex)))
                subs.add(Triple("/h_${sid}_${channel}_eqfreq$bandIndex", Pro2Commands.eqFreqAddress(band), Subscription(channel, ParamKind.EQ_FREQ, eqBand = bandIndex)))
                subs.add(Triple("/h_${sid}_${channel}_eqgain$bandIndex", Pro2Commands.eqGainAddress(band), Subscription(channel, ParamKind.EQ_GAIN, eqBand = bandIndex)))
                subs.add(Triple("/h_${sid}_${channel}_eqwidth$bandIndex", Pro2Commands.eqWidthAddress(band), Subscription(channel, ParamKind.EQ_WIDTH, eqBand = bandIndex)))
            }
            for ((handle, path, sub) in subs) {
                withContext(Dispatchers.Main) { subscriptions[handle] = sub }
                try {
                    sendRaw(sock, address, port, Pro2Commands.batchSubscribe(handle, path, channel, channel, token))
                } catch (e: Exception) {
                    // не критично
                }
            }
        }
    }

    /** Разбор blob-значения из push-обновления по подписке. См. примечание к subscribeAll(). */
    private fun handleSubscribedValue(sub: Subscription, blob: ByteArray) {
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
            ParamKind.FADER, ParamKind.GAIN -> {
                // Подтверждено: 4 байта, little-endian float32, диапазон 0..1.
                if (blob.size < 4) return
                val level = ByteBuffer.wrap(blob).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    .float.coerceIn(0f, 1f)
                if (sub.kind == ParamKind.FADER) updateFaderUi(sub.channel, level)
                else updateGainUi(sub.channel, level)
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
        }
    }

    /** Разбор push-обновления для мастер-каналов - НЕ подтверждено реальным захватом. */
    private fun handleMasterSubscribedValue(sub: Subscription, blob: ByteArray) {
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
            ParamKind.METER -> {
                if (blob.isEmpty()) return
                val level = ((blob[0].toInt() and 0xFF) / 255f).coerceIn(0f, 1f)
                updateSimpleStripMeter(masterStrips, sub.channel, level)
            }
            else -> {}
        }
    }

    /** Разбор push-обновления для aux returns - НЕ подтверждено реальным захватом. */
    private fun handleAuxReturnSubscribedValue(sub: Subscription, blob: ByteArray) {
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
            ParamKind.METER -> {
                if (blob.isEmpty()) return
                val level = ((blob[0].toInt() and 0xFF) / 255f).coerceIn(0f, 1f)
                updateSimpleStripMeter(auxStrips, sub.channel, level)
            }
            else -> {}
        }
    }

    /** Разбор push-обновления для 16 aux-шин - НЕ подтверждено реальным захватом. */
    private fun handleAuxBusSubscribedValue(sub: Subscription, blob: ByteArray) {
        when (sub.kind) {
            ParamKind.FADER -> {
                if (blob.size < 4) return
                val level = ByteBuffer.wrap(blob).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    .float.coerceIn(0f, 1f)
                ConnectionHolder.auxBusData[sub.channel].fader = level
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
            ParamKind.METER -> {
                if (blob.isEmpty()) return
                val level = ((blob[0].toInt() and 0xFF) / 255f).coerceIn(0f, 1f)
                updateSimpleStripMeter(auxBusStrips, sub.channel, level)
            }
            else -> {}
        }
    }

    /** Общая функция подсветки метра для мастера/aux returns/aux-шин (все используют SimpleStripUi). */
    private fun updateSimpleStripMeter(list: List<SimpleStripUi>, index: Int, level: Float) {
        val ui = list.getOrNull(index) ?: return
        val parent = ui.meterBar.parent as? android.view.View ?: return
        val totalHeight = parent.height
        if (totalHeight <= 0) return
        val params = ui.meterBar.layoutParams
        params.height = (totalHeight * level).toInt().coerceAtLeast(0)
        ui.meterBar.layoutParams = params
        val color = when {
            level > 0.85f -> "#ff3b30"
            level > 0.6f -> "#ff9f0a"
            else -> "#34c759"
        }
        ui.meterBar.setBackgroundColor(Color.parseColor(color))
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
    private fun updateMeterUi(channel: Int, level: Float) {
        val ui = channels.getOrNull(channel) ?: return
        val parent = ui.meterBar.parent as? android.view.View ?: return
        val totalHeight = parent.height
        if (totalHeight <= 0) return

        val params = ui.meterBar.layoutParams
        params.height = (totalHeight * level).toInt().coerceAtLeast(0)
        ui.meterBar.layoutParams = params

        val color = when {
            level > 0.85f -> "#ff3b30" // красный - близко к перегрузке
            level > 0.6f -> "#ff9f0a"  // жёлтый/оранжевый
            else -> "#34c759"          // зелёный - нормальный уровень
        }
        ui.meterBar.setBackgroundColor(Color.parseColor(color))

        // Если детальный экран для этого канала открыт - синхронизируем и его метр.
        if (openDetailChannel == channel) {
            val dv = detailViews ?: return
            val dParent = dv.meterBar.parent as? android.view.View ?: return
            val dHeight = dParent.height
            if (dHeight > 0) {
                val dParams = dv.meterBar.layoutParams
                dParams.height = (dHeight * level).toInt().coerceAtLeast(0)
                dv.meterBar.layoutParams = dParams
                dv.meterBar.setBackgroundColor(Color.parseColor(color))
            }
        }
    }

    // Если сейчас открыт детальный экран для какого-то канала - храним ссылку
    // на его элементы, чтобы push-обновления могли их вживую подкручивать,
    // пока экран открыт (то же самое, что раньше делал модальный диалог, но
    // теперь fader/mute/solo/метр остаются видны одновременно с компрессором,
    // как в Mixing Station).
    private var openDetailChannel: Int? = null
    private var detailViews: ChannelDetailViews? = null
    // Ссылки на 16 пар (ползунок, текст) вкладки SENDS детального экрана,
    // пока он открыт - для живого обновления от push.
    private var detailSendViews: Array<Pair<SeekBar, TextView>>? = null

    /** Виджеты одной полосы EQ, пока детальный экран открыт - для живого обновления. */
    private data class EqBandViews(
        val activeButton: Button,
        val freqSeek: SeekBar, val freqText: TextView,
        val gainSeek: SeekBar, val gainText: TextView,
        val widthSeek: SeekBar, val widthText: TextView
    )
    private data class EqBlockViews(val inButton: Button, val bands: Array<EqBandViews>)
    private var detailEqViews: EqBlockViews? = null

    private data class CompSliderViews(
        val ratio: SeekBar, val ratioText: TextView,
        val attack: SeekBar, val attackText: TextView,
        val release: SeekBar, val releaseText: TextView,
        val threshold: SeekBar, val thresholdText: TextView,
        val makeup: SeekBar, val makeupText: TextView
    )

    private data class ChannelDetailViews(
        val muteButton: Button,
        val soloButton: ToggleButton,
        val compInButton: Button,
        val fader: SeekBar,
        val meterBar: android.view.View,
        val levelText: TextView,
        val comp: CompSliderViews,
        val gainKnob: RotaryKnobView,
        val gainValueText: TextView
    )

    private fun updateCompParamUi(channel: Int, kind: ParamKind, level: Float) {
        val ui = channels.getOrNull(channel) ?: return
        val data = ConnectionHolder.channelData[channel]
        when (kind) {
            ParamKind.COMP_RATIO -> { ui.compRatio = level; data.compRatio = level }
            ParamKind.COMP_ATTACK -> { ui.compAttack = level; data.compAttack = level }
            ParamKind.COMP_RELEASE -> { ui.compRelease = level; data.compRelease = level }
            ParamKind.COMP_THRESHOLD -> { ui.compThreshold = level; data.compThreshold = level }
            ParamKind.COMP_MAKEUP -> { ui.compMakeup = level; data.compMakeup = level }
            else -> return
        }
        // Если детальный экран для этого канала сейчас открыт - подкручиваем
        // ползунок вживую, чтобы он отражал изменения, сделанные прямо на пульте.
        if (openDetailChannel == channel) {
            val comp = detailViews?.comp ?: return
            val (seek, text) = when (kind) {
                ParamKind.COMP_RATIO -> comp.ratio to comp.ratioText
                ParamKind.COMP_ATTACK -> comp.attack to comp.attackText
                ParamKind.COMP_RELEASE -> comp.release to comp.releaseText
                ParamKind.COMP_THRESHOLD -> comp.threshold to comp.thresholdText
                ParamKind.COMP_MAKEUP -> comp.makeup to comp.makeupText
                else -> return
            }
            seek.progress = (level * 1000).toInt()
            text.text = "%.2f".format(level)
        }
    }

    private fun updateCompInUi(channel: Int, on: Boolean) {
        ConnectionHolder.channelData[channel].compInLocal = on
        val ui = channels.getOrNull(channel) ?: return
        ui.compInLocal = on
        if (openDetailChannel == channel) {
            val btn = detailViews?.compInButton ?: return
            btn.text = if (on) "COMP ВКЛ" else "COMP ВЫКЛ"
            btn.setBackgroundColor(Color.parseColor(if (on) "#ff9f0a" else "#3a3a3c"))
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
    }

    private fun updateEqInUi(channel: Int, on: Boolean) {
        ConnectionHolder.channelData[channel].eqInLocal = on
        if (openDetailChannel == channel) {
            val btn = detailEqViews?.inButton ?: return
            btn.text = if (on) "EQ ВКЛ" else "EQ ВЫКЛ"
            btn.setBackgroundColor(Color.parseColor(if (on) "#ff9f0a" else "#3a3a3c"))
        }
    }

    private fun updateEqBandActiveUi(channel: Int, bandIndex: Int, on: Boolean) {
        if (bandIndex !in 0..3) return
        ConnectionHolder.channelData[channel].eqBandActiveLocal[bandIndex] = on
        if (openDetailChannel == channel) {
            val btn = detailEqViews?.bands?.getOrNull(bandIndex)?.activeButton ?: return
            btn.text = if (on) "ВКЛ" else "ВЫКЛ"
            btn.setBackgroundColor(Color.parseColor(if (on) "#ff9f0a" else "#3a3a3c"))
        }
    }

    private fun updateEqParamUi(channel: Int, bandIndex: Int, kind: ParamKind, level: Float) {
        if (bandIndex !in 0..3) return
        persistEq(channel, bandIndex, kind, level)
        if (openDetailChannel != channel) return
        val band = detailEqViews?.bands?.getOrNull(bandIndex) ?: return
        val (seek, text) = when (kind) {
            ParamKind.EQ_FREQ -> band.freqSeek to band.freqText
            ParamKind.EQ_GAIN -> band.gainSeek to band.gainText
            ParamKind.EQ_WIDTH -> band.widthSeek to band.widthText
            else -> return
        }
        seek.progress = (level * 1000).toInt()
        text.text = "%.2f".format(level)
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
        }

        // --- Постоянная панель mute/solo/фейдер/метр ---
        val detailMute = view.findViewById<Button>(R.id.detailMute)
        val detailSolo = view.findViewById<ToggleButton>(R.id.detailSolo)
        val detailFader = view.findViewById<SeekBar>(R.id.detailFader)
        val detailFaderContainer = view.findViewById<android.widget.FrameLayout>(R.id.detailFaderContainer)
        val detailMeterBar = view.findViewById<android.view.View>(R.id.detailMeterBar)
        val detailLevelText = view.findViewById<TextView>(R.id.textDetailLevelValue)
        val detailCompIn = view.findViewById<Button>(R.id.btnDetailCompIn)

        detailMute.backgroundTintList = null
        detailMute.stateListAnimator = null
        detailCompIn.backgroundTintList = null
        detailCompIn.stateListAnimator = null
        detailCompIn.text = if (ui.compInLocal) "COMP ВКЛ" else "COMP ВЫКЛ"
        detailCompIn.setBackgroundColor(Color.parseColor(if (ui.compInLocal) "#ff9f0a" else "#3a3a3c"))
        detailCompIn.setOnClickListener {
            updateCompInUi(channel, !ui.compInLocal)
            sendCompIn(channel)
        }
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

        // --- Блок COMP ---
        val ratio = view.findViewById<SeekBar>(R.id.seekCompRatio)
        val ratioText = view.findViewById<TextView>(R.id.textCompRatioValue)
        val attack = view.findViewById<SeekBar>(R.id.seekCompAttack)
        val attackText = view.findViewById<TextView>(R.id.textCompAttackValue)
        val release = view.findViewById<SeekBar>(R.id.seekCompRelease)
        val releaseText = view.findViewById<TextView>(R.id.textCompReleaseValue)
        val threshold = view.findViewById<SeekBar>(R.id.seekCompThreshold)
        val thresholdText = view.findViewById<TextView>(R.id.textCompThresholdValue)
        val makeup = view.findViewById<SeekBar>(R.id.seekCompMakeup)
        val makeupText = view.findViewById<TextView>(R.id.textCompMakeupValue)

        ratio.progress = (ui.compRatio * 1000).toInt()
        ratioText.text = "%.2f".format(ui.compRatio)
        attack.progress = (ui.compAttack * 1000).toInt()
        attackText.text = "%.2f".format(ui.compAttack)
        release.progress = (ui.compRelease * 1000).toInt()
        releaseText.text = "%.2f".format(ui.compRelease)
        threshold.progress = (ui.compThreshold * 1000).toInt()
        thresholdText.text = "%.2f".format(ui.compThreshold)
        makeup.progress = (ui.compMakeup * 1000).toInt()
        makeupText.text = "%.2f".format(ui.compMakeup)

        openDetailChannel = channel
        val detailGainKnob = view.findViewById<RotaryKnobView>(R.id.knobDetailGain)
        val detailGainValue = view.findViewById<TextView>(R.id.textDetailGainValue)

        detailViews = ChannelDetailViews(
            detailMute, detailSolo, detailCompIn, detailFader, detailMeterBar, detailLevelText,
            CompSliderViews(
                ratio, ratioText, attack, attackText, release, releaseText,
                threshold, thresholdText, makeup, makeupText
            ),
            detailGainKnob, detailGainValue
        )

        fun persistComp(kind: ParamKind, level: Float) {
            val data = ConnectionHolder.channelData[channel]
            when (kind) {
                ParamKind.COMP_RATIO -> data.compRatio = level
                ParamKind.COMP_ATTACK -> data.compAttack = level
                ParamKind.COMP_RELEASE -> data.compRelease = level
                ParamKind.COMP_THRESHOLD -> data.compThreshold = level
                ParamKind.COMP_MAKEUP -> data.compMakeup = level
                else -> {}
            }
        }
        fun wire(seek: SeekBar, text: TextView, kind: ParamKind) {
            var lastSend = 0L
            seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    val level = progress / 1000f
                    text.text = "%.2f".format(level)
                    if (!fromUser) return
                    persistComp(kind, level)
                    val now = System.currentTimeMillis()
                    if (now - lastSend >= minSendIntervalMs) {
                        lastSend = now
                        sendCompParam(channel, level, kind)
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    val level = (sb?.progress ?: 0) / 1000f
                    persistComp(kind, level)
                    sendCompParam(channel, level, kind)
                }
            })
        }
        wire(ratio, ratioText, ParamKind.COMP_RATIO)
        wire(attack, attackText, ParamKind.COMP_ATTACK)
        wire(release, releaseText, ParamKind.COMP_RELEASE)
        wire(threshold, thresholdText, ParamKind.COMP_THRESHOLD)
        wire(makeup, makeupText, ParamKind.COMP_MAKEUP)

        // --- Вкладки INPUT / COMP / SENDS ---
        val tabInput = view.findViewById<Button>(R.id.btnTabInput)
        val tabComp = view.findViewById<Button>(R.id.btnTabComp)
        val tabSends = view.findViewById<Button>(R.id.btnTabSends)
        val tabEq = view.findViewById<Button>(R.id.btnTabEq)
        val inputBlock = view.findViewById<android.widget.LinearLayout>(R.id.inputBlockContent)
        val compBlock = view.findViewById<android.widget.LinearLayout>(R.id.compBlockContent)
        val sendsBlock = view.findViewById<android.widget.LinearLayout>(R.id.sendsBlockContent)
        val eqBlock = view.findViewById<android.widget.LinearLayout>(R.id.eqBlockContent)

        fun selectTab(active: Button) {
            for (tab in listOf(tabInput, tabComp, tabSends, tabEq)) {
                val isActive = tab === active
                tab.backgroundTintList = android.content.res.ColorStateList.valueOf(
                    Color.parseColor(if (isActive) "#ff9f0a" else "#3a3a3c")
                )
                tab.setTextColor(Color.parseColor(if (isActive) "#000000" else "#ffffff"))
            }
        }
        fun hideAllBlocks() {
            inputBlock.visibility = android.view.View.GONE
            compBlock.visibility = android.view.View.GONE
            sendsBlock.visibility = android.view.View.GONE
            eqBlock.visibility = android.view.View.GONE
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
            sendsBlock.visibility = android.view.View.VISIBLE
            selectTab(tabSends)
        }
        fun showEq() {
            hideAllBlocks()
            eqBlock.visibility = android.view.View.VISIBLE
            selectTab(tabEq)
        }
        tabInput.setOnClickListener { showInput() }
        tabComp.setOnClickListener { showComp() }
        tabSends.setOnClickListener {
            showSends()
            // Подписываемся на 16 aux-посылов этого канала только сейчас,
            // при первом реальном открытии вкладки (см. subscribeAuxSends).
            subscribeAuxSends(channel)
        }
        tabEq.setOnClickListener {
            showEq()
            // Подписываемся на EQ этого канала только сейчас (17 параметров
            // на канал - как и с посылами, не хотим подписывать все 56
            // каналов сразу при подключении).
            subscribeEq(channel)
        }
        showInput()

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

        // --- Вкладка SENDS: 16 посылов, строится программно ---
        // НЕ подтверждено реальным захватом трафика - см. заметку в
        // Pro2Commands.kt про enSubSendLevel1..16.
        sendsBlock.removeAllViews()
        val sendViews = arrayOfNulls<Pair<SeekBar, TextView>>(16)
        val data = ConnectionHolder.channelData[channel]
        for (bus in 1..16) {
            val row = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(0, 0, 0, 24)
            }
            val label = TextView(this).apply {
                text = "AUX $bus"
                setTextColor(Color.parseColor("#ff9f0a"))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                textSize = 12f
            }
            val seek = SeekBar(this).apply {
                max = 1000
                progress = (data.auxSends[bus - 1] * 1000).toInt()
            }
            val valueText = TextView(this).apply {
                text = "%.2f".format(data.auxSends[bus - 1])
                setTextColor(Color.parseColor("#aaaaaa"))
                textSize = 12f
            }
            var lastSend = 0L
            seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    val level = progress / 1000f
                    valueText.text = "%.2f".format(level)
                    if (!fromUser) return
                    data.auxSends[bus - 1] = level
                    val now = System.currentTimeMillis()
                    if (now - lastSend >= minSendIntervalMs) {
                        lastSend = now
                        sendSubSend(channel, bus, level)
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    val level = (sb?.progress ?: 0) / 1000f
                    data.auxSends[bus - 1] = level
                    sendSubSend(channel, bus, level)
                }
            })
            row.addView(label)
            row.addView(seek)
            row.addView(valueText)
            sendsBlock.addView(row)
            sendViews[bus - 1] = seek to valueText
        }
        @Suppress("UNCHECKED_CAST")
        detailSendViews = sendViews as Array<Pair<SeekBar, TextView>>
    }

    /**
     * Строит содержимое вкладки EQ для одного канала: общий переключатель
     * IN/OUT сверху, затем 4 полосы (бас/сред-низ/сред-выс/треб), у каждой -
     * своя кнопка активности + 3 ползунка (частота/гейн/ширина).
     * Подтверждено ОПИСАНИЯМИ в списке команд, НЕ подтверждено реальным
     * захватом трафика.
     */
    private fun buildEqBlock(container: android.widget.LinearLayout, channel: Int) {
        val data = ConnectionHolder.channelData[channel]
        val bandNames = arrayOf("BASS", "LOW-MID", "MID-HIGH", "TREBLE")
        val bandKinds = arrayOf(Pro2Commands.EqBand.BASS, Pro2Commands.EqBand.LOW_MID, Pro2Commands.EqBand.MID_HIGH, Pro2Commands.EqBand.TREBLE)

        val eqInButton = Button(this).apply {
            text = if (data.eqInLocal) "EQ ВКЛ" else "EQ ВЫКЛ"
            setTextColor(Color.parseColor("#ffffff"))
            setBackgroundColor(Color.parseColor(if (data.eqInLocal) "#ff9f0a" else "#3a3a3c"))
            setOnClickListener {
                val newState = !ConnectionHolder.channelData[channel].eqInLocal
                ConnectionHolder.channelData[channel].eqInLocal = newState
                text = if (newState) "EQ ВКЛ" else "EQ ВЫКЛ"
                setBackgroundColor(Color.parseColor(if (newState) "#ff9f0a" else "#3a3a3c"))
                sendEqIn(channel)
            }
        }
        container.addView(eqInButton, android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = 24 })

        val bandViews = Array(4) { bandIndex ->
            val band = bandKinds[bandIndex]

            val header = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                setPadding(0, if (bandIndex == 0) 0 else 24, 0, 8)
            }
            val bandLabel = TextView(this).apply {
                text = bandNames[bandIndex]
                setTextColor(Color.parseColor("#ff9f0a"))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                textSize = 13f
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
            }
            val activeButton = Button(this).apply {
                text = if (data.eqBandActiveLocal[bandIndex]) "ВКЛ" else "ВЫКЛ"
                textSize = 10f
                minHeight = 0
                minimumHeight = 0
                setPadding(16, 4, 16, 4)
                setTextColor(Color.parseColor("#ffffff"))
                setBackgroundColor(Color.parseColor(if (data.eqBandActiveLocal[bandIndex]) "#ff9f0a" else "#3a3a3c"))
                setOnClickListener {
                    val newState = !ConnectionHolder.channelData[channel].eqBandActiveLocal[bandIndex]
                    ConnectionHolder.channelData[channel].eqBandActiveLocal[bandIndex] = newState
                    text = if (newState) "ВКЛ" else "ВЫКЛ"
                    setBackgroundColor(Color.parseColor(if (newState) "#ff9f0a" else "#3a3a3c"))
                    sendEqBandActive(channel, band)
                }
            }
            header.addView(bandLabel)
            header.addView(activeButton)
            container.addView(header)

            fun makeRow(label: String, initial: Float, kind: ParamKind): Pair<SeekBar, TextView> {
                val rowLabel = TextView(this).apply {
                    text = label
                    setTextColor(Color.parseColor("#8e8e93"))
                    textSize = 10f
                }
                val seek = SeekBar(this).apply { max = 1000; progress = (initial * 1000).toInt() }
                val valueText = TextView(this).apply {
                    text = "%.2f".format(initial)
                    setTextColor(Color.parseColor("#aaaaaa"))
                    textSize = 11f
                }
                var lastSend = 0L
                seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                        val level = progress / 1000f
                        valueText.text = "%.2f".format(level)
                        if (!fromUser) return
                        persistEq(channel, bandIndex, kind, level)
                        val now = System.currentTimeMillis()
                        if (now - lastSend >= minSendIntervalMs) {
                            lastSend = now
                            sendEqParam(channel, band, kind, level)
                        }
                    }
                    override fun onStartTrackingTouch(sb: SeekBar?) {}
                    override fun onStopTrackingTouch(sb: SeekBar?) {
                        val level = (sb?.progress ?: 0) / 1000f
                        persistEq(channel, bandIndex, kind, level)
                        sendEqParam(channel, band, kind, level)
                    }
                })
                container.addView(rowLabel)
                container.addView(seek)
                container.addView(valueText)
                return seek to valueText
            }

            val (freqSeek, freqText) = makeRow("ЧАСТОТА", data.eqFreq[bandIndex], ParamKind.EQ_FREQ)
            val (gainSeek, gainText) = makeRow("ГЕЙН", data.eqGain[bandIndex], ParamKind.EQ_GAIN)
            val (widthSeek, widthText) = makeRow("ШИРИНА", data.eqWidth[bandIndex], ParamKind.EQ_WIDTH)

            EqBandViews(activeButton, freqSeek, freqText, gainSeek, gainText, widthSeek, widthText)
        }

        detailEqViews = EqBlockViews(eqInButton, bandViews)
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

    private fun updateFaderUi(channel: Int, level: Float) {
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

    private fun updateMuteUi(channel: Int, muted: Boolean) {
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

    private fun updateSoloUi(channel: Int, soloed: Boolean) {
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

    private fun updateGainUi(channel: Int, level: Float) {
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

    private fun updateNameUi(channel: Int, name: String) {
        if (name.isNotBlank()) ConnectionHolder.channelData[channel].name = name
        val ui = channels.getOrNull(channel) ?: return
        if (name.isNotBlank()) ui.labelView.text = name
    }

    private fun sendFader(channelIndex: Int, level: Float) {
        sendRawAsync(Pro2Commands.setFader(channelIndex, level))
    }

    private fun sendGain(channelIndex: Int, level: Float) {
        sendRawAsync(Pro2Commands.setGain(channelIndex, level))
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
                withContext(Dispatchers.Main) { textStatus.text = "Ошибка mute: ${e.message}" }
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
                withContext(Dispatchers.Main) { textStatus.text = "Ошибка master fader: ${e.message}" }
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
                withContext(Dispatchers.Main) { textStatus.text = "Ошибка master mute: ${e.message}" }
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
                withContext(Dispatchers.Main) { textStatus.text = "Ошибка master solo: ${e.message}" }
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
                withContext(Dispatchers.Main) { textStatus.text = "Ошибка aux fader: ${e.message}" }
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
                withContext(Dispatchers.Main) { textStatus.text = "Ошибка aux mute: ${e.message}" }
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
                withContext(Dispatchers.Main) { textStatus.text = "Ошибка aux solo: ${e.message}" }
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
                withContext(Dispatchers.Main) { textStatus.text = "Ошибка aux-шины fader: ${e.message}" }
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
                withContext(Dispatchers.Main) { textStatus.text = "Ошибка aux-шины mute: ${e.message}" }
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
                withContext(Dispatchers.Main) { textStatus.text = "Ошибка aux-шины solo: ${e.message}" }
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
                withContext(Dispatchers.Main) { textStatus.text = "Ошибка aux send: ${e.message}" }
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
                withContext(Dispatchers.Main) { textStatus.text = "Ошибка EQ in: ${e.message}" }
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
                withContext(Dispatchers.Main) { textStatus.text = "Ошибка EQ band: ${e.message}" }
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
                withContext(Dispatchers.Main) { textStatus.text = "Ошибка EQ: ${e.message}" }
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
                withContext(Dispatchers.Main) { textStatus.text = "Ошибка compIn: ${e.message}" }
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
            else -> return
        }
        CoroutineScope(Dispatchers.IO).launch {
            try {
                sendRaw(sock, address, port, packet)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { textStatus.text = "Ошибка компрессора: ${e.message}" }
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
                withContext(Dispatchers.Main) { textStatus.text = "Ошибка solo: ${e.message}" }
            }
        }
    }

    private fun sendRawAsync(packet: ByteArray) {
        val address = consoleAddress ?: return
        val sock = socket ?: return
        val port = consolePort
        CoroutineScope(Dispatchers.IO).launch {
            try {
                sendRaw(sock, address, port, packet)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { textStatus.text = "Ошибка отправки: ${e.message}" }
            }
        }
    }

    private fun sendRaw(socket: DatagramSocket, address: InetAddress, port: Int, packet: ByteArray) {
        socket.send(DatagramPacket(packet, packet.size, address, port))
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (channelDetailContainer.visibility == android.view.View.VISIBLE) {
            channelDetailContainer.visibility = android.view.View.GONE
            channelDetailContainer.removeAllViews()
            openDetailChannel = null
            detailViews = null
            detailSendViews = null
            detailEqViews = null
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
