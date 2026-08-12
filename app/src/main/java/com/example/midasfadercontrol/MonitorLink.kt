package com.example.midasfadercontrol

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
import java.nio.ByteOrder

enum class BusParamKind { NAME, FADER, MUTE }
enum class ChannelParamKind { NAME, SEND }

data class BusSub(val bus: Int, val kind: BusParamKind)
data class ChannelSub(val channel: Int, val kind: ChannelParamKind)

/**
 * Единое хранилище состояния соединения + вся сетевая логика, общая для
 * ConnectionActivity (подключение, выбор шины) и MonitorActivity (сама
 * работа с посылами). Вынесено из Activity в top-level object по той же
 * причине, что и в основном проекте MidasFaderControl: сокет и подписки
 * должны переживать переход между экранами/поворот экрана, а не
 * создаваться заново каждый раз.
 *
 * Протокол - тот же самый Pro2/PPC, что реверс-инжинирился в основном
 * проекте (см. Pro2Commands.kt и OscUtil.kt - перенесены без изменений).
 * Новое здесь - только подписка на посыл канала В ВЫБРАННУЮ шину и на
 * имя/уровень самой aux-шины (enVirtualSubMixes) - в основном проекте эта
 * часть уже была реализована, но НЕ проверена на реальном пульте, так что
 * стоит свериться при первом реальном тесте (см. HANDOFF_MONITOR_README.md).
 */
object MonitorLink {
    const val numChannels = 56
    const val numBuses = 16

    var socket: DatagramSocket? = null
    private var receiveJob: Job? = null
    private var pollJob: Job? = null
    private var consoleAddress: InetAddress? = null
    private var consolePort: Int = 10000
    private var sessionToken: Int = 0
    private var sessionId: String = ""
    var isConnected: Boolean = false
        private set

    val busNames = Array(numBuses) { "" }
    val busFaders = Array(numBuses) { 0f }
    val busMutes = Array(numBuses) { false }
    val channelNames = Array(numChannels) { "" }
    val channelSendLevels = Array(numChannels) { 0f }

    var selectedBus: Int = -1

    private var namesSubscribed = false
    private var sendsSubscribedForBus = -1

    private val busSubscriptions = mutableMapOf<String, BusSub>()
    private val channelSubscriptions = mutableMapOf<String, ChannelSub>()

    /**
     * Вызывается в главном потоке при любом новом push-значении с пульта.
     * Activity вешают сюда обновление своего UI в onResume и снимают в
     * onPause - таким образом сеть продолжает работать и вне зависимости
     * от того, какой из двух экранов сейчас на переднем плане.
     */
    var onUpdate: (() -> Unit)? = null

    fun connectedHost(): String? = consoleAddress?.hostAddress

    private fun sendRaw(data: ByteArray) {
        val s = socket ?: return
        val address = consoleAddress ?: return
        try {
            s.send(DatagramPacket(data, data.size, address, consolePort))
        } catch (e: Exception) {
            // одиночная отправка не удалась - не критично, следующая отправка перезапишет
        }
    }

    fun connect(host: String, port: Int, onStatus: (String) -> Unit, onDone: (Boolean) -> Unit) {
        receiveJob?.cancel()
        pollJob?.cancel()
        socket?.close()
        resetForNewConnection()
        sessionId = System.currentTimeMillis().toString(36)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val address = InetAddress.getByName(host)
                // Отдельный локальный порт от основного приложения MidasFaderControl
                // (там 10001) - на случай, если оба когда-нибудь запустят на одном
                // телефоне для теста.
                val newSocket = DatagramSocket(10002)

                socket = newSocket
                consoleAddress = address
                consolePort = port
                isConnected = true

                withContext(Dispatchers.Main) { onStatus("Подключено, подписываюсь на шины...") }

                startReceiveLoop(newSocket)
                sessionToken = 0
                subscribeBusNamesAndFaders()
                startPollLoop()

                withContext(Dispatchers.Main) {
                    onStatus("Подключено. Выберите вашу мониторную шину.")
                    onDone(true)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onStatus("Ошибка подключения: ${e.message}")
                    onDone(false)
                }
            }
        }
    }

    private fun startPollLoop() {
        pollJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                delay(3000L)
                sendRaw(Pro2Commands.renew())
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
                    val element = OscUtil.decodeElement(data) ?: continue
                    val messages = OscUtil.flatten(element)
                    withContext(Dispatchers.Main) {
                        var changed = false
                        for (msg in messages) if (handleIncomingMessage(msg)) changed = true
                        if (changed) onUpdate?.invoke()
                    }
                } catch (e: SocketTimeoutException) {
                    // норма - просто нет данных за секунду
                } catch (e: Exception) {
                    if (!isActive) break
                }
            }
        }
    }

    private fun handleIncomingMessage(message: OscElement.Message): Boolean {
        // Учимся реальному токену пульта из любого входящего ",bi"-сообщения,
        // как и в основном приложении - на будущее, если понадобится
        // переподписаться.
        if (message.typeTag == ",bi" && message.args.size == 2) {
            (message.args[1] as? Int)?.let { sessionToken = it }
        }
        if (message.typeTag != ",bi" || message.args.isEmpty()) return false
        val blob = message.args[0] as? ByteArray ?: return false

        busSubscriptions[message.address]?.let { sub ->
            when (sub.kind) {
                BusParamKind.NAME ->
                    busNames[sub.bus] = String(blob, Charsets.US_ASCII).trimEnd('\u0000')
                BusParamKind.FADER ->
                    if (blob.size >= 4) {
                        busFaders[sub.bus] = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN).float.coerceIn(0f, 1f)
                    }
                BusParamKind.MUTE ->
                    if (blob.size >= 4) {
                        busMutes[sub.bus] = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN).int != 0
                    }
            }
            return true
        }

        channelSubscriptions[message.address]?.let { sub ->
            when (sub.kind) {
                ChannelParamKind.NAME ->
                    channelNames[sub.channel] = String(blob, Charsets.US_ASCII).trimEnd('\u0000')
                ChannelParamKind.SEND ->
                    if (blob.size >= 4) {
                        channelSendLevels[sub.channel] = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN).float.coerceIn(0f, 1f)
                    }
            }
            return true
        }
        return false
    }

    /** Подписка на имя и собственный уровень всех 16 aux-шин - нужна уже на экране выбора шины. */
    private fun subscribeBusNamesAndFaders() {
        val sid = sessionId
        CoroutineScope(Dispatchers.IO).launch {
            for (b in 0 until numBuses) {
                val nameHandle = "/h_${sid}_bn$b"
                val faderHandle = "/h_${sid}_bf$b"
                val muteHandle = "/h_${sid}_bm$b"
                busSubscriptions[nameHandle] = BusSub(b, BusParamKind.NAME)
                busSubscriptions[faderHandle] = BusSub(b, BusParamKind.FADER)
                busSubscriptions[muteHandle] = BusSub(b, BusParamKind.MUTE)
                sendRaw(Pro2Commands.batchSubscribe(nameHandle, Pro2Commands.auxBusNameAddress(), b, b, sessionToken))
                sendRaw(Pro2Commands.batchSubscribe(faderHandle, Pro2Commands.auxBusFaderAddress(), b, b, sessionToken))
                sendRaw(Pro2Commands.batchSubscribe(muteHandle, Pro2Commands.auxBusMuteAddress(), b, b, sessionToken))
            }
        }
    }

    /**
     * Подписывается на имена всех каналов (один раз за сессию) и на посылы
     * каналов в ВЫБРАННУЮ шину (заново при каждой смене шины - без явной
     * отписки от старой: пульт получит подписки на новые хендлы, старые
     * просто перестают быть нужны приложению и больше не читаются).
     */
    fun subscribeForSelectedBus(bus: Int) {
        val sid = sessionId
        val needNames = !namesSubscribed
        if (needNames) namesSubscribed = true
        val needSends = sendsSubscribedForBus != bus
        if (needSends) sendsSubscribedForBus = bus
        if (!needNames && !needSends) return

        CoroutineScope(Dispatchers.IO).launch {
            if (needNames) {
                for (i in 0 until numChannels) {
                    val handle = "/h_${sid}_cn$i"
                    channelSubscriptions[handle] = ChannelSub(i, ChannelParamKind.NAME)
                    sendRaw(Pro2Commands.batchSubscribe(handle, Pro2Commands.nameAddress(), i, i, sessionToken))
                }
            }
            if (needSends) {
                for (i in 0 until numChannels) {
                    val handle = "/h_${sid}_cs${bus}_$i"
                    channelSubscriptions[handle] = ChannelSub(i, ChannelParamKind.SEND)
                    sendRaw(Pro2Commands.batchSubscribe(handle, Pro2Commands.subSendLevelAddress(bus + 1), i, i, sessionToken))
                }
            }
        }
    }

    fun sendChannelSend(channel: Int, bus: Int, level: Float) {
        channelSendLevels[channel] = level
        CoroutineScope(Dispatchers.IO).launch { sendRaw(Pro2Commands.setSubSendLevel(channel, bus + 1, level)) }
    }

    fun sendBusFader(bus: Int, level: Float) {
        busFaders[bus] = level
        CoroutineScope(Dispatchers.IO).launch { sendRaw(Pro2Commands.setAuxBusFader(bus, level)) }
    }

    fun sendBusMute(bus: Int, muted: Boolean) {
        busMutes[bus] = muted
        CoroutineScope(Dispatchers.IO).launch { sendRaw(Pro2Commands.setAuxBusMute(bus, muted)) }
    }

    private fun resetForNewConnection() {
        socket = null
        receiveJob = null
        pollJob = null
        consoleAddress = null
        sessionToken = 0
        isConnected = false
        busSubscriptions.clear()
        channelSubscriptions.clear()
        namesSubscribed = false
        sendsSubscribedForBus = -1
        selectedBus = -1
        for (i in busNames.indices) busNames[i] = ""
        for (i in busFaders.indices) busFaders[i] = 0f
        for (i in busMutes.indices) busMutes[i] = false
        for (i in channelNames.indices) channelNames[i] = ""
        for (i in channelSendLevels.indices) channelSendLevels[i] = 0f
    }
}
