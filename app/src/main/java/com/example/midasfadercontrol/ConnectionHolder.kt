package com.example.midasfadercontrol

import kotlinx.coroutines.Job
import java.net.DatagramSocket
import java.net.InetAddress

// Вынесено из MainActivity.kt для читаемости - чисто организационная
// правка, поведение не менялось.

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
    // Текущий выбранный банк каналов и режим (КАНАЛЫ/AUX/MASTER) - хранится
    // здесь, а не в Activity, чтобы поворот экрана не сбрасывал выбор
    // обратно на банк 1-8 / режим "каналы".
    var uiBankStart: Int = 0
    var uiStripMode: StripMode = StripMode.CHANNELS
    // ВАЖНО (исправление сброса при повороте): раньше "какой канал открыт
    // в детальном экране" и "какая там вкладка" хранились ПРЯМО в
    // MainActivity как обычные поля - а при повороте экрана Android
    // ПОЛНОСТЬЮ пересоздаёт активность (подтверждено логом - разные хеши
    // окна ДО и ПОСЛЕ поворота), и такие поля сбрасываются в null/по
    // умолчанию. Теперь - как и uiBankStart/uiStripMode - хранятся здесь,
    // в ConnectionHolder, который переживает пересоздание активности.
    var openDetailChannel: Int? = null
    var openDetailTabName: String = "INPUT"
    // Режим приложения (инженер/монитор) и выбранная шина монитора -
    // тоже переживают поворот экрана.
    var uiAppMode: String = "engineer"
    var uiMonitorSelectedBus: Int = -1
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
    val gateSubscribed = mutableSetOf<Int>()
    val inputExtrasSubscribed = mutableSetOf<Int>()
    val compGateExtrasSubscribed = mutableSetOf<Int>()
    val mainOutExtrasSubscribed = mutableSetOf<Int>()
    val auxBusExtrasSubscribed = mutableSetOf<Int>()
    val channelData = Array(56) { ChannelData() }
    // По мануалу у Pro2 3 мастер-канала.
    val masterData = Array(3) { MasterData() }
    val auxReturnData = Array(8) { AuxReturnData() }
    val auxBusData = Array(16) { AuxBusData() }
    val vcaData = Array(8) { VcaData() }
    val mainOutData = Array(8) { MainOutData() }
    // Отдельная карта для 16 aux-шин.
    val auxBusSubscriptions = mutableMapOf<String, Subscription>()
    // Отдельная карта для VCA-групп (8 шт., подтверждено реальным захватом).
    val vcaSubscriptions = mutableMapOf<String, Subscription>()
    val mainOutSubscriptions = mutableMapOf<String, Subscription>()
    // Отдельная карта для aux returns - индексы 0..7 пересекаются и с
    // обычными каналами, и с VCA в будущем.
    val auxSubscriptions = mutableMapOf<String, Subscription>()
    // Членство "детей" в VCA-группах - активна, только пока открыт
    // соответствующий экран (см. openVcaMembers/subscribeVcaMembers).
    val vcaMemberSubscriptions = mutableMapOf<String, VcaMemberSub>()

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
